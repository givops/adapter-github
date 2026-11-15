package io.givops.forge.github;

import io.givops.forge.model.ChangeRequestLifecycle;
import io.givops.forge.model.ForgeModels.ActionsMetadata;
import io.givops.forge.model.ForgeModels.Branch;
import io.givops.forge.model.ForgeModels.ChangeRequest;
import io.givops.forge.model.ForgeModels.ChangeRequestKey;
import io.givops.forge.model.ForgeModels.CiState;
import io.givops.forge.model.ForgeModels.CiSummary;
import io.givops.forge.model.ForgeModels.Pipeline;
import io.givops.forge.model.ForgeModels.PipelineCoverage;
import io.givops.forge.model.ForgeModels.PipelineCoverageState;
import io.givops.forge.model.ForgeModels.PipelineKey;
import io.givops.forge.port.ForgeRequestGate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

final class GitHubActions {
    private static final String HEADS = "refs/heads/";
    private static final Comparator<Pipeline> NEWEST = Comparator.comparing(Pipeline::createdAt)
            .thenComparingLong(pipeline -> Long.parseLong(pipeline.key().id()))
            .thenComparingInt(pipeline -> pipeline.actions().runAttempt());
    private final Map<String, List<Run>> bySha = new HashMap<>();
    private final Map<ChangeRequestKey, List<Run>> byPull = new HashMap<>();

    void index(String sha, List<Run> runs) {
        bySha.put(sha, List.copyOf(runs));
        for (Run run : runs) {
            for (ChangeRequestKey key : run.pulls().keySet()) {
                byPull.computeIfAbsent(key, ignored -> new ArrayList<>()).add(run);
            }
        }
    }

    Links forPull(ChangeRequest pull) {
        var runs = new LinkedHashMap<PipelineKey, Run>();
        addRuns(runs, bySha.get(pull.sha()));
        addRuns(runs, bySha.get(testMergeSha(pull)));
        addRuns(runs, byPull.get(pull.key()));
        var verified = new ArrayList<Pipeline>();
        var candidates = new ArrayList<Pipeline>();
        for (Run run : runs.values()) {
            switch (match(pull, run)) {
                case VERIFIED -> verified.add(run.pipeline());
                case CANDIDATE -> candidates.add(run.pipeline());
                case UNRELATED -> { }
            }
        }
        return new Links(newestFirst(verified), newestFirst(candidates));
    }

    Links forBranch(String repositoryId, Branch branch) {
        String ref = branchRef(branch);
        // The workflow run API reports a short head_branch for push runs, without
        // the branch/tag namespace at execution time.
        List<Pipeline> candidates = bySha.getOrDefault(branch.sha().toLowerCase(Locale.ROOT), List.of())
                .stream().map(Run::pipeline)
                .filter(run -> compatible(ref, run.ref()) && compatible(repositoryId, run.actions().headRepositoryId())
                        && !"pull_request".equals(run.actions().event()))
                .toList();
        return new Links(List.of(), newestFirst(candidates));
    }

    static String branchRef(Branch branch) {
        if (!GitHubUrls.validSha(branch.sha()) || branch.ref() == null || !branch.ref().startsWith(HEADS)
                || branch.ref().length() == HEADS.length()) {
            throw GitHubErrors.invalidConfiguration();
        }
        return branch.ref().substring(HEADS.length());
    }

    static String testMergeSha(ChangeRequest pull) {
        // Once merged this field is the base-branch result, not the test merge commit.
        return pull.lifecycle() == ChangeRequestLifecycle.OPEN ? pull.mergeCommitSha() : null;
    }

    private static void addRuns(Map<PipelineKey, Run> result, List<Run> runs) {
        if (runs != null) {
            for (Run run : runs) result.putIfAbsent(run.pipeline().key(), run);
        }
    }

    private static List<Pipeline> newestFirst(List<Pipeline> pipelines) {
        return pipelines.stream().sorted(NEWEST.reversed()).toList();
    }

    private static Match match(ChangeRequest pull, Run run) {
        Pipeline pipeline = run.pipeline();
        ActionsMetadata actions = pipeline.actions();
        String source = pull.sourceProjectId();
        boolean sameHead = pull.sha().equals(pipeline.sha());
        boolean sameRef = pull.sourceBranch().equals(pipeline.ref());
        boolean sameSource = source != null && source.equals(actions.headRepositoryId());
        boolean pullEvent = switch (actions.event()) {
            case "pull_request", "pull_request_target", "merge_group" -> true;
            default -> false;
        };
        PullLink link = run.pulls().get(pull.key());
        if (link == null) {
            if (run.pulls().isEmpty() && pullEvent && (sameHead && compatible(pull.sourceBranch(), pipeline.ref())
                    && compatible(source, actions.headRepositoryId())
                    || pipeline.sha().equals(testMergeSha(pull)))) {
                return Match.CANDIDATE;
            }
            return Match.UNRELATED;
        }
        if (!compatible(pull.sha(), link.head().sha()) || !compatible(source, link.head().repositoryId())
                || !compatible(pull.sourceBranch(), link.head().ref())
                || !compatible(pull.targetBranch(), link.base().ref())) {
            return Match.UNRELATED;
        }
        if ("pull_request".equals(actions.event()) && sameHead && sameRef && sameSource
                && source.equals(link.head().repositoryId()) && pull.sha().equals(link.head().sha())
                && pull.sourceBranch().equals(link.head().ref()) && pull.targetBranch().equals(link.base().ref())) {
            return Match.VERIFIED;
        }
        return Match.CANDIDATE;
    }

    private static boolean compatible(String left, String right) {
        return left == null || right == null || left.equals(right);
    }

    static Run run(JsonNode row, String expectedSha, GitHubUrls urls, Instant observedAt, ForgeRequestGate gate) {
        String repository = GitHubPayload.id(row.get("repository"), "id");
        String sha = GitHubPayload.sha(row, "head_sha", true);
        if (!urls.repositoryId().equals(repository) || !expectedSha.equals(sha)) {
            throw GitHubErrors.invalidResponse();
        }
        String id = GitHubPayload.id(row, "id");
        String status = GitHubPayload.requiredText(row, "status");
        String conclusion = GitHubPayload.text(row, "conclusion");
        String event = GitHubPayload.requiredText(row, "event");
        int attempt = GitHubPayload.number(row, "run_attempt");
        if (attempt == 0) throw GitHubErrors.invalidResponse();

        var metadata = new ActionsMetadata(GitHubPayload.id(row, "workflow_id"), GitHubPayload.text(row, "name"),
                attempt, status, conclusion, event, GitHubPayload.optionalId(row.get("head_repository"), "id"));
        var pipeline = new Pipeline(new PipelineKey(repository, id), status(status, conclusion),
                GitHubPayload.text(row, "head_branch"), sha, event, urls.webUrl("/actions/runs/" + id),
                GitHubPayload.instant(row, "created_at", true), GitHubPayload.instant(row, "updated_at", true),
                observedAt, metadata);
        var pullRows = GitHubPayload.optionalArray(row.get("pull_requests"));
        gate.acceptedObjects(pullRows.size());
        var pulls = new HashMap<ChangeRequestKey, PullLink>();
        for (JsonNode item : pullRows) {
            String number = GitHubPayload.id(item, "number");
            Ref base = ref(item.get("base"));
            Ref head = ref(item.get("head"));
            // A missing base identity cannot establish even a scoped PR relationship.
            if (base.repositoryId() == null) continue;
            var key = new ChangeRequestKey(base.repositoryId(), number);
            if (pulls.putIfAbsent(key, new PullLink(base, head)) != null) throw GitHubErrors.invalidResponse();
        }
        return new Run(pipeline, Map.copyOf(pulls));
    }

    private static Ref ref(JsonNode row) {
        if (GitHubPayload.absent(row)) return new Ref(null, null, null);
        return new Ref(GitHubPayload.optionalId(GitHubPayload.object(row).get("repo"), "id"),
                GitHubPayload.text(row, "ref"), GitHubPayload.sha(row, "sha", false));
    }

    private static String status(String status, String conclusion) {
        return switch (status) {
            case "in_progress" -> "running";
            case "queued", "requested", "waiting", "pending" -> "pending";
            case "completed" -> switch (Objects.toString(conclusion, "")) {
                case "success" -> "success";
                case "failure", "startup_failure", "timed_out", "action_required" -> "failed";
                case "cancelled" -> "canceled";
                case "skipped" -> "skipped";
                default -> "unknown";
            };
            default -> "unknown";
        };
    }

    record Links(List<Pipeline> verified, List<Pipeline> candidates) {
        PipelineCoverage coverage() {
            return candidates.isEmpty() ? new PipelineCoverage(PipelineCoverageState.OBSERVED, "actions_head_sha")
                    : new PipelineCoverage(PipelineCoverageState.UNKNOWN, "ambiguous_actions_association");
        }

        CiSummary ciSummary() {
            var current = new HashMap<WorkflowScope, Pipeline>();
            for (Pipeline pipeline : verified) {
                var actions = pipeline.actions();
                var scope = new WorkflowScope(actions.workflowId(), actions.event(), actions.headRepositoryId());
                current.merge(scope, pipeline, (left, right) -> NEWEST.compare(left, right) >= 0 ? left : right);
            }
            PipelineCoverageState coverage = coverage().state();
            if (current.values().stream().anyMatch(run -> "failed".equals(run.status()))) {
                return new CiSummary(CiState.FAILED, coverage, "current_actions_failure");
            }
            if (coverage == PipelineCoverageState.UNKNOWN || current.isEmpty()) {
                return new CiSummary(CiState.UNKNOWN, coverage, coverage().evidence());
            }
            if (current.values().stream().allMatch(run -> "success".equals(run.status()))) {
                return new CiSummary(CiState.SUCCESS, coverage, "current_actions_success");
            }
            if (current.values().stream().allMatch(run -> "success".equals(run.status())
                    || "pending".equals(run.status()) || "running".equals(run.status()))) {
                return new CiSummary(CiState.PENDING, coverage, "current_actions_pending");
            }
            return new CiSummary(CiState.UNKNOWN, coverage, "inconclusive_actions_outcome");
        }
    }

    record Run(Pipeline pipeline, Map<ChangeRequestKey, PullLink> pulls) {}
    private record Ref(String repositoryId, String ref, String sha) {}
    private record PullLink(Ref base, Ref head) {}
    private record WorkflowScope(String workflowId, String event, String repositoryId) {}
    private enum Match { VERIFIED, CANDIDATE, UNRELATED }
}
