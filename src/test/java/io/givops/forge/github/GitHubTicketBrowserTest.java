package io.givops.forge.github;

import static io.givops.forge.github.GitHubFixture.*;
import static io.givops.forge.github.GitHubTicketAdapterTest.*;
import static org.assertj.core.api.Assertions.*;

import io.givops.ticketing.model.TicketBrowserModels.*;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketRequestGate;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class GitHubTicketBrowserTest {
    private static final UUID PROJECT = UUID.randomUUID();
    private static BrowserRequest request() { return request(SOURCE, null); }
    private static BrowserRequest request(InstanceSource source, SourceRevision revision) {
        return new BrowserRequest(source, PROJECT, "41", "example/fixture", revision, NOW);
    }
    private static BrowseQuery query(String text, List<String> statuses, List<String> labels, String sort, int size, String next) {
        return new BrowseQuery(text, Lifecycle.ACTIVE, statuses, List.of(), labels, sort, size, next);
    }
    private static BrowseQuery query() { return query("", List.of(), List.of(), "key-asc", 20, null); }
    private static TicketRef ticket(String id) { return new TicketRef(SOURCE.instanceId(), "41", id); }
    private static Fixture fixture(ObjectNode... rows) {
        return new Fixture(r -> r.uri().getPath().equals(REPO) ? Reply.json(repo()) : Reply.json(array(rows)));
    }
    private static void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, Code code) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(TicketException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test void advertisesImplementedOperationsWithoutFetchingOrClaimingSnapshots() {
        var f = new Fixture(r -> { throw new AssertionError(r.uri()); });
        var descriptor = f.adapter.describe(request(), f.adapter.captureAccess(SOURCE), new TicketGate());
        assertThat(descriptor.queryFields()).contains("text:key-title-literal-nfc", "labels:any", "statuses", "categories");
        assertThat(descriptor.snapshot()).isFalse();
        assertThat(descriptor.content()).isTrue();
        assertThat(descriptor.assets()).isFalse();
        assertThat(new GitHubTicketDefinition().capabilities()).contains("ticketing.browse", "ticketing.content").doesNotContain("ticketing.assets");
    }

    @Test void listsNumericKeysExcludesPullsAndContinuesInTheSameLiveContext() {
        var pull = issue(3); pull.putObject("pull_request");
        var f = fixture(issue(10), pull, issue(2), issue(1));
        var access = f.adapter.captureAccess(SOURCE);
        var first = f.adapter.list(request(), query("", List.of(), List.of(), "key-asc", 2, null), access, new TicketGate());
        assertThat(first.items()).extracting(TicketSummary::displayKey).containsExactly("example/fixture#1", "example/fixture#2");
        assertThat(first.items()).extracting(i -> i.ref().externalIssueId()).containsExactly("1001", "1002");
        assertThat(first.exactTotal()).isEqualTo(3);
        assertThat(first.revision().immutable()).isFalse();
        assertThat(first.items()).allSatisfy(i -> assertThat(i.revision()).isEqualTo(first.revision()));
        var second = f.adapter.list(request(SOURCE, first.revision()), query("", List.of(), List.of(), "key-asc", 2, first.nextContinuation()), access, new TicketGate());
        assertThat(second.items()).extracting(TicketSummary::displayKey).containsExactly("example/fixture#10");
        assertThat(second.nextContinuation()).isNull();
        assertThat(second.revision()).isEqualTo(first.revision());
        assertThat(f.script.requests).filteredOn(r -> r.uri().getPath().endsWith("/issues"))
                .allSatisfy(r -> assertThat(r.uri().getQuery()).contains("state=all").doesNotContain("labels="));
    }

    @Test void appliesLiteralNfcSearchAndOrLabelsWithAndAcrossFamilies() {
        var a = issue(1).put("title", "Fix Cafe\u0301");
        var b = issue(2).put("title", "Fix Café").put("state", "closed").put("state_reason", "not_planned");
        b.withArray("labels").removeAll().addObject().put("name", "feature");
        var c = issue(3).put("title", "Unrelated");
        var f = fixture(a, b, c);
        var access = f.adapter.captureAccess(SOURCE);
        var both = f.adapter.list(request(), query("CAFÉ", List.of("open", "closed"), List.of("bug", "feature"), "key-asc", 20, null), access, new TicketGate());
        assertThat(both.items()).hasSize(2);
        assertThat(both.items().getLast().outcome()).isEqualTo(Outcome.CANCELLED);
        var closed = f.adapter.list(request(), query("café", List.of("closed"), List.of("bug", "feature"), "key-asc", 20, null), access, new TicketGate());
        assertThat(closed.items()).extracting(TicketSummary::displayKey).containsExactly("example/fixture#2");
        assertThat(f.adapter.list(request(), query("state:closed", List.of(), List.of(), "key-asc", 20, null), access, new TicketGate()).items()).isEmpty();
        var category = new BrowseQuery("", Lifecycle.ACTIVE, List.of(), List.of("unavailable", "issue"), List.of(), "key-asc", 20, null);
        assertThat(f.adapter.list(request(), category, access, new TicketGate()).items()).hasSize(3);
    }

    @Test void sortsDescendingKeysAndLatestUpdatesAndMapsUnknownCompletionHonestly() {
        var f = fixture(issue(1).put("updated_at", NOW.plusSeconds(30).toString()), issue(10).put("state", "closed"), issue(2));
        var access = f.adapter.captureAccess(SOURCE);
        assertThat(f.adapter.list(request(), query("", List.of(), List.of(), "key-desc", 20, null), access, new TicketGate()).items())
                .extracting(TicketSummary::displayKey).containsExactly("example/fixture#10", "example/fixture#2", "example/fixture#1");
        var latest = f.adapter.list(request(), query("", List.of(), List.of(), "updated-desc", 20, null), access, new TicketGate());
        assertThat(latest.items()).extracting(TicketSummary::displayKey).containsExactly("example/fixture#1", "example/fixture#2", "example/fixture#10");
        assertThat(latest.items().getLast().outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test void readsDetailAndMarkdownByStableIdWhileUsingLocalNumberOnlyAfterResolution() {
        var f = new Fixture(r -> switch (r.uri().getPath()) {
            case REPO -> Reply.json(repo());
            case REPO + "/issues" -> Reply.json(array(issue(7)));
            case REPO + "/issues/7" -> Reply.json(issue(7).put("body", "# Description\n\n**Full text**"));
            default -> throw new AssertionError(r.uri());
        });
        var access = f.adapter.captureAccess(SOURCE);
        var detail = f.adapter.detail(request(), ticket("1007"), access, new TicketGate());
        assertThat(detail.ticket().ref()).isEqualTo(ticket("1007"));
        assertThat(detail.assigneeId()).isEqualTo("developer");
        assertThat(detail.releaseVersionIds()).containsExactly("3");
        assertThat(detail.navigation().ticketUrl()).hasToString("https://github.com/example/fixture/issues/7");
        // A direct host detail request captures its context timestamp after the provider returns.
        var subsequent = new BrowserRequest(SOURCE, PROJECT, "41", "example/fixture", detail.ticket().revision(), NOW.plusSeconds(1));
        var content = f.adapter.content(subsequent, ticket("1007"), access, new TicketGate());
        assertThat(content.format()).isEqualTo(ContentFormat.MARKDOWN);
        assertThat(content.text()).isEqualTo("# Description\n\n**Full text**");
        assertThat(content.revision()).isEqualTo(detail.ticket().revision());
        assertThat(content.assets()).isEmpty();
        assertThat(content.toString()).doesNotContain("Description");
        code(() -> f.adapter.detail(request(), ticket("7"), access, new TicketGate()), Code.NOT_FOUND);
    }

    @Test void followsValidatedProviderPagesBeforeReportingACompleteFilteredTotal() {
        var f = new Fixture(r -> {
            if (r.uri().getPath().equals(REPO)) return Reply.json(repo());
            if (r.uri().getQuery().contains("page=2")) return Reply.json(array(issue(2)));
            return Reply.text(200, Map.of("Link", List.of("<https://api.github.com" + REPO + "/issues?state=all&per_page=100&page=2>; rel=\"next\"")), JSON.writeValueAsString(array(issue(1))));
        });
        var result = f.adapter.list(request(), query(), f.adapter.captureAccess(SOURCE), new TicketGate());
        assertThat(result.items()).hasSize(2);
        assertThat(result.exactTotal()).isEqualTo(2);
        assertThat(f.script.requests).hasSize(3);
    }

    @Test void rejectsCursorTamperingAndQueryReuseBeforeRemoteCalls() {
        var f = fixture(issue(1), issue(2));
        var access = f.adapter.captureAccess(SOURCE);
        var page = f.adapter.list(request(), query("", List.of(), List.of(), "key-asc", 1, null), access, new TicketGate());
        f.script.requests.clear();
        code(() -> f.adapter.list(request(), query("", List.of(), List.of(), "key-asc", 1, "https://evil.invalid"), access, new TicketGate()), Code.INVALID_CONFIGURATION);
        code(() -> f.adapter.list(request(), query("changed", List.of(), List.of(), "key-asc", 1, page.nextContinuation()), access, new TicketGate()), Code.INVALID_CONFIGURATION);
        code(() -> f.adapter.list(request(SOURCE, new SourceRevision("github-live", "forged", false)), query(), access, new TicketGate()), Code.STALE_CONTEXT);
        assertThat(f.script.requests).isEmpty();
    }

    @Test void rejectsForeignTicketReferencesAndUnsupportedQueriesBeforeRemoteCalls() {
        var f = new Fixture(r -> { throw new AssertionError(r.uri()); });
        var access = f.adapter.captureAccess(SOURCE);
        code(() -> f.adapter.detail(request(), new TicketRef(UUID.randomUUID(), "41", "1001"), access, new TicketGate()), Code.INVALID_CONFIGURATION);
        code(() -> f.adapter.content(request(), new TicketRef(SOURCE.instanceId(), "42", "1001"), access, new TicketGate()), Code.INVALID_CONFIGURATION);
        code(() -> f.adapter.list(request(), new BrowseQuery("", Lifecycle.DRAFT, List.of(), List.of(), List.of(), "key-asc", 20, null), access, new TicketGate()), Code.INVALID_CONFIGURATION);
        code(() -> f.adapter.list(request(), query("", List.of(), List.of(), "unsupported", 20, null), access, new TicketGate()), Code.INVALID_CONFIGURATION);
    }

    @ParameterizedTest @ValueSource(strings = {"wrong-id", "wrong-number", "pull", "foreign-repo"})
    void rejectsChangedOrForeignDetailResponses(String fault) {
        var f = new Fixture(r -> {
            if (r.uri().getPath().equals(REPO)) return Reply.json(repo());
            if (r.uri().getPath().endsWith("/issues")) return Reply.json(array(issue(1)));
            var detail = issue(1);
            switch (fault) {
                case "wrong-id" -> detail.put("id", 9999);
                case "wrong-number" -> detail.put("number", 2);
                case "pull" -> detail.putObject("pull_request");
                default -> detail.put("repository_url", "https://api.github.com/repos/other/project");
            }
            return Reply.json(detail);
        });
        code(() -> f.adapter.content(request(), ticket("1001"), f.adapter.captureAccess(SOURCE), new TicketGate()), Code.INVALID_RESPONSE);
    }

    @Test void preservesAccessDenialAndRevocationAndChecksCancellation() {
        var denied = new Fixture(r -> Reply.text(404, Map.of(), "{}"));
        code(() -> denied.adapter.list(request(), query(), denied.adapter.captureAccess(SOURCE), new TicketGate()), Code.AUTHORIZATION_FAILED);
        var f = fixture(issue(1));
        var access = f.adapter.captureAccess(SOURCE);
        f.lease.invalidate();
        assertThatThrownBy(() -> f.adapter.describe(request(), access, new TicketGate())).isInstanceOf(TicketException.class);
        var live = fixture(issue(1));
        var liveAccess = live.adapter.captureAccess(SOURCE);
        TicketRequestGate cancelled = new TicketRequestGate() {
            public Duration beforeRequest() { return Duration.ofSeconds(1); }
            public void receivedBytes(long n) { }
            public void acceptedObjects(int n) { }
            public Duration remaining() { return Duration.ofSeconds(1); }
            public void check() { throw new TicketException(Code.SUPERSEDED, "Cancelled"); }
        };
        code(() -> live.adapter.list(request(), query(), liveAccess, cancelled), Code.SUPERSEDED);
        assertThat(live.script.requests).isEmpty();
    }

    @Test void enforcesPageBudgetAndRejectsDuplicateIssues() {
        var duplicate = fixture(issue(1), issue(1));
        code(() -> duplicate.adapter.list(request(), query(), duplicate.adapter.captureAccess(SOURCE), new TicketGate()), Code.INVALID_RESPONSE);
        var numberCollision = fixture(issue(1), issue(1).put("id", 2001));
        code(() -> numberCollision.adapter.list(request(), query(), numberCollision.adapter.captureAccess(SOURCE), new TicketGate()), Code.INVALID_RESPONSE);
        var capped = new Fixture(SOURCE, r -> r.uri().getPath().equals(REPO) ? Reply.json(repo())
                : Reply.text(200, Map.of("Link", List.of("<https://api.github.com" + REPO + "/issues?state=all&per_page=100&page=2>; rel=\"next\"")), JSON.writeValueAsString(array(issue(1)))), 1);
        code(() -> capped.adapter.list(request(), query(), capped.adapter.captureAccess(SOURCE), new TicketGate()), Code.LIMIT_EXCEEDED);
    }

    @Test void reportsDisabledIssuesAndChecksCredentialsAfterTheContentResponse() {
        var disabled = new Fixture(r -> Reply.json(repo().put("has_issues", false)));
        code(() -> disabled.adapter.list(request(), query(), disabled.adapter.captureAccess(SOURCE), new TicketGate()), Code.DISABLED);
        var f = fixture(issue(1));
        var access = f.adapter.captureAccess(SOURCE);
        f.script.handler = r -> {
            if (r.uri().getPath().equals(REPO)) return Reply.json(repo());
            if (r.uri().getPath().endsWith("/issues")) return Reply.json(array(issue(1)));
            f.lease.invalidate();
            return Reply.json(issue(1));
        };
        code(() -> f.adapter.content(request(), ticket("1001"), access, new TicketGate()), Code.SUPERSEDED);
    }

    @Test void supportsEnterpriseOriginsAndRejectsOversizedContent() {
        var source = new InstanceSource(UUID.randomUUID(), "enterprise", TicketProvider.GITHUB, URI.create("https://git.example.test"), 1);
        var f = new Fixture(source, r -> {
            assertThat(r.uri().getHost()).isEqualTo("git.example.test");
            assertThat(r.headers().firstValue("X-GitHub-Api-Version")).contains("2022-11-28");
            return r.uri().getPath().equals("/api/v3" + REPO) ? Reply.json(repo()) : Reply.json(array(issue(1)));
        }, 20);
        var result = f.adapter.list(request(source, null), query(), f.adapter.captureAccess(source), new TicketGate());
        assertThat(result.items().getFirst().webUrl()).hasToString("https://git.example.test/example/fixture/issues/1");
        var big = new Fixture(r -> r.uri().getPath().equals(REPO) ? Reply.json(repo())
                : r.uri().getPath().endsWith("/issues") ? Reply.json(array(issue(1))) : Reply.json(issue(1).put("body", "x".repeat(1024 * 1024 + 1))));
        code(() -> big.adapter.content(request(), ticket("1001"), big.adapter.captureAccess(SOURCE), new TicketGate()), Code.LIMIT_EXCEEDED);
    }
}
