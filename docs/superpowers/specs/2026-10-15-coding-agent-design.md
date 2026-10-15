# Hermes Mobile: reliable coding agent and integration packs

Status: proposed written specification for user review; conversational scope approved. This document does not claim implementation or Android compatibility testing of new integrations.
Date: 2026-10-15. Baseline: 47ad029, Android 2.4.1 (241), Kotlin/Compose, ARM64 Alpine/PRoot; minSDK24, target35.

## خلاصهٔ تصمیم پیشنهادی

هرمس باید یک محیط کدنویسی پروژه‌محور روی گوشی باشد: خواسته را به مشخصات تبدیل کند، فایل‌های مرتبط را پیدا کند، تغییر قابل بازبینی بسازد، تست اجرا کند و نتیجه را با مدرک تحویل دهد. اولویت اول، ورود حساب، مدل واقعاً پاسخگو و پایداری برنامه است. افزودن تعداد زیادی اسکیل بدون ابزار اجرایی، جای این زیرساخت را نمی‌گیرد.

ظاهر بر اساس سه تصویر Minis کاربر: نشست‌های مرتب با پیش‌نمایش، انتخاب مدل در سربرگ، منوی ابزارهای همان چت، ورودی پیام ساده و تنظیمات گروه‌بندی‌شده با آیکون. نشانگر رنگی فعلی با نشانگر کوچک و بدون درخشش بریده‌شده جایگزین می‌شود. فارسی و متن ترکیبی فارسی/کد باید خوانا بمانند.

## Existing capabilities, not new promises

ToolRegistry already provides file read/write/patch/search, shell/process, memory, skills, todo, session search, delegation, GitHub, browser and MCP. Agent already compresses context. Context7/GitHub presets and read-only workspace MCP exist. A YouTube transcript helper also exists; its Python dependency is not bundled by the prerequisite script and successful Android execution is unverified. Work below deepens these flows; it does not present them as newly invented tools.

## Delivery boundaries and order

A. Stabilize login, model diagnostics and chat navigation.
B. Add reviewable coding workflow, workspace context and curated skills.
C. Add bounded media extraction and optional transcription.
D. Add optional semantic/remote testing packs after compatibility checks.
Each slice needs its own regression evidence and APK gate. Do not release a speculative all-at-once bundle. Keep previous APK available until update acceptance; do not delete user data or silently reset provider settings.

## A. Accounts, models and interface

### Browser authorization
- One Android browser-auth coordinator opens Chrome Custom Tabs when available, otherwise the supported default browser. Do not require an embedded login WebView.
- Model auth as Idle, WaitingForConsent, Authorized, Expired, Cancelled, Failed. Model availability is separate state; successful authentication must remain successful if catalogue/probe fails.
- Nous currently uses device-code polling, not an arbitrary redirect protocol. Preserve the server contract; resume/reconcile polling on activity return; persist only what the flow needs in private storage with expiration. Use a verified return link where supported; otherwise expose a reliable explicit return action/notification. Do not promise automatic foreground launch Android/provider policy forbids.
- MCP retains PKCE and random state. Validate state and exact callback path before accepting code. A callback page must not claim token exchange success before exchange completes. Add a validated return-to-app action carrying an opaque operation ID, never a token.
- Cancel/expire requests cleanly; ignore stale/duplicate callbacks. Token refresh must not overwrite another account's session.
- GitHub remote MCP requires host OAuth/GitHub App setup according to upstream; until configured and verified, retain explicit PAT setup in encrypted Environment. Generic dynamic client registration is not equivalent to GitHub OAuth support.

Acceptance: correct consent -> authorized UI + protected request when account permits; denial, timeout, lost network, activity recreation, process restart and invalid state covered; model catalogue failure never erases authenticated status. Live account flow remains unverified without a consented test account/device.

### Model diagnostics and routing
- Add Test beside each model in the chat model picker. One bounded request to that profile's endpoint using its correct credential source; no chat history and no change to active model.
- Show checking, latency, responded, timeout, network error, authentication error, entitlement restriction and unsupported-model states. Store timestamp, endpoint/model identity and redacted diagnostic; never store credentials in results.
- A text response and tool-call support are different capabilities. Label them separately; no green 'coding compatible' badge from a text-only test.
- Limit one active probe per model and a small global concurrency limit. Timeout 30s; cancellation closes transport. Tests of priced models indicate that a small request can consume credit. No automatic mass testing.
- User-defined fallback groups: ordered choices, explicit free-only/paid policy, per-provider cooldown. Never retry the same deterministic FreeTierError in a loop or silently select a paid model.
- OpenCode Free is unresolved: compare packaged 9Router with supported upstream adapter/release and test real inference. Do not fake client identity or claim a provider's access restriction is removed by renaming models. If unavailable, show restricted status and user-configured alternatives. No installation or login requirement may be concealed.

Acceptance: MockWebServer covers 200,403 FreeTierError,401,404,429,5xx,timeout,cancel; probe leaves selected model and conversation unchanged; real OpenCode success requires recorded request outcome without credentials, otherwise stays unresolved.

### Chat and settings
- Sessions: date grouping, stable title, preview, running/paused/failed indicator and search. Keep user titles; sanitize generated JSON-like title only through a defined parser, not destructive heuristic replacement.
- Header: selected provider/model, capability status and model picker. Menu: New chat, Clear chat with confirmation, Terminal, Browser, Chat files, Skills/MCP/Memory in session, Token usage, Auto compact.
- Session controls show actual active scope; global configuration is not mislabeled session-only. Reuse existing compaction engine and expose threshold/status.
- Replace current offscreen additive glow (whose radius exceeds its canvas) with a restrained status dot/progress indicator and text. Respect reduced motion, screen readers and dark/light contrast. No bounding-box glow edge.
- Settings groups: Models & accounts; Appearance; Agent runtime; Connections; Device & data; Diagnostics. Reuse existing features; no dead menu entries.
- RTL paragraphs and mixed Persian/English; code, paths and URLs LTR within their own blocks. Touch targets at least48dp; long names wrap/ellipsize without covering actions.

Acceptance: Compose tests for menu routes, per-session selection and Test action; screenshot comparison in Persian/English and light/dark modes; emulator/device check for keyboard, scroll, voice pause/resume and browser return.

## B. Coding workflow

### Specification and curated skills
- Spec Kit supplies the project workflow: constitution once, then specify -> plan -> tasks -> implement -> converge. Adapt the real pinned upstream templates/skills to Hermes commands rather than merely saying 'Spec Kit installed' in a prompt.
- Default coding mode captures goal, constraints and acceptance criteria. Small fixes use a short path; ordinary chat is not forced through a long spec. Existing instructions and user edits are preserved.
- Implement project-local .specify and feature artifacts with a task/status panel; map every completed task to changed files/test evidence. Persist interrupted state; resumption does not rerun destructive commands.
- Curated Superpowers-derived pack: systematic debugging, meaningful tests, code review, verification and isolated branches. Map host tool names to Hermes tools; retain upstream attribution/license and pinned commit. Load relevant skills on demand; do not inject the entire library in every message.
- Skills registry: source, version, license, supported runtime, dependencies, enabled state and last health check. Installing a skill does not grant extra permissions. External skill content cannot override user instruction, secret boundaries or approvals.

### Workspace context and controlled edits
- Build on current read-only workspace MCP. Start with bounded ripgrep/file map; add Tree-sitter-backed symbol index per supported language after ARM64 builds pass. Syntax parsers alone do not provide LSP type/reference semantics.
- Index incrementally with content fingerprints; obey ignore rules, file-size limits and project root. Exclude credentials/build dependencies. Let users pin @files, see context budget and remove context.
- Changes panel: before/after diff, affected files, test results and a reversible checkpoint. Preserve pre-existing user changes; never use blanket reset/clean. Rollback checks current content against expected post-edit hash and stops on conflicts.
- Use Git worktrees where available for concurrent implementation; otherwise serialize writers. Reviewer can be read-only. Agent/task concurrency and token budgets bounded by project settings; avoid unbounded self-delegation.
- Test runner exposes command, cwd, start/end, exit code and capped output; timeout/cancel supported. Distinguish not-run, failed, skipped and passed. No passing badge based on an LLM summary.
- Optional pre-publish Gitleaks CLI scan, with redacted finding locations; start in CI/host until mobile executable is validated. Do not imply every Go release binary runs on Android.

Acceptance: dirty-worktree fixture preserves unrelated edits; rollback conflict is detected; symlink traversal escapes rejected; large repo indexing cancellable; task resume retains completed tools without duplicate execution; deliberate failing test remains failed in UI.

## C. Link/media understanding

- Detect submitted YouTube/Instagram/public media URLs and create a cancellable extraction task when the user asks for analysis or sends a bare link in analysis mode. Do not fetch every incidental URL quoted in code or assistant output.
- Pipeline: metadata/captions -> bounded media download if needed/accessible -> transcription -> optional sampled-frame vision. Save provenance: URL, extraction method, language, timestamps, coverage and actual retrieved artifacts.
- Use pinned yt-dlp source/wheel, ffmpeg/ffprobe and required JS support in a reproducible media pack. Check licenses of the exact build and dependencies. Existing YouTube skill must point to the actual mobile interpreter and helper, not a desktop pm checkout.
- whisper.cpp Android JNI is a candidate for offline transcription. Offer optional multilingual tiny/base weights with size, checksum, delete and device resource limits. Do not bundle large weights into every APK. Persian quality/latency requires measured samples; upstream estimates are not phone benchmarks.
- Without captions, usable audio or configured transcription, report exactly what was available. Metadata-only summary cannot be labeled full video analysis. Instagram private/login-only content requires the user's own consented session or uploaded file; no guarantee every URL works.
- Initial configurable defaults: single active media job, max20min audio, max100MiB download, max12 sampled frames; disclose limits and allow explicit adjustment. Temporary files cleaned on cancellation; durable output remains downloadable in chat.

Acceptance: captioned/no-caption/private/deleted/rate-limited fixtures, oversized download abort, cancellation, no transcript hallucination, Persian/English sample comparison and memory/storage measurements on ARM64 device.

## D. Optional packs and research shortlist

Source metadata/README retrieved2026-10-15. Listed licenses are top-level evidence, not a complete legal or dependency audit. None of these new integrations is claimed tested on this app.

| Project | Intended value | Proposed placement | Prerequisite / limitation |
|---|---|---|---|
| [Spec Kit](https://github.com/github/spec-kit) | Requirements, tasks and verification artifacts | Default adapted coding workflow | MIT; CLI requires Python3.11+/uv; pin templates and implement command adapter |
| [Superpowers](https://github.com/obra/superpowers) | Debugging, tests, review and completion discipline | Curated on-demand skills | MIT; host-specific instructions must be adapted |
| [Tree-sitter](https://github.com/tree-sitter/tree-sitter) | Code structure and symbol extraction | Optional local language pack | MIT core; grammar licenses/builds checked individually; not full semantic LSP |
| [Aider](https://github.com/Aider-AI/aider) | Repo-map and Git workflow reference | Architecture reference, not another bundled agent | Apache2.0; reimplement suitable workflow or attribute reused code |
| [Serena](https://github.com/oraios/serena) | Semantic navigation and edits via language servers | Optional remote/desktop MCP initially | Application GPL3-or-later, SolidLSP MIT; Python3.13/uv and per-language runtimes; no Android support claim |
| [Context7](https://github.com/upstash/context7) | Version-aware library documentation | Activate and test existing remote preset | MIT repository; service account/limits may apply; remote content remains untrusted |
| [GitHub MCP](https://github.com/github/github-mcp-server) | Repository, PR and CI operations | Existing remote preset plus working account configuration | MIT; OAuth host registration or PAT; scoped permissions |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Accessible media/captions extraction | Reproducible optional media pack | Source/wheel Unlicense; distributions/dependencies differ; Python, JS and ffmpeg prerequisites |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | Offline transcription | Optional Android JNI engine and model download | MIT; Android example exists; real phone performance/Persian accuracy unverified |
| [Playwright MCP](https://github.com/microsoft/playwright-mcp) | Browser testing and screenshots | User-configured remote worker | Apache2.0; Node/browser runtime; no bundled Chromium claim |
| [Maestro](https://github.com/mobile-dev-inc/Maestro) | End-to-end Android regression flows | Developer CI/host QA, not APK plugin | Apache2.0; Java17+, emulator/device; fixture account for login |
| [Gitleaks](https://github.com/gitleaks/gitleaks) | Secret detection before publication | CI/host first, optional local build later | MIT CLI; artifact/platform compatibility must be checked |

Preferred alternative to installing a server database on phones: retain simple project files and evaluate Android SQLite/Room indexing only when measured search needs it. VillageSQL extension tooling is not an Android client database layer; no VillageSQL server is added by this design.

## Compatibility, migration and release proof

- Ship a lightweight core; packs declare pinned version, supported architecture, dependency checks, size and rollback. Explicit user request before downloading large models or connecting external services; account consent remains with the user.
- Existing credentials stay encrypted; diagnostic export redacts tokens, cookies and Environment values. Model probes never include project text. New integrations use least required scopes and existing approvals.
- Keep files/download cards, English Copy/Retry, voice pause/resume, Nous-only account login, Bot Mode and local router support working. Safe recovery must remain reachable even if a new pack fails.
- No server-side access-control bypass or false successful-model claims. Error handling is not proof inference works.
- Native compilation and Robolectric tests are necessary but insufficient. Release gates include upgrade from2.4.1 without data loss, ARM64 cold start, permission denial, low storage/network interruption, Chrome return and background lifecycle behavior.
- Simulator Login requires consented test credentials/device. No adb/device currently available in this workspace; record device tests as blocked, not passed. Establish CI emulator for UI flows and a physical ARM64 run for sandbox/native/media behavior.
- Sign with existing release key; verify package/versionCode/versionName and checksum; publish one named APK only after applicable checks. Provider/account-dependent gaps stated in release notes.

## Design tooling and validation

User images define layout direction. Taste returned no learned profile or matching references. Figma can document component variants if a design file is selected; it does not add runtime capabilities. Open Design transport is unavailable here; no app installer was invoked. GodPrompt/Skillquiver/Vibe Coding are engineering workflows used in this task, not automatically embeddable Android SDKs.

## Written-spec review

The earlier user approval covered the conversational direction. This written spec adds concrete limits, candidate placement and acceptance criteria and is ready for review. Under the selected Superpowers architectural workflow, written-spec approval precedes the detailed implementation plan and execution selection. No new product code has been changed in this research/design step.
