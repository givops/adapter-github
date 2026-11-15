package io.givops.forge.github;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("givops.forge.github")
public record GitHubProperties(Map<String, Instance> instances) {
    public GitHubProperties {
        instances = instances == null ? Map.of() : Map.copyOf(instances);
    }

    public record Instance(String token, String expectedBaseUrl, String accessRevision) {
        public Instance(String token) { this(token, null, null); }

        @ConstructorBinding
        public Instance {
            expectedBaseUrl = expectedBaseUrl == null ? null : expectedBaseUrl.replaceAll("/+$", "");
            token = token == null || token.isBlank() ? null : token;
        }

        @Override
        public String toString() {
            return "Instance[]";
        }
    }
}
