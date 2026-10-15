# Project Coding Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox tracking.

**Goal:** Make coding tasks resumable, context-aware, reviewable and backed by executable tests.

**Architecture:** Persist project specs/tasks separately from chat history. Extend existing workspace MCP with bounded indexes, and add checkpoint/test services independent of the LLM.

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
1. Existing project instructions edited by user: never overwrite on workflow initialization (Task1).
2. Symlink/ignored secret/huge file during index: never escape project or expose secrets (Task2).
3. Dirty Git tree and user edits after agent patch: rollback refuses conflicts, preserves unrelated work (Task3).
4. Reopening interrupted task: never replay completed shell side effects (Task1).
5. Failing test with optimistic agent text: UI remains failed, not green (Task4).

## Execution rules
- Test filenames without a directory are under `app/src/test/java/com/ersaz/hermesmobile/`; product filenames without a directory are under `app/src/main/java/com/ersaz/hermesmobile/`. Fully qualified script/asset paths override these bases.
- Read the spec and this plan before editing. Create an isolated branch/worktree using the selected execution skill.
- Each task uses red -> minimal implementation -> green -> commit. Save actual command/result evidence; do not treat suggested commands below as already run.
- `./gradlew testDebugUnitTest --tests 'com.ersaz.hermesmobile.<TestClass>'` must report BUILD SUCCESSFUL and zero failures for green. Python checks use `python3 -m unittest <module> -v` and must report OK.
- If payload/SDK is unavailable, use the existing GitHub PR workflow for the test cycle; do not substitute a passing helper-only test for a blocked UI/native check.
- Documentation-only commits need diff/link checks; each product slice gets the full release gate defined below.

### Task 1: Project artifacts and adapted skill workflow
**Files:** Create `coding/SpecWorkflow.kt`, `coding/CodingTaskStore.kt`, `ui/CodingTasksPanel.kt`, `scripts/vendor_coding_skills.py`, `app/src/main/assets/coding/upstream-lock.json`; Modify `coding/CodingPack.kt`, `ui/CodingScreen.kt`, `agent/Agent.kt`, relevant bundled skills; Test `SpecWorkflowTest.kt`, `CodingTaskStoreTest.kt`, `scripts/tests/test_skill_bundle.py`. Product paths relative to `app/src/main/java/com/ersaz/hermesmobile/`.

**Interfaces:** `enum CodingPhase { CONSTITUTION,SPECIFY,PLAN,TASKS,IMPLEMENT,CONVERGE }`; `SpecWorkflow.initialize(projectRoot:File): List<File>` returns created files only; `advance(projectRoot:File,phase:CodingPhase): File` prepares pinned template artifact. `CodingTaskStore(root:File).load()/save(state:CodingTaskState)`; state holds featureId, phase, tasks(id,status,changedFiles,testRunIds). No shell tool replay on resume.

- [ ] Write regression test `initializePreservesInstructions: existing constitution/AGENTS.md unchanged; initialize twice creates no duplicates; state roundtrip retains completed IDs without invoking tools. Vendored manifest records commit/source/license; commands map to real Hermes tools. Ordinary chat bypasses coding phases; small fix has concise acceptance path.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.SpecWorkflowTest --tests com.ersaz.hermesmobile.CodingTaskStoreTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Pin actual Spec Kit templates and selected Superpowers skills; adapt commands rather than claiming prompt text installs a CLI. Preserve upstream notices and use skill-creation/validation guidance when changing skills. Expose phase/tasks and resulting files in CodingScreen/chat.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(coding): persist spec workflow and resumable tasks`.

### Task 2: Bounded project context and symbol packs
**Files:** Modify `app/src/main/assets/coding/workspace_mcp.py`, `coding/CodingPack.kt`, `ui/CodingScreen.kt`; Create `coding/ProjectContext.kt`, `coding/SymbolIndex.kt`; Test `scripts/tests/test_coding_mcp.py`, `ProjectContextTest.kt`.

**Interfaces:** `ProjectContext(root:File).pin(relativePath:String): Boolean`, `unpin(relativePath:String)`, `snapshot(maxChars:Int): List<ContextExcerpt>` with relativePath/text/contentHash. `SymbolIndex.update(root:File,changedPaths:List<String>): IndexResult`; language availability explicitly returned. Existing MCP tool names remain stable.

- [ ] Write regression test `indexExcludesEscapes: ignored .env/build/node_modules and outside-root symlink excluded; max1MiB source files, max200k indexed files and explicit capped output; cancellation interrupts scan; unchanged hash reuses entries. Unknown grammar falls back to bounded text search and is not labeled semantic.` with the exact assertions below.
- [ ] Run `python3 -m unittest discover -s scripts/tests -v`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Keep existing overview/search/git tools. Add @file pins and visible context budget; index fingerprints incrementally. Tree-sitter is optional per-language ARM64 pack with pinned grammar/license/build. Treat symbols as syntax, not references/type resolution.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(coding): add bounded context pins and incremental source index`.

### Task 3: Reviewable checkpoints and isolated writers
**Files:** Create `coding/WorkspaceCheckpoint.kt`, `coding/WorkspaceWriter.kt`, `ui/CodingChangesPanel.kt`; Modify `agent/tools/FileTools.kt`, `ui/CodingScreen.kt`; Test `WorkspaceCheckpointTest.kt`.

**Interfaces:** `WorkspaceCheckpoint(root:File).capture(paths:List<String>): String`; `restore(id:String): RestoreResult` with Restored or Conflict(paths); snapshot tracks before/expected-after content and existence. `WorkspaceWriter.withProjectLock(projectId:String,block:suspend()->T):T` serializes writes; Git worktree mode only when supported.

- [ ] Write regression test `rollbackPreservesDirtyWork: unrelated modified/untracked files untouched; newly created agent file removed only if unchanged; later user edit yields Conflict; outside-root paths rejected; two writers serialize. Never git reset --hard/clean as rollback.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.WorkspaceCheckpointTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Wrap direct file mutation with bounded checkpoint tracking; atomic metadata writes; display diff and conflict before restore. Read-only reviewer does not acquire write authority. Preserve user permission policy and existing project changes.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(coding): add conflict-aware checkpoints and changes panel`.

### Task 4: Evidence-based test runs and completion
**Files:** Create `coding/TestRunner.kt`, `coding/TestRunStore.kt`, `ui/TestRunCard.kt`; Modify `coding/CodingTaskStore.kt`, `ui/CodingTasksPanel.kt`, `agent/Agent.kt`; Test `TestRunnerTest.kt`.

**Interfaces:** `TestRunner.run(projectRoot:File,argv:List<String>,timeoutMs:Long): TestRunResult`; result id,command,cwd,start/end,exitCode?,status(PASSED,FAILED,TIMED_OUT,CANCELLED,NOT_RUN),truncatedOutput. Shell command use remains explicit existing approved tool boundary. Task completion stores run IDs, not natural-language pass claims.

- [ ] Write regression test `failingProcessCannotBeGreen: exit1 staysFAILED even output says passed; timeout kills owned process group without unrelated processes; cancel persistsCANCELLED; capped output signals truncation; resume reads result without rerun.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.TestRunnerTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Persist start and terminal status atomically, redact known credential values, cap log1MiB and retain exit code. Complete coding task only against actual acceptance artifacts; no required test silently skipped. Run slice A upgrade/release gate before distributing APK.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(coding): attach executable test evidence to task completion`.

## Self-review
Spec B maps to Tasks1-4, all Review Focus conditions have owning tests. Tree-sitter/LSP expansion beyond validated grammars remains optional; Serena full application is not silently redistributed. Execute after A shared navigation stabilizes; produce separate testable commits.
