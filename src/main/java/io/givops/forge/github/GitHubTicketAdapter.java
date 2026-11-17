package io.givops.forge.github;

import io.givops.ticketing.model.TicketBrowserModels.*;
import io.givops.ticketing.port.TicketNavigationAdapter;
import io.givops.ticketing.port.TicketBrowseAdapter;
import io.givops.ticketing.port.TicketContentAdapter;
import io.givops.adapters.CapabilityInspection;
import io.givops.adapters.FailureCategory;
import io.givops.adapters.CapabilityInspection.State;
import io.givops.forge.model.ForgeException;
import io.givops.forge.port.ForgeRequestGate;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.IssueIdentity;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.model.TicketModels.ProjectObservation;
import io.givops.ticketing.model.TicketModels.ProjectQuery;
import io.givops.ticketing.model.TicketModels.ProjectResult;
import io.givops.ticketing.model.TicketModels.ProviderInput;
import io.givops.ticketing.model.TicketModels.ProviderSnapshot;
import io.givops.ticketing.model.TicketModels.ReleaseMappingState;
import io.givops.ticketing.model.TicketModels.ReleaseMembership;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.model.TicketModels.VersionObservation;
import io.givops.ticketing.model.TicketWriteModels.CreateIssueCommand;
import io.givops.ticketing.model.TicketWriteModels.CreateProjectCommand;
import io.givops.ticketing.model.TicketWriteModels.CreateVersionCommand;
import io.givops.ticketing.model.TicketWriteModels.IssueKind;
import io.givops.ticketing.model.TicketWriteModels.PriorityLevel;
import io.givops.ticketing.model.TicketWriteModels.RemoteProjectRef;
import io.givops.ticketing.model.TicketWriteModels.WorkflowState;
import io.givops.ticketing.port.TicketCapabilityInspector;
import io.givops.ticketing.port.TicketProviderAdapter;
import io.givops.ticketing.port.TicketRequestGate;
import io.givops.ticketing.port.TicketWriteAdapter;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Native issues and milestones. Pull requests remain exclusively in forge observations. */
public final class GitHubTicketAdapter implements TicketProviderAdapter, TicketWriteAdapter, TicketCapabilityInspector, TicketNavigationAdapter, TicketBrowseAdapter, TicketContentAdapter {
    private final GitHubCredentials credentials;
    private final TicketProperties limits;
    private final GitHubTransport transport;
    private final GitHubPayload payload;
    private final JsonMapper mapper;
    private final Clock clock;
    private final GitHubTicketDefinition definition = new GitHubTicketDefinition();
    GitHubTicketAdapter(GitHubCredentials credentials, TicketProperties limits, GitHubTransport transport, JsonMapper mapper, Clock clock) {
        this.credentials = credentials; this.limits = limits; this.transport = transport;
        this.payload = new GitHubPayload(mapper); this.mapper = mapper; this.clock = clock;
    }
    @Override public TicketProvider provider() { return TicketProvider.GITHUB; }
    @Override public String authenticationContext(InstanceSource source) { return captureAccess(source).context(); }
    @Override public Access captureAccess(InstanceSource source) { return credentials.capture(source); }
    @Override public boolean current(InstanceSource source, Access access, boolean locked) {
        try { credentials.require(source, access, locked); return true; } catch (TicketException failure) { return false; }
    }
    @Override public void requireCurrent(InstanceSource source, Access access, boolean locked) { credentials.require(source, access, locked); }
    @Override public ProviderSnapshot read(ProviderInput input, TicketRequestGate gate) { return read(input, gate, captureAccess(input.source())); }
    @Override public ProviderSnapshot read(ProviderInput input, TicketRequestGate gate, Access access) {
        return translated(() -> {
            var captured = credentials.require(input.source(), access, false);
            if (input.projects().size() > limits.maxBindings()) throw limit();
            var results = new ArrayList<ProjectResult>();
            for (var query : input.projects()) {
                var urls = urls(captured, query.externalProjectId(), query.projectKey());
                var project = project(get(urls, "", captured, gate), urls);
                var versions = milestones(urls, captured, gate);
                var issues = new LinkedHashMap<String,IssueObservation>();
                var unresolved = new LinkedHashSet<String>();
                if (query.issueKeys().size() > limits.maxIssueKeys()) throw limit();
                for (String key : query.issueKeys()) {
                    String normalized = definition.normalizeIssueKey(key, query.projectKey()).orElseThrow(GitHubCredentials::invalid);
                    String number = normalized.substring(normalized.lastIndexOf('#') + 1);
                    var response = transport.getForCheck(urls.rest("/issues/" + number), captured.token(), gate(captured, gate), urls.apiBase());
                    if (response.status() == 404) {
                        // A missing issue and revoked Issues permission both appear as 404. Only
                        // retain an unresolved reference after verifying the collection is readable.
                        var visibility = transport.get(urls.page("/issues", Map.of("state", "all"), 1, 1),
                                captured.token(), gate(captured, gate), urls.apiBase());
                        var visibleIssues = GitHubPayload.array(payload.body(visibility));
                        if (visibleIssues.size() > 1) throw GitHubErrors.invalidResponse();
                        gate.acceptedObjects(visibleIssues.size());
                        unresolved.add(normalized); continue;
                    }
                    require(response, 200);
                    var node = payload.body(response);
                    if (node.has("pull_request")) { unresolved.add(normalized); continue; }
                    if (!number.equals(GitHubPayload.id(node, "number"))) throw GitHubErrors.identityMismatch();
                    var issue = issue(node, urls);
                    gate.acceptedObjects(1); issues.put(issue.identity().externalIssueId(), issue);
                }
                ReleaseMembership membership = new ReleaseMembership(ReleaseMappingState.NOT_REQUESTED, null, List.of(), clock.instant());
                if (query.releaseVersionName() != null) {
                    var matching = versions.stream().filter(v -> query.releaseVersionName().equals(v.name())).toList();
                    var state = matching.isEmpty() ? ReleaseMappingState.NOT_FOUND : matching.size() > 1 ? ReleaseMappingState.AMBIGUOUS : ReleaseMappingState.MATCHED;
                    if (state == ReleaseMappingState.MATCHED) {
                        String version = matching.getFirst().externalVersionId();
                        var members = members(urls, version, captured, gate, limits.maxReleaseIssues());
                        members.forEach(i -> issues.put(i.identity().externalIssueId(), i));
                        membership = new ReleaseMembership(state, version, members.stream().map(IssueObservation::identity).toList(), clock.instant());
                    } else membership = new ReleaseMembership(state, null, List.of(), clock.instant());
                }
                results.add(new ProjectResult(query.ticketProjectId(), project, versions, List.copyOf(issues.values()), unresolved, membership));
            }
            credentials.require(input.source(), captured, false);
            return new ProviderSnapshot(results);
        });
    }
    @Override public VersionMemberships readVersionMemberships(InstanceSource source, ProjectQuery query, Access access,
            TicketRequestGate gate, Function<List<VersionObservation>,Set<String>> select, int maximumVersions, int maximumIssues) {
        return translated(() -> {
            var captured = credentials.require(source, access, false);
            var urls = urls(captured, query.externalProjectId(), query.projectKey());
            project(get(urls, "", captured, gate), urls);
            var versions = milestones(urls, captured, gate);
            Set<String> selected = Set.copyOf(select.apply(versions));
            if (selected.size() > maximumVersions || maximumVersions < 0 || maximumIssues < 0) throw limit();
            Set<String> known = versions.stream().map(VersionObservation::externalVersionId).collect(java.util.stream.Collectors.toSet());
            if (!known.containsAll(selected)) throw GitHubCredentials.invalid();
            var result = new LinkedHashMap<String,List<IssueObservation>>();
            int count = 0;
            for (String id : selected) {
                var members = members(urls, id, captured, gate, maximumIssues - count);
                count += members.size(); result.put(id, members);
            }
            credentials.require(source, captured, false);
            return new VersionMemberships(result);
        });
    }
    private List<VersionObservation> milestones(GitHubUrls urls, GitHubCredentials.Captured captured, TicketRequestGate gate) {
        return pages(urls, "/milestones", Map.of("state", "all"), captured, gate).stream().map(this::version).toList();
    }
    private List<IssueObservation> members(GitHubUrls urls, String milestone, GitHubCredentials.Captured captured, TicketRequestGate gate, int maximum) {
        if (!GitHubUrls.validId(milestone)) throw GitHubCredentials.invalid();
        var result = new ArrayList<IssueObservation>();
        for (var row : pages(urls, "/issues", Map.of("state", "all", "milestone", milestone), captured, gate)) {
            if (row.has("pull_request")) continue;
            var issue = issue(row, urls);
            if (!issue.releaseVersionIds().contains(milestone)) throw GitHubErrors.invalidResponse();
            result.add(issue); if (result.size() > maximum) throw limit();
        }
        return List.copyOf(result);
    }
    private List<JsonNode> pages(GitHubUrls urls, String suffix, Map<String,String> filters, GitHubCredentials.Captured captured, TicketRequestGate gate) {
        var rows = new ArrayList<JsonNode>();
        var identities = new java.util.HashSet<String>();
        for (int page = 1; page <= limits.maxPages(); page++) {
            var response = transport.get(urls.page(suffix, filters, 100, page), captured.token(), gate(captured, gate), urls.apiBase());
            var values = GitHubPayload.array(payload.body(response));
            if (values.size() > 100) throw GitHubErrors.invalidResponse();
            gate.acceptedObjects(values.size());
            for (var row : values) if (!identities.add(GitHubPayload.id(row, "id"))) throw GitHubErrors.invalidPagination();
            rows.addAll(values);
            if (rows.size() > limits.maxObjects()) throw limit();
            if (!GitHubPages.hasNext(urls, suffix, response.headers(), filters, page, 100, values.size())) return List.copyOf(rows);
        }
        throw limit();
    }
    private JsonNode get(GitHubUrls urls, String suffix, GitHubCredentials.Captured captured, TicketRequestGate gate) {
        return payload.body(transport.get(urls.rest(suffix), captured.token(), gate(captured, gate), urls.apiBase()));
    }
    private ProjectObservation project(JsonNode row, GitHubUrls urls) {
        if (!urls.repositoryId().equals(GitHubPayload.id(row,"id")) || !(urls.owner()+"/"+urls.repository()).equalsIgnoreCase(GitHubPayload.requiredText(row,"full_name"))) throw GitHubErrors.identityMismatch();
        return new ProjectObservation(urls.repositoryId(), (urls.owner()+"/"+urls.repository()).toLowerCase(Locale.ROOT),
                GitHubPayload.requiredText(row,"name"), URI.create(urls.webUrl("")), clock.instant());
    }
    private VersionObservation version(JsonNode row) {
        String state = GitHubPayload.requiredText(row,"state");
        if (!Set.of("open","closed").contains(state)) throw GitHubErrors.invalidResponse();
        Instant due = GitHubPayload.instant(row,"due_on",false);
        return new VersionObservation(GitHubPayload.id(row,"number"), GitHubPayload.requiredText(row,"title"), false, false,
                due == null ? null : due.atOffset(ZoneOffset.UTC).toLocalDate(), clock.instant(), state, null, GitHubPayload.instant(row,"closed_at",false));
    }
    private IssueObservation issue(JsonNode row, GitHubUrls urls) {
        if (row.has("pull_request")) throw GitHubErrors.invalidResponse();
        String number = GitHubPayload.id(row,"number");
        String repositoryUrl = GitHubPayload.text(row,"repository_url");
        if (repositoryUrl != null && !repositoryUrl.equalsIgnoreCase(urls.rest("").toString())) throw GitHubErrors.identityMismatch();
        String state = GitHubPayload.requiredText(row,"state");
        if (!Set.of("open","closed").contains(state)) throw GitHubErrors.invalidResponse();
        var milestone = row.get("milestone");
        var labels = GitHubPayload.optionalArray(row.get("labels")).stream().map(n -> GitHubPayload.requiredText(n,"name")).toList();
        return new IssueObservation(new IssueIdentity(urls.repositoryId(), GitHubPayload.id(row,"id")),
                (urls.owner()+"/"+urls.repository()).toLowerCase(Locale.ROOT)+"#"+number, GitHubPayload.requiredText(row,"title"),
                "issue", state, "closed".equals(state) ? "done" : "todo", null, GitHubPayload.text(row,"state_reason"),
                GitHubPayload.instant(row,"created_at",true), GitHubPayload.instant(row,"updated_at",true), GitHubPayload.instant(row,"closed_at",false),
                GitHubPayload.absent(milestone) ? List.of() : List.of(GitHubPayload.id(milestone,"number")), URI.create(urls.webUrl("/issues/"+number)),
                clock.instant(), GitHubPayload.absent(row.get("assignee")) ? null : GitHubPayload.text(row.get("assignee"),"login"),
                null, labels);
    }
    @Override public ProjectObservation createProject(InstanceSource source, CreateProjectCommand command, Access access, TicketRequestGate gate) {
        return translated(() -> {
            var captured = credentials.require(source, access, false);
            definition.validateProjectKey(command.projectKey());
            String[] parts = command.projectKey().split("/");
            if (command.leaderExternalId()!=null && !command.leaderExternalId().isBlank() && !parts[0].equalsIgnoreCase(command.leaderExternalId())) throw GitHubCredentials.invalid();
            var row = GitHubRepositories.createProject(transport,mapper,captured.baseUrl(),captured.token(),parts[0],parts[1],command.description(),gate(captured,gate));
            var urls = urls(captured,GitHubPayload.id(row,"id"),command.projectKey());
            return project(row,urls);
        });
    }
    @Override public VersionObservation createVersion(InstanceSource source, RemoteProjectRef project, CreateVersionCommand command, Access access, TicketRequestGate gate) {
        return translated(() -> {
            if (command.released() || command.archived() || command.name()==null || command.name().isBlank()) throw GitHubCredentials.invalid();
            var captured = credentials.require(source,access,false); var urls = urls(captured,project.externalProjectId(),project.projectKey());
            project(get(urls,"",captured,gate),urls);
            var body = mapper.createObjectNode().put("title",command.name()).put("state","open");
            if(command.releaseDate()!=null) body.put("due_on",command.releaseDate().atStartOfDay().toInstant(ZoneOffset.UTC).toString());
            return version(post(urls,"/milestones",body,captured,gate));
        });
    }
    @Override public IssueObservation createIssue(InstanceSource source, RemoteProjectRef project, CreateIssueCommand command, Access access, TicketRequestGate gate) {
        return translated(() -> {
            if (command.summary()==null || command.summary().isBlank() || command.fixVersions().size()+command.versionSelectors().size()>1
                    || !command.fixVersions().isEmpty() && !command.versionSelectors().isEmpty()
                    || command.workflowState()!=null && command.workflowState()!=WorkflowState.OPEN && command.workflowState()!=WorkflowState.DONE
                    || command.providerStatus()!=null || command.providerPriority()!=null
                    || command.priority()!=null && command.priority()!=PriorityLevel.NORMAL
                    || command.providerType()!=null || command.type()!=null && command.type()!=IssueKind.TASK) throw GitHubCredentials.invalid();
            var captured = credentials.require(source,access,false); var urls = urls(captured,project.externalProjectId(),project.projectKey());
            project(get(urls,"",captured,gate),urls);
            var body = mapper.createObjectNode().put("title",command.summary());
            if(command.description()!=null) body.put("body",command.description());
            if(!command.fixVersions().isEmpty() || !command.versionSelectors().isEmpty()) {
                String version;
                if (!command.fixVersions().isEmpty()) version = command.fixVersions().getFirst();
                else {
                    var selector = command.versionSelectors().getFirst();
                    var matches = milestones(urls,captured,gate).stream().filter(v -> switch(selector.kind()) {
                        case NAME -> selector.value().equals(v.name());
                        case EXTERNAL_ID -> selector.value().equals(v.externalVersionId());
                    }).toList();
                    if(matches.size()!=1) throw GitHubCredentials.invalid();
                    version=matches.getFirst().externalVersionId();
                }
                if(!GitHubUrls.validId(version)) throw GitHubCredentials.invalid();
                body.put("milestone",Long.parseLong(version));
            }
            String assignee=command.assigneeExternalId();
            if(command.assignToSelf()) {
                URI api=urls.apiBase();
                var user=payload.body(transport.get(URI.create(api+"/user"),captured.token(),gate(captured,gate),api));
                assignee=GitHubPayload.requiredText(user,"login");
            }
            if(assignee!=null) { if(!assignee.matches("[A-Za-z0-9-]{1,100}")) throw GitHubCredentials.invalid(); body.putArray("assignees").add(assignee); }
            var created=issue(post(urls,"/issues",body,captured,gate),urls);
            if(command.workflowState()!=WorkflowState.DONE) return created;
            String suffix="/issues/"+created.issueKey().substring(created.issueKey().lastIndexOf('#')+1);
            ForgeException closeFailure=null;
            try {
                var response=transport.patch(urls.rest(suffix),captured.token(),mapper.writeValueAsBytes(Map.of("state","closed")),gate(captured,gate),urls.apiBase());
                require(response,200);
            } catch(ForgeException failure) { closeFailure=failure; }
            var actual=issue(get(urls,suffix,captured,gate),urls);
            if(!created.identity().equals(actual.identity()) || !created.issueKey().equals(actual.issueKey())) throw GitHubErrors.identityMismatch();
            if(!"closed".equals(actual.status())) { if(closeFailure!=null) throw closeFailure; throw GitHubErrors.invalidResponse(); }
            return actual;
        });
    }
    private JsonNode post(GitHubUrls urls,String suffix,JsonNode body,GitHubCredentials.Captured captured,TicketRequestGate gate) {
        var response=transport.post(urls.rest(suffix),captured.token(),mapper.writeValueAsBytes(body),gate(captured,gate),urls.apiBase());
        require(response,201); return payload.body(response);
    }
    @Override public CapabilityInspection inspect(InstanceSource source,String externalProjectId,String capability,Access access,TicketRequestGate gate) {
        var captured=credentials.require(source,access,false);
        String profile="github.com".equalsIgnoreCase(captured.baseUrl().getHost()) ? "github-cloud-rest-2026-03-10" : "github-enterprise-rest-2022-11-28";
        if(!definition.capabilities().contains(capability)) return new CapabilityInspection("GITHUB",capability,State.UNSUPPORTED,"Not implemented by this adapter",null,profile,access.context(),clock.instant());
        if(!GitHubUrls.validId(externalProjectId)) return new CapabilityInspection("GITHUB",capability,State.UNKNOWN,"Repository context required",null,profile,access.context(),clock.instant());
        URI api=GitHubUrls.apiBase(captured.baseUrl());
        try {
            var response=transport.getForCheck(URI.create(api+"/repositories/"+externalProjectId),captured.token(),gate(captured,gate),api);
            String product=response.headers().firstValue("X-GitHub-Enterprise-Version").orElse(null);
            State state=switch(response.status()) {case 401,403 -> State.FORBIDDEN; case 404 -> State.UNKNOWN; case 410 -> State.UNSUPPORTED; case 429 -> State.TEMPORARILY_UNAVAILABLE; case 200 -> State.AVAILABLE; default -> response.status()>=500 ? State.TEMPORARILY_UNAVAILABLE : State.UNKNOWN;};
            String reason="Repository metadata inspection";
            if(response.status()==200) {
                var row=payload.body(response);
                if(!externalProjectId.equals(GitHubPayload.id(row,"id"))) throw GitHubErrors.identityMismatch();
                if(Boolean.FALSE.equals(GitHubPayload.flag(row,"has_issues"))) {state=State.DISABLED; reason="Issues are disabled for this repository";}
                else if(capability.contains("create")) {
                    if (Boolean.TRUE.equals(GitHubPayload.flag(row,"archived"))) { state=State.DISABLED; reason="Repository is archived"; }
                    else {state=State.UNKNOWN; reason="Repository visibility does not establish token write permissions";}
                } else {
                    String path=GitHubPayload.requiredText(row,"full_name");
                    var urls=urls(captured,externalProjectId,path);
                    var endpoints="ticketing.version-membership".equals(capability) ? List.of("/milestones","/issues") : List.of("/issues");
                    for (String endpoint:endpoints) {
                        var probe=transport.getForCheck(urls.page(endpoint,Map.of("state","all"),1,1),captured.token(),gate(captured,gate),api);
                        if (probe.status()!=200) {
                            state=switch(probe.status()) {case 401,403 -> State.FORBIDDEN;case 410 -> State.UNSUPPORTED;case 429 -> State.TEMPORARILY_UNAVAILABLE;default -> probe.status()>=500 ? State.TEMPORARILY_UNAVAILABLE : State.UNKNOWN;};
                            if (probe.status()==403 && "github_rate_limited".equals(GitHubErrors.remoteStatus(403,probe.headers(),probe.body()).getCode())) state=State.TEMPORARILY_UNAVAILABLE;
                            reason="Scoped endpoint permission inspection"; break;
                        }
                        var rows=GitHubPayload.array(payload.body(probe));
                        if(rows.size()>1) throw GitHubErrors.invalidResponse();
                        gate.acceptedObjects(rows.size());
                        reason="Scoped endpoint read succeeded";
                    }
                }
            } else if(response.status()==403 && "github_rate_limited".equals(GitHubErrors.remoteStatus(response.status(),response.headers(),response.body()).getCode())) state=State.TEMPORARILY_UNAVAILABLE;
            return new CapabilityInspection("GITHUB",capability,state,reason,product,profile,access.context(),clock.instant());
        } catch(ForgeException failure) {
            State state=Set.of("github_timeout","github_unavailable","github_transport_failed","github_rate_limited").contains(failure.getCode()) ? State.TEMPORARILY_UNAVAILABLE : State.UNKNOWN;
            return new CapabilityInspection("GITHUB",capability,state,failure.getCode(),null,profile,access.context(),clock.instant());
        }
    }
    private GitHubUrls urls(GitHubCredentials.Captured captured,String id,String path) {
        definition.validateProject(id,path); String[] parts=path.split("/"); return new GitHubUrls(parts[0],parts[1],id,captured.baseUrl());
    }
    private ForgeRequestGate gate(GitHubCredentials.Captured captured,TicketRequestGate delegate) {
        return new ForgeRequestGate() {
            @Override public java.time.Duration beforeRequest() { credentials.require(captured.source(),captured,false); return delegate.beforeRequest(); }
            @Override public void receivedBytes(long count) { delegate.receivedBytes(count); }
            @Override public void acceptedObjects(int count) { delegate.acceptedObjects(count); }
            @Override public void check() { credentials.require(captured.source(),captured,false); delegate.check(); }
        };
    }
    private static void require(GitHubTransport.Response response,int status) { if(response.status()!=status) throw GitHubErrors.remoteStatus(response.status(),response.headers(),response.body()); }
    private static TicketException limit() { return new TicketException(Code.LIMIT_EXCEEDED,"GitHub ticketing exceeded its resource budget."); }
    private static <T> T translated(Supplier<T> action) {
        try { return action.get(); } catch(ForgeException failure) {
            Code code = failure.category() == FailureCategory.ACCESS_DENIED
                    ? ("github_unauthorized".equals(failure.getCode()) ? Code.AUTHENTICATION_FAILED : Code.AUTHORIZATION_FAILED)
                    : switch(failure.getCode()) {
                case "github_rate_limited" -> Code.RATE_LIMITED; case "github_timeout" -> Code.TIMEOUT; case "github_api_unsupported" -> Code.UNSUPPORTED_PROVIDER;
                case "github_invalid_response","github_invalid_pagination","github_repository_identity_mismatch" -> Code.INVALID_RESPONSE;
                case "forge_budget_exhausted" -> Code.LIMIT_EXCEEDED; case "github_configuration_invalid" -> Code.INVALID_CONFIGURATION; default -> Code.PROVIDER_ERROR;};
            throw new TicketException(code,failure.getMessage());
        }
    }
    private static final BrowseDescriptor BROWSER = new BrowseDescriptor(
            List.of("text:key-title-literal-nfc", "statuses", "categories", "labels:any"),
            List.of(Lifecycle.ACTIVE), List.of("key-asc", "key-desc", "updated-desc"), false, true, false);

    @Override public BrowseDescriptor describe(BrowserRequest request, Access access, TicketRequestGate gate) {
        gate.check();
        var captured = credentials.require(request.source(), access, false);
        urls(captured, request.externalProjectId(), request.projectKey());
        liveRevision(request, access);
        return BROWSER;
    }

    @Override public TicketPage list(BrowserRequest request, BrowseQuery query, Access access, TicketRequestGate gate) {
        return translated(() -> {
            describe(request, access, gate);
            validateBrowseQuery(query);
            var revision = liveRevision(request, access);
            String scope = browserHash(revision.value(), query.text(), query.lifecycle(), String.join("\u0000", query.statuses()),
                    String.join("\u0000", query.categories()), String.join("\u0000", query.labels()), query.sort(), query.pageSize());
            int offset = browseOffset(query.continuation(), scope);
            var captured = credentials.require(request.source(), access, false);
            var urls = urls(captured, request.externalProjectId(), request.projectKey());
            browserProject(urls, captured, gate);
            // REST labels are conjunctive; complete bounded enumeration gives the SDK's OR semantics.
            var matches = new ArrayList<TicketSummary>();
            var keys = new java.util.HashSet<String>();
            String search = normalized(query.text());
            for (var row : pages(urls, "/issues", Map.of("state", "all"), captured, gate)) {
                gate.check();
                if (row.has("pull_request")) continue;
                var item = browserSummary(request, row, urls, revision);
                if (!keys.add(item.displayKey())) throw GitHubErrors.invalidPagination();
                if ((!search.isEmpty() && !normalized(item.displayKey()).contains(search) && !normalized(item.title()).contains(search))
                        || !query.statuses().isEmpty() && !query.statuses().contains(item.nativeStatus())
                        || !query.categories().isEmpty() && !query.categories().contains(item.category())
                        || !query.labels().isEmpty() && query.labels().stream().noneMatch(item.labels()::contains)) continue;
                matches.add(item);
            }
            java.util.Comparator<TicketSummary> byNumber = java.util.Comparator.comparingLong(
                    item -> Long.parseLong(item.displayKey().substring(item.displayKey().lastIndexOf('#') + 1)));
            matches.sort(switch (query.sort()) {
                case "key-desc" -> byNumber.reversed();
                case "updated-desc" -> java.util.Comparator.comparing(TicketSummary::updatedAt).reversed().thenComparing(byNumber);
                default -> byNumber;
            });
            if (offset > matches.size()) throw new TicketException(Code.STALE_CONTEXT, "GitHub ticket results changed; reload the list.");
            int end = Math.min(matches.size(), offset + query.pageSize());
            credentials.require(request.source(), captured, false);
            gate.check();
            return new TicketPage(matches.subList(offset, end), end < matches.size() ? "v1:" + scope + ":" + end : null,
                    (long) matches.size(), revision, request.capturedAt(), query);
        });
    }

    @Override public TicketDetail detail(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        return translated(() -> {
            describe(request, access, gate);
            var captured = credentials.require(request.source(), access, false);
            var urls = urls(captured, request.externalProjectId(), request.projectKey());
            var row = browserIssue(request, ticket, urls, captured, gate);
            var observation = issue(row, urls);
            var summary = browserSummary(request, row, urls, liveRevision(request, access));
            credentials.require(request.source(), captured, false);
            gate.check();
            return new TicketDetail(summary, observation.createdAt(), observation.assigneeAccountId(), observation.releaseVersionIds(),
                    new Navigation(URI.create(urls.webUrl("/issues")), summary.webUrl()));
        });
    }

    @Override public TicketContent content(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        return translated(() -> {
            describe(request, access, gate);
            var captured = credentials.require(request.source(), access, false);
            var urls = urls(captured, request.externalProjectId(), request.projectKey());
            var row = browserIssue(request, ticket, urls, captured, gate);
            String body = java.util.Objects.toString(GitHubPayload.text(row, "body"), "");
            if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024) throw limit();
            credentials.require(request.source(), captured, false);
            gate.check();
            return new TicketContent(ticket, liveRevision(request, access), ContentFormat.MARKDOWN, body, List.of(), List.of());
        });
    }

    private JsonNode browserIssue(BrowserRequest request, TicketRef ticket, GitHubUrls urls,
                                  GitHubCredentials.Captured captured, TicketRequestGate gate) {
        if (ticket == null || !request.source().instanceId().equals(ticket.instanceId())
                || !request.externalProjectId().equals(ticket.externalProjectId()) || !GitHubUrls.validId(ticket.externalIssueId()))
            throw GitHubCredentials.invalid();
        browserProject(urls, captured, gate);
        // Stable database IDs are not issue numbers. Resolve within the authorized repository first.
        for (var row : pages(urls, "/issues", Map.of("state", "all"), captured, gate)) {
            gate.check();
            if (row.has("pull_request") || !ticket.externalIssueId().equals(GitHubPayload.id(row, "id"))) continue;
            issue(row, urls);
            String number = GitHubPayload.id(row, "number");
            var detail = get(urls, "/issues/" + number, captured, gate);
            gate.acceptedObjects(1);
            if (!ticket.externalIssueId().equals(GitHubPayload.id(detail, "id"))
                    || !number.equals(GitHubPayload.id(detail, "number"))) throw GitHubErrors.identityMismatch();
            issue(detail, urls);
            return detail;
        }
        throw new TicketException(Code.NOT_FOUND, "GitHub ticket was not found in the bound repository.");
    }

    private void browserProject(GitHubUrls urls, GitHubCredentials.Captured captured, TicketRequestGate gate) {
        var row = get(urls, "", captured, gate);
        project(row, urls);
        if (Boolean.FALSE.equals(GitHubPayload.flag(row, "has_issues")))
            throw new TicketException(Code.DISABLED, "GitHub repository issues are disabled.");
    }

    private TicketSummary browserSummary(BrowserRequest request, JsonNode row, GitHubUrls urls, SourceRevision revision) {
        var observation = issue(row, urls);
        Outcome outcome = "open".equals(observation.status()) ? Outcome.OPEN
                : "not_planned".equals(GitHubPayload.text(row, "state_reason")) ? Outcome.CANCELLED
                : "completed".equals(GitHubPayload.text(row, "state_reason")) ? Outcome.SUCCEEDED : Outcome.UNKNOWN;
        return new TicketSummary(new TicketRef(request.source().instanceId(), request.externalProjectId(), observation.identity().externalIssueId()),
                observation.issueKey(), observation.summary(), observation.status(), "issue", Lifecycle.ACTIVE, outcome,
                observation.labels(), observation.webUrl(), observation.updatedAt(), true, revision);
    }

    private static SourceRevision liveRevision(BrowserRequest request, Access access) {
        if (request.capturedAt() == null) throw GitHubCredentials.invalid();
        var revision = new SourceRevision("github-live", browserHash(request.source().instanceId(), request.source().revision(),
                request.ticketProjectId(), request.externalProjectId(), request.projectKey(), access.context()), false);
        if (request.revision() != null && !revision.equals(request.revision()))
            throw new TicketException(Code.STALE_CONTEXT, "GitHub browser context changed; reload the list.");
        return revision;
    }

    private static String browserHash(Object... values) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static int browseOffset(String cursor, String scope) {
        if (cursor == null) return 0;
        String prefix = "v1:" + scope + ":";
        if (!cursor.startsWith(prefix) || !cursor.substring(prefix.length()).matches("[1-9][0-9]{0,8}")) throw GitHubCredentials.invalid();
        return Integer.parseInt(cursor.substring(prefix.length()));
    }

    private static void validateBrowseQuery(BrowseQuery query) {
        if (query == null || query.lifecycle() != Lifecycle.ACTIVE || !BROWSER.sorts().contains(query.sort())) throw GitHubCredentials.invalid();
        for (var values : List.of(query.statuses(), query.categories(), query.labels()))
            for (String value : values) if (value.isBlank() || value.length() > 256 || value.codePoints().anyMatch(Character::isISOControl))
                throw GitHubCredentials.invalid();
    }

    private static String normalized(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** Provider-space navigation is available independently of rich content; global IDs cannot infer issue numbers. */
    @Override public Navigation navigate(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        gate.check();
        var captured = credentials.require(request.source(), access, false);
        var urls = urls(captured, request.externalProjectId(), request.projectKey());
        return new Navigation(URI.create(urls.webUrl("/issues")), null);
    }
}
