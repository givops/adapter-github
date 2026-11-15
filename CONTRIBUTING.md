# Contributing

Use Java 25 and Maven 3.9+. All build dependencies are public; no private GivOps
repository, parent POM, credential store or fixture package is required. Run
`./scripts/build.sh` for the full pinned-SDK build. For SDK development, explicitly
set `GIVOPS_SDK_SOURCE` and keep Maven caches isolated between concurrent builds.

Run `mvn verify` after installing the public SDK into the selected Maven cache.
Tests use synthetic payloads and an HTTP server bound only to loopback; allow local
sockets in your sandbox. No tests are skipped when a provider account is absent.
Mockito uses an explicit test JVM agent to avoid dynamic attachment restrictions.

Keep changes within the provider boundary: vendor transport, authentication
construction, URL parsing, payload mapping and API-version differences belong
here. Host persistence, authorization, credential custody and scheduling do not.
Use bounded requests and pagination; test revocation, foreign origins, malformed
responses, unsupported features, permissions, temporary failures and numeric
identity collisions when changing those paths. Do not log tokens or remote error
bodies. Add representative synthetic responses rather than customer data.

Public definitions declare each domain and operation independently. Preserve one
ordinary JAR and one artifact manifest for both domains. Update the pinned SDK
commit and compatibility documentation together when SDK API signatures change.
CI checks out only this repository and the pinned public SDK.

This project is licensed under Apache License 2.0. The retained forge algorithms
and focused tests were extracted from GivOps and are published under that license;
all new contributions are accepted on the same basis.
