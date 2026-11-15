package io.givops.forge.github;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.CredentialHost;
import io.givops.adapters.CredentialLease;
import io.givops.adapters.CredentialRequest;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.port.TicketProviderAdapter;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/** Host owns storage, rotation, destination policy and transaction freshness. */
public final class GitHubCredentials {
    private final CredentialHost host;
    private final ContextSigner signer;
    public GitHubCredentials(CredentialHost host, ContextSigner signer) { this.host = host; this.signer = signer; }
    record Captured(InstanceSource source, URI baseUrl, String token, CredentialLease lease, String context)
            implements TicketProviderAdapter.Access {
        @Override public String toString() { return "Captured[redacted]"; }
    }
    Captured capture(InstanceSource source) {
        if (!"GITHUB".equals(source.provider().name())) throw invalid();
        URI base = GitHubUrls.normalize(source.baseUrl());
        try {
            CredentialLease lease = host.capture(new CredentialRequest(source.instanceId(), source.instanceKey(), "GITHUB", base));
            if (!Objects.equals(source.instanceKey(), lease.destinations().get("instanceKey"))
                    || !base.toString().equals(lease.destinations().get("baseUrl"))) throw invalid();
            String token = lease.fields().get("apiToken");
            if (!validToken(token)) throw invalid();
            String context = signer.sign("GITHUB", "ticketing", List.of(source.instanceId().toString(),
                    source.instanceKey(), base.toString(), Long.toString(source.revision()), token,
                    Objects.toString(lease.accessRevision(), ""), lease.mode()));
            lease.requireCurrent(false);
            return new Captured(source, base, token, lease, context);
        } catch (CredentialAccessException failure) {
            throw new TicketException(Code.MISSING_CREDENTIALS, "GitHub credentials are unavailable.");
        }
    }
    Captured require(InstanceSource source, TicketProviderAdapter.Access access, boolean locked) {
        if (!(access instanceof Captured value) || !value.source().equals(source)) throw invalid();
        try { value.lease().requireCurrent(locked); }
        catch (CredentialAccessException failure) { throw new TicketException(Code.SUPERSEDED, "GitHub credentials changed."); }
        return value;
    }
    static boolean validToken(String token) { return token != null && token.length() <= 4096 && token.matches("[!-~]+"); }
    static TicketException invalid() { return new TicketException(Code.INVALID_CONFIGURATION, "The GitHub connection configuration is invalid."); }
}
