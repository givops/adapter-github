# Compatibility and operations

SDK API compatibility, adapter artifact version, remote API profile and provider
product version are independent. This release requires SDK API 0.2 and uses Java
25 with Spring Boot 4.0.9. Installing an adapter fix compatible with API 0.2 does
not require changing host binaries; API changes require a compatible host.

| Operation | GitHub.com | Enterprise 3.20 profile |
| --- | --- | --- |
| Repository identity, anonymous public metadata | Supported | Supported |
| Authenticated repository discovery | Supported | Supported |
| Pull requests, commit associations, Actions observations | Supported | Supported |
| Review evidence through GraphQL | Supported | Supported when schema/permissions permit |
| Native issues, milestones and complete bounded membership | Supported | Supported |
| Private repository, open milestone and open issue creation | Supported with permissions | Supported with permissions |
| Organization/group discovery | Not implemented | Not implemented |
| Actions mutations, release reports/publication | Not implemented | Not implemented |

REST requests pin `X-GitHub-Api-Version`: cloud uses `2026-03-10`, Enterprise uses
`2022-11-28`. GraphQL requests do not send that REST header. The Enterprise server
product version is observed independently from `X-GitHub-Enterprise-Version` when
available. Unknown product versions stay unknown. No product-version threshold
is used to guess entitlement or permissions.

Capability inspections are scoped to the captured instance/repository and
credential context. Read operations inspect the corresponding endpoint; write
permission stays unknown when metadata cannot establish it. Unsupported means
this implementation or remote API explicitly lacks the operation; 404 remains
unknown because GitHub can hide inaccessible resources. Forbidden, temporary
failure/rate limiting, and disabled issues are distinct states. Permission or
schema failure never produces a fabricated successful empty observation.

Requests refuse redirects and rebuild canonical URLs instead of trusting payload
links. Pagination validates origin/path/query and rejects duplicates, loops, page
caps, byte caps and result caps rather than reporting partial membership as
complete. Writes are not retried automatically: a lost response can leave a
remote resource created; callers must reconcile before retrying.

The test suite uses synthetic responses and a local HTTP server. It establishes
mapping and transport behavior, not live-server certification. Enterprise 3.20 is
the documented schema profile; other releases need their own evidence. GitHub
Enterprise Cloud custom subdomains are not claimed by the Enterprise Server
profile. Product/version support policies remain GitHub's responsibility.

Official documentation reviewed 15 November 2026:

- [Cloud API versions](https://docs.github.com/en/rest/about-the-rest-api/api-versions)
- [Enterprise Server 3.20 API versions](https://docs.github.com/en/enterprise-server@3.20/rest/about-the-rest-api/api-versions)
- [Enterprise REST requests](https://docs.github.com/en/enterprise-server@3.20/rest/using-the-rest-api/getting-started-with-the-rest-api)
- [Issues and pull-request discrimination](https://docs.github.com/en/rest/issues/issues)
- [Milestone state, due dates and permissions](https://docs.github.com/en/rest/issues/milestones)
- [Repository discovery and creation](https://docs.github.com/en/rest/repos/repos)
- [Enterprise 3.20 pull-request GraphQL schema](https://docs.github.com/en/enterprise-server@3.20/graphql/reference/pulls)
