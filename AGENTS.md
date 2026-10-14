# Working on this repository

Read `docs/specification.md`, `docs/interop.md` and the active plan in `superpowers/docs/plans/` before protocol changes. They define the supported revision, capability profile and release gates. The current repository is a bootstrap, not a completed MCP implementation.

## Ownership

- `mcp-2026-core`: protocol values, validation, dispatch and execution contracts. No Spring, servlet, database, product plans or vendor APIs.
- `mcp-2026-spring-boot`: HTTP binding, configuration properties, conditional bean wiring and the bridge to the caller's security context.
- `mcp-2026-spring-boot-starter`: dependency-only entry point. No executable application or business policy.
- `samples/api-server`: Spring REST fixtures and sample MCP registrations. Test-only state, credentials and reset routes must never enter published libraries.
- `interop`: pinned official TypeScript reference, raw HTTP differential scenarios and conformance tooling. It is a development dependency, never a production gateway.

Use `io.github.enryold` coordinates and package names; do not present this community project as an official Spring project. Use `enryold.mcp` for configuration. Keep Spring Boot's executable plugin in the sample only. Do not add a custom annotation scanner or generate schemas from arbitrary Java reflection unless a demonstrated consumer needs it.

## Development loop

Use `devenv shell -- <command>`; `direnv exec . <command>` is allowed after the user has approved this repository's `.envrc`. Use the Gradle wrapper and `npm ci --prefix interop`. Do not update version pins while fixing an unrelated test.

For each protocol behavior, add a raw wire scenario and expected normative assertion first, run it against the pinned TypeScript reference, implement Java, then compare both. A TypeScript defect does not override the specification. Record a narrow, source-backed divergence; never globally strip error fields or rewrite expected responses to make a test pass.

Run the narrowest affected tests, then `./gradlew check` and `npm --prefix interop run check`. Before compatibility claims, run the real HTTP differential suite and the appropriate official conformance scenarios. Bootstrap checks do not prove MCP compatibility. Before publication, inspect normal JARs, POMs, metadata and an external consumer; no `BOOT-INF` in library artifacts.

Validate input, authorization and tenant context on every invocation. Do not cache protected responses across principals. Do not log tokens, raw private arguments or private output. Do not retry mutations automatically.

The user requested a local repository only. Do not create a GitHub repository, push, publish packages or migrate ToastMCP/saas-boilerplate without a later instruction. Preserve unrelated work in those projects.
