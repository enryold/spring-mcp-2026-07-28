# Spring MCP 2026-07-28 server specification

Status: expanded stateless HTTP server implemented locally, 2026-10-14. The active plan is `superpowers/docs/plans/2026-10-14-120244-01-plan-protocol-gap-closure.md`; verification evidence and limits are in `docs/compatibility.md`.

## Goal and supported release

Provide a community-maintained Java server library with Spring Boot integration for MCP `2026-07-28`. Products register their services directly, without a TypeScript production process, Spring AI or the MCP Java SDK. Adoption in saas-boilerplate or ToastMCP remains separate work.

Included: stateless Streamable HTTP, discovery, tools, resources/templates, prompts/completion, schema validation, typed errors, per-request identity/authorization, SSE progress/logging, filtered change subscriptions, MRTR and the pinned `io.modelcontextprotocol/tasks` extension. Every optional capability is declared alongside its registered handlers. The core declares tools and request-scoped logging; other modules are explicit host registrations. Startup validates the complete capability/handler graph.

The originally verified tools-only profile is historical. The user authorized closing the reported server gaps on 2026-10-14. Stdio, client-role behavior, MCP Apps, legacy initialization and product adoption were not reported gaps in this HTTP server suite and remain outside the task. Legacy `initialize`, `ping`, `logging/setLevel`, `resources/subscribe`, `resources/unsubscribe`, `tasks/list` and `tasks/result` are unsupported under the selected revision.

## Normative baseline

Authority order: dated specification and schema, then official conformance scenarios, then observed TypeScript behavior. Conflicts are recorded and resolved from the normative source; no implementation bug is copied simply to make the diff disappear. `spec/baseline.json` identifies immutable upstream inputs; local copies retain upstream notices.

References:

- [Specification](https://modelcontextprotocol.io/specification/2026-07-28)
- [Base messages and metadata](https://modelcontextprotocol.io/specification/2026-07-28/basic)
- [Discovery](https://modelcontextprotocol.io/specification/2026-07-28/server/discover)
- [Version negotiation](https://modelcontextprotocol.io/specification/2026-07-28/basic/lifecycle)
- [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
- [Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- [Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)

## Module boundaries

| Module | Ownership | Exclusions |
| --- | --- | --- |
| `mcp-2026-core` | JSON-RPC values, request validation, capability/version checks, tool registry and dispatch, result/error modeling | Spring, servlet state, products, databases, provider credentials |
| `mcp-2026-spring-boot` | MVC transport, HTTP validation, context bridge, conditional wiring, property metadata | Business authorization policy, identity provider implementation |
| `mcp-2026-spring-boot-starter` | Transitive core/integration/MVC dependencies | Application entry point or executable JAR |
| `samples/api-server` | Deterministic REST fixtures, sample tool beans and test security configuration | Production data or credentials |
| `interop` | Official TS reference, independent HTTP client, comparison reports | Production dependency |

Use a small explicit tool registration API: name, description, schemas, annotations and execution function. Pass a request-scoped context containing authenticated subject, authorization data and cancellation/deadline information. The host supplies these values from trusted authentication; client `_meta` and arguments cannot select a tenant or impersonate a principal. Final public signatures are fixed after the first vertical slice, before a stable release.

The transport and core invoke product services through that API. Product tools remain responsible for domain permissions, data filtering and plan checks. A request-level authorization hook protects discovery/list/call; checking a cached catalog never substitutes for checking a call. A static tool registry can be shared; credentials, mutable call state and protected results cannot.

## Protocol contract and requirement IDs

| ID | Required behavior | Differential scenarios |
| --- | --- | --- |
| P01 | Parse one UTF-8 JSON-RPC request per HTTP POST; preserve request ID type/value, reject malformed envelopes and unsupported message directions/batches | `wire-*` |
| P02 | Validate per-request version and client capabilities; treat clientInfo as optional self-reported metadata | `meta-*` |
| P03 | Implement server/discover with actual supported versions/capabilities, final schema identity placement and required cache metadata | `discover-*` |
| P04 | Produce deterministic tools/list with exact schemas, authorized visibility and correct cursor semantics if pagination is used | `catalog-*` |
| P05 | Validate tool name/arguments and declared schemas; execute once, return typed content and structured results conforming to outputSchema | `call-*`, `schema-*` |
| P06 | Distinguish JSON-RPC failures from tool failures (`isError`); supply required resultType and preserve explicit field absence/null semantics | `error-*` |
| P07 | Validate HTTP content negotiation, required metadata headers and equality with body fields; obey canonical status/error combinations | `http-*` |
| P08 | No initialize handshake, MCP transport session, sticky-session requirement or reuse of another request's capabilities/identity | `stateless-*` |
| P09 | Validate Origin when present; limit body size/depth/processing time; sanitize unexpected errors and avoid secret disclosure | `security-*`, `limits-*` |
| P10 | Authenticate and authorize each operation; isolate users/tenants; private cache policy for protected catalogs; reject missing/revoked/invalid credentials | `identity-*`, `cache-*` |
| P11 | Declared capabilities match implemented behavior; unsupported legacy/optional calls fail deterministically | `capability-*` |
| P12 | Respect request cancellation/deadlines and SSE disconnect cleanup without rollback or automatic retries | `deadline-*`, `subscription-*` |
| P13 | Authorized deterministic resources/templates and validated reads, including missing-resource data | `resource-*`, `resources-*` |
| P14 | Explicit prompts, string argument validation and typed messages | `prompt-*`, `prompts-*` |
| P15 | Completion for declared prompt/template arguments with validated response bounds | `completion-*` |
| P16 | Typed MRTR input, caller/request-bound state, partial rounds, capability checks and safe replay | `mrtr-*` |
| P17 | Ordered SSE progress/logging and identity-scoped subscription filters | `stream-*`, `subscription-*` |
| P18 | Opted-in Tasks, strong creation, status/result/error/input/TTL and cancellation | `tasks-*` |

This matrix is a starting inventory. Outcome 1 of the plan expands it into a MUST/SHOULD checklist linked to exact pinned source sections and tests before a compatibility claim.

`server/discover` is a JSON-RPC method sent to the MCP endpoint, not an extra REST path. Discovery does not establish a session. A client can send tools/list or tools/call without first sending discovery; each request must still be self-contained.

For modern HTTP requests, compare `MCP-Protocol-Version`, `Mcp-Method`, and applicable `Mcp-Name`/`Mcp-Param-*` against the request body using the specification's encoding rules. The header/body mismatch and unsupported-version errors must retain their standardized codes and statuses. Do not confuse an authentication 401/403 with a version fallback opportunity. No legacy session GET endpoint is exposed.

Finite ordinary calls return JSON. Calls emitting request-scoped notifications and subscriptions use SSE, with one terminal response for a finite call and an initial acknowledgment for a subscription. Content blocks and structuredContent are serialized with Jackson 3, never concatenated JSON strings. Required cache hints are explicit (`private`, zero TTL by default for protected metadata). Tool execution errors are not disguised as successful text containing an arbitrary `error` key.

Schema validation uses the installed 2020-12 validator with bounded validation and controlled reference resolution. Regex validation uses RE2/J so hostile inputs cannot keep backtracking workers alive after a request timeout. Schemas containing unsupported regex constructs (including lookaround or backreferences) fail registration explicitly; they are not silently reinterpreted. This is the first release’s regex syntax limit. Never allow arbitrary remote `$ref` fetching from untrusted inputs. Test nested schemas, composition, Unicode, number boundaries, nullable/absent values and output-schema failures. Do not introduce silent coercion unless the contract explicitly requires it.

## Spring contract

Follow the [official custom starter guide](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html): `@AutoConfiguration`, candidates registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, class/web/property conditions and consumer bean backoff. Auto-configuration must not use broad component scans. Use a project-owned property namespace and generated configuration/autoconfiguration metadata. Test with `ApplicationContextRunner` and `WebApplicationContextRunner`, including filtered classpaths and overrides. Library artifacts are normal JARs; only the sample applies the executable Boot plugin. Artifact names must not begin with `spring-boot`.

Project decisions, not upstream requirements:

- Namespace `enryold.mcp`; defaults `enabled=false`, `path=/mcp`. Enabling a route must be explicit.
- Configuration supplies server identity, limits and permitted origins. Document durations as Duration; validate invalid values on startup.
- Expose beans for tool registration and security integration; avoid an annotation-scanning framework in v1.
- Fail startup when enabled without an explicit authentication/authorization integration. Anonymous behavior is an explicit sample/development choice.
- Integrate with the consumer's SecurityFilterChain; do not replace its global security or disable CSRF globally. Any MCP-specific CSRF choice is scoped and tested.
- Core does not depend on Spring Security. Spring integration passes a trusted immutable context; never a globally mutable current-user value.
- A consumer must be able to disable the starter and replace supported defaults without duplicate routes or beans.

Baseline: Java 21, Boot 4.0.8, MVC. Verify Boot 4.1.1 as a compatibility target before release. The existing boilerplate uses Boot 4.0.0; compatibility with that exact application is a separate consumer smoke test, not inferred from a matching major version. WebFlux, GraalVM/native images, Java 17 and Boot 3 are not claimed in v1.

## Authentication and product integration

The library validates request identity through host-provided integration and protects execution; it does not become an OAuth authorization server. Provide an OAuth Resource Server integration example with local test issuer/JWKS before Microsoft 365 adoption. Validate issuer, audience, expiry and scopes; expose required protected-resource metadata where applicable. Never forward a token to an unrelated audience.

An API-key host integration is possible for products that use it, but that does not imply Microsoft 365 MCP-plugin compatibility. Account linking, token issuance/consent, member or tenant lookup and plan accounting belong to consuming applications. OAuth and tenant tests are security acceptance criteria independent of protocol parity.

## Release acceptance

1. Every applicable requirement in the expanded HTTP server profile maps to passing independent wire assertions, JVM tests and differential scenarios.
2. The exact same deterministic Spring APIs back Java and TS sample tools. Both real HTTP paths match on the observed contract and produce equivalent side effects.
3. Run all 37 required official server scenarios against Java and the native pinned TypeScript reference. Run Tasks extension scenarios against Java and the clearly labeled low-level SDK diagnostic reference. Retain the full native reference run and any tooling skips; an adapter pass must never be described as native SDK Tasks support.
4. No unexplained or untracked mismatch. Each accepted divergence has a normative citation, evidence, narrow assertion and removal condition.
5. Starter metadata, bean backoff, disabled behavior, security integration and normal-JAR packaging pass; a separate consumer resolves the published local artifacts.
6. Only then propose adoption in saas-boilerplate. ToastMCP's Ghost data, existing endpoints and client migrations are separate work.
