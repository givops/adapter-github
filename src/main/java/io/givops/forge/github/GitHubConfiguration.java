package io.givops.forge.github;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialHost;
import io.givops.forge.config.ForgeProperties;
import io.givops.ticketing.config.TicketProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/** Each domain can be registered independently with the same vendor infrastructure. */
@AutoConfiguration
@EnableConfigurationProperties({ForgeProperties.class, TicketProperties.class, GitHubProperties.class})
public class GitHubConfiguration {
    @Bean GitHubForgeDefinition gitHubForgeDefinition(GitHubProperties runtime) { return new GitHubForgeDefinition(runtime); }
    @Bean GitHubTicketDefinition gitHubTicketDefinition(GitHubProperties runtime) { return new GitHubTicketDefinition(runtime); }
    @Bean GitHubTransport gitHubTransport(ForgeProperties forge,TicketProperties ticket) {
        return new GitHubTransport(forge.requestTimeout().compareTo(ticket.requestTimeout())<0 ? forge.requestTimeout() : ticket.requestTimeout(),
                Math.min(forge.maxResponseBytes(),ticket.maxResponseBytes()));
    }
    @Bean GitHubAdapter gitHubAdapter(ForgeProperties limits,GitHubTransport transport,JsonMapper mapper,Clock clock) { return new GitHubAdapter(limits,transport,mapper,clock); }
    @Bean GitHubConnectionProbe gitHubConnectionProbe(GitHubTransport transport,JsonMapper mapper,Clock clock) { return new GitHubConnectionProbe(transport,mapper,clock); }
    @Bean GitHubRepositoryIdentity gitHubRepositoryIdentity(GitHubTransport transport,JsonMapper mapper) { return new GitHubRepositoryIdentity(transport,mapper); }
    @Bean GitHubDiscovery gitHubDiscovery(GitHubTransport transport,JsonMapper mapper) { return new GitHubDiscovery(transport,mapper); }
    @Bean GitHubForgeInspector gitHubForgeInspector(GitHubTransport transport,JsonMapper mapper,Clock clock) { return new GitHubForgeInspector(transport,mapper,clock); }
    @Bean @ConditionalOnBean({CredentialHost.class,ContextSigner.class})
    GitHubCredentials gitHubCredentials(CredentialHost host,ContextSigner signer) { return new GitHubCredentials(host,signer); }
    @Bean @ConditionalOnBean(GitHubCredentials.class)
    GitHubTicketAdapter gitHubTicketAdapter(GitHubCredentials credentials,TicketProperties limits,GitHubTransport transport,JsonMapper mapper,Clock clock) {
        return new GitHubTicketAdapter(credentials,limits,transport,mapper,clock);
    }
}
