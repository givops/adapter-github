package io.givops.forge.github;

import static io.givops.forge.github.GitHubFixture.*;
import static org.assertj.core.api.Assertions.*;
import io.givops.forge.discovery.DiscoveryModels.Server;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.model.ForgeProvisioningModels.CreateRepositoryCommand;
import io.givops.forge.port.ForgeAdapter.Access;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class GitHubRepositoriesTest {
    static final Server SERVER=new Server(URI.create("https://github.com"));
    static final Access ACCESS=new Access(TOKEN,Hosting.SAAS);
    static ObjectNode repo(String branch) {return repository().put("name","fixture").put("private",true).put("description","Run marker").put("html_url","https://github.com/example/fixture").put("clone_url","https://github.com/example/fixture.git").put("default_branch",branch);}
    @Test void createsEmptyPrivatePersonalRepositoryAndReadsIdentityAndRefs() {
        var script=new Script(r -> {
            if(r.uri().getPath().equals("/user")) return Reply.json(JSON.createObjectNode().put("login","example"));
            if(r.method().equals("POST")) {
                var body=requestJson(r);assertThat(body.path("auto_init").asBoolean()).isFalse();assertThat(body.path("private").asBoolean()).isTrue();
                return Reply.text(201,Map.of(),JSON.writeValueAsString(repo("main")));
            }
            if(r.uri().getPath().endsWith("/branches") || r.uri().getPath().endsWith("/tags")) return Reply.json(array());
            return Reply.json(repo("main"));
        });
        var adapter=new GitHubRepositories(script.transport(),JSON);
        var result=adapter.createRepository(SERVER,new CreateRepositoryCommand("example","fixture","Run marker",null,true,false),ACCESS,new Gate());
        assertThat(result.empty()).isTrue();assertThat(result.externalId()).isEqualTo("41");assertThat(result.defaultBranch()).isEqualTo("main");
        assertThat(script.requests).extracting(HttpRequest::method).containsExactly("GET","POST","GET","GET","GET");
    }
    @Test void selectsOrganizationEndpointAndRefusesUnsupportedInitializationBeforeHttp() {
        var script=new Script(r -> {
            if(r.uri().getPath().equals("/user")) return Reply.json(JSON.createObjectNode().put("login","different"));
            if(r.method().equals("POST")) {assertThat(r.uri().getPath()).isEqualTo("/orgs/example/repos");return Reply.text(403,Map.of(),"private secret");}
            throw new AssertionError(r.uri());
        });
        var adapter=new GitHubRepositories(script.transport(),JSON);
        assertThatThrownBy(() -> adapter.createRepository(SERVER,new CreateRepositoryCommand("example","fixture",null,"prod",true,false),ACCESS,new Gate())).isInstanceOf(RuntimeException.class);
        assertThat(script.requests).isEmpty();
        assertThatThrownBy(() -> adapter.createRepository(SERVER,new CreateRepositoryCommand("example","fixture",null,null,true,false),ACCESS,new Gate())).hasMessageNotContaining("private secret");
        assertThat(script.requests).hasSize(2);
    }
    @Test void verifiesCanonicalUrlsAndRejectsForeignIdentity() {
        var script=new Script(r -> Reply.json(repo("main").put("clone_url","https://attacker.invalid/repository.git")));
        var adapter=new GitHubRepositories(script.transport(),JSON);
        assertThatThrownBy(() -> adapter.findRepository(SERVER,"example","fixture",ACCESS,new Gate())).isInstanceOf(RuntimeException.class);
        assertThat(script.requests).hasSize(1);
    }
    @Test void updatesPushedDefaultWithOneMutationAndReadback() {
        var changed=new AtomicBoolean();
        var script=new Script(r -> {
            if(r.method().equals("PATCH")) {changed.set(true);assertThat(requestJson(r).path("default_branch").asString()).isEqualTo("prod");}
            if(r.uri().getPath().endsWith("/branches") || r.uri().getPath().endsWith("/tags")) return Reply.json(array(JSON.createObjectNode().put("ref","refs/heads/prod")));
            return Reply.json(repo(changed.get()?"prod":"main"));
        });
        new GitHubRepositories(script.transport(),JSON).setDefaultBranch(SERVER,"41","example/fixture","prod",ACCESS,new Gate());
        assertThat(script.requests).extracting(HttpRequest::method).containsExactly("GET","GET","GET","PATCH","GET","GET","GET");
    }
}
