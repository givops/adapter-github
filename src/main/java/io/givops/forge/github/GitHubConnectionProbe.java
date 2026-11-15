package io.givops.forge.github;

import io.givops.catalog.ForgeProvider;

import io.givops.forge.admin.ApiCheckOperations;
import io.givops.forge.admin.ApiCheckOperations.Observation;
import io.givops.forge.model.ForgeModels.Source;
import io.givops.forge.port.ForgeApiProbe;
import io.givops.integrations.checks.CheckBudget;
import io.givops.forge.port.ForgeCheckInput;
import io.givops.integrations.checks.CheckOutcome;
import io.givops.forge.port.ForgeCheckInput.Scope;
import io.givops.integrations.checks.OperationStatus;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GitHubConnectionProbe implements ForgeApiProbe {
    private static final URI RATE_LIMIT = URI.create("https://api.github.com/rate_limit");
    private final GitHubTransport transport;
    private final GitHubPayload payload;
    private final Clock clock;

    GitHubConnectionProbe(GitHubTransport transport, JsonMapper mapper, Clock clock) {
        this.transport = transport;
        this.payload = new GitHubPayload(mapper);
        this.clock = clock;
    }

    @Override
    public ForgeProvider provider() { return ForgeProvider.GITHUB; }

    @Override
    public void validate(ForgeCheckInput input) {
        if (!GitHubUrls.supportedBase(input.baseUrl())) throw GitHubErrors.invalidConfiguration();
        if (input.repository() != null) urls(input);
    }

    @Override
    public CheckOutcome probe(ForgeCheckInput input, CheckBudget budget) {
        var operations = new ApiCheckOperations(clock.instant());
        String token = input.credentials().get("apiToken");
        if (input.scope() == Scope.FORGE_API) {
            operations.observe("rateLimit", GitHubMessages.format("forge.gitHubConnectionProbe.the.github.rate.limit.endpoint.responded"),
                    () -> read(URI.create(GitHubUrls.apiBase(input.baseUrl()) + "/rate_limit"), GitHubUrls.apiBase(input.baseUrl()), token, budget, node -> GitHubPayload.object(node.path("resources"))));
        } else {
            var urls = urls(input);
            operations.observe("metadata", GitHubMessages.format("forge.gitHubConnectionProbe.the.repository.metadata.matched.the.saved.repository.identity"),
                    () -> read(urls.rest(""), urls.apiBase(), token, budget, node -> {
                        if (!urls.repositoryId().equals(GitHubPayload.id(node, "id"))
                                || !input.repository().path().equalsIgnoreCase(GitHubPayload.text(node, "full_name"))) {
                            throw GitHubErrors.identityMismatch();
                        }
                    }));
            operations.observe("openMergeRequests", GitHubMessages.format("forge.gitHubConnectionProbe.the.first.page.of.open.pull.requests.was.readable"),
                    () -> read(urls.page("/pulls", Map.of("state", "open"), 1, 1), urls.apiBase(), token, budget, node -> {
                        var rows = GitHubPayload.array(node);
                        if (rows.size() > 1) throw GitHubErrors.invalidResponse();
                        for (var row : rows) {
                            if (!"open".equals(GitHubPayload.requiredText(row, "state"))) throw GitHubErrors.invalidResponse();
                            if (!urls.repositoryId().equals(GitHubPayload.pullIdentity(row).key().providerRepositoryId())) {
                                throw GitHubErrors.identityMismatch();
                            }
                        }
                    }));
            operations.observe("workflowRuns", GitHubMessages.format("forge.gitHubConnectionProbe.the.first.page.of.workflow.runs.was.readable"),
                    () -> read(urls.page("/actions/runs", Map.of(), 1, 1), urls.apiBase(), token, budget, node -> {
                        if (GitHubPayload.array(node.path("workflow_runs")).size() > 1) throw GitHubErrors.invalidResponse();
                    }));
        }
        return operations.outcome();
    }

    private Observation read(URI uri, URI apiBase, String token, CheckBudget budget, Consumer<JsonNode> validate) {
        var response = transport.getForCheck(uri, token, budget, apiBase);
        if (response.status() != 200) {
            var failure = GitHubErrors.remoteStatus(response.status(), response.headers(), response.body());
            return new Observation(OperationStatus.FAILED, failure.getCode(), response.headers());
        }
        JsonNode body = payload.body(response);
        if (body == null || body.isMissingNode()) throw GitHubErrors.invalidResponse();
        validate.accept(body);
        return null;
    }

    private static GitHubUrls urls(ForgeCheckInput input) {
        var repository = input.repository();
        return GitHubUrls.from(new Source(repository.id(), null, input.instanceKey(), ForgeProvider.GITHUB,
                input.baseUrl(), 0, repository.externalId(), repository.path()));
    }
}
