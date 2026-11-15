package io.givops.forge.github;

import io.givops.forge.model.ForgeException;
import io.givops.forge.model.ForgeModels.CapabilityState;
import io.givops.forge.model.ForgeModels.ChangeRequest;
import io.givops.forge.model.ForgeModels.CiSummary;
import io.givops.forge.model.ForgeModels.ConflictState;
import io.givops.forge.model.ForgeModels.Person;
import io.givops.forge.model.ForgeModels.ReviewerCoverage;
import io.givops.forge.model.ForgeModels.TaskSignals;
import io.givops.forge.port.ForgeRequestGate;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class GitHubReviews {
    private static final int PAGE_SIZE = 100;
    private static final String QUERY = """
            query GivOpsPullReviewEvidence($owner: String!, $repository: String!, $number: Int!,
                $threadsAfter: String, $reviewersAfter: String, $opinionsAfter: String, $requestsAfter: String,
                $threads: Boolean!, $reviewers: Boolean!, $opinions: Boolean!, $requests: Boolean!) {
              repository(owner: $owner, name: $repository) {
                databaseId
                pullRequest(number: $number) {
                  number fullDatabaseId headRefOid headRefName baseRefName state isDraft updatedAt mergeable
                  headRepository { databaseId }
                  reviewThreads(first: 100, after: $threadsAfter) @include(if: $threads) {
                    totalCount pageInfo { hasNextPage endCursor } nodes { id isResolved }
                  }
                  latestReviews(first: 100, after: $reviewersAfter) @include(if: $reviewers) {
                    totalCount pageInfo { hasNextPage endCursor }
                    nodes { id author { ...ReviewActor } }
                  }
                  latestOpinionatedReviews(first: 100, after: $opinionsAfter) @include(if: $opinions) {
                    totalCount pageInfo { hasNextPage endCursor }
                    nodes { id author { ...ReviewActor } state commit { oid } }
                  }
                  reviewRequests(first: 100, after: $requestsAfter) @include(if: $requests) {
                    totalCount pageInfo { hasNextPage endCursor }
                    nodes {
                      id requestedReviewer {
                        __typename
                        ... on User { databaseId login name }
                        ... on Bot { databaseId login }
                      }
                    }
                  }
                }
              }
            }
            fragment ReviewActor on Actor {
              __typename
              ... on User { databaseId login name }
              ... on Bot { databaseId login }
            }
            """;

    private final GitHubTransport transport;
    private final GitHubPayload payload;
    private final JsonMapper mapper;
    private final int maxPages;

    GitHubReviews(GitHubTransport transport, GitHubPayload payload, JsonMapper mapper, int maxPages) {
        this.transport = transport;
        this.payload = payload;
        this.mapper = mapper;
        this.maxPages = maxPages;
    }

    Result read(GitHubUrls urls, String token, ChangeRequest pull, CiSummary ci, ForgeRequestGate gate) {
        if (token == null) return unavailable(pull, ci, CapabilityState.UNKNOWN, "authentication_required");
        try {
            return readEvidence(urls, token, pull, ci, gate);
        } catch (ForgeException failure) {
            return switch (failure.getCode()) {
                case "github_api_unsupported" -> unavailable(pull, ci, CapabilityState.UNSUPPORTED, "graphql_schema_unsupported");
                case "github_unauthorized", "github_forbidden", "github_not_found" ->
                        unavailable(pull, ci, CapabilityState.FORBIDDEN, "graphql_access_denied");
                default -> throw failure;
            };
        }
    }

    private Result readEvidence(GitHubUrls urls, String token, ChangeRequest pull, CiSummary ci, ForgeRequestGate gate) {
        var pages = new EnumMap<Connection, Pages>(Connection.class);
        for (Connection connection : Connection.values()) pages.put(connection, new Pages());

        Instant firstUpdatedAt = null;
        String firstMergeable = null;
        for (int page = 1; page <= maxPages; page++) {
            var variables = new HashMap<String, Object>();
            variables.put("owner", urls.owner());
            variables.put("repository", urls.repository());
            variables.put("number", pullNumber(pull));
            pages.forEach((connection, data) -> {
                variables.put(connection.variable, !data.done);
                if (data.cursor != null && !data.done) variables.put(connection.variable + "After", data.cursor);
            });
            byte[] body = mapper.writeValueAsBytes(Map.of("query", QUERY, "variables", variables));
            var response = transport.graphQl(urls, token, body, gate);
            JsonNode root = GitHubPayload.object(payload.body(response));
            if (denied(root, response, gate)) {
                return unavailable(pull, ci, CapabilityState.FORBIDDEN, "graphql_access_denied");
            }
            JsonNode repository = GitHubPayload.object(root.get("data")).get("repository");
            if (GitHubPayload.absent(repository)
                    || GitHubPayload.absent(GitHubPayload.object(repository).get("databaseId"))) {
                return unavailable(pull, ci, CapabilityState.UNKNOWN, "graphql_identity_unavailable");
            }
            if (!urls.repositoryId().equals(GitHubPayload.id(repository, "databaseId"))) {
                throw GitHubErrors.identityMismatch();
            }
            JsonNode current = GitHubPayload.object(repository.get("pullRequest"));
            if (GitHubPayload.absent(current.get("fullDatabaseId"))) {
                return unavailable(pull, ci, CapabilityState.UNKNOWN, "graphql_identity_unavailable");
            }
            validateIdentity(current, pull);
            Instant updatedAt = GitHubPayload.instant(current, "updatedAt", true);
            String mergeable = GitHubPayload.requiredText(current, "mergeable");
            if (page == 1) {
                if (!updatedAt.equals(pull.updatedAt())) throw changed();
                firstUpdatedAt = updatedAt;
                firstMergeable = mergeable;
            } else if (!updatedAt.equals(firstUpdatedAt) || !mergeable.equals(firstMergeable)) {
                throw changed();
            }
            gate.acceptedObjects(2);

            for (var entry : pages.entrySet()) {
                if (entry.getValue().done) continue;
                JsonNode connection = current.get(entry.getKey().field);
                if (connection != null && connection.isNull()) {
                    return unavailable(pull, ci, CapabilityState.UNKNOWN, "graphql_evidence_unavailable");
                }
                entry.getValue().addPage(connection, gate);
            }
            if (pages.values().stream().allMatch(data -> data.done)) {
                return summarize(pages, pull, ci, mergeable);
            }
        }
        throw GitHubErrors.budgetExceeded();
    }

    private static int pullNumber(ChangeRequest pull) {
        try {
            return Integer.parseInt(pull.key().localId());
        } catch (NumberFormatException exception) {
            throw GitHubErrors.invalidResponse();
        }
    }

    private static void validateIdentity(JsonNode current, ChangeRequest pull) {
        JsonNode fullId = current.get("fullDatabaseId");
        String databaseId = fullId.isString() ? fullId.stringValue() : GitHubPayload.id(current, "fullDatabaseId");
        if (!pull.providerId().equals(databaseId) || pullNumber(pull) != GitHubPayload.number(current, "number")) {
            throw GitHubErrors.invalidResponse();
        }
        Boolean draft = GitHubPayload.flag(current, "isDraft");
        if (draft == null) throw GitHubErrors.invalidResponse();
        if (!"OPEN".equals(GitHubPayload.requiredText(current, "state")) || draft != pull.draft()
                || !pull.sha().equals(GitHubPayload.sha(current, "headRefOid", true))
                || !pull.sourceBranch().equals(GitHubPayload.requiredText(current, "headRefName"))
                || !pull.targetBranch().equals(GitHubPayload.requiredText(current, "baseRefName"))
                || !Objects.equals(pull.sourceProjectId(), GitHubPayload.optionalId(current.get("headRepository"), "databaseId"))) {
            throw changed();
        }
    }

    private static ForgeException changed() {
        return new ForgeException("github_observation_changed", HttpStatus.SERVICE_UNAVAILABLE,
                GitHubMessages.format("forge.gitHubReviews.the.github.pull.request.changed.during.the.refresh"));
    }

    private static boolean denied(JsonNode root, GitHubTransport.Response response, ForgeRequestGate gate) {
        var errors = GitHubPayload.optionalArray(root.get("errors"));
        if (errors.isEmpty()) return false;
        gate.acceptedObjects(errors.size());
        boolean permissionOnly = true;
        for (JsonNode error : errors) {
            String type = Objects.toString(GitHubPayload.text(error, "type"), "");
            String message = GitHubPayload.requiredText(error, "message").toLowerCase(Locale.ROOT);
            String schemaCode = GitHubPayload.absent(error.get("extensions")) ? null : GitHubPayload.text(error.get("extensions"), "code");
            if ("undefinedField".equals(schemaCode) || "GRAPHQL_VALIDATION_FAILED".equals(schemaCode)) {
                throw new ForgeException("github_api_unsupported", HttpStatus.UNPROCESSABLE_CONTENT, "The GitHub GraphQL schema does not support review evidence.");
            }
            if ("RATE_LIMITED".equals(type) || message.contains("rate limit")) {
                throw GitHubErrors.rateLimited(response.headers());
            }
            boolean permission = switch (type) {
                case "FORBIDDEN", "NOT_FOUND", "INSUFFICIENT_SCOPES", "UNAUTHORIZED" -> true;
                default -> message.contains("resource not accessible by");
            };
            permissionOnly &= permission;
        }
        if (!permissionOnly) throw GitHubErrors.unavailable();
        return true;
    }

    private static Result summarize(Map<Connection, Pages> pages, ChangeRequest pull, CiSummary ci, String mergeable) {
        int unresolved = 0;
        for (JsonNode thread : pages.get(Connection.THREADS).nodes.values()) {
            Boolean resolved = GitHubPayload.flag(thread, "isResolved");
            if (resolved == null) throw GitHubErrors.invalidResponse();
            if (!resolved) unresolved++;
        }

        var roster = new LinkedHashMap<String, Person>();
        var requested = new HashSet<String>();
        String authorId = pull.author() == null ? null : pull.author().id();
        for (Person person : pull.reviewers()) {
            if (person.id().equals(authorId)) continue;
            requested.add(person.id());
            roster.put(person.id(), person);
        }
        boolean team = pull.taskSignals().reviewerCoverage() == ReviewerCoverage.TEAM_REQUESTED;
        boolean requestsKnown = true;
        for (JsonNode node : pages.get(Connection.REQUESTS).nodes.values()) {
            JsonNode reviewer = node.get("requestedReviewer");
            if (!GitHubPayload.absent(reviewer) && Set.of("Team", "EnterpriseTeam")
                    .contains(GitHubPayload.requiredText(reviewer, "__typename"))) {
                team = true;
                continue;
            }
            Person person = person(reviewer);
            if (person == null) {
                requestsKnown = false;
            } else if (!person.id().equals(authorId)) {
                requested.add(person.id());
                roster.put(person.id(), person);
            }
        }

        boolean rosterKnown = requestsKnown && authorId != null;
        var latestReviewers = new HashSet<String>();
        for (JsonNode node : pages.get(Connection.REVIEWERS).nodes.values()) {
            Person person = person(node.get("author"));
            if (person == null) {
                rosterKnown = false;
            } else if (!person.id().equals(authorId)) {
                latestReviewers.add(person.id());
                roster.putIfAbsent(person.id(), person);
            }
        }

        boolean approvalsKnown = requestsKnown;
        var approved = new LinkedHashSet<String>();
        var opinionatedReviewers = new HashSet<String>();
        for (JsonNode node : pages.get(Connection.OPINIONS).nodes.values()) {
            Person person = person(node.get("author"));
            String state = GitHubPayload.requiredText(node, "state");
            if (person == null) {
                approvalsKnown = false;
                rosterKnown = false;
                continue;
            }
            if (person.id().equals(authorId)) continue;
            if (!opinionatedReviewers.add(person.id())) throw GitHubErrors.invalidResponse();
            roster.putIfAbsent(person.id(), person);
            switch (state) {
                case "APPROVED" -> {
                    JsonNode commit = node.get("commit");
                    if (GitHubPayload.absent(commit) || !pull.sha().equals(GitHubPayload.sha(commit, "oid", true))) {
                        approvalsKnown = false;
                    } else if (requested.contains(person.id())) {
                        // A new request may postdate the approval. The review request has no timestamp.
                        approvalsKnown = false;
                    } else {
                        approved.add(person.id());
                    }
                }
                case "CHANGES_REQUESTED", "DISMISSED" -> { }
                default -> approvalsKnown = false;
            }
        }
        // A comment-only participant may never have been assigned to review the pull request.
        rosterKnown &= latestReviewers.stream().allMatch(id -> requested.contains(id) || opinionatedReviewers.contains(id));
        ConflictState conflict = switch (mergeable) {
            case "MERGEABLE" -> ConflictState.CLEAR;
            case "CONFLICTING" -> ConflictState.CONFLICT;
            default -> ConflictState.UNKNOWN;
        };
        ReviewerCoverage coverage = team ? ReviewerCoverage.TEAM_REQUESTED
                : rosterKnown ? ReviewerCoverage.COMPLETE : ReviewerCoverage.UNKNOWN;
        var signals = new TaskSignals(unresolved, approved, approvalsKnown, pull.taskSignals().mergeStatus(),
                ci, conflict, coverage);
        return new Result(List.copyOf(roster.values()), signals, CapabilityState.AVAILABLE, "graphql_review_evidence");
    }

    private static Person person(JsonNode row) {
        if (GitHubPayload.absent(row)) return null;
        String type = GitHubPayload.requiredText(row, "__typename");
        if (!("User".equals(type) || "Bot".equals(type)) || GitHubPayload.absent(row.get("databaseId"))) {
            return null;
        }
        return new Person(GitHubPayload.id(row, "databaseId"), GitHubPayload.requiredText(row, "login"),
                GitHubPayload.text(row, "name"));
    }

    private static Result unavailable(ChangeRequest pull, CiSummary ci, CapabilityState state, String evidence) {
        TaskSignals rest = pull.taskSignals();
        return new Result(pull.reviewers(), new TaskSignals(null, null, false, rest.mergeStatus(), ci,
                rest.conflictState(), rest.reviewerCoverage()), state, evidence);
    }

    record Result(List<Person> reviewers, TaskSignals signals, CapabilityState state, String evidence) {}

    private enum Connection {
        THREADS("reviewThreads", "threads"),
        REVIEWERS("latestReviews", "reviewers"),
        OPINIONS("latestOpinionatedReviews", "opinions"),
        REQUESTS("reviewRequests", "requests");

        private final String field;
        private final String variable;

        Connection(String field, String variable) {
            this.field = field;
            this.variable = variable;
        }
    }

    private static final class Pages {
        private final Map<String, JsonNode> nodes = new LinkedHashMap<>();
        private final Set<String> cursors = new HashSet<>();
        private Integer total;
        private String cursor;
        private boolean done;

        private void addPage(JsonNode page, ForgeRequestGate gate) {
            int count = GitHubPayload.number(page, "totalCount");
            var rows = GitHubPayload.array(page.get("nodes"));
            var pageInfo = GitHubPayload.object(page.get("pageInfo"));
            Boolean next = GitHubPayload.flag(pageInfo, "hasNextPage");
            if (rows.size() > PAGE_SIZE || next == null || total != null && total != count) {
                throw GitHubErrors.invalidPagination();
            }
            gate.acceptedObjects(rows.size());
            for (JsonNode node : rows) {
                String id = GitHubPayload.requiredText(node, "id");
                if (nodes.putIfAbsent(id, node) != null) throw GitHubErrors.invalidPagination();
            }
            total = count;
            if (nodes.size() > count || next != (nodes.size() < count) || next && rows.isEmpty()) {
                throw GitHubErrors.invalidPagination();
            }
            if (next) {
                cursor = GitHubPayload.requiredText(pageInfo, "endCursor");
                if (!cursors.add(cursor)) throw GitHubErrors.invalidPagination();
            } else {
                done = true;
            }
        }
    }
}
