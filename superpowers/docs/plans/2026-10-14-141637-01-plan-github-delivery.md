# GitHub delivery

**Goal:** Commit and push this repository to public `enryold/spring-mcp-2026-07-28`.
**Authorization:** User requested push and selected public visibility and MIT licensing on 2026-10-14.
**Why planning is required:** First public delivery creates external repository state and exposes source files.
**Acceptance:** Selected project license and upstream notices retained; only repository sources committed; relevant verification evidence matches the delivered implementation; GitHub `main` equals local `HEAD`. No package publication or product migration. Stop if credentials are found or the destination contains unrelated history; never force-push or overwrite a remote. Preserve local work on failure.

### Outcome 1: Reviewable initial commit
- Work: Verify included files, retain build/dependency/artifact exclusions, add the user's selected license, and update current publication notes.
- License: MIT for project-owned code; upstream notices unchanged.
- Verify: Existing JVM/consumer/artifact and E2E/conformance evidence, source fingerprints, candidate-file scan, and `devenv shell -- git -c core.whitespace=cr-at-eol diff --cached --check -- . ':!spec/upstream/schema.json'`. The pinned upstream schema's EOF blank line is retained byte-for-byte and verified against its recorded SHA-256; the Windows wrapper retains its original CRLF endings.
- Evidence: 95 JVM tests with no failures/errors/skips; 146 raw HTTP scenarios with no unexpected failures; all four Java/TypeScript source fingerprints and the sample JAR match the passing E2E/conformance reports. Three normal library JARs, POMs, metadata and sources/Javadoc inspected. No generated artifacts or credential findings among the 143 staged source files.

### Outcome 2: Public remote containing the verified commit
- Work: Create the authorized public repository, configure `origin`, and push `main` after Outcome 1.
- Verify: `devenv shell -- gh repo view enryold/spring-mcp-2026-07-28 --json url,visibility,defaultBranchRef` and `devenv shell -- git ls-remote origin refs/heads/main`.
