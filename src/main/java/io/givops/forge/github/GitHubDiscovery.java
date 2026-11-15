package io.givops.forge.github;

import io.givops.catalog.ForgeProvider;
import io.givops.forge.discovery.DiscoveryModels.Namespace;
import io.givops.forge.discovery.DiscoveryModels.RemoteGroup;
import io.givops.forge.discovery.DiscoveryModels.RemotePage;
import io.givops.forge.discovery.DiscoveryModels.RemoteProject;
import io.givops.forge.discovery.DiscoveryModels.Server;
import io.givops.forge.discovery.DiscoveryValues;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.model.ForgeException;
import io.givops.forge.port.ForgeDiscoveryAdapter;
import io.givops.forge.port.ForgeAdapter;
import io.givops.forge.port.ForgeRequestGate;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded authenticated repository discovery; no search API result-cap truncation. */
public final class GitHubDiscovery implements ForgeDiscoveryAdapter {
    private final GitHubTransport transport;
    private final GitHubPayload payload;
    GitHubDiscovery(GitHubTransport transport,JsonMapper mapper) { this.transport=transport; this.payload=new GitHubPayload(mapper); }
    @Override public Support support(ForgeProvider provider,Hosting hosting) { return new Support(ForgeProvider.GITHUB.equals(provider) && (hosting==Hosting.SAAS || hosting==Hosting.SELF_MANAGED),false); }
    @Override public RemotePage<RemoteGroup> groups(Server server,ForgeAdapter.Access access,String query,int limit,int page,long lastId,ForgeRequestGate gate) {
        throw new ForgeException("forge_provider_unsupported",HttpStatus.UNPROCESSABLE_CONTENT,"GitHub group discovery is unavailable.");
    }
    @Override public RemotePage<RemoteProject> projects(Server server,ForgeAdapter.Access access,String groupId,String query,int limit,int page,long lastId,ForgeRequestGate gate) {
        if(groupId!=null || access.token()==null || limit<1 || limit>100 || page<1 || page>100 || lastId!=0) throw GitHubErrors.invalidConfiguration();
        URI api=GitHubUrls.apiBase(server.baseUrl());
        URI uri=URI.create(api+"/user/repos?sort=full_name&per_page="+limit+"&page="+page);
        var response=transport.get(uri,access.token(),gate,api);
        var rows=GitHubPayload.array(payload.body(response)); if(rows.size()>limit) throw GitHubErrors.invalidResponse();
        gate.acceptedObjects(rows.size());
        // Follow only a provider pagination signal; subsequent requests are reconstructed locally.
        boolean next=false;
        for(String value:response.headers().allValues("Link")) {
            if(value.length()>4096) throw GitHubErrors.invalidPagination();
            for(String part:value.split(",")) {
                var match=java.util.regex.Pattern.compile("\\s*<([^<>]+)>;\\s*rel=\"(next|prev|first|last)\"\\s*").matcher(part);
                if(!match.matches()) throw GitHubErrors.invalidPagination();
                URI target=URI.create(match.group(1));
                if(!GitHubUrls.sameApi(target,api) || !target.getRawPath().equals(api.getRawPath()+"/user/repos")) throw GitHubErrors.invalidPagination();
                if("next".equals(match.group(2))) {
                    if(next || rows.isEmpty()) throw GitHubErrors.invalidPagination();
                    var params=new java.util.HashMap<String,String>();
                    if(target.getRawQuery()==null) throw GitHubErrors.invalidPagination();
                    for(String pair:target.getRawQuery().split("&")) {var kv=pair.split("=",2);if(kv.length!=2 || params.put(kv[0],kv[1])!=null) throw GitHubErrors.invalidPagination();}
                    if(!params.equals(java.util.Map.of("sort","full_name","per_page",Integer.toString(limit),"page",Integer.toString(page+1)))) throw GitHubErrors.invalidPagination();
                    next=true;
                }
            }
        }
        if(next && page==100) throw GitHubErrors.budgetExceeded();
        String search=query==null ? "" : query.toLowerCase(Locale.ROOT);
        return new RemotePage<>(rows.stream().map(row -> project(server,row)).filter(p -> p.fullPath().toLowerCase(Locale.ROOT).contains(search)).toList(),next,0);
    }
    @Override public RemoteProject project(Server server,ForgeAdapter.Access access,String externalId,ForgeRequestGate gate) {
        if(!GitHubUrls.validId(externalId)) throw GitHubErrors.invalidConfiguration();
        URI api=GitHubUrls.apiBase(server.baseUrl());
        var row=payload.body(transport.get(URI.create(api+"/repositories/"+externalId),access.token(),gate,api));
        if(!externalId.equals(GitHubPayload.id(row,"id"))) throw GitHubErrors.identityMismatch();
        gate.acceptedObjects(1);return project(server,row);
    }
    private RemoteProject project(Server server,JsonNode row) {
        String id=GitHubPayload.id(row,"id"), path=GitHubPayload.requiredText(row,"full_name");
        if(!GitHubUrls.validRepositoryPath(path)) throw GitHubErrors.invalidResponse();
        String name=GitHubPayload.requiredText(row,"name"), base=GitHubUrls.normalize(server.baseUrl()).toString();
        var owner=row.path("owner");String login=GitHubPayload.requiredText(owner,"login");
        if(!path.startsWith(login+"/")) throw GitHubErrors.invalidResponse();
        var namespace=new Namespace(GitHubPayload.id(owner,"id"),GitHubPayload.requiredText(owner,"type"),login,login);
        String web=base+"/"+path,clone=web+".git";
        return new RemoteProject(id,name,path,clone,web,namespace,DiscoveryValues.hash(id+"\n"+path+"\n"+clone),DiscoveryValues.suggestedKey(path,id));
    }
}
