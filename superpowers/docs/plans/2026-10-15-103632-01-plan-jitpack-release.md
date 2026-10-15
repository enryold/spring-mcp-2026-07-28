# JitPack v0.1.0 delivery

**Goal:** Publish an annotated `v0.1.0` tag with a downloadable JitPack starter and installation instructions.
**Authorization:** User requested a tag so consumers can download the project through JitPack.
**Why planning is required:** First external artifact publication establishes immutable release coordinates and exposes library archives.
**Acceptance:** Java 21 JitPack build publishes only the three libraries; ordinary JARs, POMs, sources/Javadoc and licenses are inspected; a standalone consumer resolves the starter and invokes its endpoint. GitHub tag resolves to the verified release commit. Never move or overwrite a published tag; stop and preserve state on conflicting history or publication failure. No product migration or deployment.

### Outcome 1: Verified publication configuration
- Work: Add `jitpack.yml`, retain native `io.github.enryold` coordinates, include MIT license metadata/notices, document JitPack's multi-module coordinates, and allow the existing external consumer to check them.
- Verify: `devenv shell -- ./gradlew check build consumerSmokeTest consumerSmoke4_0_0 publishToMavenLocal`, `devenv shell -- npm --prefix interop run check`, archive/POM inspection, and a standalone starter consumer.

### Outcome 2: Downloadable immutable tag
- Work: Commit and push the publication configuration, verify JitPack's commit build, create/push annotated `v0.1.0`, and trigger its build.
- Verify: Remote tag commit, JitPack build status and published POMs/JARs, then the standalone consumer using the public tag coordinates.
