package io.givops.forge.github;

import io.givops.forge.model.ForgeModels.Source;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

record GitHubUrls(String owner, String repository, String repositoryId, URI baseUrl) {
    private static final String API = "https://api.github.com";
    static final URI GRAPHQL = URI.create(API + "/graphql");
    private static final Pattern PATH = Pattern.compile("([A-Za-z0-9-]{1,100})/([A-Za-z0-9_.-]{1,100})");
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern SHA = Pattern.compile("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})");

    GitHubUrls(String owner, String repository, String repositoryId) { this(owner, repository, repositoryId, URI.create("https://github.com")); }

    static GitHubUrls from(Source source) {
        URI base = source.baseUrl();
        String path = source.repositoryPath();
        if (!supportedBase(base) || path == null) {
            throw GitHubErrors.invalidConfiguration();
        }
        if (!validRepositoryPath(path) || !validId(source.projectId())) {
            throw GitHubErrors.invalidConfiguration();
        }
        var match = PATH.matcher(path);
        match.matches();
        return new GitHubUrls(match.group(1), match.group(2), source.projectId(), normalize(base));
    }

    static boolean validRepositoryPath(String path) {
        if (path == null) return false;
        var match = PATH.matcher(path);
        return match.matches() && !".".equals(match.group(2)) && !"..".equals(match.group(2));
    }

    URI rest(String suffix) {
        return URI.create(apiBase() + namedPath() + suffix);
    }

    URI page(String suffix, Map<String, String> filters, int perPage, int page) {
        var query = new java.util.TreeMap<>(filters);
        query.put("per_page", Integer.toString(perPage));
        query.put("page", Integer.toString(page));
        String encoded = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(apiBase() + namedPath() + suffix + "?" + encoded);
    }

    void validateNext(String link, String suffix, Map<String, String> filters, int perPage, int nextPage) {
        try {
            URI target = URI.create(link);
            if (!sameApi(target, apiBase()) || !(matchesNamedPath(target.getRawPath(), suffix)
                    || (apiBase().getRawPath() + "/repositories/" + repositoryId + suffix).equals(target.getRawPath()))) {
                throw GitHubErrors.invalidPagination();
            }
            var expected = new HashMap<>(filters);
            expected.put("per_page", Integer.toString(perPage));
            expected.put("page", Integer.toString(nextPage));
            if (!expected.equals(query(target.getRawQuery()))) throw GitHubErrors.invalidPagination();
        } catch (IllegalArgumentException exception) {
            throw GitHubErrors.invalidPagination();
        }
    }

    String webUrl(String suffix) {
        return baseUrl + "/" + owner + "/" + repository + suffix;
    }

    private boolean matchesNamedPath(String path, String suffix) {
        return path != null && path.endsWith(suffix)
                && path.substring(0, path.length() - suffix.length()).equalsIgnoreCase(apiBase().getRawPath() + namedPath());
    }

    private String namedPath() {
        return "/repos/" + owner + "/" + repository;
    }

    static boolean supportedBase(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null && uri.getRawQuery() == null
                && ("/".equals(uri.getRawPath()) || "".equals(uri.getRawPath()))
                && !"api.github.com".equalsIgnoreCase(uri.getHost())
                && (!"github.com".equalsIgnoreCase(uri.getHost()) || uri.getPort() == -1 || uri.getPort() == 443);
    }

    static URI normalize(URI uri) {
        if (!supportedBase(uri)) throw GitHubErrors.invalidConfiguration();
        return URI.create("https://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort()));
    }

    static URI apiBase(URI base) {
        URI normalized = normalize(base);
        return "github.com".equalsIgnoreCase(normalized.getHost()) ? URI.create(API) : URI.create(normalized + "/api/v3");
    }

    URI apiBase() { return apiBase(baseUrl); }
    URI graphQl() { return cloud() ? GRAPHQL : URI.create(baseUrl + "/api/graphql"); }
    boolean cloud() { return "github.com".equalsIgnoreCase(baseUrl.getHost()); }
    static String apiVersion(URI api) { return "api.github.com".equalsIgnoreCase(api.getHost()) ? "2026-03-10" : "2022-11-28"; }
    static boolean sameApi(URI candidate, URI expected) {
        return candidate != null && "https".equalsIgnoreCase(candidate.getScheme())
                && candidate.getRawUserInfo() == null && candidate.getRawFragment() == null
                && candidate.getHost() != null && candidate.getHost().equalsIgnoreCase(expected.getHost())
                && candidate.getPort() == expected.getPort()
                && candidate.getRawPath().startsWith(expected.getRawPath() + "/");
    }

    static boolean apiOrigin(URI uri) {
        return origin(uri, "api.github.com");
    }

    static boolean validSha(String value) {
        return value != null && SHA.matcher(value).matches();
    }

    static boolean validId(String value) {
        if (value == null || !POSITIVE_ID.matcher(value).matches()) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean origin(URI uri, String host) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
    }

    private static Map<String, String> query(String rawQuery) {
        if (rawQuery == null) throw GitHubErrors.invalidPagination();
        var parsed = new HashMap<String, String>();
        for (String part : rawQuery.split("&", -1)) {
            int separator = part.indexOf('=');
            if (separator < 1) throw GitHubErrors.invalidPagination();
            String key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
            if (parsed.putIfAbsent(key, value) != null) throw GitHubErrors.invalidPagination();
        }
        return parsed;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
