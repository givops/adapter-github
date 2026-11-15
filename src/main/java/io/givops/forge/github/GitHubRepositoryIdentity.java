package io.givops.forge.github;

import io.givops.catalog.ForgeProvider;
import io.givops.forge.model.ForgeException;
import io.givops.forge.port.ForgeRepositoryIdentity;
import io.givops.forge.port.ForgeRequestGate;
import java.net.URI;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/** Anonymous remote lookup only; catalog access and concurrency policy belong to the host. */
public final class GitHubRepositoryIdentity implements ForgeRepositoryIdentity {
    private final GitHubTransport transport;
    private final GitHubPayload payload;
    GitHubRepositoryIdentity(GitHubTransport transport, JsonMapper mapper) {
        this.transport = transport;
        this.payload = new GitHubPayload(mapper);
    }
    @Override public ForgeProvider provider() { return ForgeProvider.GITHUB; }
    @Override public Identity lookup(URI baseUrl, String repositoryPath, ForgeRequestGate gate) {
        if (!GitHubUrls.validRepositoryPath(repositoryPath)) throw GitHubErrors.invalidConfiguration();
        URI apiBase = GitHubUrls.apiBase(baseUrl);
        var response = transport.getForCheck(URI.create(apiBase + "/repos/" + repositoryPath), null, gate, apiBase);
        if (response.status() == 404) throw notPublic();
        if (response.status() != 200) {
            var failure = GitHubErrors.remoteStatus(response.status(), response.headers(), response.body());
            if ("github_rate_limited".equals(failure.getCode())) throw new ForgeException(failure.getCode(), HttpStatus.TOO_MANY_REQUESTS, failure.getMessage());
            throw failure;
        }
        var body = payload.body(response);
        String id = GitHubPayload.id(body, "id");
        String path = GitHubPayload.requiredText(body, "full_name");
        Boolean privateRepository = GitHubPayload.flag(body, "private");
        if (Boolean.TRUE.equals(privateRepository)) throw notPublic();
        if (!Boolean.FALSE.equals(privateRepository) || !GitHubUrls.validRepositoryPath(path)
                || !repositoryPath.equalsIgnoreCase(path)) throw GitHubErrors.invalidResponse();
        gate.acceptedObjects(1);
        return new Identity(id, path);
    }
    private static ForgeException notPublic() {
        return new ForgeException("github_repository_not_public", HttpStatus.NOT_FOUND,
                "GitHub did not expose a public repository at this path.");
    }
}
