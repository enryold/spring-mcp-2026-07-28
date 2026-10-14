# Differential E2E development contract

## Topology

```text
                       shared deterministic case + fixtures
                                      |
                         independent raw HTTP runner
                             /                    \
                Java MCP endpoint            official TS MCP endpoint
                         |                         |
                    Java tool                 TS tool adapter
                         |                         |
              Spring REST fixture A      Spring REST fixture B
                         |                         |
               identical seeded data, clock and scoped test identity
```

Use real TCP/HTTP for the release E2E gate. The bootstrap's in-process TS test only proves that the pinned SDK can answer discovery and a tool call; it is not the differential gate. The sample's real HTTP test proves the REST fixture only.

Both implementations call the same REST API contract. For the main differential lane, Java tools use an HTTP client too, keeping the backend and network topology equivalent. A second sample lane invokes Spring service beans directly and checks equivalent behavior for embedded product use.

Stateless echo/catalog scenarios can share one fixture process. Mutation scenarios require two isolated fixture instances/stores with identical seeds; sequentially calling one shared store would give the second server different input state. The runner owns process startup, dynamic/free ports, readiness, per-case reset and cleanup. Reset routes are test-only, authenticated and loopback-bound. Failed startup, unavailable endpoint, zero selected tests and skipped required scenarios fail the run.

## Reference policy

Pin `@modelcontextprotocol/server` and `client` 2.3.1 and Node adapter 2.1.1 via package-lock. Configure the TS server for the same 2026-only capability profile, limits, origin policy, identity and cache policy as Java. Do not rely on changing SDK defaults or run `npx ...@latest`.

Use raw HTTP as the primary observer because an SDK client may normalize missing fields, coerce defaults, retry or hide errors. Add an official SDK client interoperability lane after the wire comparison. Validate messages against the pinned upstream schema independently of either server.

## Equality rules

- Assert HTTP status and relevant headers: content type, auth challenges, Retry-After, protocol headers and cache policy. Header names are case-insensitive; compare media types structurally.
- Compare parsed JSON structurally. Object key order is immaterial; array order, numeric/string ID distinction, missing versus null, resultType, error code/data, isError, schema content, permissions and business outputs are material.
- Prefer identical configured serverInfo and deterministic timestamps/IDs. Normalize only known transport noise (Date, Server, connection framing, generated correlation IDs) using a small path-specific allowlist.
- Error messages for our fixtures are deterministic and compare exactly. Validator/SDK-specific prose may differ only where the specification leaves it open; compare its required meaning/code/data with a documented per-case rule. Do not delete all error messages globally.
- For SSE when added, parse events and compare ordering/final-result/cancellation behavior, not chunk boundaries, keepalive comments or elapsed milliseconds.
- Compare persistent fixture state and execution counts after side-effect cases. Matching JSON with duplicated writes is a failure.
- The runner does not automatically retry calls. A deliberately retried request is its own case; JSON-RPC ID is not an idempotency guarantee.

## Initial scenario inventory

| Family | Cases |
| --- | --- |
| discovery | Direct call, no initialization, capabilities, identity placement, supported versions, cache hints |
| metadata/version | Missing fields, unsupported version, header/body conflict, optional clientInfo, unknown extensions/keys according to schema |
| HTTP/wire | Invalid JSON, invalid envelopes, ID type, batch, wrong verb/content type/Accept, missing method/name headers, encoded names, body limits |
| tool catalog | Empty/multiple tools, deterministic order, exact input/output schemas, authorized views, cursor errors if supported |
| execution | Echo Unicode, structured result, missing/null/extra arguments, nested/composed schema, unknown tool, expected domain error, sanitized unexpected exception |
| content | Text and other supported content blocks, content metadata, output schema failures, no double encoding |
| isolation | Parallel principals/tenants, changed credentials between calls, revoked identity, stale catalog followed by denied call |
| limits | Rate limit response, retry header, timeout, bounded JSON depth/size and denied remote schema references |
| state | Create/read fixture item, rejected mutation, timeout after commit, repeated call with no automatic hidden retry |
| optional/legacy | Unknown/unsupported method and version failures, no accidental capability advertisement |

Expand each family into checked-in requests and assertions. Every failure report includes case ID, baseline versions, sanitized request, Java/reference status/headers/body, structured diff and backend execution counts. Keep raw sensitive tokens/data out of reports. Artifacts go under ignored `interop/artifacts/`; checked-in fixtures contain invented data only.

## Development loop and stopping rule

1. Add a failing normative assertion and fixture for one behavior.
2. Run the same case on the reference. Investigate a reference failure before changing expected output.
3. Implement the Java behavior and its focused JVM test.
4. Run the differential case and adjacent cases, then the complete selected profile.
5. Run official conformance separately; record capabilities and applicability explicitly.

Completion means all selected scenarios and required assertions pass with no unexplained difference, and security/side-effect tests pass. It does not mean repeatedly changing normalization until the report is green. A mismatch log records `case`, `source clause`, `reference observation`, `Java observation`, `decision`, `test` and `removal condition` for a justified deviation. No broad expected-failure baseline is a release waiver.

## Commands and implementation status

The reference, comparator, raw wire runner, official client lane and conformance profile are implemented:

```bash
devenv shell -- ./gradlew check
devenv shell -- npm --prefix interop run check
devenv shell -- npm --prefix interop run reference:check
devenv shell -- npm --prefix interop run e2e
devenv shell -- npm --prefix interop run sdk:interop
devenv shell -- npm --prefix interop run conformance:profile
```

Run every lane in order with `devenv shell -- npm --prefix interop run e2e:all`. Raw cases load from `wire-cases.json`, `catalog-cases.json`, `streaming-cases.json` and `interaction-cases.json`; every case reciprocally links to a granular P01–P18 requirement. Positive module cases exercise every frozen server method and the three Tasks extension methods. Default sample rejection probes separately verify absent optional registrations.

Filter raw cases with `e2e -- --case case-id` or `--group discovery,call,http,expanded`. A zero-case selection fails. Conformance supports `--filter scenario-a,scenario-b`; extension scenarios receive the pinned runner's required `--force` flag, rather than silently skipping revision-timeline filtering. All 37 required server scenarios are applicable. The exact runner-disabled optional Tasks notification check is classified `TOOLING_SKIP`, with its checks retained; any other required skip or unknown failure fails the gate.

The native pinned SDK supports the core MRTR/catalog/SSE scenarios. It has no high-level Tasks v2 runtime. A dedicated reference lane uses official SDK HTTP transport and low-level `Server.setRequestHandler` with source-backed SEP-2663 diagnostic handlers. The lane is labeled in reports; it neither proxies Java nor replaces the native full-revision run. That native run's Tasks failures remain visible as an SDK/fixture limitation. Java must pass the extension directly.

The installed `conformance:java` command targets an already-running, expanded conformance sample at 127.0.0.1:18080/mcp. Prefer the process-owning profile command for consistent origin policy and reproducible reports.

Each source-backed difference has its exact case/path, independent normative assertions and removal condition in `interop/fixtures/divergences.json`. Known SDK null-ID errors and implementation-specific validator prose are treated per case; required error code/data/status and readable IDs remain material. The independent Ajv oracle validates concrete response definitions before comparison, including specialized error data and exclusive terminal result/error members. Java receives no schema exceptions; reference exceptions are restricted to exact documented cases, paths, values and error codes.

Successful conformance runs retain provenance reports. Every completed raw run retains `interop/artifacts/e2e-report.json` and `e2e-report.md`, including effective shared requests, both observations, every preparatory/parallel response, MRTR/task milestone and finite SSE message, independent validation/assertions, strict differences before exclusions and scoped differences after them. The report separates `identical`, `documented_fields`, `documented_behavior` and `failed`; using an exclusion without a source-backed divergence fails the run. Failed runs also retain `e2e-failures.json`; stale failure files are removed after success. Sources, build inputs and the packaged JAR are fingerprinted rather than implying an uncommitted tree is a Git revision. Reports use invented fixture data and redact request authorization/query credentials. The dated local verification summary is in `docs/compatibility.md`.

Multi-round variables are saved from each endpoint independently and used verbatim in subsequent requests. Polling only repeats `tasks/get`, never a mutation; intermediate polls remain in the observational trace, while stable milestones are compared. Subscription tests publish only after acknowledgment, observe exact filters/URI/principal isolation, drain a bounded quiet window, then close the stream. Generated IDs/state/time exclusions list exact case paths and retain the complete raw values and correlation assertions.
