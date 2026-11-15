package io.givops.forge.github;

import static io.givops.forge.github.GitHubFixture.*;
import static org.assertj.core.api.Assertions.*;
import io.givops.adapters.CredentialHost;
import io.givops.adapters.ContextSigner;
import io.givops.adapters.testkit.AdapterTestkit;
import io.givops.catalog.ForgeProvider;
import io.givops.forge.discovery.DiscoveryModels.Server;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.model.ForgeModels.CapabilityState;
import io.givops.forge.port.ForgeAdapter;
import io.givops.forge.port.ForgeCheckInput;
import io.givops.integrations.checks.CheckBudget;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class GitHubProviderContractTest {
    @Test void exposesBothDomainsFromOneArtifactAndSupportsEitherDomainAlone() {
        var runner=new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GitHubConfiguration.class))
                .withBean(JsonMapper.class,()->JSON).withBean(Clock.class,()->Clock.fixed(NOW,ZoneOffset.UTC));
        runner.run(context->{assertThat(context).hasNotFailed().hasSingleBean(GitHubAdapter.class).hasSingleBean(GitHubForgeDefinition.class)
                .hasSingleBean(GitHubTicketDefinition.class).doesNotHaveBean(GitHubTicketAdapter.class);});
        runner.withBean(CredentialHost.class,()->request->AdapterTestkit.lease(Map.of("apiToken",TOKEN),Map.of("baseUrl",request.baseUrl().toString(),"instanceKey",request.instanceKey()),"1"))
                .withBean(ContextSigner.class,()->AdapterTestkit.signer("synthetic-secret"))
                .run(context->{assertThat(context).hasNotFailed().hasSingleBean(GitHubTicketAdapter.class);AdapterTestkit.verifyDefinition(context.getBean(GitHubTicketDefinition.class));});
    }
    @Test void scopesNumericReferencesToRepositoryAndRecognizesOnlyIssueUrls() {
        var definition=new GitHubTicketDefinition();AdapterTestkit.verifyDefinition(definition);
        assertThat(definition.normalizeIssueKey("#12","Example/Repo")).contains("example/repo#12");
        assertThat(definition.normalizeIssueKey("other/repo#12","example/repo")).isEmpty();
        assertThat(definition.normalizeIssueKey("#0","example/repo")).isEmpty();
        assertThat(definition.issueReference(URI.create("https://git.example.test/Example/Repo/issues/12")).orElseThrow().issueKey()).isEqualTo("example/repo#12");
        assertThat(definition.issueReference(URI.create("https://github.com/example/repo/pull/12"))).isEmpty();
        assertThat(definition.commandLabels()).containsEntry("versionResource","Milestone");
    }
    @Test void resolvesPublicIdentityAnonymouslyAndRefusesPrivateOrChangedIdentity() {
        var script=new Script(r->{assertThat(r.headers().firstValue("Authorization")).isEmpty();return Reply.json(repository().put("private",false));});
        var identity=new GitHubRepositoryIdentity(script.transport(),JSON).lookup(URI.create("https://github.com"),"example/fixture",new Gate());
        assertThat(identity.externalId()).isEqualTo("41");
        script.handler=r->Reply.json(repository().put("private",true));
        assertCode(()->new GitHubRepositoryIdentity(script.transport(),JSON).lookup(URI.create("https://github.com"),"example/fixture",new Gate()),"github_repository_not_public");
        script.handler=r->Reply.json(repository().put("private",false).put("full_name","other/fixture"));
        assertCode(()->new GitHubRepositoryIdentity(script.transport(),JSON).lookup(URI.create("https://github.com"),"example/fixture",new Gate()),"github_invalid_response");
    }
    @Test void keepsEnterpriseRestAndGraphQlOnTheirApprovedOrigin() {
        var urls=new GitHubUrls("example","fixture","41",URI.create("https://git.example.test"));
        var script=new Script(r->{assertThat(r.uri().getHost()).isEqualTo("git.example.test");return Reply.json(JSON.createObjectNode());});
        var transport=script.transport();transport.get(urls.rest(""),TOKEN,new Gate(),urls.apiBase());
        transport.graphQl(urls,TOKEN,"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),new Gate());
        assertThat(script.requests.getFirst().uri().getPath()).isEqualTo("/api/v3"+REPO);
        assertThat(script.requests.getFirst().headers().firstValue("X-GitHub-Api-Version")).contains("2022-11-28");
        assertThat(script.requests.getLast().uri().getPath()).isEqualTo("/api/graphql");
        assertThat(script.requests.getLast().headers().firstValue("X-GitHub-Api-Version")).isEmpty();
        assertCode(()->transport.get(URI.create("https://attacker.invalid/api/v3/repos/example/fixture"),TOKEN,new Gate(),urls.apiBase()),"github_configuration_invalid");
        urls.validateNext("https://git.example.test/api/v3"+REPO+"/issues?page=2&per_page=100&state=all","/issues",Map.of("state","all"),100,2);
    }
    @Test void retainsEnterpriseObservationProductVersionAndHosting() {
        var script=new Script(r->{assertThat(r.uri().getHost()).isEqualTo("git.example.test");
            if(r.uri().getPath().equals("/api/v3"+REPO))return Reply.text(200,Map.of("X-GitHub-Enterprise-Version",List.of("3.20.4")),JSON.writeValueAsString(repository()));return Reply.json(array());});
        var adapter=new GitHubAdapter(limits(20),script.transport(),JSON,Clock.fixed(NOW,ZoneOffset.UTC));
        var snapshot=adapter.read(input(source("https://git.example.test","41","example/fixture"),List.of(),List.of(),Map.of()),new Gate(),new ForgeAdapter.Access(TOKEN,Hosting.SELF_MANAGED));
        assertThat(snapshot.capabilities().version()).isEqualTo("3.20.4");assertThat(snapshot.capabilities().hosting()).isEqualTo("SELF_MANAGED");
    }
    @Test void preservesProbesAndReconstructsEnterpriseRateLimitEndpoint() {
        var script=new Script(r->{assertThat(r.uri()).hasToString("https://git.example.test/api/v3/rate_limit");var root=JSON.createObjectNode();root.putObject("resources");return Reply.json(root);});
        var probe=new GitHubConnectionProbe(script.transport(),JSON,Clock.fixed(NOW,ZoneOffset.UTC));
        var input=new ForgeCheckInput(ForgeCheckInput.Scope.FORGE_API,Map.of("apiToken",TOKEN),"GITHUB",URI.create("https://git.example.test"),"enterprise",null);
        probe.validate(input);var result=probe.probe(input,new CheckBudget(){public Duration beforeRequest(){return Duration.ofSeconds(5);}public void receivedBytes(long n){}public void check(){}});
        assertThat(result.errorCode()).isNull();assertThat(result.operations()).hasSize(1);
    }
    @Test void discoversRepositoriesWithBoundPaginationAndCanonicalUrls() {
        var repository=GitHubTicketAdapterTest.repo();repository.putObject("owner").put("id",9).put("login","example").put("type","Organization");
        var script=new Script(r->Reply.link(array(repository),"<https://api.github.com/user/repos?sort=full_name&per_page=20&page=2>; rel=\"next\""));
        var discovery=new GitHubDiscovery(script.transport(),JSON);
        assertThat(discovery.support(ForgeProvider.GITHUB,Hosting.SAAS).projectDiscovery()).isTrue();
        assertThat(discovery.support(ForgeProvider.GITHUB,Hosting.SAAS).groupDiscovery()).isFalse();
        var page=discovery.projects(new Server(URI.create("https://github.com")),new ForgeAdapter.Access(TOKEN,Hosting.SAAS),null,"fixture",20,1,0,new Gate());
        assertThat(page.hasNext()).isTrue();assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().cloneUrl()).isEqualTo("https://github.com/example/fixture.git");
        script.handler=r->Reply.link(array(repository),"<https://attacker.invalid/user/repos?sort=full_name&per_page=20&page=2>; rel=\"next\"");
        assertCode(()->discovery.projects(new Server(URI.create("https://github.com")),new ForgeAdapter.Access(TOKEN,Hosting.SAAS),null,null,20,1,0,new Gate()),"github_invalid_pagination");
    }
    @Test void classifiesUnsupportedReviewSchemaWithoutLosingRestObservations() {
        var script=new Script(r->{if(r.uri().getPath().equals("/graphql")) {var root=JSON.createObjectNode();var error=root.putArray("errors").addObject().put("message","Field unavailable");error.putObject("extensions").put("code","undefinedField");return Reply.json(root);}throw new AssertionError(r.uri());});
        var review=new GitHubReviews(script.transport(),new GitHubPayload(JSON),JSON,20);
        var urls=GitHubUrls.from(source());var row=pull(1,"open",false,41,"topic",A);
        var pull=GitHubPayload.pull(row,GitHubPayload.pullIdentity(row),urls,NOW,new Gate());
        var result=review.read(urls,TOKEN,pull,null,new Gate());assertThat(result.state()).isEqualTo(CapabilityState.UNSUPPORTED);
    }
}
