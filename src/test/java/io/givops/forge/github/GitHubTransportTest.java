package io.givops.forge.github;

import static io.givops.forge.github.GitHubFixture.REPO;
import static io.givops.forge.github.GitHubFixture.TOKEN;
import static io.givops.forge.github.GitHubFixture.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.givops.forge.github.GitHubFixture.Gate;
import io.givops.forge.github.GitHubFixture.Reply;
import io.givops.forge.github.GitHubFixture.Script;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitHubTransportTest {
    private static final URI API = URI.create("https://api.github.com" + REPO);

    @Test
    void rejectsWrongOriginsAndUnsafeTokensBeforeAdmission() {
        var script = new Script(request -> { throw new AssertionError("Unexpected network request"); });
        try (var transport = script.transport()) {
            var gate = new Gate();
            for (String uri : List.of("https://api.github.com:444" + REPO, "http://api.github.com" + REPO,
                    "https://api.github.com@evil.test" + REPO, "https://evil.test" + REPO)) {
                assertCode(() -> transport.get(URI.create(uri), TOKEN, gate), "github_configuration_invalid");
            }
            assertCode(() -> transport.get(API, "unsafe\r\ntoken", gate), "github_configuration_invalid");
            assertThat(gate.requests).isZero();
            assertThat(script.requests).isEmpty();
        }
    }

    @Test
    void accountsForBytesOnSuccessfulAndUnsuccessfulResponses() {
        var script = new Script(request -> Reply.text(200, Map.of(), "12345"));
        try (var transport = script.transport(4)) {
            var gate = new Gate();
            assertCode(() -> transport.get(API, TOKEN, gate), "forge_budget_exhausted");
            assertThat(gate.bytes).isEqualTo(5);
        }
        try (var transport = script.transport(12)) {
            var gate = new Gate();
            gate.maxBytes = 8;
            transport.get(API, TOKEN, gate);
            script.handler = request -> Reply.text(403, Map.of(), "67890");
            assertCode(() -> transport.get(API, TOKEN, gate), "forge_budget_exhausted");
            assertThat(gate.bytes).isEqualTo(10);
            assertThat(gate.requests).isEqualTo(2);
        }
    }

    @Test
    void distinguishesRateLimitsFromForbiddenWithoutExposingResponseText() {
        var script = new Script(request -> Reply.text(403, Map.of("Retry-After", List.of("42")), TOKEN));
        try (var transport = script.transport()) {
            assertThatThrownBy(() -> transport.get(API, TOKEN, new Gate()))
                    .hasMessageContaining("Wait 42 seconds").hasMessageNotContaining(TOKEN);
            script.handler = request -> Reply.text(403, Map.of(), "{\"message\":\"Secondary rate limit\"}");
            assertCode(() -> transport.get(API, TOKEN, new Gate()), "github_rate_limited");
            script.handler = request -> Reply.text(403, Map.of(), "{\"message\":\"Access denied\"}");
            assertCode(() -> transport.get(API, TOKEN, new Gate()), "github_forbidden");
            assertThat(script.requests).hasSize(3);
        }
    }
}
