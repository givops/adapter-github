package io.givops.forge.github;

import io.givops.forge.model.ForgeException;
import io.givops.adapters.FailureCategory;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpStatus;

final class GitHubErrors {
    private GitHubErrors() {}

    static ForgeException invalidResponse() {
        return remote("github_invalid_response", GitHubMessages.format("forge.gitHubErrors.github.returned.an.invalid.response"));
    }

    static ForgeException invalidPagination() {
        return remote("github_invalid_pagination", GitHubMessages.format("forge.gitHubErrors.github.returned.invalid.pagination.information"));
    }

    static ForgeException resultCap() {
        return remote("github_result_cap", GitHubMessages.format("forge.gitHubErrors.the.github.actions.search.reached.its.result.limit"));
    }

    static ForgeException identityMismatch() {
        return remote("github_repository_identity_mismatch", GitHubMessages.format("forge.gitHubErrors.the.github.repository.path.does.not.identify.the.catalog"));
    }

    static ForgeException budgetExceeded() {
        return new ForgeException("forge_budget_exhausted", HttpStatus.UNPROCESSABLE_CONTENT,
                GitHubMessages.format("forge.gitHubErrors.the.forge.refresh.exceeded.its.resource.budget"));
    }

    static ForgeException invalidConfiguration() {
        return new ForgeException("github_configuration_invalid", HttpStatus.UNPROCESSABLE_CONTENT,
                GitHubMessages.format("forge.gitHubErrors.the.github.com.api.configuration.is.invalid"));
    }

    static ForgeException remoteStatus(int status, HttpHeaders headers, byte[] body) {
        if (status == 429 || status == 403 && (headers.firstValue("Retry-After").isPresent()
                || remainingExhausted(headers)
                || new String(body, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("secondary rate limit"))) {
            return rateLimited(headers);
        }
        return switch (status) {
            case 401 -> remote("github_unauthorized", GitHubMessages.format("forge.gitHubErrors.github.requires.valid.credentials.for.the.requested.data"));
            case 403 -> remote("github_forbidden", GitHubMessages.format("forge.gitHubErrors.github.denied.access.to.the.requested.data"));
            case 404 -> remote("github_not_found", GitHubMessages.format("forge.gitHubErrors.github.did.not.expose.the.requested.resource"));
            case 408 -> timeout();
            case 410 -> remote("github_api_unsupported", "The configured GitHub REST API version or feature is unavailable.");
            default -> {
                if (status >= 300 && status < 400) {
                    yield remote("github_redirect", GitHubMessages.format("forge.gitHubErrors.github.returned.a.redirect.verify.the.catalog.repository.path"));
                }
                if (status >= 500) {
                    yield unavailable();
                }
                yield remote("github_request_rejected", GitHubMessages.format("forge.gitHubErrors.github.rejected.the.api.request"));
            }
        };
    }

    static ForgeException rateLimited(HttpHeaders headers) {
        Long retryAfter = nonnegativeHeader(headers, "Retry-After");
        Long reset = nonnegativeHeader(headers, "X-RateLimit-Reset");
        String guidance = GitHubMessages.format("forge.gitHubErrors.wait.at.least.one.minute.before.refreshing.again");
        if (retryAfter != null) {
            guidance = GitHubMessages.format("forge.github.retry.wait", java.util.Map.of("seconds", String.valueOf(retryAfter)));
        } else if (remainingExhausted(headers) && reset != null) {
            try {
                guidance = GitHubMessages.format("forge.github.retry.after", java.util.Map.of("instant", String.valueOf(Instant.ofEpochSecond(reset))));
            } catch (DateTimeException ignored) {
                // A malformed advisory header cannot change the failure classification.
            }
        }
        return new ForgeException("github_rate_limited", HttpStatus.SERVICE_UNAVAILABLE,
                GitHubMessages.format("forge.github.rate.limited", java.util.Map.of("guidance", String.valueOf(guidance))), FailureCategory.TRANSIENT);
    }

    static ForgeException unavailable() {
        return new ForgeException("github_unavailable", HttpStatus.SERVICE_UNAVAILABLE, GitHubMessages.format("forge.gitHubErrors.github.is.temporarily.unavailable"), FailureCategory.TRANSIENT);
    }

    static ForgeException timeout() {
        return new ForgeException("github_timeout", HttpStatus.GATEWAY_TIMEOUT, GitHubMessages.format("forge.gitHubErrors.the.github.request.timed.out"), FailureCategory.TRANSIENT);
    }

    static ForgeException connectionFailed() {
        return remote("github_transport_failed", GitHubMessages.format("forge.gitHubErrors.the.github.request.could.not.be.completed"));
    }

    private static boolean remainingExhausted(HttpHeaders headers) {
        return headers.firstValue("X-RateLimit-Remaining").map("0"::equals).orElse(false);
    }

    private static Long nonnegativeHeader(HttpHeaders headers, String name) {
        try {
            long parsed = Long.parseLong(headers.firstValue(name).orElse("-1"));
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ForgeException remote(String code, String message) {
        FailureCategory category = switch (code) {
            case "github_unauthorized", "github_forbidden", "github_not_found" -> FailureCategory.ACCESS_DENIED;
            case "github_invalid_response", "github_invalid_pagination", "github_repository_identity_mismatch" -> FailureCategory.INVALID_RESPONSE;
            case "github_transport_failed" -> FailureCategory.TRANSIENT;
            case "github_api_unsupported" -> FailureCategory.UNSUPPORTED;
            default -> FailureCategory.INVALID_REQUEST;
        };
        return new ForgeException(code, HttpStatus.BAD_GATEWAY, message, category);
    }
}
