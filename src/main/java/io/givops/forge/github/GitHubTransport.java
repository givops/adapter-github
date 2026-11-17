package io.givops.forge.github;

import io.givops.forge.model.ForgeException;
import io.givops.forge.port.ForgeRequestGate;
import io.givops.integrations.checks.ConnectionCheckException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;

final class GitHubTransport implements AutoCloseable {
    static final String REST_VERSION = "2026-03-10";
    private final HttpClient client;
    private final long maxResponseBytes;

    GitHubTransport(Duration connectTimeout, long maxResponseBytes) {
        this(HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build(), maxResponseBytes);
    }

    GitHubTransport(HttpClient client, long maxResponseBytes) {
        this.client = client;
        this.maxResponseBytes = maxResponseBytes;
    }

    Response get(URI uri, String token, ForgeRequestGate gate) {
        return requireOk(getForCheck(uri, token, gate));
    }

    Response getForCheck(URI uri, String token, ForgeRequestGate gate) {
        return request(uri, token, null, gate, URI.create("https://api.github.com"), "GET", false);
    }

    Response graphQl(String token, byte[] query, ForgeRequestGate gate) {
        if (token == null) throw GitHubErrors.invalidConfiguration();
        return requireOk(request(GitHubUrls.GRAPHQL, token, query, gate, URI.create("https://api.github.com"), "POST", true));
    }

    Response get(URI uri, String token, ForgeRequestGate gate, URI apiBase) {
        return requireOk(getForCheck(uri, token, gate, apiBase));
    }
    Response getForCheck(URI uri, String token, ForgeRequestGate gate, URI apiBase) {
        return request(uri, token, null, gate, apiBase, "GET", false);
    }
    Response post(URI uri, String token, byte[] body, ForgeRequestGate gate, URI apiBase) {
        return request(uri, token, body, gate, apiBase, "POST", false);
    }
    Response patch(URI uri, String token, byte[] body, ForgeRequestGate gate, URI apiBase) {
        return request(uri, token, body, gate, apiBase, "PATCH", false);
    }
    Response graphQl(GitHubUrls urls, String token, byte[] query, ForgeRequestGate gate) {
        if (token == null) throw GitHubErrors.invalidConfiguration();
        URI api = urls.cloud() ? urls.apiBase() : URI.create(urls.baseUrl() + "/api");
        return requireOk(request(urls.graphQl(), token, query, gate, api, "POST", true));
    }
    private Response request(URI uri, String token, byte[] query, ForgeRequestGate gate,
                             URI apiBase, String method, boolean graphQl) {
        if (!GitHubUrls.sameApi(uri, apiBase) || token != null && !token.matches("[!-~]+")) {
            throw GitHubErrors.invalidConfiguration();
        }
        var request = HttpRequest.newBuilder(uri)
                .header("User-Agent", "GivOps")
                .header("Accept-Encoding", "identity");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.header("Accept", graphQl ? "application/json" : "application/vnd.github+json");
        if (!graphQl) request.header("X-GitHub-Api-Version", GitHubUrls.apiVersion(apiBase));
        if (query == null) request.GET();
        else request.method(method, HttpRequest.BodyPublishers.ofByteArray(query)).header("Content-Type", "application/json");

        Duration timeout = gate.beforeRequest();
        long started = System.nanoTime();
        var pending = client.sendAsync(request.timeout(timeout).build(), info -> new LimitedBody(gate, maxResponseBytes));
        try {
            // Request timeout alone does not cover streaming a complete body.
            long remaining = Math.max(0, timeout.toNanos() - (System.nanoTime() - started));
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            gate.check();
            return new Response(response.headers(), response.body(), response.statusCode());
        } catch (TimeoutException exception) {
            pending.cancel(true);
            gate.check();
            throw GitHubErrors.timeout();
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ForgeException("forge_cancelled", HttpStatus.SERVICE_UNAVAILABLE,
                    GitHubMessages.format("forge.gitHubTransport.the.forge.refresh.was.interrupted"));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof ForgeException failure) throw failure;
            if (cause instanceof io.givops.ticketing.model.TicketException failure) throw failure;
            if (cause instanceof ConnectionCheckException failure) throw failure;
            gate.check();
            if (cause instanceof HttpTimeoutException) throw GitHubErrors.timeout();
            throw GitHubErrors.connectionFailed();
        }
    }

    private static Response requireOk(Response response) {
        if (response.status() != 200) {
            throw GitHubErrors.remoteStatus(response.status(), response.headers(), response.body());
        }
        return response;
    }

    @Override
    public void close() {
        client.close();
    }

    record Response(HttpHeaders headers, byte[] body, int status) {}

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ForgeRequestGate gate;
        private final long limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBody(ForgeRequestGate gate, long limit) {
            this.gate = gate;
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int size = buffer.remaining();
                    gate.receivedBytes(size);
                    if ((long) bytes.size() + size > limit) throw GitHubErrors.budgetExceeded();
                    var chunk = new byte[size];
                    buffer.get(chunk);
                    bytes.writeBytes(chunk);
                }
                subscription.request(1);
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
