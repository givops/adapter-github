package io.givops.forge.github;

import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.regex.Pattern;

final class GitHubPages {
    private static final Pattern LINK = Pattern.compile(
            "\\s*<([^<>]+)>\\s*((?:;\\s*[\\w-]+\\s*=\\s*(?:\"[^\"\\r\\n]*\"|[^\\s,;]+)\\s*)*)(,|$)");
    private static final Pattern RELATION = Pattern.compile(
            "(?:^|;)\\s*rel\\s*=\\s*(?:\"([^\"]*)\"|([^\\s;]+))", Pattern.CASE_INSENSITIVE);

    private GitHubPages() {}

    static boolean hasNext(GitHubUrls urls, String suffix, HttpHeaders headers, Map<String, String> filters,
                           int page, int pageSize, int rowCount) {
        String next = null;
        var values = headers.allValues("Link");
        for (String value : values) {
            if (value.isBlank() || value.length() > 4096) throw GitHubErrors.invalidPagination();
            var link = LINK.matcher(value);
            for (int start = 0; start < value.length(); start = link.end()) {
                link.region(start, value.length());
                if (!link.lookingAt() || ",".equals(link.group(3)) && link.end() == value.length()) {
                    throw GitHubErrors.invalidPagination();
                }
                var relation = RELATION.matcher(link.group(2));
                if (!relation.find()) throw GitHubErrors.invalidPagination();
                String names = relation.group(1) == null ? relation.group(2) : relation.group(1);
                if (relation.find() || names.isBlank()) throw GitHubErrors.invalidPagination();
                for (String name : names.split("\\s+")) {
                    if (name.equalsIgnoreCase("next")) {
                        if (next != null || rowCount == 0) throw GitHubErrors.invalidPagination();
                        next = link.group(1);
                    }
                }
            }
        }
        if (next == null) return false;
        urls.validateNext(next, suffix, filters, pageSize, page + 1);
        return true;
    }
}
