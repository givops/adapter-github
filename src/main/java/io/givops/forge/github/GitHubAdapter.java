package io.givops.forge.github;

import io.givops.catalog.ForgeProvider;
import io.givops.forge.config.ForgeProperties;
import io.givops.forge.github.GitHubPayload.PullIdentity;
import io.givops.forge.model.ChangeRequestLifecycle;
import io.givops.forge.model.ForgeException;
import io.givops.forge.model.ForgeModels.Branch;
import io.givops.forge.model.ForgeModels.BranchPipeline;
import io.givops.forge.model.ForgeModels.CachedAssociations;
import io.givops.forge.model.ForgeModels.Capabilities;
import io.givops.forge.model.ForgeModels.Capability;
import io.givops.forge.model.ForgeModels.CapabilityState;
import io.givops.forge.model.ForgeModels.ChangeRequest;
import io.givops.forge.model.ForgeModels.ChangeRequestKey;
import io.givops.forge.model.ForgeModels.CommitAssociations;
import io.givops.forge.model.ForgeModels.ConflictState;
import io.givops.forge.model.ForgeModels.Person;
import io.givops.forge.model.ForgeModels.PipelineKey;
import io.givops.forge.model.ForgeModels.RefreshInput;
import io.givops.forge.model.ForgeModels.ReviewerCoverage;
import io.givops.forge.model.ForgeModels.Selection;
import io.givops.forge.model.ForgeModels.Settings;
import io.givops.forge.model.ForgeModels.Snapshot;
import io.givops.forge.model.ForgeModels.Source;
import io.givops.forge.model.ForgeModels.TaskSignals;
import io.givops.forge.port.ForgeAdapter;
import io.givops.forge.port.ForgeRequestGate;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class GitHubAdapter implements ForgeAdapter {
    private static final int PAGE_SIZE = 100;
    private static final int ACTIONS_SEARCH_CAP = 1000;
    private static final String REVIEW_EVIDENCE = "taskReviewEvidence";
    private final ForgeProperties limits;
    private final GitHubTransport transport;
    private final GitHubPayload payload;
    private final GitHubReviews reviews;
    private final Clock clock;

    GitHubAdapter(ForgeProperties limits, GitHubTransport transport, JsonMapper mapper, Clock clock) {
        this.limits = limits;
        this.transport = transport;
        this.payload = new GitHubPayload(mapper);
        this.reviews = new GitHubReviews(transport, payload, mapper, limits.maxPages());
        this.clock = clock;
    }

    @Override
    public ForgeProvider provider() {
        return ForgeProvider.GITHUB;
    }

    @Override
    public boolean supports(URI baseUrl) { return GitHubUrls.supportedBase(baseUrl); }

    @Override
    public Snapshot read(RefreshInput input, ForgeRequestGate gate, Access access) {
        if (!input.settings().enabled()) {
            throw new ForgeException("forge_disabled", HttpStatus.CONFLICT, GitHubMessages.format("forge.gitHubAdapter.forge.access.is.disabled"));
        }
        gate.check();
        if (input.selection().branches().size() > limits.maxBranches()) throw GitHubErrors.budgetExceeded();
        for (String sha : input.selection().commitShas()) {
            if (!GitHubUrls.validSha(sha)) throw GitHubErrors.invalidConfiguration();
        }
        for (Branch branch : input.selection().branches()) GitHubActions.branchRef(branch);
        return new Read(input.source(), input.cachedAssociations(), gate, access.token()).snapshot(input.selection());
    }

    @Override
    public BranchPipeline readBranchPipeline(Source source, Settings settings, Branch branch,
                                             ForgeRequestGate gate, Access access) {
        if (!settings.enabled()) {
            throw new ForgeException("forge_disabled", HttpStatus.CONFLICT, GitHubMessages.format("forge.gitHubAdapter.forge.access.is.disabled"));
        }
        GitHubActions.branchRef(branch);
        gate.check();
        var read = new Read(source, Map.of(), gate, access.token());
        read.repository();
        String sha = branch.sha().toLowerCase(Locale.ROOT);
        var actions = new GitHubActions();
        actions.index(sha, read.runs(sha));
        return read.branchPipeline(actions, branch);
    }

    private final class Read {
        private final Source source;
        private final Map<String, CachedAssociations> cachedAssociations;
        private final ForgeRequestGate gate;
        private final String token;
        private final GitHubUrls urls;
        private final Map<String, Capability> operations = new LinkedHashMap<>();
        private final Map<ChangeRequestKey, PullIdentity> identities = new LinkedHashMap<>();
        private final Map<ChangeRequestKey, ChangeRequest> cachedDetails = new LinkedHashMap<>();
        private int edges;
        private String productVersion;

        private Read(Source source, Map<String, CachedAssociations> cachedAssociations, ForgeRequestGate gate,
                     String token) {
            this.source = source;
            this.cachedAssociations = cachedAssociations;
            this.gate = gate;
            this.token = token;
            this.urls = GitHubUrls.from(source);
        }

        private Snapshot snapshot(Selection selection) {
            repository();
            openPulls();
            var associations = new ArrayList<CommitAssociations>();
            for (String sha : selection.commitShas()) associations.add(associations(sha));

            var details = new ArrayList<ChangeRequest>();
            var shas = new LinkedHashSet<String>();
            for (PullIdentity identity : identities.values()) {
                ChangeRequest pull = cachedDetails.get(identity.key());
                if (pull == null) pull = pullDetail(identity);
                details.add(pull);
                shas.add(pull.sha());
                String mergeSha = GitHubActions.testMergeSha(pull);
                if (mergeSha != null) shas.add(mergeSha);
            }
            for (Branch branch : selection.branches()) shas.add(branch.sha().toLowerCase(Locale.ROOT));

            var actions = new GitHubActions();
            for (String sha : shas) actions.index(sha, runs(sha));

            var pulls = new ArrayList<ChangeRequest>();
            for (ChangeRequest pull : details) pulls.add(withActions(pull, actions.forPull(pull)));
            var branches = new ArrayList<BranchPipeline>();
            for (Branch branch : selection.branches()) {
                branches.add(branchPipeline(actions, branch));
            }
            observed("mergeRequestPipelines", !pulls.isEmpty(), "actions_head_sha");
            observed("branchPipelines", !branches.isEmpty(), "actions_head_sha");
            operations.putIfAbsent("commitAssociations", capability(CapabilityState.UNKNOWN, "not_observed"));
            operations.putIfAbsent(REVIEW_EVIDENCE, capability(CapabilityState.UNKNOWN, "not_observed"));
            gate.check();
            var capabilities = new Capabilities("GitHub", productVersion, null, urls.cloud() ? "SAAS" : "SELF_MANAGED",
                    null, operations, clock.instant());
            return new Snapshot(capabilities, pulls, associations, branches);
        }

        private BranchPipeline branchPipeline(GitHubActions actions, Branch branch) {
            var links = actions.forBranch(urls.repositoryId(), branch);
            countEdges(links);
            return new BranchPipeline(branch.ref(), branch.sha(), null,
                    links.verified(), links.candidates(), links.coverage());
        }

        private void repository() {
            var response = transport.get(urls.rest(""), token, gate, urls.apiBase());
            productVersion = response.headers().firstValue("X-GitHub-Enterprise-Version").orElse(null);
            JsonNode repository = payload.body(response);
            if (!urls.repositoryId().equals(GitHubPayload.id(repository, "id"))) {
                throw GitHubErrors.identityMismatch();
            }
            String path = GitHubPayload.text(repository, "full_name");
            if (path != null && !source.repositoryPath().equalsIgnoreCase(path)) {
                throw GitHubErrors.identityMismatch();
            }
            observed("metadata", true, "verified_repository_identity");
        }

        private void openPulls() {
            var seen = new HashSet<ChangeRequestKey>();
            collection("/pulls", Map.of("state", "open"), row -> {
                PullIdentity identity = GitHubPayload.pullIdentity(row);
                if (!"open".equals(GitHubPayload.requiredText(row, "state")) || !seen.add(identity.key())) {
                    throw GitHubErrors.invalidResponse();
                }
                remember(identity);
            });
            observed("openMergeRequests", true, "open_pull_requests_endpoint");
        }

        private CommitAssociations associations(String sha) {
            var keys = new LinkedHashSet<ChangeRequestKey>();
            var cached = cachedAssociations.get(sha);
            if (cached != null) {
                gate.acceptedObjects(cached.changeRequests().size());
                for (ChangeRequest pull : cached.changeRequests()) {
                    PullIdentity identity = new PullIdentity(pull.key(), pull.providerId());
                    boolean alreadySeen = identities.containsKey(identity.key());
                    addAssociation(keys, identity);
                    if (!alreadySeen && (pull.lifecycle() == ChangeRequestLifecycle.CLOSED
                            || pull.lifecycle() == ChangeRequestLifecycle.MERGED) && GitHubUrls.validSha(pull.sha())) {
                        cachedDetails.put(identity.key(), pull);
                    }
                }
                operations.putIfAbsent("commitAssociations",
                        new Capability(CapabilityState.AVAILABLE, "cached_commit_pull_requests", cached.observedAt()));
                return new CommitAssociations(sha, List.copyOf(keys), cached.observedAt());
            }
            collection("/commits/" + sha.toLowerCase(Locale.ROOT) + "/pulls", Map.of(), row -> {
                PullIdentity identity = GitHubPayload.pullIdentity(row);
                addAssociation(keys, identity);
                cachedDetails.remove(identity.key());
            });
            observed("commitAssociations", true, "commit_pull_requests_endpoint");
            return new CommitAssociations(sha, List.copyOf(keys), clock.instant());
        }

        private void addAssociation(LinkedHashSet<ChangeRequestKey> keys, PullIdentity identity) {
            if (!keys.add(identity.key())) throw GitHubErrors.invalidResponse();
            countEdges(1);
            remember(identity);
        }

        private void remember(PullIdentity identity) {
            if (!urls.repositoryId().equals(identity.key().providerRepositoryId())
                    || !GitHubUrls.validId(identity.key().localId()) || !GitHubUrls.validId(identity.id())) {
                throw GitHubErrors.invalidResponse();
            }
            PullIdentity previous = identities.putIfAbsent(identity.key(), identity);
            if (previous != null && !previous.equals(identity)) throw GitHubErrors.invalidResponse();
            if (identities.size() > limits.maxMergeRequests()) throw GitHubErrors.budgetExceeded();
        }

        private ChangeRequest pullDetail(PullIdentity identity) {
            JsonNode row = detail("/pulls/" + identity.key().localId());
            return GitHubPayload.pull(row, identity, urls, clock.instant(), gate);
        }

        private ChangeRequest withActions(ChangeRequest pull, GitHubActions.Links links) {
            countEdges(links);
            List<Person> reviewers = pull.reviewers();
            TaskSignals signals;
            if (pull.lifecycle() == ChangeRequestLifecycle.OPEN) {
                var result = reviews.read(urls, token, pull, links.ciSummary(), gate);
                reviewers = result.reviewers();
                signals = result.signals();
                operations.merge(REVIEW_EVIDENCE, capability(result.state(), result.evidence()),
                        (previous, current) -> current.state() == CapabilityState.FORBIDDEN
                                || previous.state() == CapabilityState.AVAILABLE ? current : previous);
            } else {
                signals = new TaskSignals(null, null, false, null, links.ciSummary(),
                        ConflictState.UNKNOWN, ReviewerCoverage.UNKNOWN);
            }
            return new ChangeRequest(pull.key(), pull.providerId(), pull.webUrl(), pull.title(), pull.description(),
                    pull.state(), pull.draft(), pull.sourceProjectId(), pull.targetProjectId(), pull.sourceBranch(),
                    pull.targetBranch(), pull.sha(), pull.mergeCommitSha(), pull.squashCommitSha(), pull.author(),
                    pull.assignees(), reviewers, pull.labels(), pull.createdAt(), pull.updatedAt(), pull.mergedAt(),
                    pull.closedAt(), null, pull.observedAt(), signals, pull.lifecycle(), links.verified(),
                    links.candidates(), links.coverage());
        }

        private List<GitHubActions.Run> runs(String sha) {
            String suffix = "/actions/runs";
            var filters = Map.of("head_sha", sha);
            var result = new LinkedHashMap<PipelineKey, GitHubActions.Run>();
            Integer expected = null;
            for (int page = 1; page <= limits.maxPages(); page++) {
                var response = getPage(suffix, filters, page);
                JsonNode body = GitHubPayload.object(payload.body(response));
                int total = GitHubPayload.number(body, "total_count");
                if (total >= ACTIONS_SEARCH_CAP) throw GitHubErrors.resultCap();
                if (expected != null && total != expected) throw GitHubErrors.invalidPagination();
                expected = total;
                List<JsonNode> rows = pageRows(body.get("workflow_runs"));
                for (JsonNode row : rows) {
                    var run = GitHubActions.run(row, sha, urls, clock.instant(), gate);
                    if (result.putIfAbsent(run.pipeline().key(), run) != null) throw GitHubErrors.invalidPagination();
                }
                boolean more = GitHubPages.hasNext(urls, suffix, response.headers(), filters, page, PAGE_SIZE, rows.size());
                if (result.size() > total || more != (result.size() < total)) throw GitHubErrors.invalidPagination();
                if (!more) return List.copyOf(result.values());
            }
            throw GitHubErrors.budgetExceeded();
        }

        private void collection(String suffix, Map<String, String> filters, Consumer<JsonNode> consume) {
            for (int page = 1; page <= limits.maxPages(); page++) {
                var response = getPage(suffix, filters, page);
                List<JsonNode> rows = pageRows(payload.body(response));
                for (JsonNode row : rows) consume.accept(row);
                if (!GitHubPages.hasNext(urls, suffix, response.headers(), filters, page, PAGE_SIZE, rows.size())) return;
            }
            throw GitHubErrors.budgetExceeded();
        }

        private GitHubTransport.Response getPage(String suffix, Map<String, String> filters, int page) {
            return transport.get(urls.page(suffix, filters, PAGE_SIZE, page), token, gate, urls.apiBase());
        }

        private JsonNode detail(String suffix) {
            var response = transport.get(urls.rest(suffix), token, gate, urls.apiBase());
            gate.acceptedObjects(1);
            return GitHubPayload.object(payload.body(response));
        }

        private List<JsonNode> pageRows(JsonNode body) {
            List<JsonNode> rows = GitHubPayload.array(body);
            if (rows.size() > PAGE_SIZE) throw GitHubErrors.invalidPagination();
            gate.acceptedObjects(rows.size());
            return rows;
        }

        private void observed(String operation, boolean available, String evidence) {
            operations.put(operation, available ? capability(CapabilityState.AVAILABLE, evidence)
                    : capability(CapabilityState.UNKNOWN, "not_observed"));
        }

        private Capability capability(CapabilityState state, String evidence) {
            return new Capability(state, evidence, clock.instant());
        }

        private void countEdges(GitHubActions.Links links) {
            countEdges(links.verified().size() + links.candidates().size());
        }

        private void countEdges(int count) {
            edges += count;
            if (edges > limits.maxAssociations()) throw GitHubErrors.budgetExceeded();
        }
    }
}
