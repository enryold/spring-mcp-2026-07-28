# Spring MCP 2026-07-28

Community Java implementation of the MCP `2026-07-28` **stateless Streamable HTTP server**, with a Spring Boot MVC integration and dependency-only starter. Maintained under the `enryold` namespace, with coordinates `io.github.enryold`. This is an independent community project, unaffiliated with Spring or the official MCP SDK maintainers.

## Purpose and motivation

The goal is to expose existing Spring application services to MCP clients through explicit Java registrations. Tools, resources and prompts can call the application's own service beans and reuse its authentication, tenant context and authorization policy. The target is Java 21 and Spring Boot 4.

We are building this to bring the revised MCP HTTP protocol directly into a Spring application, without requiring a separate TypeScript production gateway or duplicating business logic. A standalone protocol core, a Spring MVC binding and a dependency-only starter keep adoption small and leave domain decisions with the host application. The libraries do not depend on Spring AI or the MCP Java SDK.

Compatibility is a measured contract: the development suite sends raw HTTP requests to Java and a pinned official TypeScript reference, validates each against the specification, and compares responses and observable effects. The TypeScript implementation is a development dependency only. If its behavior conflicts with the specification, the specification takes precedence and the difference is documented.

## Compatibility with the official TypeScript SDK

Verified on **2026-10-14** against the [official MCP TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk), using `@modelcontextprotocol/server`, `@modelcontextprotocol/client` and `@modelcontextprotocol/core` **2.3.1**, plus `@modelcontextprotocol/node` **2.1.1**. The protocol revision is [MCP **2026-07-28**](https://modelcontextprotocol.io/specification/2026-07-28); it is distinct from the SDK package version. Exact source and dependency pins are recorded in the [baseline](spec/baseline.json) and [npm lockfile](interop/package-lock.json). The official conformance runner is pinned to `@modelcontextprotocol/conformance` **0.2.0-alpha.12**.

| Area | Java implementation | TypeScript 2.3.1 reference | Verification |
| --- | --- | --- | --- |
| Stateless Streamable HTTP, discovery and tools | Implemented | Native SDK | Raw HTTP scenarios and required server conformance |
| Resources/templates, prompts and completion | Implemented through explicit registrations | Native SDK | Catalog, execution, schema and authorization scenarios |
| SSE progress/logging and filtered change subscriptions | Implemented | Native SDK | Stream ordering, filtering, identity isolation and disconnect scenarios |
| Multi-round requests: elicitation, sampling and roots | Implemented with protected continuation state | Native SDK | Round-trip and replay scenarios; two Java conformance warnings explained below |
| Tasks extension: background work, polling, input and cancellation | Implemented with bounded in-memory state | Low-level SDK diagnostic adapter; no native high-level Tasks v2 support | 9/9 executable extension scenarios pass on Java and the diagnostic lane |
| Required official HTTP server scenarios | 37/37 pass | 37/37 pass with native SDK | Frozen official conformance scenarios |
| Official TypeScript client interoperability | Pass | Client 2.3.1 against both servers | Discovery, six-tool catalog and Unicode invocation |
| Raw HTTP input/output comparison | 146 scenarios | Same request sequences against the reference | 88 structurally identical, 48 documented field differences, 10 documented behavior differences; zero unexpected failures |

These results apply to the pinned versions and server profile above, and do not imply identical behavior for every input. The native TypeScript full-revision run retains 30 failed checks across nine Tasks scenarios; the separate diagnostic lane uses official SDK transport with explicit extension handlers and does not establish native high-level Tasks support. Java's two MRTR warnings concern input responses without server-issued continuation state, which Java rejects before invoking a handler. The runner-disabled optional `tasks-status-notifications` scenario is recorded as `TOOLING_SKIP`; Java supports polling and does not implement task status notifications.

The [dated compatibility report](docs/compatibility.md), [requirement matrix](interop/fixtures/requirements.json) and [documented divergences](interop/fixtures/divergences.json) contain the evidence and limits. Stdio, client-role conformance, MCP Apps and legacy initialization are outside this project's supported profile. Capabilities are enabled through explicit registrations; startup rejects declarations without matching handlers.

## Run and verify locally

Nix/devenv supplies Java 21, Node 24 and the pinned Gradle wrapper.

```bash
devenv shell -- npm ci --prefix interop
devenv shell -- ./gradlew check build consumerSmokeTest consumerSmoke4_0_0
devenv shell -- npm --prefix interop run e2e:all
```

The E2E commands own processes, dynamic ports, fixture resets, isolated mutation stores and cleanup. `e2e:all` runs type/unit checks, reference-first raw requests, Java/TypeScript comparison, the official SDK client and conformance.

The detailed comparison reports are generated locally at `interop/artifacts/e2e-report.md` and `interop/artifacts/e2e-report.json` and are ignored by Git. They preserve full request sequences, both responses, finite SSE messages, subscription filters, backend state and strict differences before path-specific exclusions. Generated IDs and wall clocks are checked for type and correlation before excluding their exact paths from cross-host equality. The checked-in [compatibility report](docs/compatibility.md) summarizes the verified results.

`consumerSmokeTest` stages ordinary library JARs/POMs into `build/consumer-repository` and runs a separate starter-only consumer on Boot 4.0.8 and 4.1.1. `consumerSmoke4_0_0` checks the exact older Boot baseline separately. `verifyLibraryArtifacts`, included in `check`, verifies ordinary JAR layout, metadata, upstream notices and dependency scopes.

## Register a tool

Use the starter and define `McpTool` beans with explicit JSON Schemas; no reflection scanner is involved.

```java
@Bean
McpTool echoTool() {
    var json = JsonMapper.builder().build();
    var descriptor = (ObjectNode) json.readTree("""
        {"name":"echo","inputSchema":{"type":"object",
          "properties":{"text":{"type":"string"}},
          "required":["text"],"additionalProperties":false},
         "outputSchema":{"type":"object","properties":{"text":{"type":"string"}},
          "required":["text"],"additionalProperties":false}}
        """);
    return new McpTool(descriptor, (arguments, context) -> {
        var content = json.createArrayNode().add(json.createObjectNode()
                .put("type", "text").put("text", arguments.path("text").asString()));
        return ToolResult.success(content, arguments);
    });
}
```

Types are under `io.github.enryold.mcp`; Jackson 3 types are under `tools.jackson.databind`. Tool results support text, image, audio, embedded resources, resource links, structured JSON and content metadata. `ToolResult.success(content)` omits structured content; passing a JSON null preserves an explicit null. `ToolResult.error(content)` reports an actionable tool failure. Unexpected Java exceptions return a sanitized protocol error with a correlation ID; invalid structured output becomes a safe tool error with the invalid output discarded.

Schemas are validated before registration and on every invocation. External schema references are denied. Regexes use [RE2/J](https://github.com/google/re2j), with a 4,096-character limit; unsupported lookaround/backreference syntax fails startup explicitly. The project therefore does not promise every ECMAScript regex construct.

## Register the expanded capabilities

Register a `McpCatalogModule` bean containing explicit `McpResource`, `McpResourceTemplate`, `McpPrompt` and `McpCompletion` registrations. Readers and prompt/completion handlers receive the current trusted `McpContext`; their descriptors and results use the pinned schema. `McpAuthorization.visible(context, method, name)` distinguishes colliding tool, prompt and resource identifiers. Existing two-argument visibility policies still work.

Register `McpInteractions` with a host-owned bounded `McpInteractionStore` and bounded `ExecutorService` to enable resumable requests and Tasks. Handlers return `ToolResult.inputRequired(inputRequests, handlerState)` for another round or `ToolResult.deferred(executionContext -> result)` for background work. Declare `execution.taskSupport` explicitly in the tool descriptor. Background code must use the supplied execution context, whose lifetime is detached from the original HTTP request. Continuations are bound to principal, tenant, method, name, arguments and expiry; replay rechecks the current request's capabilities and does not repeat effects.

`McpNotifications.forRequest(context)` emits progress for the supplied progress token and logs only at the client-requested log level. Logging is declared by the core. Register `McpEvents` for change subscriptions and publish through `toolsChanged`, `promptsChanged`, `resourcesChanged` or `resourceUpdated`. Select only event kinds whose catalog handlers are installed. Streams have bounded queues and cancel on disconnect, timeout or shutdown. The executable [conformance registrations](samples/api-server/src/main/java/io/github/enryold/mcp/sample/ConformanceInteractionConfiguration.java) illustrate the full flow using invented data.

## Configure security and limits

The endpoint defaults to disabled. Enabling it requires a host-owned `McpSecurity` bean, even when defaults are overridden.

```properties
enryold.mcp.enabled=true
enryold.mcp.path=/mcp
enryold.mcp.server-name=my-service
enryold.mcp.server-version=1.0.0
enryold.mcp.max-request-bytes=1048576
enryold.mcp.max-json-depth=64
enryold.mcp.request-timeout=30s
enryold.mcp.max-concurrent-requests=64
enryold.mcp.allowed-origins[0]=https://client.example
```

Origins must be exact HTTP origins. With an empty list, requests without Origin are accepted and requests carrying Origin are denied. Invalid paths/identities/limits fail startup. All responses use private, no-store HTTP caching; discovery/catalog JSON carries private cache scope and zero TTL. No transport session or initialization handshake is created.

`McpSecurity.authenticate` supplies the trusted subject, tenant, immutable authorization data, deadline and cancellation state. `authorize` runs on every invocation, including task access and event delivery; `visible` filters catalogs by method and identifier. Client arguments and `_meta` cannot impersonate a tenant. Host rate policies can throw `McpRateLimitException` to return HTTP 429 and an explicit Retry-After value. Bounded workers reject saturation, and timeout never implies rollback or an automatic retry. Host authentication and incoming HTTP I/O need the host's own network timeouts; request-timeout bounds dispatch and result processing. Handlers must respect cancellation and give their upstream clients deadlines.

`SpringSecurityMcpSecurity` captures the existing Spring Security authentication before worker dispatch. It installs no global filter chain. The executable [OAuth host example](mcp-2026-spring-boot/src/test/java/io/github/enryold/mcp/spring/OAuthResourceServerTest.java) validates signed JWT issuer, audience, expiry and scope against a local JWKS issuer, supplies protected-resource metadata through Spring Security, checks 401/403 challenges and preserves CSRF outside the MCP route. Production issuers, account linking and permission policy belong to the host.

## Samples and contracts

```bash
devenv shell -- ./gradlew :api-server:bootRun --args='--enryold.mcp.enabled=true --enryold.mcp.server-name=spring-mcp-sample --enryold.mcp.server-version=0.1.0'
```

The sample binds loopback at port 18080 and uses invented identities/data. `fixture.direct-service=true` runs the same registrations directly against Spring service beans; the default lane calls the fixture REST API with separate server-owned API credentials. Reset state/routes and conformance fixtures exist only in the sample. The `conformance` profile explicitly enables anonymous diagnostic fixtures for the official runner; it is a local test profile.

- [Specification](docs/specification.md) and [active implementation plan](superpowers/docs/plans/2026-10-14-120244-01-plan-protocol-gap-closure.md)
- [Verified compatibility and limitations](docs/compatibility.md)
- [HTTP comparison contract](docs/interop.md) and [requirement evidence](interop/fixtures/requirements.json)
- [Conformance applicability](interop/fixtures/conformance-applicability.json) and [narrow reference differences](interop/fixtures/divergences.json)
- [Dependencies](docs/dependencies.md) and [pinned sources](spec/baseline.json)

## License

Project-owned code is licensed under the [MIT License](LICENSE). Vendored specification and conformance inputs retain their upstream license notices in `spec/upstream/`. Library packages have not been published; product adoption remains separate work.
