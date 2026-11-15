# Authentication and destination binding

The public descriptor uses one secret field, `apiToken`. The provider constructs
`Authorization: Bearer ...`; the host owns secrets, access controls, encryption,
destination approval and lease lifetime. Tokens must be printable ASCII without
whitespace, at most 4096 characters. Credentials are never placed in URLs,
exceptions or `toString` output. Runtime files containing secrets must stay out of
source control.

Ticketing captures a lease through `CredentialHost`, matching the exact normalized
HTTPS base URL and instance key. The signed context includes instance identity,
revision and credential revision. Every outbound request revalidates the lease;
publication revalidation delegates the `locked` flag to the host. Captured access
cannot be replayed for another instance. A credential rotation or revocation
invalidates the old capture. Forge access and its request gate provide the same
host-controlled freshness boundary. A token may be reused across domains only
when the host approves the same destination and permissions.

Public repository identity lookup always omits authorization, even when the host
has configured a token. A repository is accepted only when the response explicitly
states `private: false` and its full path matches. Normal forge observations can
also read public metadata without a token; GraphQL review evidence then remains
unknown. Native ticketing uses an explicit captured credential lease.

Use the least privileges needed by the selected operations. Repository metadata,
pull requests, Actions and issues have separate fine-grained permissions. Native
issue/milestone reads need Issues read (milestone reads also accept Pull requests
read); writes need the documented write permission. Repository creation requires
its separate user/organization authorization. Read access does not prove write
permission. Repository discovery uses `/user/repos`; installation-only tokens
whose API does not expose that endpoint should bind known repositories directly.
This adapter does not mint, refresh or store GitHub App installation tokens.

HTTPS/TLS validation remains enabled. GitHub.com web origins map to
`api.github.com`; Enterprise web origins map to their own `/api/v3` and
`/api/graphql` endpoints. The host must approve the destination before provider
calls; the adapter then prevents credential forwarding through redirects or
foreign pagination links.

For host-managed runtime configuration, both definitions also expose the optional
`givops.forge.github.instances.<instance-key>` map (`token`, `expected-base-url`,
`access-revision`). The host captures those values through the same credential
lease API. `expected-base-url` is mandatory for a usable destination pin; it is
never inferred from the current request. Set a new access revision when rotating
runtime material. This configuration does not enable either domain by itself.
