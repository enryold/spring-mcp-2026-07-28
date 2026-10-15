# Dependency and source baseline

Verified on 2026-10-14 against Maven Central, npm and the official repositories. Exact direct versions live in `gradle/libs.versions.toml` and `interop/package.json`; transitive versions live in Gradle lockfiles, `interop/package-lock.json` and `devenv.lock`.

| Dependency | Pin | Purpose |
| --- | --- | --- |
| devenv | 2.2.2 module input | Reproducible local toolchain; use the installed 2.2.2 CLI |
| JDK | 21, package revision in devenv.lock | Compile and run the Java library/sample |
| JitPack JDK | Corretto 21.0.12.12.1, SHA-256 in jitpack.yml | Linux x64 cloud builder; direct verified download avoids failing SDKMAN downloads |
| Node.js | 24, package revision in devenv.lock | TypeScript reference and wire tests |
| Gradle | 9.0.0 + distribution SHA-256 | Wrapper build, no global Gradle installation |
| Spring Boot BOM/plugin | 4.0.8 | Maintained 4.0 baseline, close to saas-boilerplate's 4.0 family |
| Spring MVC, Security, Jackson 3, JUnit, AssertJ | Boot BOM | Avoid independently mixing Spring/Jackson versions |
| NetworkNT JSON Schema Validator | 3.0.6 | JSON Schema 2020-12 validation with Jackson 3 |
| RE2/J | 1.8 | Linear schema regex validation; unsupported constructs fail registration |
| Official MCP server/client/core | 2.3.1 | Independent protocol reference and client compatibility |
| Official MCP Node adapter | 2.1.1 | Reference HTTP hosting |
| Official conformance | 0.2.0-alpha.12 | 2026-aware test runner; the stable latest tag is still 0.1.16 |
| Ajv | 8.20.0 | Independent development-only oracle for concrete upstream response schemas |
| TypeScript / tsx | 5.9.3 / 4.23.15 | Strict type checking and test execution |
| Zod / Node types | 4.6.7 / 24.19.2 | Reference tool schemas and Node 24 typing |

NetworkNT 3.0.8 requires Jackson 3.2.1; 3.0.6 declares 3.1.4 and aligns with this Boot BOM's Jackson 3.1.5. Do not upgrade the validator independently of a compatibility run. This is a deliberate baseline, not a claim that every dependency is the newest release.

The core uses the BOM for version alignment but has no Spring runtime dependency. The Spring integration uses `compileOnly` for the optional core/web/security classpath and conditional configuration; the starter supplies core and MVC transitively. OAuth Resource Server dependencies are prepared for security tests, not forced on every consumer. A JSON Schema validator is justified by the protocol's input-validation contract; do not hand-write a partial validator.

No Spring AI, MCP Java SDK, Lombok, database, Docker, browser automation or production TypeScript process is required. Add another runtime dependency only for a protocol requirement that the current dependencies cannot implement cleanly.

## Reproducibility

- `devenv init` created the environment; `devenv.lock` pins its Nix inputs. `.envrc` is optional and not automatically approved.
- `./gradlew resolveDependencies --write-locks` refreshes JVM locks intentionally. Routine builds do not refresh them.
- `npm ci --prefix interop` installs the npm lock exactly. `npm install` is reserved for reviewed dependency changes.
- `spec/baseline.json` pins normative schema and conformance sources to immutable commits. `spec/upstream/` preserves their license notices.
- A conformance runner update requires verifying the included requirements against the stored revision; alpha status is a tooling limitation, not permission to ignore failures.
- Project-owned code is licensed under the [MIT License](../LICENSE). Vendored inputs retain their upstream license notices.

## Official references

- [Spring custom auto-configuration/starter guide](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)
- [Spring Boot Gradle BOM support](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html)
- [Spring Boot 4.0 requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html)
- [Spring configuration metadata](https://docs.spring.io/spring-boot/specification/configuration-metadata/annotation-processor.html)
- [devenv Java](https://devenv.sh/languages/java/) and [JavaScript](https://devenv.sh/languages/javascript/)
- [TypeScript 2026 migration](https://github.com/modelcontextprotocol/typescript-sdk/blob/main/docs/migration/support-2026-07-28.md)
- [NetworkNT validator](https://github.com/networknt/json-schema-validator)

The Spring guides are upstream conventions, not certification of this project. The dated MCP schema/requirements are pinned; live documentation links can change.
