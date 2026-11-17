# GivOps GitHub adapter

One Apache-2.0 library supplies GitHub **forge** observations and native **ticketing**
to hosts implementing the public [GivOps adapter SDK](https://github.com/givops/adapter-sdk).
Neither building nor testing requires private GivOps source, accounts or fixtures.

| Component | Version/profile |
| --- | --- |
| Adapter artifact | `io.givops:adapter-github:0.3.0-SNAPSHOT` |
| Public SDK API | `0.3` (exact experimental API level) |
| Runtime | Java 25, Spring Boot 4.0.9 |
| GitHub.com REST | `2026-03-10`, `https://api.github.com` |
| GitHub Enterprise Server REST | `2022-11-28`, approved web origin + `/api/v3` |
| GraphQL | Cloud `/graphql`; Enterprise `/api/graphql` |

The Enterprise profile is documented and fixture-tested against the GitHub
Enterprise Server 3.20 API schema. The adapter observes the actual
`X-GitHub-Enterprise-Version` header; it does not infer server versions from REST
API dates or invent feature thresholds. Missing GraphQL schema fields report
unsupported review evidence while retaining REST observations. Read the
[compatibility and operation matrix](docs/compatibility.md).

## Install

Install the ordinary `target/adapter-github-0.3.0-SNAPSHOT.jar` in the host's adapter directory and restart. The host supplies the matching
SDK libraries; do not bundle duplicate SDK or framework JARs with the provider. Do not install
both old and new copies of the same adapter. The JAR contains one
`META-INF/givops-adapter.properties` manifest with `domains=ticketing,forge` and
Spring Boot auto-configuration. It is a library, not an executable application.

A host supplies `JsonMapper`, `Clock`, and request budgets. Native ticketing also
requires `CredentialHost` and `ContextSigner`; forge receives ephemeral access
through the public port. Neither domain requires the other to be configured.
Credential storage, encryption, authorization, destination approval, transaction
freshness and persistence belong to the host. See [authentication](docs/authentication.md).

## Native ticketing

Bind a repository using its numeric repository ID and `owner/repository` path.
Binding performs no remote creation. Native issue keys are canonical
`owner/repository#123`; `#123` is interpreted only within its bound repository.
Issue URLs retain the instance origin. GitHub pull requests are excluded from
issue lookup and milestone membership and continue through forge observations.

Milestones supply versions. Their `open`/`closed` state, due date and closed
instant remain native metadata; closed milestones are **not** Jira releases.
`released` and `archived` remain false. Labels, primary assignee,
state, resolution reason and timestamps are mapped for issues.

Explicit create commands create a private repository, an open milestone, or an
open issue. A repository path's owner selects the authenticated user or an
organization. The repository name comes from its path; GitHub has no separate
repository display-name field. An optional owner field must agree with that path.
Issue creation supports one milestone and optional assignee/self assignment.
Generic non-default issue type, workflow and priority values are rejected because
this adapter does not implement a verified mapping for them. Release reports,
release publication, archived milestones and Actions mutations are not advertised.

## Build and test

Install Java 25, Maven 3.9+ and Git, then run:

```sh
./scripts/build.sh
```

The script checks out the exact public SDK commit in `sdk-revision.txt`, builds it,
and verifies this provider. It defaults to an isolated `.m2-build` cache. For
coordinated development against a local public SDK checkout:

```sh
GIVOPS_SDK_SOURCE=/absolute/path/to/adapter-sdk \
GIVOPS_MAVEN_REPO=/absolute/path/to/maven-cache ./scripts/build.sh
```

After installing the SDK, `mvn -Dmaven.repo.local=/absolute/path/to/maven-cache verify`
also works. Tests include synthetic payload contracts, full request/response
scripts and a real loopback HTTP server. They do not contact live GitHub or
Enterprise accounts. See [contributor instructions](CONTRIBUTING.md).

### SDK API 0.3 compatibility

This source requires the exact public SDK API 0.3 and artifact version
0.3.0-SNAPSHOT. Observation acquisition is metadata-only: issue descriptions and
comments are not mapped. Existing creation/write descriptions and forge
merge-request descriptions retain their existing behavior. The `ticketing.browse`
and `ticketing.content` capabilities provide lists, detail and on-demand Markdown.
The `ticketing.navigation` capability supplies provider-owned project
space links without a content fetch. A global ticket ID does not imply a local
issue number, so an unknown ticket link remains absent.

### Generic ticket browser

The same configured ticket adapter implements `TicketBrowseAdapter` and
`TicketContentAdapter`. Lists exclude pull requests and support literal,
case-insensitive NFC-normalized key/title search, native `open`/`closed` status,
category `issue`, and exact labels. Values within a filter family use OR; families
combine with AND. Sorts are numeric `key-asc`, `key-desc`, and `updated-desc`
(with ascending key ties). Only `ACTIVE` lifecycle is supported; closed issues
remain active tickets, and cancellation is distinguished when GitHub reports
`state_reason=not_planned`. An unspecified closure reason remains unknown.

To preserve these filter semantics, each list request enumerates the repository's
issues within the existing request, byte, page and object budgets before returning
a page. Exceeding a budget fails explicitly; results are never silently truncated.
Continuations are bound to source, captured access, query, sort and page size.
This is live data, not a snapshot: concurrent remote edits can move items between
pages, and totals describe only that request's observed collection.

Detail and content resolve the stable global issue ID within the authorized
repository, then fetch the repository-local issue number and revalidate both IDs.
No issue bodies are cached or persisted. Full Markdown is returned only by the
explicit content operation, bounded to 1 MiB. Attachments and remote inline images
are not proxied: mutable provider content cannot satisfy the SDK's immutable
asset contract, so `ticketing.assets` is not advertised. Provider links remain
available for those resources. The host renders Markdown with its existing
sanitization and external-image restrictions.

Provider protocol reference: [GitHub REST issues](https://docs.github.com/en/rest/issues/issues).

The SDK source pin selects public commit
[`ad5dc81e0378d31a8cb4e80a531dee559adea648`](https://github.com/givops/adapter-sdk/commit/ad5dc81e0378d31a8cb4e80a531dee559adea648),
which supplies API 0.3. The repository's default build uses that public SDK source;
no unpublished SDK checkout is required. Browser support is available on the
`task/provider-browser` source branch. The artifact remains a development snapshot.
