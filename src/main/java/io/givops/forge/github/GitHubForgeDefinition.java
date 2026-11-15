package io.givops.forge.github;

import io.givops.adapters.CredentialField;
import io.givops.adapters.CredentialRequest;
import io.givops.adapters.RuntimeCredential;
import java.util.Optional;
import io.givops.catalog.ForgeProvider;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.port.ForgeProviderDefinition;
import java.net.URI;
import java.util.List;
import java.util.Map;

public final class GitHubForgeDefinition implements ForgeProviderDefinition {
    private final GitHubTicketDefinition shared;
    public GitHubForgeDefinition() { this(new GitHubProperties(Map.of())); }
    public GitHubForgeDefinition(GitHubProperties runtime) { shared = new GitHubTicketDefinition(runtime); }
    @Override public ForgeProvider provider() { return ForgeProvider.GITHUB; }
    @Override public String displayName() { return shared.displayName(); }
    @Override public String adapterId() { return shared.adapterId(); }
    @Override public String adapterVersion() { return shared.adapterVersion(); }
    @Override public List<String> apiProfiles() { return shared.apiProfiles(); }
    @Override public List<String> capabilities() { return List.of("forge.read", "forge.branch-pipeline", "forge.repository-identity", "forge.project-discovery"); }
    @Override public List<CredentialField> credentialFields() { return shared.credentialFields(); }
    @Override public URI normalizeBaseUrl(URI value) { return shared.normalizeBaseUrl(value); }
    @Override public Map<String,String> validateCredentials(CredentialRequest target,Map<String,String> fields) { return shared.validateCredentials(target,fields); }
    @Override public Optional<RuntimeCredential> runtimeCredentials(CredentialRequest target) { return shared.runtimeCredentials(target); }
    @Override public Hosting defaultHosting() { return Hosting.SAAS; }
    @Override public List<Hosting> hostingModes() { return List.of(Hosting.SAAS,Hosting.SELF_MANAGED); }
    @Override public String repositoryPathPattern() { return shared.projectKeyPattern(); }
    @Override public String externalIdPattern() { return shared.externalIdPattern(); }
    @Override public String commitPathTemplate() { return "/commit/{sha}"; }
    @Override public void validateRepository(String id,String path) { shared.validateProject(id,path); }
}
