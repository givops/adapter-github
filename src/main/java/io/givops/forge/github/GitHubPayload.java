package io.givops.forge.github;

import io.givops.forge.model.ChangeRequestLifecycle;
import io.givops.forge.model.ForgeModels.ChangeRequest;
import io.givops.forge.model.ForgeModels.ChangeRequestKey;
import io.givops.forge.model.ForgeModels.ConflictState;
import io.givops.forge.model.ForgeModels.Person;
import io.givops.forge.model.ForgeModels.ReviewerCoverage;
import io.givops.forge.model.ForgeModels.TaskSignals;
import io.givops.forge.port.ForgeRequestGate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

final class GitHubPayload {
    private final ObjectReader json;

    GitHubPayload(JsonMapper mapper) {
        json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    JsonNode body(GitHubTransport.Response response) {
        try {
            return json.readTree(response.body());
        } catch (JacksonException exception) {
            throw GitHubErrors.invalidResponse();
        }
    }

    static PullIdentity pullIdentity(JsonNode row) {
        object(row);
        return new PullIdentity(new ChangeRequestKey(id(object(row.get("base")).get("repo"), "id"),
                id(row, "number")), id(row, "id"));
    }

    static ChangeRequest pull(JsonNode row, PullIdentity expected, GitHubUrls urls, Instant observedAt,
                              ForgeRequestGate gate) {
        if (!expected.equals(pullIdentity(row))) throw GitHubErrors.invalidResponse();
        String state = requiredText(row, "state");
        Boolean merged = flag(row, "merged");
        Instant mergedAt = instant(row, "merged_at", false);
        ChangeRequestLifecycle lifecycle = lifecycle(state, merged, mergedAt);
        JsonNode base = object(row.get("base"));
        JsonNode head = object(row.get("head"));
        var requested = people(row, "requested_reviewers", gate);
        var teams = optionalArray(row.get("requested_teams"));
        gate.acceptedObjects(teams.size());
        for (JsonNode team : teams) id(team, "id");
        ReviewerCoverage roster = teams.isEmpty() ? ReviewerCoverage.UNKNOWN : ReviewerCoverage.TEAM_REQUESTED;
        Boolean mergeable = flag(row, "mergeable");
        ConflictState conflict = mergeable == null || lifecycle != ChangeRequestLifecycle.OPEN
                ? ConflictState.UNKNOWN : mergeable ? ConflictState.CLEAR : ConflictState.CONFLICT;
        var signals = new TaskSignals(null, null, false, text(row, "mergeable_state"), null, conflict, roster);

        return new ChangeRequest(expected.key(), expected.id(), urls.webUrl("/pull/" + expected.key().localId()),
                requiredText(row, "title"), text(row, "body"), state, Boolean.TRUE.equals(flag(row, "draft")),
                optionalId(head.get("repo"), "id"), expected.key().providerRepositoryId(),
                requiredText(head, "ref"), requiredText(base, "ref"), sha(head, "sha", true),
                sha(row, "merge_commit_sha", false), null, person(row.get("user")), people(row, "assignees", gate),
                requested, labels(row, gate), instant(row, "created_at", true), instant(row, "updated_at", true),
                mergedAt, instant(row, "closed_at", false), null, observedAt, signals, lifecycle, List.of(), List.of(), null);
    }

    private static ChangeRequestLifecycle lifecycle(String state, Boolean merged, Instant mergedAt) {
        if (mergedAt != null && Boolean.FALSE.equals(merged) || "open".equals(state)
                && (Boolean.TRUE.equals(merged) || mergedAt != null)) {
            throw GitHubErrors.invalidResponse();
        }
        return switch (state) {
            case "open" -> ChangeRequestLifecycle.OPEN;
            case "closed" -> Boolean.TRUE.equals(merged) || mergedAt != null ? ChangeRequestLifecycle.MERGED
                    : Boolean.FALSE.equals(merged) ? ChangeRequestLifecycle.CLOSED : ChangeRequestLifecycle.UNKNOWN;
            default -> ChangeRequestLifecycle.UNKNOWN;
        };
    }

    private static List<Person> people(JsonNode row, String field, ForgeRequestGate gate) {
        var rows = optionalArray(row.get(field));
        gate.acceptedObjects(rows.size());
        var result = new LinkedHashMap<String, Person>();
        for (JsonNode item : rows) {
            Person person = person(item);
            if (person == null || result.putIfAbsent(person.id(), person) != null) {
                throw GitHubErrors.invalidResponse();
            }
        }
        return List.copyOf(result.values());
    }

    private static Person person(JsonNode value) {
        return absent(value) ? null : new Person(id(value, "id"), requiredText(value, "login"), text(value, "name"));
    }

    private static List<String> labels(JsonNode row, ForgeRequestGate gate) {
        var rows = optionalArray(row.get("labels"));
        gate.acceptedObjects(rows.size());
        var labels = new ArrayList<String>();
        for (JsonNode item : rows) labels.add(requiredText(item, "name"));
        return labels;
    }

    static JsonNode object(JsonNode row) {
        if (row == null || !row.isObject()) throw GitHubErrors.invalidResponse();
        return row;
    }

    static List<JsonNode> array(JsonNode node) {
        if (node == null || !node.isArray()) throw GitHubErrors.invalidResponse();
        return List.copyOf(node.values());
    }

    static List<JsonNode> optionalArray(JsonNode node) {
        return absent(node) ? List.of() : array(node);
    }

    static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }

    static String text(JsonNode row, String field) {
        JsonNode value = object(row).get(field);
        if (absent(value)) return null;
        if (!value.isString()) throw GitHubErrors.invalidResponse();
        return value.stringValue();
    }

    static String requiredText(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.isBlank()) throw GitHubErrors.invalidResponse();
        return value;
    }

    static Boolean flag(JsonNode row, String field) {
        JsonNode value = object(row).get(field);
        if (absent(value)) return null;
        if (!value.isBoolean()) throw GitHubErrors.invalidResponse();
        return value.booleanValue();
    }

    static String id(JsonNode row, String field) {
        JsonNode value = object(row).get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw GitHubErrors.invalidResponse();
        }
        return Long.toString(value.longValue());
    }

    static String optionalId(JsonNode row, String field) {
        return absent(row) ? null : id(row, field);
    }

    static int number(JsonNode row, String field) {
        JsonNode value = object(row).get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw GitHubErrors.invalidResponse();
        }
        return value.intValue();
    }

    static String sha(JsonNode row, String field, boolean required) {
        String value = text(row, field);
        if (value == null && !required) return null;
        if (!GitHubUrls.validSha(value)) throw GitHubErrors.invalidResponse();
        return value.toLowerCase(Locale.ROOT);
    }

    static Instant instant(JsonNode row, String field, boolean required) {
        String value = text(row, field);
        if (value == null && !required) return null;
        try {
            if (value != null) return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Treat an invalid provider timestamp as a malformed observation.
        }
        throw GitHubErrors.invalidResponse();
    }

    record PullIdentity(ChangeRequestKey key, String id) {}
}
