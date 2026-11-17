package io.givops.forge.github;

import io.givops.catalog.ForgeProvider;
import io.givops.forge.discovery.DiscoveryModels.Server;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.model.ForgeProvisioningModels.*;
import io.givops.forge.port.ForgeAdapter.Access;
import io.givops.forge.port.ForgeProvisioningAdapter;
import io.givops.forge.port.ForgeRequestGate;
import java.net.URI;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Empty private repository creation for explicit personal or organization owners. */
public final class GitHubRepositories implements ForgeProvisioningAdapter {
    private final GitHubTransport transport;
    private final JsonMapper mapper;
    private final GitHubPayload payload;
    GitHubRepositories(GitHubTransport transport,JsonMapper mapper) { this.transport=transport;this.mapper=mapper;this.payload=new GitHubPayload(mapper); }
    @Override public boolean supports(ForgeProvider provider,Hosting hosting) { return provider==ForgeProvider.GITHUB && (hosting==Hosting.SAAS || hosting==Hosting.SELF_MANAGED); }
    @Override public Repository createRepository(Server server,CreateRepositoryCommand command,Access access,ForgeRequestGate gate) {
        if(command==null) throw GitHubErrors.invalidConfiguration(); validate(command.namespacePath(),command.name()); requireAccess(access);
        if(!command.privateRepository() || command.createNamespaces() || command.defaultBranch()!=null && !command.defaultBranch().isBlank()) throw GitHubErrors.invalidConfiguration();
        var row=createProject(transport,mapper,server.baseUrl(),access.token(),command.namespacePath(),command.name(),command.description(),gate);
        String id=GitHubPayload.id(row,"id");
        var result=findRepository(server,command.namespacePath(),command.name(),access,gate).orElseThrow(GitHubErrors::invalidResponse);
        if(!id.equals(result.externalId()) || !result.privateRepository() || !result.empty()) throw GitHubErrors.invalidResponse();
        return result;
    }
    /** Shared by both forge and ticketing creation. An existing repository is never adopted by this operation. */
    static JsonNode createProject(GitHubTransport transport,JsonMapper mapper,URI base,String token,String owner,String name,String description,ForgeRequestGate gate) {
        var payload=new GitHubPayload(mapper); URI api=GitHubUrls.apiBase(base);
        var user=payload.body(transport.get(URI.create(api+"/user"),token,gate,api));
        String endpoint=owner.equalsIgnoreCase(GitHubPayload.requiredText(user,"login"))?"/user/repos":"/orgs/"+owner+"/repos";
        var body=mapper.createObjectNode().put("name",name).put("private",true).put("has_issues",true).put("auto_init",false);
        if(description!=null) body.put("description",description);
        byte[] bytes=mapper.writeValueAsBytes(body);if(bytes.length>65_536) throw GitHubErrors.invalidConfiguration();
        var response=transport.post(URI.create(api+endpoint),token,bytes,gate,api);
        if(response.status()!=201) throw GitHubErrors.remoteStatus(response.status(),response.headers(),response.body());
        gate.acceptedObjects(1);return payload.body(response);
    }
    @Override public Optional<Repository> findRepository(Server server,String namespacePath,String name,Access access,ForgeRequestGate gate) {
        requireAccess(access); validate(namespacePath,name); URI api=GitHubUrls.apiBase(server.baseUrl()); String path=namespacePath+"/"+name;
        var response=transport.getForCheck(URI.create(api+"/repos/"+path),access.token(),gate,api);
        if(response.status()==404) return Optional.empty();
        if(response.status()!=200) throw GitHubErrors.remoteStatus(response.status(),response.headers(),response.body());
        var row=payload.body(response); gate.acceptedObjects(1);
        String actual=GitHubPayload.requiredText(row,"full_name"); if(!path.equalsIgnoreCase(actual)) throw GitHubErrors.identityMismatch();
        String web=GitHubPayload.requiredText(row,"html_url"),clone=GitHubPayload.requiredText(row,"clone_url");
        if(!web.equals(server.baseUrl().toString().replaceAll("/+$","")+"/"+actual) || !clone.equals(web+".git") || !row.path("private").isBoolean()) throw GitHubErrors.invalidResponse();
        // List heads and tags explicitly: repository size alone cannot prove an empty Git store.
        boolean empty=true;
        for(String collection:java.util.List.of("branches","tags")) {
            var refs=transport.get(URI.create(api+"/repos/"+actual+"/"+collection+"?per_page=1&page=1"),access.token(),gate,api);
            var rows=GitHubPayload.array(payload.body(refs));
            if(rows.size()>1) throw GitHubErrors.invalidResponse();
            gate.acceptedObjects(rows.size());empty &= rows.isEmpty();
        }
        int slash=actual.indexOf('/');
        return Optional.of(new Repository(GitHubPayload.id(row,"id"),actual,actual.substring(0,slash),actual.substring(slash+1),GitHubPayload.text(row,"description"),clone,web,GitHubPayload.text(row,"default_branch"),row.path("private").booleanValue(),empty));
    }
    @Override public void setDefaultBranch(Server server,String externalId,String fullPath,String branch,Access access,ForgeRequestGate gate) {
        int slash=fullPath.indexOf('/'); if(slash<1 || !GitHubUrls.validId(externalId) || branch==null || !branch.matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}") || branch.contains("..") || branch.endsWith("/") || branch.endsWith(".lock")) throw GitHubErrors.invalidConfiguration();
        var before=findRepository(server,fullPath.substring(0,slash),fullPath.substring(slash+1),access,gate).orElseThrow(GitHubErrors::invalidResponse);
        if(!externalId.equals(before.externalId())) throw GitHubErrors.identityMismatch();
        URI api=GitHubUrls.apiBase(server.baseUrl());
        if(branch.equals(before.defaultBranch())) return;
        io.givops.forge.model.ForgeException writeFailure=null;
        try {
            var response=transport.patch(URI.create(api+"/repos/"+fullPath),access.token(),mapper.writeValueAsBytes(java.util.Map.of("default_branch",branch)),gate,api);
            if(response.status()!=200) throw GitHubErrors.remoteStatus(response.status(),response.headers(),response.body());
        } catch(io.givops.forge.model.ForgeException failure) {writeFailure=failure;}
        var actual=findRepository(server,fullPath.substring(0,slash),fullPath.substring(slash+1),access,gate).orElseThrow(GitHubErrors::invalidResponse);
        if(!externalId.equals(actual.externalId())) throw GitHubErrors.identityMismatch();
        if(!branch.equals(actual.defaultBranch())) {if(writeFailure!=null) throw writeFailure;throw GitHubErrors.invalidResponse();}
    }
    private static void validate(String namespace,String name) { if(namespace==null || name==null || !GitHubUrls.validRepositoryPath(namespace+"/"+name)) throw GitHubErrors.invalidConfiguration(); }
    private static void requireAccess(Access access) { if(access==null || access.token()==null || !access.token().matches("[!-~]+")) throw GitHubErrors.invalidConfiguration(); }
}
