package io.givops.forge.github;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.givops.catalog.ForgeProvider;
import io.givops.forge.config.ForgeProperties;
import io.givops.forge.model.ForgeException;
import io.givops.forge.model.ForgeModels.Branch;
import io.givops.forge.model.ForgeModels.CachedAssociations;
import io.givops.forge.model.ForgeModels.RefreshInput;
import io.givops.forge.model.ForgeModels.Selection;
import io.givops.forge.model.ForgeModels.Settings;
import io.givops.forge.model.ForgeModels.Source;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.model.ForgeModels.Snapshot;
import io.givops.forge.port.ForgeAdapter.Access;
import io.givops.forge.port.ForgeRequestGate;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class GitHubFixture {
    static final String TOKEN = "github_synthetic_only";
    static final String A = "a".repeat(40);
    static final String B = "b".repeat(40);
    static final String C = "c".repeat(40);
    static final String D = "d".repeat(40);
    static final String REPO = "/repos/example/fixture";
    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();

    private GitHubFixture() {}

    static Source source() {
        return source("https://github.com", "41", "example/fixture");
    }

    static Source source(String webOrigin, String project, String path) {
        return new Source(UUID.randomUUID(), UUID.randomUUID(), "github-fixture", ForgeProvider.GITHUB,
                URI.create(webOrigin), 0, project, path);
    }

    static RefreshInput input(List<String> shas, List<Branch> branches, Map<String, CachedAssociations> cached) {
        return input(source(), shas, branches, cached);
    }

    static RefreshInput input(Source source, List<String> shas, List<Branch> branches, Map<String, CachedAssociations> cached) {
        return new RefreshInput(source, new Settings(source.repositoryId(), 1, true, 30, 100),
                new Selection(UUID.randomUUID(), NOW, null, NOW, NOW.minus(Duration.ofDays(30)), shas, branches), cached);
    }

    static ForgeProperties limits(int pages) {
        return new ForgeProperties(Duration.ofSeconds(120), Duration.ofSeconds(10), 300, pages,
                2_097_152, 16_777_216, 500, 200, 5000, 10_000, 4,
                Duration.ofMinutes(5), Duration.ofMinutes(15), 50);
    }

    static FixtureAdapter adapter(Script script, String token, int pages) {
        var adapter = new GitHubAdapter(limits(pages), script.transport(), JSON, Clock.fixed(NOW, ZoneOffset.UTC));
        var access = new Access(token == null || token.isBlank() ? null : token, Hosting.SAAS);
        return (input, gate) -> adapter.read(input, gate, access);
    }

    interface FixtureAdapter {
        Snapshot read(RefreshInput input, ForgeRequestGate gate);
    }

    static ObjectNode repository() {
        return JSON.createObjectNode().put("id", 41).put("full_name", "example/fixture");
    }

    static ObjectNode pull(int number, String state, boolean merged, int sourceRepository, String branch, String sha) {
        var pull = JSON.createObjectNode().put("id", 10000L + number).put("number", number)
                .put("state", state).put("merged", merged).put("draft", false).put("mergeable", true)
                .put("mergeable_state", "clean").put("title", "Pull request " + number)
                .put("body", "Fixture description").put("created_at", NOW.toString()).put("updated_at", NOW.toString())
                .put("html_url", "https://untrusted.example.test/pull/" + number);
        if (merged) pull.put("merged_at", NOW.toString());
        if ("closed".equals(state)) pull.put("closed_at", NOW.toString());
        pull.set("head", ref(sourceRepository, branch, sha));
        pull.set("base", ref(41, "main", D));
        pull.set("user", JSON.createObjectNode().put("id", 20).put("login", "fixture-author"));
        pull.putArray("labels").addObject().put("name", "fixture-label");
        return pull;
    }

    static ObjectNode ref(int repository, String branch, String sha) {
        var ref = JSON.createObjectNode().put("ref", branch).put("sha", sha);
        if (repository == 0) ref.putNull("repo");
        else ref.putObject("repo").put("id", repository);
        return ref;
    }

    static ObjectNode pullLink(int number, int sourceRepository, String branch, String headSha) {
        var link = JSON.createObjectNode().put("number", number);
        link.set("head", ref(sourceRepository, branch, headSha));
        link.set("base", ref(41, "main", D));
        return link;
    }

    static ObjectNode run(long id, int workflow, int attempt, String event, String status, String conclusion,
                          String branch, String sha, int headRepository, JsonNode... links) {
        var run = JSON.createObjectNode().put("id", id).put("workflow_id", workflow).put("run_attempt", attempt)
                .put("name", "Workflow " + workflow).put("head_branch", branch).put("head_sha", sha)
                .put("event", event).put("status", status).put("conclusion", conclusion)
                .put("created_at", NOW.plusSeconds(id).toString()).put("updated_at", NOW.plusSeconds(id + 10).toString())
                .put("html_url", "https://untrusted.example.test/action/" + id);
        run.putObject("repository").put("id", 41);
        if (headRepository == 0) run.putNull("head_repository");
        else run.putObject("head_repository").put("id", headRepository);
        run.set("pull_requests", array(links));
        return run;
    }

    static ObjectNode actions(JsonNode... runs) {
        var body = JSON.createObjectNode().put("total_count", runs.length);
        body.set("workflow_runs", array(runs));
        return body;
    }

    static ArrayNode array(JsonNode... rows) {
        var array = JSON.createArrayNode();
        for (JsonNode row : rows) array.add(row);
        return array;
    }

    static ObjectNode graphPull(int number, int headRepository, String branch, String sha) {
        var pull = JSON.createObjectNode().put("number", number).put("fullDatabaseId", Long.toString(10000L + number))
                .put("headRefOid", sha).put("headRefName", branch).put("baseRefName", "main")
                .put("state", "OPEN").put("isDraft", false).put("updatedAt", NOW.toString()).put("mergeable", "MERGEABLE");
        if (headRepository == 0) pull.putNull("headRepository");
        else pull.putObject("headRepository").put("databaseId", headRepository);
        return pull;
    }

    static ObjectNode graphResponse(ObjectNode pull) {
        var root = JSON.createObjectNode();
        root.putObject("data").putObject("repository").put("databaseId", 41).set("pullRequest", pull);
        return root;
    }

    static ObjectNode graphPage(int total, String cursor, JsonNode... nodes) {
        var page = JSON.createObjectNode().put("totalCount", total);
        page.putObject("pageInfo").put("hasNextPage", cursor != null).put("endCursor", cursor);
        page.set("nodes", array(nodes));
        return page;
    }

    static ObjectNode graphActor(int id) {
        return JSON.createObjectNode().put("__typename", "User").put("databaseId", id).put("login", "reviewer-" + id);
    }

    static JsonNode requestJson(HttpRequest request) {
        var result = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            private final ByteArrayOutputStream output = new ByteArrayOutputStream();

            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                output.writeBytes(chunk);
            }
            @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
            @Override public void onComplete() { result.complete(output.toByteArray()); }
        });
        return JSON.readTree(result.join());
    }

    static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ForgeException.class, failure ->
                org.assertj.core.api.Assertions.assertThat(failure.getCode()).isEqualTo(code));
    }

    record Reply(int statusCode, HttpHeaders headers, byte[] body) implements HttpResponse.ResponseInfo {
        static Reply json(JsonNode value) {
            return text(200, Map.of(), JSON.writeValueAsString(value));
        }

        static Reply link(JsonNode value, String link) {
            return text(200, Map.of("Link", List.of(link)), JSON.writeValueAsString(value));
        }

        static Reply text(int status, Map<String, List<String>> headers, String body) {
            return new Reply(status, HttpHeaders.of(headers, (name, value) -> true), body.getBytes(StandardCharsets.UTF_8));
        }

        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    }

    static final class Script {
        final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
        private final HttpClient client = mock(HttpClient.class);
        volatile Function<HttpRequest, Reply> handler;

        Script(Function<HttpRequest, Reply> handler) {
            this.handler = handler;
            when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                    .thenAnswer(invocation -> exchange(invocation.getArgument(0), invocation.getArgument(1)));
        }

        GitHubTransport transport() {
            return transport(2_097_152);
        }

        GitHubTransport transport(int maxBytes) {
            return new GitHubTransport(client, maxBytes);
        }

        private CompletableFuture<HttpResponse<byte[]>> exchange(HttpRequest request, HttpResponse.BodyHandler<byte[]> handler) {
            requests.add(request);
            Reply reply = this.handler.apply(request);
            var subscriber = handler.apply(reply);
            subscriber.onSubscribe(mock(Flow.Subscription.class));
            subscriber.onNext(List.of(ByteBuffer.wrap(reply.body())));
            subscriber.onComplete();
            return subscriber.getBody().toCompletableFuture().thenApply(body -> response(reply, body));
        }

        @SuppressWarnings("unchecked")
        private static HttpResponse<byte[]> response(Reply reply, byte[] body) {
            HttpResponse<byte[]> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(reply.statusCode());
            when(response.headers()).thenReturn(reply.headers());
            when(response.body()).thenReturn(body);
            return response;
        }
    }

    static final class Gate implements ForgeRequestGate {
        int requests;
        long bytes;
        int objects;
        int maxRequests = Integer.MAX_VALUE;
        long maxBytes = Long.MAX_VALUE;
        int maxObjects = Integer.MAX_VALUE;

        @Override public Duration beforeRequest() {
            if (requests >= maxRequests) throw GitHubErrors.budgetExceeded();
            requests++;
            return Duration.ofSeconds(5);
        }

        @Override public void receivedBytes(long count) {
            bytes += count;
            if (bytes > maxBytes) throw GitHubErrors.budgetExceeded();
        }

        @Override public void acceptedObjects(int count) {
            objects += count;
            if (objects > maxObjects) throw GitHubErrors.budgetExceeded();
        }

        @Override public void check() { }
    }
}
