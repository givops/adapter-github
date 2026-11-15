package io.givops.forge.github;

import static io.givops.forge.github.GitHubFixture.*;
import static org.assertj.core.api.Assertions.*;
import io.givops.adapters.CapabilityInspection.State;
import io.givops.adapters.testkit.AdapterTestkit;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.*;
import io.givops.ticketing.model.TicketWriteModels.*;
import io.givops.ticketing.port.TicketRequestGate;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class GitHubTicketAdapterTest {
    static final InstanceSource SOURCE=new InstanceSource(UUID.randomUUID(),"github-test",TicketProvider.GITHUB,URI.create("https://github.com"),1);
    static ProjectQuery query(Set<String> refs,String release) { return new ProjectQuery(UUID.randomUUID(),"41","example/fixture",refs,release); }
    static ObjectNode repo() { return repository().put("name","fixture").put("has_issues",true); }
    static ObjectNode milestone(int number,String state) { return JSON.createObjectNode().put("id",9000+number).put("number",number).put("title","Version "+number).put("state",state).put("due_on","2026-12-01T00:00:00Z").put("closed_at","closed".equals(state)?NOW.toString():null); }
    static ObjectNode issue(int number) {
        var row=JSON.createObjectNode().put("id",1000+number).put("number",number).put("title","Issue "+number).put("body","Description")
                .put("state","open").put("created_at",NOW.toString()).put("updated_at",NOW.toString()).put("html_url","https://untrusted.invalid/issues/1");
        row.putObject("milestone").put("number",3);row.putObject("assignee").put("login","developer");row.putArray("labels").addObject().put("name","bug");return row;
    }
    static TicketProperties limits(int pages) {return new TicketProperties(true,Duration.ofSeconds(120),Duration.ofSeconds(5),100,pages,2_097_152,16_777_216,500,500,1000,5000,8_388_608,20,500,10000,Duration.ofMinutes(15),50,500);}
    static final class Fixture {
        final GitHubFixture.Script script;
        final AdapterTestkit.MutableCredentialLease lease;
        final GitHubTicketAdapter adapter;
        Fixture(java.util.function.Function<java.net.http.HttpRequest,GitHubFixture.Reply> handler) {this(SOURCE,handler,20);}
        Fixture(InstanceSource source,java.util.function.Function<java.net.http.HttpRequest,GitHubFixture.Reply> handler,int pages) {
            script=new GitHubFixture.Script(handler);lease=AdapterTestkit.lease(Map.of("apiToken",TOKEN),Map.of("baseUrl",source.baseUrl().toString(),"instanceKey",source.instanceKey()),"revision-1");
            adapter=new GitHubTicketAdapter(new GitHubCredentials(request->lease,AdapterTestkit.signer("synthetic-test-secret")),limits(pages),script.transport(),JSON,Clock.fixed(NOW,ZoneOffset.UTC));
        }
    }
    static final class TicketGate implements TicketRequestGate {
        int requests;
        @Override public Duration beforeRequest(){requests++;return Duration.ofSeconds(5);}
        @Override public void receivedBytes(long n){}
        @Override public void acceptedObjects(int n){}
        @Override public void check(){}
        @Override public Duration remaining(){return Duration.ofSeconds(20);}
    }
    @Test void mapsNativeIssueAndMilestoneWithoutEquatingClosedWithReleasedAndExcludesPulls() {
        var f=new Fixture(request->switch(request.uri().getPath()) {
            case REPO -> Reply.json(repo());
            case REPO+"/milestones" -> Reply.json(array(milestone(3,"closed")));
            case REPO+"/issues/1" -> Reply.json(issue(1));
            case REPO+"/issues/2" -> {var pull=issue(2);pull.putObject("pull_request");yield Reply.json(pull);}
            case REPO+"/issues" -> {var pull=issue(2);pull.putObject("pull_request");yield Reply.json(array(issue(1),pull));}
            default -> throw new AssertionError(request.uri());
        });
        var result=f.adapter.read(new ProviderInput(SOURCE,List.of(query(Set.of("#1","example/fixture#2"),"Version 3")),NOW),new TicketGate()).projects().getFirst();
        assertThat(result.issues()).hasSize(1);var issue=result.issues().getFirst();
        assertThat(issue.issueKey()).isEqualTo("example/fixture#1");assertThat(issue.description()).isEqualTo("Description");assertThat(issue.labels()).containsExactly("bug");
        assertThat(issue.assigneeAccountId()).isEqualTo("developer");assertThat(issue.webUrl()).hasToString("https://github.com/example/fixture/issues/1");
        assertThat(result.unresolvedKeys()).containsExactly("example/fixture#2");assertThat(result.releaseMembership().issues()).containsExactly(issue.identity());
        var milestone=result.versions().getFirst();assertThat(milestone.nativeState()).isEqualTo("closed");assertThat(milestone.released()).isFalse();assertThat(milestone.archived()).isFalse();assertThat(milestone.closedAt()).isEqualTo(NOW);assertThat(milestone.releaseDate()).isEqualTo(LocalDate.of(2026,12,1));
        assertThat(f.script.requests).allSatisfy(r->assertThat(r.headers().firstValue("X-GitHub-Api-Version")).contains("2026-03-10"));
    }
    @Test void retainsMissingAndPrReferencesAsUnresolved() {
        var f=new Fixture(r->r.uri().getPath().equals(REPO)?Reply.json(repo()):r.uri().getPath().endsWith("/milestones")?Reply.json(array()):Reply.text(404,Map.of(),"{}"));
        var result=f.adapter.read(new ProviderInput(SOURCE,List.of(query(Set.of("#99"),null)),NOW),new TicketGate()).projects().getFirst();
        assertThat(result.unresolvedKeys()).containsExactly("example/fixture#99");assertThat(result.issues()).isEmpty();
    }
    @Test void validatesRepositoryIdentityAndRejectsForeignQualifiedReferences() {
        var f=new Fixture(r->r.uri().getPath().equals(REPO)?Reply.json(repo()):Reply.json(array()));
        assertCode(()->f.adapter.read(new ProviderInput(SOURCE,List.of(query(Set.of("another/repo#1"),null)),NOW),new TicketGate()),Code.INVALID_CONFIGURATION);
        var wrong=new Fixture(r->Reply.json(repo().put("id",42)));
        assertCode(()->wrong.adapter.read(new ProviderInput(SOURCE,List.of(query(Set.of(),null)),NOW),new TicketGate()),Code.INVALID_RESPONSE);
    }
    @Test void observesSelectedMilestoneMembershipWithBoundAndPrFiltering() {
        var f=new Fixture(r->switch(r.uri().getPath()){case REPO->Reply.json(repo());case REPO+"/milestones"->Reply.json(array(milestone(3,"open")));default->Reply.json(array(issue(1),issue(2)));});
        var access=f.adapter.captureAccess(SOURCE);
        var members=f.adapter.readVersionMemberships(SOURCE,query(Set.of(),null),access,new TicketGate(),versions->Set.of("3"),1,2);
        assertThat(members.members().get("3")).hasSize(2);
        assertCode(()->f.adapter.readVersionMemberships(SOURCE,query(Set.of(),null),access,new TicketGate(),versions->Set.of("3"),1,1),Code.LIMIT_EXCEEDED);
        assertCode(()->f.adapter.readVersionMemberships(SOURCE,query(Set.of(),null),access,new TicketGate(),versions->Set.of("44"),1,10),Code.INVALID_CONFIGURATION);
    }
    @Test void followsBoundedMilestonePaginationAndRefusesHostChanges() {
        var f=new Fixture(r->{if(r.uri().getPath().equals(REPO))return Reply.json(repo());
            if(r.uri().getQuery().contains("page=1&"))return Reply.link(array(milestone(3,"open")),"<https://api.github.com"+REPO+"/milestones?page=2&per_page=100&state=all>; rel=\"next\"");return Reply.json(array(milestone(4,"closed")));});
        assertThat(f.adapter.read(new ProviderInput(SOURCE,List.of(query(Set.of(),null)),NOW),new TicketGate()).projects().getFirst().versions()).hasSize(2);
        var wrong=new Fixture(r->r.uri().getPath().equals(REPO)?Reply.json(repo()):Reply.link(array(milestone(3,"open")),"<https://attacker.invalid"+REPO+"/milestones?page=2&per_page=100&state=all>; rel=\"next\""));
        assertCode(()->wrong.adapter.read(new ProviderInput(SOURCE,List.of(query(Set.of(),null)),NOW),new TicketGate()),Code.INVALID_RESPONSE);
        assertThat(wrong.script.requests).hasSize(2);
    }
    @Test void enforcesPageCapInsteadOfPublishingPartialSnapshot() {
        var f=new Fixture(SOURCE,r->r.uri().getPath().equals(REPO)?Reply.json(repo()):Reply.link(array(milestone(3,"open")),"<https://api.github.com"+REPO+"/milestones?page=2&per_page=100&state=all>; rel=\"next\""),1);
        assertCode(()->f.adapter.read(new ProviderInput(SOURCE,List.of(query(Set.of(),null)),NOW),new TicketGate()),Code.LIMIT_EXCEEDED);
    }
    @Test void revalidatesRevocationBeforeAnyRequestAndAtPublicationLock() {
        var f=new Fixture(r->{throw new AssertionError("No request expected");});var access=f.adapter.captureAccess(SOURCE);
        f.adapter.requireCurrent(SOURCE,access,true);assertThat(f.lease.lastLocked()).isTrue();f.lease.invalidate();
        assertCode(()->f.adapter.read(new ProviderInput(SOURCE,List.of(query(Set.of(),null)),NOW),new TicketGate(),access),Code.SUPERSEDED);
        assertThat(f.adapter.current(SOURCE,access,true)).isFalse();assertThat(f.script.requests).isEmpty();
    }
    @Test void refusesCrossInstanceCredentialReplayAndRedactsCredentials() {
        var f=new Fixture(r->{throw new AssertionError("No request expected");});var access=f.adapter.captureAccess(SOURCE);
        var other=new InstanceSource(UUID.randomUUID(),SOURCE.instanceKey(),SOURCE.provider(),SOURCE.baseUrl(),1);
        assertCode(()->f.adapter.read(new ProviderInput(other,List.of(query(Set.of(),null)),NOW),new TicketGate(),access),Code.INVALID_CONFIGURATION);
        assertThat(access.toString()).doesNotContain(TOKEN);assertThat(access.context()).doesNotContain(TOKEN);
    }
    @ParameterizedTest @CsvSource({"401,AUTHENTICATION_FAILED","403,AUTHORIZATION_FAILED","429,RATE_LIMITED","410,UNSUPPORTED_PROVIDER","503,PROVIDER_ERROR"})
    void mapsRemoteFailuresWithoutReturningRemoteSecrets(int status,Code code) {
        var f=new Fixture(r->Reply.text(status,Map.of(),"sensitive provider details"));
        assertThatThrownBy(()->f.adapter.read(new ProviderInput(SOURCE,List.of(query(Set.of(),null)),NOW),new TicketGate())).isInstanceOfSatisfying(TicketException.class,e->{assertThat(e.code()).isEqualTo(code);assertThat(e.getMessage()).doesNotContain("sensitive");});
    }
    @ParameterizedTest @CsvSource({"200,AVAILABLE","401,FORBIDDEN","403,FORBIDDEN","404,UNKNOWN","410,UNSUPPORTED","429,TEMPORARILY_UNAVAILABLE","503,TEMPORARILY_UNAVAILABLE"})
    void inspectsScopedCapabilityStatesAndEnterpriseProductVersion(int status,State state) {
        var source=new InstanceSource(UUID.randomUUID(),"enterprise",TicketProvider.GITHUB,URI.create("https://git.example.test"),1);
        var f=new Fixture(source,r->{if (!r.uri().getPath().equals("/api/v3/repositories/41")) return Reply.json(array());assertThat(r.headers().firstValue("X-GitHub-Api-Version")).contains("2022-11-28");return Reply.text(status,Map.of("X-GitHub-Enterprise-Version",List.of("3.20.4")),JSON.writeValueAsString(repo()));},20);
        var observation=f.adapter.inspect(source,"41","ticketing.read",f.adapter.captureAccess(source),new TicketGate());
        assertThat(observation.state()).isEqualTo(state);assertThat(observation.productVersion()).isEqualTo("3.20.4");assertThat(observation.apiProfile()).isEqualTo("github-enterprise-rest-2022-11-28");
    }
    @Test void distinguishesDisabledUnknownAndUnsupportedCapabilities() {
        var f=new Fixture(r->Reply.json(repo().put("has_issues",false)));var access=f.adapter.captureAccess(SOURCE);
        assertThat(f.adapter.inspect(SOURCE,"41","ticketing.read",access,new TicketGate()).state()).isEqualTo(State.DISABLED);
        assertThat(f.adapter.inspect(SOURCE,"41","release.report-create",access,new TicketGate()).state()).isEqualTo(State.UNSUPPORTED);
        var unknown=new Fixture(r->Reply.json(repo()));
        assertThat(unknown.adapter.inspect(SOURCE,"41","ticketing.issue-create",unknown.adapter.captureAccess(SOURCE),new TicketGate()).state()).isEqualTo(State.UNKNOWN);
    }
    @Test void createsOnlyExplicitNativeRepositoryMilestoneAndIssueCommands() {
        var f=new Fixture(r->{if(r.method().equals("GET")) return Reply.json(r.uri().getPath().equals("/user")?JSON.createObjectNode().put("login","example"):repo());
            JsonNode body=requestJson(r);
            return switch(r.uri().getPath()) {
                case "/user/repos" -> {assertThat(body.path("private").asBoolean()).isTrue();assertThat(body.path("name").asString()).isEqualTo("fixture");yield Reply.text(201,Map.of(),JSON.writeValueAsString(repo()));}
                case REPO+"/milestones" -> {assertThat(body.path("title").asString()).isEqualTo("Version 3");assertThat(body.path("due_on").asString()).isEqualTo("2026-12-01T00:00:00Z");yield Reply.text(201,Map.of(),JSON.writeValueAsString(milestone(3,"open")));}
                case REPO+"/issues" -> {assertThat(body.path("milestone").asInt()).isEqualTo(3);assertThat(body.path("assignees").get(0).asString()).isEqualTo("example");yield Reply.text(201,Map.of(),JSON.writeValueAsString(issue(1)));}
                default -> throw new AssertionError(r.uri());
            };});
        var access=f.adapter.captureAccess(SOURCE);var gate=new TicketGate();
        assertThat(f.adapter.createProject(SOURCE,new CreateProjectCommand("example/fixture","Fixture","Description",null),access,gate).externalProjectId()).isEqualTo("41");
        var project=new RemoteProjectRef(UUID.randomUUID(),SOURCE.instanceId(),"41","example/fixture","Fixture",1);
        assertThat(f.adapter.createVersion(SOURCE,project,new CreateVersionCommand("Version 3",false,false,LocalDate.of(2026,12,1)),access,gate).externalVersionId()).isEqualTo("3");
        var command=new CreateIssueCommand("Issue","Description",IssueKind.TASK,null,WorkflowState.OPEN,null,PriorityLevel.NORMAL,null,List.of("3"),true,null);
        assertThat(f.adapter.createIssue(SOURCE,project,command,access,gate).issueKey()).isEqualTo("example/fixture#1");
        assertThat(f.script.requests.stream().filter(r->r.method().equals("POST"))).hasSize(3);
        assertCode(()->f.adapter.createVersion(SOURCE,project,new CreateVersionCommand("Release",true,false,null),access,gate),Code.INVALID_CONFIGURATION);
    }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,Code code) { assertThatThrownBy(call).isInstanceOfSatisfying(TicketException.class,e->assertThat(e.code()).isEqualTo(code)); }
}
