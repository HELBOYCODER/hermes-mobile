# Optional Integrations and QA Packs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox tracking.

**Goal:** Offer verifiable optional tools without bloating or destabilizing the Android core.

**Architecture:** One pack manifest exposes runtime requirements and installation state; remote/server tools and developer QA remain outside the APK unless explicitly supported and tested.

**Tech Stack:** Kotlin/Compose, coroutines, OkHttp, JUnit/MockWebServer/Robolectric; ARM64 Alpine/PRoot where specified.

**Spec:** `docs/superpowers/specs/2026-10-15-coding-agent-design.md` (approved by user 2026-10-15).

## Global Constraints
- Android minSDK24, target35, ARM64; preserve encrypted credentials, chats and recovery.
- Preserve English Copy/Retry, RTL paragraphs/LTR code, voice pause/resume, Bot Mode and existing downloads.
- No fabricated inference success, concealed paid fallback, provider access-control bypass or uncontrolled plugin installation.
- Pin upstream sources/artifacts and retain license notices. Existing presets/tools must be enhanced, not duplicated.
- Product/CI tests prove only their actual execution boundary. Live login needs consented account/device; native/PRoot/media require ARM64 device evidence.
- Run focused checks before full build. CI currently handles payload generation; local Gradle network/device availability must be checked at execution time.

## Review Focus
1. Pack install interrupted/checksum failure: previous working version remains usable (Task1).
2. Removed/disabled preset: update must not re-enable it (Task1).
3. Skill instruction requests secrets/privilege: no grant beyond existing approval policy (Task1).
4. Remote host disappears mid-task: clear failure and cancellation, no false completion (Task2).
5. Gitleaks finding contains a real token: report location, never token value (Task2).

## Execution rules
- Test filenames without a directory are under `app/src/test/java/com/ersaz/hermesmobile/`; product filenames without a directory are under `app/src/main/java/com/ersaz/hermesmobile/`. Fully qualified script/asset paths override these bases.
- Read the spec and this plan before editing. Create an isolated branch/worktree using the selected execution skill.
- Each task uses red -> minimal implementation -> green -> commit. Save actual command/result evidence; do not treat suggested commands below as already run.
- `./gradlew testDebugUnitTest --tests 'com.ersaz.hermesmobile.<TestClass>'` must report BUILD SUCCESSFUL and zero failures for green. Python checks use `python3 -m unittest <module> -v` and must report OK.
- If payload/SDK is unavailable, use the existing GitHub PR workflow for the test cycle; do not substitute a passing helper-only test for a blocked UI/native check.
- Documentation-only commits need diff/link checks; each product slice gets the full release gate defined below.

### Task 1: Pack manifest and health/upgrade management
**Files:** Create `coding/IntegrationPack.kt`, `coding/PackManager.kt`, `ui/IntegrationPacksScreen.kt`, `app/src/main/assets/coding/packs.json`; Modify `coding/CodingPack.kt`, `ui/SettingsHub.kt`; Test `PackManagerTest.kt`, extend `CodingDefaultsTest.kt`.

**Interfaces:** `IntegrationPack(id:String,version:String,source:String,license:String,architecture:String,requires:List<String>,bytes:Long,sha256:String)`; `PackManager.inspect(id:String):PackHealth`, `install(id:String):PackInstallResult`, `remove(id:String)`. Health states missing,ready,disabled,unsupported,failed; upstream lock commit captured.

- [ ] Write regression test `upgradeIsAtomicAndRespectsDisabled: interrupted staging keeps prior pack; bad checksum cannot execute; architecture mismatch fails before install; disabled/removed Context7/GitHub preset remains so; changed skill cannot increase tool permissions; model download requires explicit selection.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.PackManagerTest --tests com.ersaz.hermesmobile.CodingDefaultsTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Expose version/source/license/size/dependencies/last check. Use pinned staged install+rename and rollback; health requires executable checks, not directory presence. No bundled VillageSQL server. Existing Context7/GitHub remote presets get working setup diagnostics, not duplicates.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(packs): add verified optional integration lifecycle`.

### Task 2: Remote coding tools and CI quality checks
**Files:** Create `qa/remote/README.md`, `qa/remote/presets.json`, `qa/secret-scan.sh`; Modify `.github/workflows/build.yml`, `ui/IntegrationPacksScreen.kt`; Test `RemotePackPolicyTest.kt`; add fixtures under `qa/fixtures/` with synthetic secret patterns.

**Interfaces:** Presets reference user-configured Serena/Playwright remote endpoints; reuse existing McpServer/McpEnvironment types. Read-only default; writable scopes explicit. Gitleaks CLI pinned on CI/host, not assumed executable on Android. Maestro flows from plan A reused, not duplicated.

- [ ] Write regression test `remoteFailureAndRedaction: unreachable host terminates with actionable error; no automatic local Chromium/Docker install; synthetic secret flags filename/line/rule but output excludes value; fixture allowlist does not allow real credentials.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.RemotePackPolicyTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Document Serena component licenses and per-language runtimes before distribution; prefer remote connection initially. Configure Playwright on remote browser host. Add Gitleaks CLI CI check with reviewed baseline, and actual Maestro device flows when infrastructure exists. Surface account/setup requirements without claiming OAuth tests passed.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(integrations): add optional remote tooling and CI secret checks`.

## Self-review
Spec D and compatibility/tooling constraints map to Tasks1-2 plus plan A release gate. Upstream support is not app validation. Package and model licenses checked on exact artifacts before redistribution. This plan can ship an honest catalogue without marking unavailable integrations ready.
