package io.givops.forge.github;

import static io.givops.forge.github.GitHubFixture.*;
import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

/** Real HTTP framing and body subscriptions, with only routing replaced by a synthetic loopback origin. */
class GitHubHttpTest {
    @Test void exchangesRealHttpWithoutFollowingRedirectsAndBoundsStreamedBodies() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        var authorization=new AtomicReference<String>();var version=new AtomicReference<String>();
        server.createContext(REPO,exchange->{
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));version.set(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"));
            String path=exchange.getRequestURI().getPath();
            if(path.endsWith("/redirect")) {exchange.getResponseHeaders().add("Location","https://attacker.invalid/");exchange.sendResponseHeaders(302,-1);exchange.close();return;}
            byte[] body=path.endsWith("/large")?new byte[4096]:"{\"id\":41,\"full_name\":\"example/fixture\",\"private\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();
        try(var client=new LoopbackClient(server.getAddress().getPort());var transport=new GitHubTransport(client,1024)) {
            var response=transport.get(URI.create("https://api.github.com"+REPO),TOKEN,new Gate());
            assertThat(new GitHubPayload(JSON).body(response).path("id").asInt()).isEqualTo(41);
            assertThat(authorization.get()).isEqualTo("Bearer "+TOKEN);assertThat(version.get()).isEqualTo("2026-03-10");
            var identity=new GitHubRepositoryIdentity(transport,JSON).lookup(URI.create("https://github.com"),"example/fixture",new Gate());
            assertThat(identity.externalId()).isEqualTo("41");assertThat(authorization.get()).isNull();
            assertCode(()->transport.get(URI.create("https://api.github.com"+REPO+"/redirect"),TOKEN,new Gate()),"github_redirect");
            assertCode(()->transport.get(URI.create("https://api.github.com"+REPO+"/large"),TOKEN,new Gate()),"forge_budget_exhausted");
        } finally {server.stop(0);}
    }
    private static final class LoopbackClient extends HttpClient {
        private final HttpClient delegate=HttpClient.newBuilder().followRedirects(Redirect.NEVER).build();
        private final int port;
        LoopbackClient(int port){this.port=port;}
        private HttpRequest route(HttpRequest request) {
            var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+request.uri().getRawPath()+(request.uri().getRawQuery()==null?"":"?"+request.uri().getRawQuery())))
                    .method(request.method(),request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            request.headers().map().forEach((name,values)->values.forEach(value->builder.header(name,value)));
            request.timeout().ifPresent(builder::timeout);return builder.build();
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){return delegate.sendAsync(route(request),handler);}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){return delegate.sendAsync(route(request),handler,push);}
        @Override public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler)throws IOException,InterruptedException{return delegate.send(route(request),handler);}
        @Override public Optional<CookieHandler> cookieHandler(){return delegate.cookieHandler();}
        @Override public Optional<Duration> connectTimeout(){return delegate.connectTimeout();}
        @Override public Redirect followRedirects(){return delegate.followRedirects();}
        @Override public Optional<ProxySelector> proxy(){return delegate.proxy();}
        @Override public SSLContext sslContext(){return delegate.sslContext();}
        @Override public SSLParameters sslParameters(){return delegate.sslParameters();}
        @Override public Optional<Authenticator> authenticator(){return delegate.authenticator();}
        @Override public Version version(){return delegate.version();}
        @Override public Optional<Executor> executor(){return delegate.executor();}
        @Override public void close(){delegate.close();}
    }
}
