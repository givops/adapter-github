package io.givops.forge.github;

import static io.givops.forge.github.GitHubFixture.A;
import static io.givops.forge.github.GitHubFixture.B;
import static io.givops.forge.github.GitHubFixture.C;
import static io.givops.forge.github.GitHubFixture.D;
import static io.givops.forge.github.GitHubFixture.NOW;
import static io.givops.forge.github.GitHubFixture.REPO;
import static io.givops.forge.github.GitHubFixture.TOKEN;
import static io.givops.forge.github.GitHubFixture.actions;
import static io.givops.forge.github.GitHubFixture.adapter;
import static io.givops.forge.github.GitHubFixture.array;
import static io.givops.forge.github.GitHubFixture.assertCode;
import static io.givops.forge.github.GitHubFixture.input;
import static io.givops.forge.github.GitHubFixture.pull;
import static io.givops.forge.github.GitHubFixture.pullLink;
import static io.givops.forge.github.GitHubFixture.repository;
import static io.givops.forge.github.GitHubFixture.run;
import static io.givops.forge.github.GitHubFixture.source;
import static org.assertj.core.api.Assertions.assertThat;

import io.givops.forge.github.GitHubFixture.Gate;
import io.givops.forge.github.GitHubFixture.Reply;
import io.givops.forge.github.GitHubFixture.Script;
import io.givops.forge.model.ChangeRequestLifecycle;
import io.givops.forge.model.ForgeModels.Branch;
import io.givops.forge.model.ForgeModels.CachedAssociations;
import io.givops.forge.model.ForgeModels.CiState;
import io.givops.forge.model.ForgeModels.PipelineCoverageState;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubAdapterTest {
    @Test
    void exactBranchReadKeepsMultipleWorkflowCandidatesWithoutFetchingPullRequests() {
        var script = new Script(request -> switch (request.uri().getPath()) {
            case REPO -> Reply.json(repository());
            case REPO + "/actions/runs" -> {
                assertThat(request.uri().getQuery()).contains("head_sha=" + B);
                yield Reply.json(actions(
                        run(101, 8, 1, "push", "completed", "success", "topic", B, 41),
                        run(102, 9, 1, "push", "completed", "failure", "topic", B, 41)));
            }
            default -> throw new AssertionError("Unexpected request " + request.uri());
        });
        var adapter = new GitHubAdapter(GitHubFixture.limits(5), script.transport(),
                tools.jackson.databind.json.JsonMapper.builder().build(),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        var input = input(List.of(), List.of(), Map.of());
        var observed = adapter.readBranchPipeline(input.source(), input.settings(), new Branch("refs/heads/topic", B),
                new Gate(), new io.givops.forge.port.ForgeAdapter.Access(TOKEN,
                        io.givops.forge.model.ForgeConnectionSettings.Hosting.SAAS));

        assertThat(observed.sha()).isEqualTo(B);
        assertThat(observed.pipelines()).isEmpty();
        assertThat(observed.pipelineCandidates()).extracting(run -> run.key().id()).containsExactly("102", "101");
        assertThat(observed.pipelineCoverage().state()).isEqualTo(PipelineCoverageState.UNKNOWN);
    }

    @Test
    void collectsOpenAndAssociatedPullsAndSeparatesVerifiedRunsFromCandidates() {
        var open = pull(1, "open", false, 41, "topic", A).put("merge_commit_sha", B).put("draft", true);
        var second = pull(2, "open", false, 77, "fork-topic", C);
        var merged = pull(3, "closed", true, 0, "deleted", C).put("merge_commit_sha", D);
        var linked = pullLink(1, 41, "topic", A);
        var script = new Script(request -> {
            if ((REPO + "/commits/" + A + "/pulls").equals(request.uri().getPath())) return Reply.json(array(open, merged));
            return switch (request.uri().getPath()) {
            case REPO -> Reply.json(repository());
            case REPO + "/pulls" -> request.uri().getQuery().contains("page=2") ? Reply.json(array(second))
                    : Reply.link(array(open), "<https://api.github.com/repositories/41/pulls?state=open&page=2&per_page=100>; rel=\"next\"");
            case REPO + "/pulls/1" -> Reply.json(open);
            case REPO + "/pulls/2" -> Reply.json(second);
            case REPO + "/pulls/3" -> Reply.json(merged);
            case REPO + "/actions/runs" -> {
                String query = request.uri().getQuery();
                if (query.contains(A)) {
                    yield Reply.json(actions(
                            run(101, 8, 1, "pull_request", "completed", "failure", "topic", A, 41, linked),
                            run(102, 8, 2, "pull_request", "completed", "success", "topic", A, 41, linked),
                            run(103, 9, 1, "pull_request", "completed", "action_required", "topic", A, 41, linked),
                            run(104, 10, 1, "pull_request_target", "completed", "success", "topic", A, 41, linked),
                            run(105, 11, 1, "push", "completed", "success", "topic", A, 41),
                            run(106, 11, 1, "push", "completed", "success", "topic", A, 77),
                            run(107, 12, 1, "pull_request", "completed", "success", "topic", A, 77,
                                    pullLink(1, 77, "topic", A)),
                            run(108, 12, 1, "pull_request", "completed", "success", "topic", A, 41,
                                    pullLink(1, 41, "topic", C))));
                }
                if (query.contains(B)) {
                    yield Reply.json(actions(
                            run(109, 13, 1, "pull_request", "completed", "success", "topic", B, 41, linked),
                            run(110, 14, 1, "merge_group", "completed", "success", "queue", B, 41, linked)));
                }
                if (query.contains(C)) {
                    yield Reply.json(actions(run(111, 15, 1, "pull_request", "completed", "success", "fork-topic", C,
                            77, pullLink(2, 77, "fork-topic", C))));
                }
                throw new AssertionError("Unexpected Actions scope " + query);
            }
            default -> throw new AssertionError("Unexpected request " + request.uri());
            };
        });
        var gate = new Gate();
        var snapshot = adapter(script, " ", 5).read(input(List.of(A), List.of(new Branch("refs/heads/topic", A)), Map.of()), gate);

        assertThat(snapshot.changeRequests()).hasSize(3);
        var observed = snapshot.changeRequests().getFirst();
        assertThat(observed.key().providerRepositoryId()).isEqualTo("41");
        assertThat(observed.providerId()).isEqualTo("10001");
        assertThat(observed.lifecycle()).isEqualTo(ChangeRequestLifecycle.OPEN);
        assertThat(observed.draft()).isTrue();
        assertThat(observed.headPipeline()).isNull();
        assertThat(observed.sha()).isEqualTo(A);
        assertThat(observed.mergeCommitSha()).isEqualTo(B);
        assertThat(observed.webUrl()).isEqualTo("https://github.com/example/fixture/pull/1");
        assertThat(observed.pipelines()).extracting(pipeline -> pipeline.key().id()).containsExactly("103", "102", "101");
        assertThat(observed.pipelines().getFirst().status()).isEqualTo("failed");
        assertThat(observed.pipelines().getFirst().actions().status()).isEqualTo("completed");
        assertThat(observed.pipelines().getFirst().actions().conclusion()).isEqualTo("action_required");
        assertThat(observed.pipelines().get(1).actions().runAttempt()).isEqualTo(2);
        assertThat(observed.pipelineCandidates()).extracting(pipeline -> pipeline.key().id()).containsExactly("110", "109", "104");
        assertThat(observed.pipelineCoverage().state()).isEqualTo(PipelineCoverageState.UNKNOWN);
        assertThat(observed.taskSignals().ciSummary().state()).isEqualTo(CiState.FAILED);
        assertThat(observed.taskSignals().unresolvedDiscussionCount()).isNull();
        assertThat(observed.taskSignals().approvalsKnown()).isFalse();

        var fork = snapshot.changeRequests().get(1);
        assertThat(fork.pipelines()).extracting(pipeline -> pipeline.actions().headRepositoryId()).containsExactly("77");
        assertThat(fork.taskSignals().ciSummary().state()).isEqualTo(CiState.SUCCESS);
        var closed = snapshot.changeRequests().getLast();
        assertThat(closed.state()).isEqualTo("closed");
        assertThat(closed.lifecycle()).isEqualTo(ChangeRequestLifecycle.MERGED);
        assertThat(closed.sourceProjectId()).isNull();
        assertThat(closed.mergeCommitSha()).isEqualTo(D);
        assertThat(closed.squashCommitSha()).isNull();
        assertThat(closed.pipelines()).isEmpty();

        var branch = snapshot.branchPipelines().getFirst();
        assertThat(branch.pipeline()).isNull();
        assertThat(branch.pipelines()).isEmpty();
        assertThat(branch.pipelineCandidates()).extracting(pipeline -> pipeline.key().id()).containsExactly("105", "104");
        assertThat(branch.pipelineCoverage().state()).isEqualTo(PipelineCoverageState.UNKNOWN);
        assertThat(snapshot.associations().getFirst().changeRequests()).containsExactly(observed.key(), closed.key());
        assertThat(script.requests).hasSize(gate.requests).allSatisfy(request -> {
            assertThat(request.uri().getHost()).isEqualTo("api.github.com");
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.headers().firstValue("Authorization")).isEmpty();
            assertThat(request.headers().firstValue("X-GitHub-Api-Version")).contains("2026-03-10");
        });
        assertThat(script.requests.stream().map(HttpRequest::uri)
                .filter(uri -> uri.getPath().endsWith("/actions/runs") && uri.getQuery().contains(A))).hasSize(1);
    }

    @Test
    void cachedEdgesRetainObservationTimeAndCachedOpenPullsAreRevalidated() {
        var old = NOW.minus(Duration.ofMinutes(3));
        var open = pull(1, "open", false, 41, "topic", A);
        var nowClosed = pull(1, "closed", false, 41, "topic", A);
        var merged = pull(2, "closed", true, 41, "done", B);
        var urls = GitHubUrls.from(source());
        var cache = Map.of(C, new CachedAssociations(List.of(
                GitHubPayload.pull(open, GitHubPayload.pullIdentity(open), urls, old, new Gate()),
                GitHubPayload.pull(merged, GitHubPayload.pullIdentity(merged), urls, old, new Gate())), old));
        var script = new Script(request -> switch (request.uri().getPath()) {
            case REPO -> Reply.json(repository());
            case REPO + "/pulls" -> Reply.json(array());
            case REPO + "/pulls/1" -> Reply.json(nowClosed);
            case REPO + "/actions/runs" -> Reply.json(actions());
            default -> throw new AssertionError("Unexpected request " + request.uri());
        });
        var snapshot = adapter(script, TOKEN, 5).read(input(List.of(C), List.of(), cache), new Gate());
        assertThat(snapshot.associations().getFirst().observedAt()).isEqualTo(old);
        assertThat(snapshot.changeRequests()).extracting(item -> item.lifecycle())
                .containsExactly(ChangeRequestLifecycle.CLOSED, ChangeRequestLifecycle.MERGED);
        assertThat(snapshot.changeRequests()).extracting(item -> item.observedAt()).containsExactly(NOW, old);
        assertThat(snapshot.changeRequests()).allSatisfy(item -> assertThat(item.pipelines()).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"401,github_unauthorized", "403,github_forbidden", "404,github_not_found", "301,github_redirect"})
    void requiredAccessErrorsKeepTheirCodeAndNeverRetryAnonymously(int status, String code) {
        var script = new Script(request -> Reply.text(status, Map.of("Location", List.of("https://untrusted.example.test")), "{}"));
        assertCode(() -> adapter(script, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate()), code);
        assertThat(script.requests).singleElement().satisfies(request ->
                assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + TOKEN));
    }

    @Test
    void rejectsDifferentIdentityAndUntrustedCatalogOrigin() {
        var script = new Script(request -> Reply.json(repository().put("id", 42)));
        var adapter = adapter(script, TOKEN, 5);
        assertCode(() -> adapter.read(input(List.of(), List.of(), Map.of()), new Gate()), "github_repository_identity_mismatch");
        assertThat(script.requests).hasSize(1);
        var spoof = source("https://github.com@untrusted.example.test", "41", "example/fixture");
        assertCode(() -> adapter.read(input(spoof, List.of(), List.of(), Map.of()), new Gate()), "github_configuration_invalid");
        assertThat(script.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://api.github.com.evil.test/repositories/41/pulls?state=open&page=2&per_page=100",
        "https://api.github.com/repositories/42/pulls?state=open&page=2&per_page=100",
        "https://api.github.com/repositories/41/pulls?state=all&page=2&per_page=100",
        "https://api.github.com/repositories/41/pulls?state=open&page=1&per_page=100",
        "https://api.github.com/repositories/41/pulls?state=open&page=2&per_page=100&page=3",
        "https://api.github.com/repositories/41/pulls?state=open&page=2&per_page=100#unexpected"
    })
    void refusesUnsafeOrCyclicPaginationBeforeNextRequest(String target) {
        var script = new Script(request -> REPO.equals(request.uri().getPath()) ? Reply.json(repository())
                : Reply.link(array(pull(1, "open", false, 41, "topic", A)), "<" + target + ">; rel=\"next\""));
        assertCode(() -> adapter(script, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate()),
                "github_invalid_pagination");
        assertThat(script.requests).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({"1000,github_result_cap", "2,github_invalid_pagination"})
    void rejectsUpstreamCapsAndMissingActionsPages(int total, String code) {
        var script = new Script(request -> switch (request.uri().getPath()) {
            case REPO -> Reply.json(repository());
            case REPO + "/pulls" -> Reply.json(array());
            case REPO + "/actions/runs" -> Reply.json(actions(run(101, 1, 1, "push", "queued", null, "main", A, 41))
                    .put("total_count", total));
            default -> throw new AssertionError("Unexpected request " + request.uri());
        });
        var input = input(List.of(), List.of(new Branch("refs/heads/main", A)), Map.of());
        assertCode(() -> adapter(script, null, 5).read(input, new Gate()), code);
        assertThat(script.requests).hasSize(3);
    }

    @Test
    void stopsAtLocalPageAndRequestBudgets() {
        var script = new Script(request -> REPO.equals(request.uri().getPath()) ? Reply.json(repository())
                : Reply.link(array(pull(1, "open", false, 41, "topic", A)),
                        "<https://api.github.com/repositories/41/pulls?state=open&page=2&per_page=100>; rel=next"));
        assertCode(() -> adapter(script, null, 1).read(input(List.of(), List.of(), Map.of()), new Gate()),
                "forge_budget_exhausted");
        assertThat(script.requests).hasSize(2);

        var gate = new Gate();
        gate.maxRequests = 1;
        assertCode(() -> adapter(script, null, 5).read(input(List.of(), List.of(), Map.of()), gate),
                "forge_budget_exhausted");
        assertThat(script.requests).hasSize(3);
    }
}
