# GivOps GitHub adapter

One Apache-2.0 library supplies GitHub **forge** observations and native **ticketing**
to hosts implementing the public [GivOps adapter SDK](https://github.com/givops/adapter-sdk).
Neither building nor testing requires private GivOps source, accounts or fixtures.

| Component | Version/profile |
| --- | --- |
| Adapter artifact | `io.givops:adapter-github:0.2.0-SNAPSHOT` |
| Public SDK API | `0.2` (exact experimental API level) |
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

Install the ordinary `target/adapter-github-0.2.0-SNAPSHOT.jar` in the host's adapter directory and restart. The host supplies the matching
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
`released` and `archived` remain false. Descriptions, labels, primary assignee,
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
