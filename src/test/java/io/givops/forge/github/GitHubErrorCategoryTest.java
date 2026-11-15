package io.givops.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import io.givops.adapters.FailureCategory;
import java.net.http.HttpHeaders;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GitHubErrorCategoryTest {
    @ParameterizedTest
    @CsvSource({"401,ACCESS_DENIED", "403,ACCESS_DENIED", "404,ACCESS_DENIED", "410,UNSUPPORTED", "429,TRANSIENT", "500,TRANSIENT", "408,TRANSIENT", "302,INVALID_REQUEST", "422,INVALID_REQUEST"})
    void reportsProviderIndependentFailureCategories(int status, FailureCategory category) {
        assertThat(GitHubErrors.remoteStatus(status, HttpHeaders.of(Map.of(), (name, value) -> true), new byte[0]).category()).isEqualTo(category);
    }
    @Test void classifiesPayloadAndTransportFailures() {
        assertThat(GitHubErrors.invalidResponse().category()).isEqualTo(FailureCategory.INVALID_RESPONSE);
        assertThat(GitHubErrors.invalidPagination().category()).isEqualTo(FailureCategory.INVALID_RESPONSE);
        assertThat(GitHubErrors.identityMismatch().category()).isEqualTo(FailureCategory.INVALID_RESPONSE);
        assertThat(GitHubErrors.connectionFailed().category()).isEqualTo(FailureCategory.TRANSIENT);
    }
}
