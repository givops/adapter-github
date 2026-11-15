package io.givops.forge.github;

import io.givops.adapters.CredentialField;
import io.givops.adapters.CredentialRequest;
import io.givops.adapters.RuntimeCredential;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketProviderDefinition;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class GitHubTicketDefinition implements TicketProviderDefinition {
    private final GitHubProperties runtime;
    public GitHubTicketDefinition() { this(new GitHubProperties(Map.of())); }
    public GitHubTicketDefinition(GitHubProperties runtime) { this.runtime = java.util.Objects.requireNonNull(runtime); }
    @Override public TicketProvider provider() { return TicketProvider.GITHUB; }
    @Override public String displayName() { return "GitHub"; }
    @Override public String adapterId() { return "io.givops:adapter-github"; }
    @Override public String adapterVersion() { return "0.2.0-SNAPSHOT"; }
    @Override public List<String> capabilities() { return List.of("ticketing.read", "ticketing.version-membership",
            "ticketing.project-create", "ticketing.version-create", "ticketing.issue-create"); }
    @Override public List<String> apiProfiles() { return List.of("github-cloud-rest-2026-03-10", "github-enterprise-rest-2022-11-28"); }
    @Override public List<CredentialField> credentialFields() { return List.of(new CredentialField("apiToken", "API token", CredentialField.Type.SECRET, true, 4096)); }
    @Override public Map<String,String> commandLabels() { return Map.of("projectKey", "Repository path (owner/name)",
            "projectName", "Repository display name", "projectOwner", "Owner is taken from repository path",
            "projectResource", "Private repository", "versionResource", "Milestone", "issueResource", "Issue"); }
    @Override public String projectKeyPattern() { return "[A-Za-z0-9-]{1,100}/[A-Za-z0-9_.-]{1,100}"; }
    @Override public String externalIdPattern() { return "[1-9][0-9]{0,18}"; }
    @Override public URI normalizeBaseUrl(URI value) { return GitHubUrls.normalize(value); }
    @Override public void validateProject(String id, String key) {
        if (!GitHubUrls.validId(id) || !GitHubUrls.validRepositoryPath(key)) throw GitHubCredentials.invalid();
    }
    @Override public void validateProjectKey(String key) { if (!GitHubUrls.validRepositoryPath(key)) throw GitHubCredentials.invalid(); }
    @Override public Optional<String> normalizeIssueKey(String token, String projectKey) {
        if (!GitHubUrls.validRepositoryPath(projectKey) || token == null) return Optional.empty();
        var match = Pattern.compile("(?:(" + projectKeyPattern() + "))?#([1-9][0-9]{0,18})").matcher(token);
        if (!match.matches() || !GitHubUrls.validId(match.group(2))
                || match.group(1) != null && !projectKey.equalsIgnoreCase(match.group(1))) return Optional.empty();
        return Optional.of(projectKey.toLowerCase(Locale.ROOT) + "#" + match.group(2));
    }
    @Override public Optional<IssueReference> issueReference(URI candidate) {
        if (candidate == null || candidate.getRawPath() == null) return Optional.empty();
        var match = Pattern.compile("/(" + projectKeyPattern() + ")/issues/([1-9][0-9]{0,18})/?").matcher(candidate.getRawPath());
        if (!match.matches() || !GitHubUrls.validId(match.group(2))) return Optional.empty();
        return Optional.of(new IssueReference(match.group(1).toLowerCase(Locale.ROOT) + "#" + match.group(2), ""));
    }
    @Override public Optional<RuntimeCredential> runtimeCredentials(CredentialRequest target) {
        if (target == null || !"GITHUB".equals(target.provider()) || target.instanceKey() == null) return Optional.empty();
        var configured = runtime.instances().get(target.instanceKey());
        if (configured == null) return Optional.empty();
        Map<String,String> destinations;
        try { destinations = Map.of("baseUrl", normalizeBaseUrl(URI.create(configured.expectedBaseUrl())).toString(), "instanceKey", target.instanceKey()); }
        catch (RuntimeException failure) { destinations = Map.of("baseUrl", ""); }
        return Optional.of(new RuntimeCredential(Map.of("apiToken", java.util.Objects.toString(configured.token(), "")), destinations, configured.accessRevision()));
    }
    @Override public Map<String,String> validateCredentials(CredentialRequest target, Map<String,String> fields) {
        if (target == null || !"GITHUB".equals(target.provider()) || target.instanceId() == null || target.instanceKey() == null
                || fields == null || fields.size() != 1 || !GitHubCredentials.validToken(fields.get("apiToken"))) throw GitHubCredentials.invalid();
        normalizeBaseUrl(target.baseUrl());
        return Map.of("apiToken", fields.get("apiToken"));
    }
}
