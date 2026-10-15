# Media Link Understanding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox tracking.

**Goal:** Turn accessible media links into bounded, attributable transcripts and analyses.

**Architecture:** Use a cancellable extraction job with explicit evidence coverage, then optional Android transcription. Existing YouTube helper becomes a supported mobile pack instead of a desktop-only setup instruction.

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
1. URL embedded in code/quoted output: no accidental download (Task1).
2. Private/deleted/rate-limited link: no invented transcript (Task1).
3. Large/chunked response exceeding cap: terminate download and clean temporary files (Task1).
4. Cancellation/process interruption: no orphan process/download loop (Task1).
5. Persian content with English-only model: reject mismatch, show actual coverage (Task2).

## Execution rules
- Test filenames without a directory are under `app/src/test/java/com/ersaz/hermesmobile/`; product filenames without a directory are under `app/src/main/java/com/ersaz/hermesmobile/`. Fully qualified script/asset paths override these bases.
- Read the spec and this plan before editing. Create an isolated branch/worktree using the selected execution skill.
- Each task uses red -> minimal implementation -> green -> commit. Save actual command/result evidence; do not treat suggested commands below as already run.
- `./gradlew testDebugUnitTest --tests 'com.ersaz.hermesmobile.<TestClass>'` must report BUILD SUCCESSFUL and zero failures for green. Python checks use `python3 -m unittest <module> -v` and must report OK.
- If payload/SDK is unavailable, use the existing GitHub PR workflow for the test cycle; do not substitute a passing helper-only test for a blocked UI/native check.
- Documentation-only commits need diff/link checks; each product slice gets the full release gate defined below.

### Task 1: Extraction pipeline with provenance
**Files:** Create `media/MediaJob.kt`, `media/MediaJobStore.kt`, `agent/tools/AnalyzeLinkTool.kt`, `app/src/main/assets/media/extract_media.py`, `scripts/bundle_media.sh`; Modify `agent/tools/Tool.kt`, `ui/ChatScreen.kt`, bundled youtube-content skill/helper; Test `MediaJobTest.kt`, `scripts/tests/test_media_extract.py`.

**Interfaces:** `MediaJob.run(url:String,options:MediaOptions): MediaEvidence`; defaults maxDurationSec1200,maxDownloadBytes104857600,maxFrames12,one active job. Evidence includes sourceUrl,method,language,segments(startSec,endSec,text),coverage(METADATA,CAPTIONS,AUDIO_TRANSCRIPT,FRAMES),artifactPaths. `cancel(jobId:String)` closes owned processes/transports.

- [ ] Write regression test `accessibleCaptionAndFailureCases: caption fixture yields exact segments/source; metadata-only cannot claim full transcript; login-required/deleted/429 results carry explicit error; streamed bytes exceeding100MiB abort; quoted URL does not auto-run; bare link in analysis mode runs once; cancellation cleans temp files.` with the exact assertions below.
- [ ] Run `python3 -m unittest discover -s scripts/tests -v`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Bundle pinned yt-dlp source/wheel, ffmpeg/ffprobe and JS prerequisites with exact build notices/checksums; preserve existing helper and use actual mobile interpreter. Captions first, download/transcription only if needed. Feed extracted content as untrusted data, not instructions; return artifacts through existing file cards.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(media): add bounded link extraction with source evidence`.

### Task 2: Optional offline transcription and measured acceptance
**Files:** Create `media/TranscriptionEngine.kt`, Android JNI source under `app/src/main/cpp/whisper/`, `media/WhisperModelStore.kt`, `ui/MediaPackScreen.kt`, `qa/media/README.md`; Modify `app/build.gradle`, pack settings; Test `TranscriptionEngineTest.kt`, device instrumentation cases.

**Interfaces:** `TranscriptionEngine.transcribe(audio:File,language:String?): TranscriptResult`; `WhisperModelStore.install(id:String,source:URL,sha256:String):ModelInstallResult` verifies streamed download and atomic install. Use pinned multilingual tiny/base choices; weights opt-in and removable.

- [ ] Write regression test `rejectCorruptOrEnglishOnlyWeights: checksum mismatch never marks installed; fa rejects .en model; missing engine/model is actionable unavailability; cancellation releases JNI/audio memory; segmented output retains timestamps. Device samples must record actual Persian/English accuracy, latency and memory, not upstream claims.` with the exact assertions below.
- [ ] Run `./gradlew testDebugUnitTest --tests com.ersaz.hermesmobile.TranscriptionEngineTest`; observe the intended missing-behavior failure, not an unrelated setup failure.
- [ ] Implement: Adapt upstream whisper.cpp Android example via pinned JNI build, model/dependency license manifest and bounds. Use configured cloud transcription only when user selected it and credentials exist. Sample frames only for accessible media and available vision model; no claim private Instagram works without account authorization. Run ARM64 media and slice A regression gate.
- [ ] Re-run the same command; require all stated assertions to pass. Inspect output and any relevant UI evidence.
- [ ] Commit only this task's files: `feat(media): add optional Android offline transcription pack`.

## Self-review
Spec C maps to Tasks1-2. Defaults exactly20min/100MiB/12frames/one job. Production link extraction and Persian performance remain unverified until fixtures and device checks run. This slice may ship without installed weights but must clearly report that state.
