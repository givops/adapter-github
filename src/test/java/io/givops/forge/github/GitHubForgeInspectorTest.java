package io.givops.forge.github;

import static io.givops.forge.github.GitHubFixture.*;
import static org.assertj.core.api.Assertions.*;
import io.givops.adapters.CapabilityInspection.State;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.port.ForgeAdapter.Access;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GitHubForgeInspectorTest {
    static final String CAPABILITY="forge.repository-create";
    static final Access ACCESS=new Access(TOKEN,Hosting.SAAS);

    @ParameterizedTest
    @CsvSource({"200,UNKNOWN","401,FORBIDDEN","403,FORBIDDEN","408,TEMPORARILY_UNAVAILABLE","410,UNSUPPORTED","429,TEMPORARILY_UNAVAILABLE","500,TEMPORARILY_UNAVAILABLE","503,TEMPORARILY_UNAVAILABLE"})
    void classifiesCreationPreflightWithoutClaimingWritePermission(int status,State expected) {
        var script=new Script(r -> Reply.text(status,Map.of(),"private-provider-sentinel"));
        var actual=inspector(script).inspect(source("https://github.com",null,null),CAPABILITY,ACCESS,new Gate());
        assertThat(actual.state()).isEqualTo(expected);
        assertThat(actual.toString()).doesNotContain("private-provider-sentinel",TOKEN);
        assertThat(script.requests).singleElement().satisfies(r -> {
            assertThat(r.uri().getPath()).isEqualTo("/user");
            assertThat(r.method()).isEqualTo("GET");
        });
    }
    @Test void recognizesPrimaryAndSecondaryRateLimitEvidenceOnForbiddenResponses() {
        for(var headers:List.of(Map.of("Retry-After",List.of("60")),Map.of("X-RateLimit-Remaining",List.of("0")),Map.<String,List<String>>of())) {
            String body=headers.isEmpty()?"private-provider-sentinel: secondary rate limit":"private-provider-sentinel";
            var script=new Script(r -> Reply.text(403,headers,body));
            var actual=inspector(script).inspect(source(),CAPABILITY,ACCESS,new Gate());
            assertThat(actual.state()).isEqualTo(State.TEMPORARILY_UNAVAILABLE);
            assertThat(actual.toString()).doesNotContain("private-provider-sentinel",TOKEN);
        }
    }
    @Test void convertsTransportFailuresToTemporaryInspectionEvidenceWithoutRetrying() {
        for(var failure:List.of(GitHubErrors.timeout(),GitHubErrors.unavailable(),GitHubErrors.connectionFailed())) {
            var script=new Script(r -> {throw failure;});
            assertThat(inspector(script).inspect(source(),CAPABILITY,ACCESS,new Gate()).state()).isEqualTo(State.TEMPORARILY_UNAVAILABLE);
            assertThat(script.requests).hasSize(1);
        }
    }
    @Test void absentCredentialsFailBeforeAnyRemoteProbe() {
        var script=new Script(r -> {throw new AssertionError("No request expected");});
        assertThat(inspector(script).inspect(source(),CAPABILITY,new Access((String)null,Hosting.SAAS),new Gate()).state()).isEqualTo(State.FORBIDDEN);
        assertThat(script.requests).isEmpty();
    }
    private static GitHubForgeInspector inspector(Script script) {
        return new GitHubForgeInspector(script.transport(),JSON,Clock.fixed(NOW,ZoneOffset.UTC));
    }
}
