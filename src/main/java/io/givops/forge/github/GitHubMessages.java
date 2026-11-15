package io.givops.forge.github;

import io.givops.adapters.i18n.MessageCatalog;
import java.util.Map;

/** Messages travel with this adapter jar; no application catalog or Spring context is required. */
final class GitHubMessages {
    private static final MessageCatalog ENGLISH = MessageCatalog.load(GitHubMessages.class,
            "/io/givops/adapters/github/i18n/en.yml");

    private GitHubMessages() { }

    static String format(String key) { return format(key, Map.of()); }

    static String format(String key, Map<String, ?> parameters) { return ENGLISH.format(key, parameters); }
}
