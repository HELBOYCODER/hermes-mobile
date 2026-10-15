# Reliable Login, Model Diagnostics and Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox tracking.

**Goal:** Ship reliable account state, model Test actions and a usable Minis-inspired chat without regression.

**Architecture:** Separate authorization from model access. Centralize browser launches/return operations, add isolated model probes, then expose existing per-session tools through a consistent chat interface.

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
1. Successful account authorization followed by catalogue failure: account remains signed in (Task1).
2. Expired/duplicate/mismatched browser callback: no account switch or false success (Task2).
3. Switching provider during an outstanding model probe: result stays bound to original profile, secrets redacted (Task3).
4. Very long Persian model/title mixed with URLs/code: actions remain accessible (Task5).
5. App update while a chat/voice workflow exists: user data and recovery remain functional (Task6).

## Execution rules
- Test filenames without a directory are under `app/src/test/java/com/ersaz/hermesmobile/`; product filenames without a directory are under `app/src/main/java/com/ersaz/hermesmobile/`. Fully qualified script/asset paths override these bases.
- Read the spec and this plan before editing. Create an isolated branch/worktree using the selected execution skill.
- Each task uses red -> minimal implementation -> green -> commit. Save actual command/result evidence; do not treat suggested commands below as already run.
- `./gradlew testDebugUnitTest --tests 'com.ersaz.hermesmobile.<TestClass>'` must report BUILD SUCCESSFUL and zero failures for green. Python checks use `python3 -m unittest <module> -v` and must report OK.
- If payload/SDK is unavailable, use the existing GitHub PR workflow for the test cycle; do not substitute a passing helper-only test for a blocked UI/native check.
- Documentation-only commits need diff/link checks; each product slice gets the full release gate defined below.

### Task 1: Separate Nous authorization and model access
**Files:** Modify `app/src/main/java/com/ersaz/hermesmobile/auth/NousSetup.kt`, `ui/NousLoginCard.kt`; Create `auth/NousConnectionFlow.kt`; Test `app/src/test/java/com/ersaz/hermesmobile/NousConnectionFlowTest.kt`. All abbreviated product paths below are relative to `app/src/main/java/com/ersaz/hermesmobile/`.

**Interfaces:** `NousConnectionFlow(authorize: suspend () -> Unit, configure: suspend () -> NousRoute)` exposes `suspend fun run(): NousConnectionResult`; result is `Ready(route: NousRoute)` or `AuthorizedWithoutModel(message: String)`. Authorization failures throw; cancellation propagates. Extend LoginState with authorized/no-model UI state.

- [ ] Write regression test `configureFailureRetainsAuthorization: authorize callback runs once; configure throws IOException; result is AuthorizedWithoutModel; cancellation is not mapped to success. Also assert existing signedIn token state remains true.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.NousConnectionFlowTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Use existing NousAuth device-code protocol and NousSetup.configure; return authorization status before catalogue work. Add retry-model access without relogin and preserve refresh state.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `fix(auth): preserve Nous login when model setup fails`.

### Task 2: Browser return and MCP callback correctness
**Files:** Create `auth/BrowserAuthCoordinator.kt`, `auth/AuthReturnActivity.kt`, `auth/AuthOperationStore.kt`; Modify `MainActivity.kt`, `HermesRuntime.kt`, `mcp/McpOAuth.kt`, `mcp/McpManager.kt`, `ui/NousLoginCard.kt`, `app/src/main/AndroidManifest.xml`, `app/build.gradle`; Test `AuthReturnTest.kt`, extend `McpTest.kt`.

**Interfaces:** `AuthOperationStore.create(kind: String, expiresAtMs: Long): String`; `consume(id: String, nowMs: Long): Boolean` is one-shot. `BrowserAuthCoordinator.open(activity: Activity, url: String, operationId: String)` and `onResume()` reconcile pending flow; no token in return URI. `LoopbackCallback.await(expectedState: String): Map<String,String>` validates callback before response.

- [ ] Write regression test `invalidStateDoesNotComplete: wrong state/path does not consume valid operation; expiry rejects at expiry time; duplicate consume=false. Callback page only acknowledges receipt and links to opaque hermes-auth return; successful token exchange alone marks Connected. Device-code flow survives resume/recreation without starting a second grant.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.AuthReturnTest --tests com.ersaz.hermesmobile.McpTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Use AndroidX Custom Tabs with browser fallback and an allowlisted return scheme/host/path. Keep Nous polling contract; do not invent server redirect parameters. Retain PKCE. Browser/notification return is explicit when background-launch policy prevents automatic foregrounding. GitHub OAuth requires registered host app; retain PAT path until that exists.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `fix(auth): coordinate browser returns and validate MCP callbacks`.

### Task 3: Profile-specific model Test action
**Files:** Create `llm/ModelProbe.kt`, `ui/ModelProbeAction.kt`; Modify `ui/ModelPicker.kt`; Test `ModelProbeTest.kt`, `ModelProbeUiTest.kt`.

**Interfaces:** `enum ProbeStatus { RESPONDED, AUTH_ERROR, RESTRICTED, UNAVAILABLE, RATE_LIMITED, SERVER_ERROR, NETWORK_ERROR, TIMED_OUT }`; `data class ProbeResult(val profileId:String,val status:ProbeStatus,val latencyMs:Long,val checkedAtMs:Long,val detail:String)`; `ModelProbe(clientFactory: (ModelProfile)->LlmClient, nowMs:()->Long)` exposes `suspend fun test(profile: ModelProfile): ProbeResult`; cancel propagates. Text response and optional tool probe are separate results.

- [ ] Write regression test `testDoesNotSelectModel: settings provider/base/model and session messages unchanged; request body only minimal synthetic message/max16 output tokens; Nous bearer sourced through nousKeyProvider, never profile API key. Test 200/401/403 FreeTierError/404/429/503/timeouts; two concurrent calls max, one per profile; changing picker selection cannot rebind result; no token/error-body secret shown.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.ModelProbeTest --tests com.ersaz.hermesmobile.ModelProbeUiTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Use an isolated client/call for each profile, timeout30s and explicit cancellation. Inline Test, spinner/result/time; priced-model notice. Do not mark tool support based on text test or auto-test all models.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(models): add isolated connectivity tests in model picker`.

### Task 4: Bounded model groups and router diagnostics
**Files:** Create `llm/ModelRoutingPolicy.kt`, `core/ModelGroupStore.kt`, `ui/ModelGroupsScreen.kt`; Modify `core/Settings.kt`, `ui/SettingsHub.kt`, `llm/LlmClient.kt`, `router/RouterApi.kt`, `router/NineRouter.kt`; Test `ModelRoutingPolicyTest.kt`, extend `RouterTest.kt`.

**Interfaces:** `data class ModelGroup(val id:String,val profileIds:List<String>,val allowPaid:Boolean=false)`; `ModelRoutingPolicy.next(group:ModelGroup,profiles:List<ModelProfile>,attempted:Set<String>,cooldowns:Map<String,Long>,nowMs:Long): ModelProfile?`; store list/upsert/remove. Each failure is classified before retry.

- [ ] Write regression test `restrictedModelNotRetried: deterministic FreeTierError attempts model once; allowPaid=false excludes paid/unknown pricing, empty permitted group returns actionable error; 429 cooldown honored; cancelled requests do not route onward; no settings mutation. Router catalogue presence is not a successful probe.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.ModelRoutingPolicyTest --tests com.ersaz.hermesmobile.RouterTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Add explicit group editor and disabled-by-default fallback policy. Compare pinned9Router0.5.99 with supported upstream release; update only after adapter regression tests and compatible real inference evidence. A provider restriction remains visible/unresolved if access is not permitted; do not spoof official-client identity.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(models): add explicit fallback policies and router health states`.

### Task 5: Chat navigation and restrained progress
**Files:** Modify `ui/ThinkingOrbs.kt`, `ui/ChatScreen.kt`, `ui/SettingsHub.kt`, `ui/Screens.kt`, `MainActivity.kt`, `agent/Session.kt`; Create `ui/ChatSessionMenu.kt`, `ui/SessionList.kt`; Test `ChatNavigationTest.kt`, `SessionTitleTest.kt`, extend `ScreenStartupTest.kt`.

**Interfaces:** Preserve `ThinkingOrbs(mode:OrbMode,size:Dp,modifier:Modifier)` call interface while replacing visual implementation. `ChatSessionMenu(onAction:(SessionAction)->Unit)` uses enum NewChat/ClearChat/Terminal/Browser/Files/Skills/Mcp/Memory/Usage/AutoCompact. `Session.displayTitle()` preserves user-set text. Session-only scopes must be persisted/implemented, not labels for global toggles.

- [ ] Write regression test `menuRoutesAndLongRtlTitle: every listed action opens real destination; Clear requires confirmation; no clipped action with 120-char Persian mixed title; parsed title object displays title only without overwriting storage. Indicator remains inside bounds; reduced motion stops animation; compact toggle drives actual existing compaction behavior.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.ChatNavigationTest --tests com.ersaz.hermesmobile.SessionTitleTest --tests com.ersaz.hermesmobile.ScreenStartupTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Use supplied Minis images for grouping,48dp controls and uncluttered composer. Keep LTR code/paths inside RTL paragraph layout. Sessions grouped by date with search/preview/status. Small dot/progress plus textual state replaces additive glow.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(chat): organize session tools and replace clipped progress glow`.

### Task 6: Upgrade and browser regression gate
**Files:** Modify `.github/workflows/build.yml`, `README.md`, `app/build.gradle`; Create `qa/flows/startup.yaml`, `qa/flows/chat-tools.yaml`, `qa/flows/browser-return.yaml`, `qa/README.md`; extend `RecoveryActivityTest.kt`.

**Interfaces:** CI runs unit/Compose + existing Python tests; Maestro flows run on available host/emulator, actual auth credentials injected only by consent. Version/code chosen after checking latest published version at execution; retain release signing identity.

- [ ] Write regression test `upgradePreservesData: seeded2.4.1 chats, Environment and disabled presets survive upgrade. Run denied permissions/offline/low-storage/startup recovery/voice pause-resume/browser cancel-return. Actual protected account request required for live login success; synthetic callback test cannot replace it.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest assembleRelease`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Run Python integration suite, version badging/signature/checksum checks, native ARM64 smoke and configured UI flows. If real-device/login checks blocked, record exact gap; do not call them passed. Review whole branch before publishing one APK with honest capability notes.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `test(android): cover upgrade chat and browser authentication flows`.

## Self-review and handoff
Spec A maps to Tasks1-6. All five Review Focus cases map to tests. No external account consent or inference success assumed. B/C/D are separate plans; this slice can ship independently. Execution method is awaiting user selection (Native recommended for shared auth/UI interfaces).
