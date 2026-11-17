package io.givops.forge.github;

import io.givops.adapters.CapabilityInspection;
import io.givops.adapters.CapabilityInspection.State;
import io.givops.catalog.ForgeProvider;
import io.givops.forge.model.ForgeException;
import io.givops.forge.model.ForgeModels.Source;
import io.givops.forge.port.ForgeAdapter;
import io.givops.forge.port.ForgeCapabilityInspector;
import io.givops.forge.port.ForgeRequestGate;
import java.time.Clock;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

public final class GitHubForgeInspector implements ForgeCapabilityInspector {
    private final GitHubTransport transport;
    private final GitHubPayload payload;
    private final Clock clock;
    GitHubForgeInspector(GitHubTransport transport,JsonMapper mapper,Clock clock) { this.transport=transport;this.payload=new GitHubPayload(mapper);this.clock=clock; }
    @Override public ForgeProvider provider() { return ForgeProvider.GITHUB; }
    @Override public CapabilityInspection inspect(Source source,String capability,ForgeAdapter.Access access,ForgeRequestGate gate) {
        String profile="github.com".equalsIgnoreCase(source.baseUrl().getHost())?"github-cloud-rest-2026-03-10":"github-enterprise-rest-2022-11-28";
        String context=io.givops.forge.discovery.DiscoveryValues.hash(source.baseUrl()+"\n"+source.forgeId()+"\n"+source.projectId()+"\n"+access.fields());
        if(!new GitHubForgeDefinition().capabilities().contains(capability)) return new CapabilityInspection("GITHUB",capability,State.UNSUPPORTED,"Not implemented",null,profile,context,clock.instant());
        if ("forge.repository-create".equals(capability)) {
            if (access.token()==null) return new CapabilityInspection("GITHUB",capability,State.FORBIDDEN,"Authenticated provisioning access required",null,profile,context,clock.instant());
            try {
                var api=GitHubUrls.apiBase(source.baseUrl());
                var response=transport.getForCheck(java.net.URI.create(api+"/user"),access.token(),gate,api);
                if(response.status()!=200) throw GitHubErrors.remoteStatus(response.status(),response.headers(),response.body());
                return new CapabilityInspection("GITHUB",capability,State.UNKNOWN,"Owner and repository creation permission require explicit command",
                        response.headers().firstValue("X-GitHub-Enterprise-Version").orElse(null),profile,context,clock.instant());
            } catch(ForgeException failure) {
                State state=switch(failure.getCode()) {
                    case "github_unauthorized", "github_forbidden" -> State.FORBIDDEN;
                    case "github_rate_limited", "github_timeout", "github_unavailable", "github_transport_failed" -> State.TEMPORARILY_UNAVAILABLE;
                    case "github_api_unsupported" -> State.UNSUPPORTED;
                    default -> State.UNKNOWN;
                };
                return new CapabilityInspection("GITHUB",capability,state,failure.getCode(),null,profile,context,clock.instant());
            }
        }
        if (source.projectId()==null || source.repositoryPath()==null) return new CapabilityInspection("GITHUB",capability,State.UNKNOWN,"Repository context required",null,profile,context,clock.instant());
        var urls=GitHubUrls.from(source);
        try {
            var response=transport.getForCheck(urls.rest(""),access.token(),gate,urls.apiBase());
            State state=switch(response.status()) {case 200 -> State.AVAILABLE;case 401,403 -> State.FORBIDDEN;case 410 -> State.UNSUPPORTED;case 429 -> State.TEMPORARILY_UNAVAILABLE;default -> response.status()>=500?State.TEMPORARILY_UNAVAILABLE:State.UNKNOWN;};
            if(response.status()==200) {
                var row=payload.body(response);
                if(!source.projectId().equals(GitHubPayload.id(row,"id"))) throw GitHubErrors.identityMismatch();
                if ("forge.read".equals(capability) || "forge.branch-pipeline".equals(capability)) {
                    String endpoint="forge.branch-pipeline".equals(capability)?"/actions/runs":"/pulls";
                    var probe=transport.getForCheck(urls.page(endpoint,java.util.Map.of(),1,1),access.token(),gate,urls.apiBase());
                    state=switch(probe.status()) {case 200 -> State.AVAILABLE;case 401,403 -> State.FORBIDDEN;case 410 -> State.UNSUPPORTED;case 429 -> State.TEMPORARILY_UNAVAILABLE;default -> probe.status()>=500?State.TEMPORARILY_UNAVAILABLE:State.UNKNOWN;};
                    if (probe.status()==200) {
                        var body=payload.body(probe);
                        var rows=GitHubPayload.array("/actions/runs".equals(endpoint)?body.path("workflow_runs"):body);
                        if(rows.size()>1) throw GitHubErrors.invalidResponse();
                        gate.acceptedObjects(rows.size());
                    } else if(probe.status()==403 && "github_rate_limited".equals(GitHubErrors.remoteStatus(403,probe.headers(),probe.body()).getCode())) state=State.TEMPORARILY_UNAVAILABLE;
                } else if ("forge.project-discovery".equals(capability)) state=State.UNKNOWN;
            } else if(response.status()==403 && "github_rate_limited".equals(GitHubErrors.remoteStatus(403,response.headers(),response.body()).getCode())) state=State.TEMPORARILY_UNAVAILABLE;
            return new CapabilityInspection("GITHUB",capability,state,"Repository metadata inspection",response.headers().firstValue("X-GitHub-Enterprise-Version").orElse(null),profile,context,clock.instant());
        } catch(ForgeException failure) {
            State state=Set.of("github_timeout","github_unavailable","github_transport_failed").contains(failure.getCode())?State.TEMPORARILY_UNAVAILABLE:State.UNKNOWN;
            return new CapabilityInspection("GITHUB",capability,state,failure.getCode(),null,profile,context,clock.instant());
        }
    }
}
