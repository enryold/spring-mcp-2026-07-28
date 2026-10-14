# MCP 2026-07-28 protocol gap closure

**Goal:** Close the missing Java server behaviors exposed by the complete official server inventory and compare them over real HTTP with the pinned TypeScript SDK.
**Authorization:** User: “possiamo colmare tutti questi gap?” (2026-10-14). Implementation and local verification are authorized; remote publication, product migrations and client-role/stdio implementations remain outside this task.
**Why planning is required:** Public protocol APIs, streaming concurrency, principal/tenant isolation and resumable/background execution cross core, Spring, fixtures and the independent oracle.
**Current stage:** Complete — all five outcomes and local verification gates achieved. This supersedes the previous tools-only scope for capability expansion, while preserving its completed gates and historical reports.
**Acceptance:** No unresolved implementation failures across the 37 required official server scenarios and applicable Tasks extension scenarios. Complete raw request/result/notification flows must have independent normative assertions and Java/TypeScript comparisons, including authorization, cancellation, tampered/replayed state and side effects. Existing tools consumers, security bridge and ordinary library artifacts must keep working. Pinned upstream defects, non-normative validator prose and runner checks it cannot execute remain separately visible; Java must not adopt invalid UTF-8, null/fractional response IDs or weaken bounds merely to imitate them.

## Outcome 1: Complete immutable sources and expanded contract

- Pin the existing specification commit's resources, prompts, completion, logging, MRTR, progress and subscriptions documents without changing runtime dependency pins. Inspect the pinned official conformance fixture contract and Tasks extension sources.
- Update specification and executable requirement/capability inventories. Keep legacy initialize/ping/resources-subscribe RPCs unsupported under the new protocol; subscriptions/listen replaces legacy subscription calls.
- Use explicit registered core handlers/modules, shared pinned request/result validation and the caller's per-invocation authorization. No reflection scanner or production TypeScript gateway.
- Verify source digests and a complete exact server-scenario inventory before widening compatibility claims.

## Outcome 2: Resources, prompts and completion

- Implement explicit registration, deterministic authorized catalogs, resource/template resolution, prompt argument validation, completion and private cache hints. Validate all descriptors, inputs and returned content against the pinned schema.
- Add corresponding Java and TypeScript diagnostic fixtures, raw success/error/tenant cases and official fixtures before enabling their capabilities. Missing resources use -32602 and data.uri as specified.
- Verify focused core/fixture tests, reference-first raw cases and official catalog scenarios.

## Outcome 3: Multi-round requests and Tasks

- Support input_required tool/prompt results, typed client inputRequests/inputResponses, partial/multiple rounds and missing capability errors. Bind resumable state to the authenticated caller, method/name/arguments and expiry; reject tampering and prevent duplicate effects on replay.
- Implement the pinned Tasks extension's opted-in capability/mode negotiation, strongly consistent creation, get/update/cancel, completed versus failed results, TTL/status fields, safe cancellation and MRTR composition. Use a bounded host-owned store, never a global unprotected result cache.
- Cover revoked credentials, concurrent callers, malicious state, partial fulfillment, invalid inputs, cancellation, polling/expiry and exactly-once effects. No automatic mutation retry.
- Verify focused concurrency/security tests, both raw implementations and the applicable official MRTR/Tasks scenarios.

## Outcome 4: SSE, progress, logging and subscriptions

- Implement Streamable HTTP SSE notifications/final response framing and subscription acknowledgment/filtering/tagging. Keep request-scoped progress/logging separate from long-lived change streams and reject independent JSON-RPC requests on response streams.
- Preserve bounded workers and queues, authentication before opening streams, per-principal filtering, deadline/disconnect cancellation and shutdown cleanup. Do not expose private notification payloads across tenants.
- Add an explicit core event sink and host-owned event publication API; expose Spring wiring without changing host global security/CSRF policy.
- Verify framing/order/parallel streams, no logs without logLevel, no unwanted notification types, revocation and disconnected-client cleanup against both implementations and official streaming scenarios.

## Outcome 5: Full independent comparison and delivery gates

- Expand the reference to the same implemented capabilities, preserving exact SDK pins and real isolated REST stores. Update schema oracles for new concrete results and capture entire finite SSE flows plus bounded long-lived observation windows.
- Replace prior capability-gap assertions with success tests where features now exist; preserve explicit unknown-method tests and source-backed SDK defects. Record strict differences before exclusions, capability coverage and effects for all flows.
- Run focused checks, `./gradlew check build consumerSmokeTest consumerSmoke4_0_0`, `npm --prefix interop run check`, the full real HTTP/SDK lanes and the unfiltered official revision. Inspect normal JAR/POM/metadata and obtain a fresh independent security/concurrency/contract review.
- Update README, active specification and a dated compatibility report with exact provenance, implementation failures, tooling limits and remaining semantic differences. No commits, push, package publication or deployment.

## Coordination

Catalogs, MRTR/Tasks and streaming/event transport use separate implementation files and tests. The controller owns shared engine dispatch, module registration and auto-configuration integration. Each implementer first records reference observations and tests, returns the integration contract, and avoids editing another domain's files. All meaningful shared-state/security findings require resolution and scoped re-review before completion.

## Completion evidence — 2026-10-14

- Expanded immutable source baseline, 74 P01–P18 requirement entries and positive raw flows for all ten core server methods plus Tasks get/update/cancel.
- Final Gradle build/check/artifact gates: 95 JVM tests, normal JAR/POM/metadata, and separate Boot 4.0.0/4.0.8/4.1.1 consumers all pass.
- Final `e2e:all`: typecheck + 45 interop tests, 146 reference-first cases, 146 differential cases (88 identical / 48 documented field / 10 documented behavior / 0 failed), and official SDK client all pass.
- Official expanded gate: 37/37 required scenarios and 9/9 executable Tasks extension scenarios pass on Java and the applicable TypeScript lane. Full native Java has 194 successful checks, 0 failures, 2 explicit bound-state policy warnings and 1 runner-disabled optional check. Native SDK Tasks v2 limitations remain in the report; the diagnostic adapter is labeled.
- Fresh independent review: every concrete security/concurrency/public-contract finding fixed, regression-tested and re-reviewed; no unresolved reviewed findings.
- Durable evidence and limitations: docs/compatibility.md; retained conformance report interop/artifacts/conformance/2026-10-14T11-52-04-443Z/report.json.
- Local-only authorization preserved: no commit, push, remote repository, publication, deployment or product migration.
