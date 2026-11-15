package io.givops.forge.github;

import static io.givops.forge.github.GitHubFixture.A;
import static io.givops.forge.github.GitHubFixture.B;
import static io.givops.forge.github.GitHubFixture.JSON;
import static io.givops.forge.github.GitHubFixture.NOW;
import static io.givops.forge.github.GitHubFixture.REPO;
import static io.givops.forge.github.GitHubFixture.TOKEN;
import static io.givops.forge.github.GitHubFixture.actions;
import static io.givops.forge.github.GitHubFixture.adapter;
import static io.givops.forge.github.GitHubFixture.array;
import static io.givops.forge.github.GitHubFixture.assertCode;
import static io.givops.forge.github.GitHubFixture.graphActor;
import static io.givops.forge.github.GitHubFixture.graphPage;
import static io.givops.forge.github.GitHubFixture.graphPull;
import static io.givops.forge.github.GitHubFixture.graphResponse;
import static io.givops.forge.github.GitHubFixture.input;
import static io.givops.forge.github.GitHubFixture.pull;
import static io.givops.forge.github.GitHubFixture.pullLink;
import static io.givops.forge.github.GitHubFixture.repository;
import static io.givops.forge.github.GitHubFixture.requestJson;
import static io.givops.forge.github.GitHubFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import io.givops.forge.github.GitHubFixture.Gate;
import io.givops.forge.github.GitHubFixture.Reply;
import io.givops.forge.github.GitHubFixture.Script;
import io.givops.forge.model.ForgeModels.CapabilityState;
import io.givops.forge.model.ForgeModels.CiState;
import io.givops.forge.model.ForgeModels.ConflictState;
import io.givops.forge.model.ForgeModels.Person;
import io.givops.forge.model.ForgeModels.ReviewerCoverage;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;

class GitHubReviewsTest {
    @Test
    void paginatesEachConnectionAndJoinsNumericReviewerIdentity() {
        var thread1 = JSON.createObjectNode().put("id", "thread-1").put("isResolved", true);
        var thread2 = JSON.createObjectNode().put("id", "thread-2").put("isResolved", false);
        var currentApproval = opinion("approval", 24, A);
        var commenter = JSON.createObjectNode().put("id", "comment-review");
        commenter.set("author", graphActor(25));
        var request = JSON.createObjectNode().put("id", "individual-request");
        request.set("requestedReviewer", graphActor(26));
        var teamRequest = JSON.createObjectNode().put("id", "team-request");
        teamRequest.putObject("requestedReviewer").put("__typename", "Team");

        var page1 = graphPull(1, 41, "topic", A);
        page1.set("reviewThreads", graphPage(2, "next-threads", thread1));
        page1.set("latestReviews", graphPage(1, null, commenter));
        page1.set("latestOpinionatedReviews", graphPage(1, null, currentApproval));
        page1.set("reviewRequests", graphPage(2, "next-requests", request));

        var page2 = graphPull(1, 41, "topic", A);
        page2.set("reviewThreads", graphPage(2, null, thread2));
        page2.set("reviewRequests", graphPage(2, null, teamRequest));

        var pages = new AtomicInteger();
        var script = script(http -> {
            var graph = requestJson(http);
            assertThat(http.method()).isEqualTo("POST");
            assertThat(graph.path("query").asString()).startsWith("query GivOpsPullReviewEvidence").doesNotContain("mutation");
            assertThat(http.headers().firstValue("Authorization")).contains("Bearer " + TOKEN);
            var variables = graph.path("variables");
            assertThat(variables.path("owner").asString()).isEqualTo("example");
            assertThat(variables.path("number").asInt()).isEqualTo(1);
            if (pages.incrementAndGet() == 1) return Reply.json(graphResponse(page1));
            assertThat(variables.path("threadsAfter").asString()).isEqualTo("next-threads");
            assertThat(variables.path("requestsAfter").asString()).isEqualTo("next-requests");
            assertThat(variables.path("reviewers").asBoolean()).isFalse();
            assertThat(variables.path("opinions").asBoolean()).isFalse();
            return Reply.json(graphResponse(page2));
        });

        var snapshot = adapter(script, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate());
        var item = snapshot.changeRequests().getFirst();
        assertThat(pages).hasValue(2);
        assertThat(item.reviewers()).extracting(Person::id).containsExactlyInAnyOrder("24", "25", "26");
        assertThat(item.taskSignals().approvedProviderIds()).containsExactly("24");
        assertThat(item.taskSignals().approvalsKnown()).isTrue();
        assertThat(item.taskSignals().unresolvedDiscussionCount()).isEqualTo(1);
        assertThat(item.taskSignals().conflictState()).isEqualTo(ConflictState.CLEAR);
        assertThat(item.taskSignals().reviewerCoverage()).isEqualTo(ReviewerCoverage.TEAM_REQUESTED);
        assertThat(item.taskSignals().ciSummary().state()).isEqualTo(CiState.SUCCESS);
        assertThat(snapshot.capabilities().operations().get("taskReviewEvidence").state()).isEqualTo(CapabilityState.AVAILABLE);
    }

    @Test
    void staleOrRerequestedApprovalDoesNotBecomeKnownCurrentApproval() {
        var graph = emptyGraph();
        graph.set("latestOpinionatedReviews", graphPage(3, null, opinion("old", 23, B),
                opinion("rerequested", 24, A), opinion("valid", 25, A)));
        var request = JSON.createObjectNode().put("id", "review-again");
        request.set("requestedReviewer", graphActor(24));
        graph.set("reviewRequests", graphPage(1, null, request));
        var script = script(http -> Reply.json(graphResponse(graph)));
        var signals = adapter(script, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate())
                .changeRequests().getFirst().taskSignals();

        assertThat(signals.approvedProviderIds()).containsExactly("25");
        assertThat(signals.approvalsKnown()).isFalse();
        assertThat(signals.unresolvedDiscussionCount()).isZero();
        assertThat(signals.reviewerCoverage()).isEqualTo(ReviewerCoverage.COMPLETE);
    }

    @Test
    void selfCommentsAreExcludedAndUnassignedCommentersLeaveReviewerCoverageUnknown() {
        var authorComment = JSON.createObjectNode().put("id", "self-review");
        authorComment.set("author", graphActor(20));
        var otherComment = JSON.createObjectNode().put("id", "comment-review");
        otherComment.set("author", graphActor(25));
        var graph = emptyGraph();
        graph.set("latestReviews", graphPage(2, null, authorComment, otherComment));
        var script = script(http -> Reply.json(graphResponse(graph)));

        var item = adapter(script, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate())
                .changeRequests().getFirst();
        assertThat(item.reviewers()).extracting(Person::id).containsExactly("25");
        assertThat(item.taskSignals().reviewerCoverage()).isEqualTo(ReviewerCoverage.UNKNOWN);
    }

    @Test
    void permissionErrorsDiscardPartialGraphQlData() {
        var root = graphResponse(emptyGraph());
        root.putArray("errors").addObject().put("type", "FORBIDDEN").put("message", "Access denied");
        var script = script(http -> Reply.json(root));
        var snapshot = adapter(script, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate());
        var signals = snapshot.changeRequests().getFirst().taskSignals();

        assertThat(signals.unresolvedDiscussionCount()).isNull();
        assertThat(signals.approvalsKnown()).isFalse();
        assertThat(signals.reviewerCoverage()).isEqualTo(ReviewerCoverage.UNKNOWN);
        assertThat(signals.ciSummary().state()).isEqualTo(CiState.SUCCESS);
        assertThat(snapshot.capabilities().operations().get("taskReviewEvidence").state()).isEqualTo(CapabilityState.FORBIDDEN);
    }

    @ParameterizedTest
    @CsvSource({"RATE_LIMITED,github_rate_limited", "INTERNAL,github_unavailable"})
    void transientErrorsInPartialGraphQlDataFailTheRefresh(String type, String code) {
        var root = graphResponse(emptyGraph());
        root.putArray("errors").addObject().put("type", type).put("message", "Upstream failure");
        var script = script(http -> Reply.json(root));
        assertCode(() -> adapter(script, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate()), code);
    }

    @Test
    void failsWhenGraphQlIdentityOrObservationChanges() {
        var incorrectIdentity = script(http -> Reply.json(graphResponse(emptyGraph().put("fullDatabaseId", "wrong"))));
        assertCode(() -> adapter(incorrectIdentity, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate()),
                "github_invalid_response");
        var changed = script(http -> Reply.json(graphResponse(emptyGraph().put("updatedAt", NOW.plusSeconds(1).toString()))));
        assertCode(() -> adapter(changed, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate()),
                "github_observation_changed");
        var draft = script(http -> Reply.json(graphResponse(emptyGraph().put("isDraft", true))));
        assertCode(() -> adapter(draft, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate()),
                "github_observation_changed");
    }

    @Test
    void failsOnCursorLoopAndBudgetExhaustion() {
        var counter = new AtomicInteger();
        var script = script(http -> {
            var graph = emptyGraph();
            var thread = JSON.createObjectNode().put("id", "thread-" + counter.incrementAndGet()).put("isResolved", false);
            graph.set("reviewThreads", graphPage(3, "repeat", thread));
            return Reply.json(graphResponse(graph));
        });
        assertCode(() -> adapter(script, TOKEN, 5).read(input(List.of(), List.of(), Map.of()), new Gate()),
                "github_invalid_pagination");
        assertThat(counter).hasValue(2);
        assertCode(() -> adapter(script, TOKEN, 1).read(input(List.of(), List.of(), Map.of()), new Gate()),
                "forge_budget_exhausted");
        assertThat(counter).hasValue(3);
    }

    private static Script script(Function<HttpRequest, Reply> graph) {
        var pull = pull(1, "open", false, 41, "topic", A);
        var link = pullLink(1, 41, "topic", A);
        return new Script(http -> switch (http.uri().getPath()) {
            case REPO -> Reply.json(repository());
            case REPO + "/pulls" -> Reply.json(array(pull));
            case REPO + "/pulls/1" -> Reply.json(pull);
            case REPO + "/actions/runs" -> Reply.json(actions(
                    run(100, 8, 7, "pull_request", "completed", "failure", "topic", A, 41, link),
                    run(101, 8, 2, "pull_request", "completed", "success", "topic", A, 41, link)));
            case "/graphql" -> graph.apply(http);
            default -> throw new AssertionError("Unexpected request " + http.uri());
        });
    }

    private static ObjectNode emptyGraph() {
        var graph = graphPull(1, 41, "topic", A);
        for (String field : List.of("reviewThreads", "latestReviews", "latestOpinionatedReviews", "reviewRequests")) {
            graph.set(field, graphPage(0, null));
        }
        return graph;
    }

    private static ObjectNode opinion(String id, int actor, String sha) {
        var node = JSON.createObjectNode().put("id", id).put("state", "APPROVED");
        node.set("author", graphActor(actor));
        node.putObject("commit").put("oid", sha);
        return node;
    }
}
