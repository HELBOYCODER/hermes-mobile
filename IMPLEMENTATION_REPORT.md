# Hermes Mobile — implementation report (LOCAL ONLY, not pushed)

Repo: local clone at `/home/hatch/workspace/hermes-mobile-audit` (HELBOYCODER/hermes-mobile, main, v2.6.3).
Nothing was pushed to GitHub, no token/password/secret was used, and no other
app's UI, assets, text or branding was copied. All new UI uses Hermes' own
design system (`ui/theme/Skins.kt`: rounded 18dp cards, skin colors, existing
buttons/chips). "ChatGPT-like" was interpreted as soft, light, spacious,
rounded and professional — not a copy of ChatGPT's UI.

## 1. 9Router update + wreq-js

- `scripts/bundle_prerequisites.sh`: pinned 9router `0.5.99` → `0.5.112`
  (verified as npm latest on 2026-10-30). Right after that install, the
  script now installs `wreq-js` into the router's own directory
  (`usr/lib/node_modules/9router`) and asserts its folder exists. The
  existing check for `usr/lib/node_modules/9router/app/custom-server.js`
  is unchanged and still present.
- `router/NineRouter.kt`: new `wreqJsStatus` flow (`unknown` / `available` /
  `missing` / `install failed`) and `ensureWreqJs()` / `checkWreqJs()`.
  `installAndSetup()` now calls the ensure step right after
  `installBundledRouter()`, so EXISTING installs get wreq-js too, not only
  fresh bundles. The check is `node -e "require('wreq-js')"` inside
  `/usr/lib/node_modules/9router`; on failure it runs
  `npm install --no-audit --no-fund wreq-js` there and logs the result.
  A failed wreq-js install does not fail the whole router setup (other
  lanes still work); the status shows the failure honestly.
- HONEST LIMIT: wreq-js fixes the 502 "wreq-js unavailable" error only.
  It does NOT remove OpenCode's upstream free-tier daily quota — that
  limit is set by the provider. This is stated in the bundle script
  comment, in `NineRouter.kt`, and in the Router screen text.

## 2. 9Router as a default, separate section

- `MainActivity.kt`: new top-level destination `Dest.Router` ("9Router",
  Hub icon), added to the drawer in its own group "AI router".
- New `ui/RouterScreen.kt`: state, Install / Start / Stop / Refresh free
  models buttons, dashboard URL (copyable), dashboard password (masked by
  default, copyable — the same `settings.routerPassword` value the
  Settings section uses, not a second copy), wreq-js status + "Ensure
  wreq-js" button, and free-model count (models with source `9router`).
- Default install: verified already wired — `MainActivity` calls
  `rt.startOptionalServices()` on startup (non-safe mode), which calls
  `router.autoStartIfNeeded()`; `routerAutoStart` is already `true` by
  default in `core/Settings.kt`. No duplicate call was added. The existing
  Settings → Local 9Router section is untouched and still works as the
  detail page.

## 3. More providers by backend link (user's own account/key only)

- New `ui/ModelPicker.kt` → `AddProviderDialog` ("Add provider" button
  in the ModelPickerSheet): name + backend base URL + the user's own API
  key. Presets only fill the URL: z.ai
  (`https://api.z.ai/api/paas/v4`), OpenRouter
  (`https://openrouter.ai/api/v1`), DeepSeek
  (`https://api.deepseek.com/v1`), Gemini
  (`https://generativelanguage.googleapis.com/v1beta/openai`), Mistral
  (`https://api.mistral.ai/v1`), Groq (`https://api.groq.com/openai/v1`),
  Custom (free-form, e.g. the user's own cline2api-style bridge).
- "Fetch models" calls `GET <baseUrl>/models` with the user's key via the
  existing `LlmClient.listModelInfo()` → `ModelCatalog.parse`, stores via
  `modelStore.replaceSource` with source id `provider:<id>`, lists free
  models first (`ModelCatalog.freeAgentModels`), and tapping a fetched
  model selects it exactly like Nous/9Router models.
- No provider login/OAuth flow was added for any of these, no shared
  account, no extracted cookies/tokens, no hardcoded key, and no
  affiliation is claimed. The existing Nous OAuth flow is untouched.
- `llm/LlmClient.kt`: added the `zai` preset (URL only). DeepSeek,
  Gemini, Mistral, Groq presets already existed in `Providers.all`.
- Key storage: same as every existing provider key — the app-private
  model-profile list in SharedPreferences (NOT encrypted at rest, the
  same limitation as existing providers). Keys are only sent to the
  backend URL the user entered. The dialog states this.
- z.ai account login: NOT implemented, marked unverified. Official z.ai
  docs describe API-key creation and a Coding Plan restricted to
  officially supported tools; no verified third-party account-login
  protocol was found, so z.ai stays an API-key preset. The dialog says
  this explicitly.

## 4. ChatGPT-like polish, inside Hermes' own skins

- Thinking selector: the existing `settings.reasoningEffort` ("", none,
  low, medium, high) is now a segmented chip row in the ModelPickerSheet
  labelled "Thinking". `LlmClient` already sent it (Agent passes
  `settings.reasoningEffort`; OpenRouter gets `reasoning.effort`, others
  `reasoning_effort`) — no wiring change was needed there, and none
  beyond the DeepSeek case below was made.
- Model picker: kept the existing bottom sheet, its provider grouping,
  Free filter and search — not rebuilt.
- Categorized features: SettingsHub groups are unchanged in structure
  except the new entries land in the right group (see §7): providers
  under Models (Intelligence), 9Router under Connections. Nothing is
  duplicated.
- Back button: `MainActivity` TopAppBar now shows an explicit Back
  action (ArrowBack → existing `goBack()` history) on every non-Chat
  destination, in addition to the existing system BackHandler. The drawer
  menu stays reachable on non-Chat screens via a menu action.

## 5. Engine selection — Hermes native vs Pi harness (concept only)

- `core/Settings.kt`: new `engine` setting (`native` default | `pi`).
- New `agent/PiHarness.kt` (Hermes' own code; nothing copied from Pi):
  Pi = pi-coding-agent CLI (Earendil Works, MIT), installed inside the
  existing Alpine sandbox via
  `npm install -g --ignore-scripts @earendil-works/pi-coding-agent`
  (Node 22.19+ per Pi docs), run non-interactively as
  `pi --print --no-session --thinking <level> --model <model> "<prompt>"`
  in the project directory.
- Thinking mapping (Hermes → Pi `--thinking`): blank = Pi default (flag
  omitted), none → off, minimal → minimal, low → low, medium → medium,
  high → high, xhigh → xhigh, max → max (model support varies).
- Engine selector appears in Settings → Agent engine AND in the chat
  ModelPicker area. Settings → Agent engine also has Check/Install Pi
  buttons. If Pi cannot be installed or run, status is "Pi harness not
  installed" and chat falls back to native — a Pi run is never faked.
  The Coding workspace has a "Run with Pi" button; on Pi failure it
  shows that message and sends the task in chat on the native engine.
- Approval limit, stated in code and UI: Hermes' per-tool approval gate
  does NOT intercept Pi-internal tool calls. A Pi run starts only on an
  explicit user button press, and that press is the approval for that
  run. Do not use Pi mode for unattended or approval-sensitive work.
- DeepSeek + both engines: DeepSeek is a provider preset (above), not a
  harness. Native engine runs it via `LlmClient` (below). Pi mode passes
  the selected model id through to Pi; Pi must itself be configured for
  the DeepSeek provider, otherwise the run fails honestly — DeepSeek+Pi
  is offered, not guaranteed.

## 6. DeepSeek thinking mapping (LlmClient)

- For provider `deepseek`, Hermes levels map to the only valid DeepSeek
  thinking values: low/minimal → low, medium/high → high, xhigh/max →
  max, blank/none → no thinking field. Sent as
  `thinking: {type: "enabled", effort: <mapped>}` instead of
  `reasoning_effort`.
- If a DeepSeek model rejects the thinking field (HTTP 400), the request
  is retried exactly once without thinking, then the error surfaces.
  Other providers are unchanged.

## 7. Other portable agentic features (Hermes' own code only)

- Project workspace: Coding screen now has a "Project name" field +
  "Start project" button. A name is sanitized and maps to
  `/root/projects/<name>`; an existing folder is reused with its files,
  a missing one is created. The existing path field and its
  inside-`/root/projects` check remain. File edits were already shown
  as a reviewable checkpoint list (CodingPanels "File checkpoints",
  preview + restore with conflict stop) — that concept is reused, not
  rebuilt.
- Live preview + real export: CodingPanels now has "Web preview &
  export". Preview loads the project's `index.html` into the EXISTING
  Browser screen via a new `BrowserManager.previewHtml()` (loadData
  into a normal tab, no new backend). Limit: linked local assets are
  not served, so a multi-file site previews as its single document.
  Export zips the project (excluding `.git`) and saves it through the
  existing `DownloadsExporter` path into Downloads/Hermes.
- Settings reorganization (structure only, Hermes skins/cards): the hub
  now starts with an account/profile row (Nous login state, current
  provider · model · engine, tapping opens Models), then groups:
  Model & providers, Appearance, Chat & thinking, 9Router, Data &
  privacy, Development, About. `SettingsCategory` group labels were
  remapped accordingly; no category was duplicated. Every detail screen
  already had a working Back button (`SettingsDetailHeader` + the
  screen's `BackHandler`); that was verified, not re-implemented.

## 8. Spec Kit, ON by default in chat

- `core/Settings.kt`: new `specFirst` boolean, default `true`.
- Reused the ALREADY bundled templates under
  `app/src/main/assets/coding/spec-kit/` (constitution, spec, plan,
  tasks templates) — nothing re-downloaded, and `specify-cli` itself
  was NOT installed (desktop Python tool, not an Android runtime).
- Chat composer now has a "Spec first" chip (toggles `specFirst`,
  persisted in settings); Settings → Agent engine has the same toggle.
  When ON, `PromptBuilder` adds a Spec-first block: build requests
  first go constitution → spec → plan → task checklist (shown in chat),
  then implementation task by task with test evidence. When OFF, the
  block is absent and chat behaves as before. The Coding workspace
  already showed the spec/plan/tasks checklist (CodingPanels
  "Specification workflow" + "Tasks & verification"); that checklist
  is now also driven by the same Spec-first setting via the prompt.

## 9. Tools on phone — desktop Hermes toolset vs phone equivalents

Audit basis: `agent/tools/Tool.kt` (`ToolRegistry`: 19 toolsets, 31
built-in tools + MCP tools), plus the backing implementations. Finding:
every desktop tool concept already had a phone equivalent in this
codebase; the only real gap was visibility — the toolset toggles lived
only under Settings → Tools. So the change below wires them into the
Coding screen; no new tool runtime was invented, and no tool below is
claimed to work beyond its code-level backing (see Verification).

- `app/src/main/java/com/ersaz/hermesmobile/ui/ToolsPanel.kt` (NEW) —
  "Tools on this phone" panel: every toolset with its phone-equivalent
  note and an on/off switch writing the same
  `settings.setToolsetEnabled` the agent's ToolRegistry reads.
- `app/src/main/java/com/ersaz/hermesmobile/ui/CodingScreen.kt` —
  `ToolsPanel(rt)` inserted after `CodingPanels(...)`.

| Desktop tool (concept) | Phone equivalent in this app | Verification status |
|---|---|---|
| File read/write/edit/patch | ReadFile/WriteFile/PatchTool over the sandbox filesystem (`/root/projects`; `/sdcard` only if bound) | Code-level (ToolRegistry + FileTools.kt); not run on device |
| Shell / terminal | TerminalTool + ProcessTool in the Alpine/PRoot sandbox | Code-level; sandbox itself installs python3/git/nodejs via apk (Sandbox.kt) |
| Code execution | Same sandbox terminal: `node`/`python3` scripts run as shell commands (no separate code-execution toolset — terminal covers it) | Code-level; no on-device run verified |
| File/code search | SearchFilesTool (sandbox) + bounded source search via bundled MCP | Code-level |
| Web search / fetch | WebSearchTool (DuckDuckGo/Tavily/Brave) + WebExtractTool + AnalyzeLinkTool | Code-level |
| Browser / web automation | BrowserTool over the in-app Chrome-engine browser (snapshot/click/type/eval/screenshot, user handover for login/CAPTCHA) | Code-level (BrowserTool.kt, BrowserManager.kt) |
| Memory | MemoryTool → on-device MEMORY.md / USER.md (MemoryStore) | Code-level |
| Skills | SkillsList/View/Manage tools → SkillStore (bundled + user skills) | Code-level |
| Task planning | TodoTool | Code-level |
| Session recall | SessionSearchTool | Code-level |
| Scheduled tasks | CronjobTool → CronRunner/CronStore | Code-level; jobs run while the app/service is alive (not verified across a reboot here) |
| Sub-agents | DelegateTool → isolated sub-sessions (depth-limited; delegate/clarify/memory/send_message/cronjob/message_bot blocked in sub-agents by ToolRegistry) | Code-level |
| Messaging gateway | GatewayManager (Telegram + Discord adapters) + SendMessageTool + MessageBotTool (Bot Mode roster) | Code-level; gateway adapters gated on the user's own tokens in Settings |
| Clarifying questions | ClarifyTool in chat | Code-level |
| Text-to-speech | TextToSpeechTool (on-device TTS) | Code-level |
| Vision / image analysis | VisionTool, incl. browser screenshots | Code-level |
| GitHub | GitHubTool (REST API, user's own token from Settings) | Code-level |
| MCP tools | McpTools from connected MCP servers (incl. bundled local coding MCP) | Code-level |
| Phone-only extras (no desktop counterpart) | AndroidDeviceTool (clipboard, notifications, URLs, battery), PhoneTool + WeatherTool | Code-level |
| Desktop GUI automation | NOT added — cannot run on Android. Phone equivalent: PhoneControlTool via Shizuku (tap/swipe/type/screenshot/install apps) drives the Android UI only, never a desktop GUI | Code-level (PhoneControl.kt) |
| Speech-to-text (agent tool) | No STT agent tool exists; phone equivalent is UI voice input (stt/ VoiceInput, incl. dictation) — not wired as an agent tool | Documented as absent; not added |
| Image / video generation | No such tool exists in this app | NOT implemented, NOT claimed; adding one would need a verified provider endpoint, which was not available here |

## 10. Z.ai official login, first-page permissions, Android IDE, Playwright-style browser tools

All in Hermes' own code/UI/engine only. No code copied from any
other project (Ghostide/Ghostty concepts only, not code; Playwright MCP
tool concepts only — navigate/snapshot/click/type/etc. — implemented on
Hermes' existing BrowserManager/WebView, Playwright itself NOT bundled).

### 10a. Z.ai account login — STUB (official endpoint unverified)
- Checked 2026-10-30 against official sources: docs.z.ai (API
  reference + HTTP API + Quick Start + API Key Management Guide) and
  ZCode docs (zcode.z.ai). What they actually publish: API-key
  creation in the developer console — a standard key for
  `https://api.z.ai/api/paas/v4` and a SEPARATE Coding Plan key for
  `https://api.z.ai/api/coding/paas/v4` (OpenAI-compatible coding
  endpoint; the two key types are not interchangeable, and Coding Plan
  use is restricted to officially supported tools). ZCode docs
  describe "authorize and sign in with a Z.ai account" inside ZCode
  itself — NOT a reusable third-party OAuth authorize/token endpoint,
  client_id, or scope for Hermes. No such endpoint was found, so none
  was invented.
- `app/src/main/java/com/ersaz/hermesmobile/ui/ZaiLoginCard.kt` (NEW)
  — "Connect Z.ai account" card, labelled in the UI itself
  "official login (unverified endpoint — needs checking)". Tapping
  it sends NOTHING anywhere: no URL is opened, no token is created,
  logged, or stored, and no key paste is asked for by this flow. The
  card explains what was checked and points at the supported path
  today (Add provider → z.ai preset → the user's own key). When a
  real official endpoint is verified, the one-tap flow belongs here:
  system-browser authorize URL + hermes-auth return link (exactly the
  existing BrowserAuthCoordinator pattern), then provisioning into the
  existing provider store — that flow is described in the code comment,
  not implemented on a guessed URL.
- Placed next to the Nous login card in BOTH places: onboarding
  first page (`OnboardingScreen.kt`, always shown) and the Providers
  list (`SettingsScreen.kt` Models section, when provider == "zai").
- After-login model listing: already works through the existing
  fetch path — models fetched with the user's own z.ai key appear in
  the ModelPicker exactly like Nous models (replaceSource +
  freeAgentModels). That path is unchanged; only the automatic
  provisioning step is stubbed.

### 10b. First-page "Grant all permissions" button
- `app/src/main/java/com/ersaz/hermesmobile/ui/GrantAllPermissions.kt`
  (NEW) + a "Permissions" section on the onboarding first page
  (`OnboardingScreen.kt`). One button launches a single
  RequestMultiplePermissions batch (the app's existing launcher
  pattern, as used for mic/notifications elsewhere) with every runtime
  permission Hermes actually declares in AndroidManifest.xml
  (checked 2026-10-30) — exactly these 6: POST_NOTIFICATIONS,
  RECORD_AUDIO, READ_CALENDAR, WRITE_CALENDAR, READ_CONTACTS,
  ACCESS_COARSE_LOCATION. The UI then lists each permission as
  granted/denied, because Android itself still decides each one
  individually; the button only starts the batch.
- NOT requested, deliberately: SMS (no SEND/READ/RECEIVE_SMS in the
  manifest, not used anywhere — not added); CAMERA, fine location,
  READ_MEDIA_* (not declared); WRITE_EXTERNAL_STORAGE (legacy,
  maxSdkVersion 28 — not runtime-requestable on modern Android; file
  access goes through system pickers + the All-files setting).
  MANAGE_EXTERNAL_STORAGE, REQUEST_INSTALL_PACKAGES,
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, QUERY_ALL_PACKAGES are special
  settings Android never grants from a permission dialog — the UI note
  says they need their own Settings screens (All-files access is
  already opened from Settings → Sandbox). Normal permissions
  (INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED,
  VIBRATE, SET_ALARM, FOREGROUND_SERVICE) are install-granted and need
  no request.

### 10c. Android IDE, ON by default, inside Coding
- `app/src/main/java/com/ersaz/hermesmobile/ui/IdePanel.kt` (NEW),
  inserted at the top of `CodingScreen.kt` (before the Project
  section) — visible by default, not in a submenu. Hermes' own engine
  only (native/Pi setting shown in the panel; "Generate code in chat"
  sends the task to chat with the current engine named).
- Templates: "Create minimal Kotlin/Compose app skeleton" writes
  settings.gradle.kts, root + app build.gradle.kts, AndroidManifest,
  MainActivity.kt (Compose setContent), and a README into the current
  project folder (existing files are NOT overwritten). The template
  header says plugin versions may need adjusting to the device's SDK.
- File tree: recursive listing of the project folder (max 200 shown,
  .git/build/.gradle/.idea skipped); tapping a text file < 500 KB
  opens the SkillsScreen-style monospace editor (Edit + Save/Cancel)
  — the existing Hermes editing pattern, reused.
- Terminal: button navigates to the existing sandbox Terminal screen.
- Logcat viewer: read-only "Refresh logcat" runs `logcat -d -t 200`
  on-device and shows the last ~20 KB. Honest note in the UI: Android
  restricts third-party apps to their own app's log lines; system-wide
  logcat needs a system app or ADB. Nothing is cleared. (READ_LOGS was
  NOT added to the manifest — it would not be granted to a
  third-party app anyway.)
- Git: "Git status" / "Git log" run the sandbox git binary in the
  project folder via the existing `Sandbox.exec`, output + exit code
  shown verbatim.
- Build APK: one tap FIRST checks for an Android SDK inside the
  sandbox (ANDROID_HOME / ANDROID_SDK_ROOT / /opt/android-sdk /
  /usr/lib/android-sdk / ~/Android/Sdk, platform-tools present) AND
  for a gradlew in the project; only if both exist does it run
  `./gradlew assembleDebug` and show its real exit code/output. If
  either is missing it reports exactly that ("Android SDK not
  installed on this device" + install steps: cmdline tools,
  ANDROID_HOME, platform android-35 + build-tools 35.0.0, Gradle
  wrapper) and attempts NO build. No build is ever faked.

### 10d. Playwright-style browser tools (MCP concept, Hermes implementation)
- The agent tool already had most of the set on BrowserManager/WebView
  (navigate/open, snapshot, click, type, select, press, scroll, text,
  eval, screenshot, back/forward/reload, wait, tabs, cookies,
  handover). Added the missing piece: a `fill` action in
  `browser/BrowserTool.kt` (type with clear=true, same ref/text/submit
  args), listed in the tool schema and description. Playwright itself
  is NOT bundled (desktop Node tool) and no Playwright compatibility
  beyond the tool names is claimed.
- User-facing exposure in `ui/BrowserScreen.kt`: a tools row under the
  address bar with Snapshot (structured text outline from
  `BrowserManager.snapshot`, shown scrollable — NOT a screenshot),
  Wait 2s, and Screenshot (saved JPEG path via the same
  `BrowserManager.screenshot` the agent tool uses). Navigate/click/
  type/fill remain in the address bar and the page itself.
- Approval: a tap in the Browser screen is the user acting directly
  (their tap is the approval); agent-initiated browser actions remain
  agent tool calls under the existing tool loop, and the "browser"
  toolset toggle disables them. There is no separate submit-gate for
  agent browser actions in this codebase, and none is claimed — the
  existing handover mechanism still covers login/CAPTCHA steps.

## 11. Full provider catalog (auto backend URLs, login/key actions, auto model fetch)

- `app/src/main/java/com/ersaz/hermesmobile/llm/ProviderCatalog.kt` (NEW)
  — the catalog data. Every base URL is reused verbatim from the app's
  pre-existing Providers list; none is invented. Each entry carries
  display name, official backend base URL, login type, default model,
  key hint, and a note (including the caveats below).
- `app/src/main/java/com/ersaz/hermesmobile/auth/OpenRouterAuth.kt` (NEW)
  — OpenRouter "Sign in with OpenRouter", Hermes' own code, from the
  official OpenRouter docs (openrouter.ai/docs/guides/overview/auth/oauth,
  checked 2026-10-30): PKCE S256 (SecureRandom verifier, SHA-256
  challenge), authorize at https://openrouter.ai/auth, exchange at
  POST https://openrouter.ai/api/v1/auth/keys for a user-controlled
  key. Because this app has no website for a callback and whether
  OpenRouter accepts an Android intent:// callback URL is unverified,
  the docs' HEADLESS mode is used: no callback_url, OpenRouter
  displays a one-time authorization code, the user pastes that code
  (single-use, 10-minute expiry — it is NOT an API key) and Hermes
  exchanges it. The code itself is never stored; only the returned key
  goes into the existing key store, and it is never logged. The
  exchange response is parsed tolerantly ("key", then "api_key", then
  any top-level "sk-or-…" string) because the fetched doc page renders
  the example without its body text; if no key is found, login fails
  cleanly instead of storing anything.
- `app/src/main/java/com/ersaz/hermesmobile/ui/ProviderCatalogPanel.kt` (NEW)
  — the catalog UI, wired into BOTH Providers places: Settings →
  Models (replacing the old provider Picker) and onboarding (replacing
  the old Picker + standalone login cards). Behaviour, enforced in
  code: tapping an entry selects it and AUTO-FILLS its official
  backend URL (never typed by hand, except Custom where the URL is the
  point); a signed-in Nous account keeps its route/model; the selected
  entry shows exactly ONE primary action — Login (Nous card /
  OpenRouter PKCE), Fetch free models (local 9Router, no external
  account), or Save key (key providers); after a successful login or
  key save, models fetch AUTOMATICALLY via GET <baseUrl>/models with
  the existing `LlmClient.listModelInfo()` + `ModelCatalog.freeAgentModels`
  + `modelStore.replaceSource("provider:<id>")` pattern, free first,
  into the ModelPicker — no extra manual step. Nous auto-fetch fires
  on the signed-out → signed-in transition via `refreshNousModels()`.
- Catalog contents: LOGIN — Nous (existing device-code flow,
  untouched), OpenRouter (PKCE above); ROUTER — OpenCode free via
  local 9Router (`refreshFreeModels(selectAsProvider = true)`, no
  external account, upstream daily quota still applies); KEY —
  DeepSeek, Gemini (official Google AI API only; no consumer
  Antigravity sessions or CLI wrapping, per issue #12), Mistral,
  Groq, OpenAI, xAI, plus local Ollama / LM Studio (LAN, no key);
  LOGIN_UNVERIFIED → key entry only — Z.ai (see §10a; its stub card
  renders inside the Z.ai entry); CUSTOM — name + URL + key.
- Anthropic caveat (corrects the old Providers label that called it
  "OpenAI-compatible"): Anthropic's official API is Messages-format,
  so the app's OpenAI-style chat path and Bearer-authenticated
  /models fetch against it are unverified and may fail; the entry is
  kept with its official base URL and a failed fetch shows its real
  error instead of a faked success.
- Supersession note (fixes §10a): the Nous and Z.ai login cards no
  longer sit as always-visible cards on onboarding / Settings Models;
  they now render inside the selected catalog entry (Nous card for
  Nous, stub card for Z.ai). Nothing was removed — the cards still
  exist and render, reached by tapping the entry. The old provider
  Picker's auto-fill logic is preserved inside the panel's select().

## 12. Suggested replies (ON by default)

- `app/src/main/java/com/ersaz/hermesmobile/ui/SuggestedReplies.kt`
  (NEW): local keyword rules over the last assistant message, max 3,
  no extra LLM call — code → ["ادامه بده", "خط‌به‌خط توضیح بده",
  "تستش کن"], question → ["بله", "نه", "توضیح بیشتر بده"], design →
  ["نسخه دیگری بساز", "رنگ‌ها را عوض کن", "خروجی را ذخیره کن"],
  fallback → ["ادامه بده", "خلاصه کن", "مثال بزن"]. Chips sit above
  the composer in ChatScreen (Coding chat shares it); tapping one
  FILLS the composer (never sends) and dismisses the chips for that
  message; hidden while busy/streaming or when the toggle is off.
- `settings.suggestedReplies`, default ON (Settings → Chat).
- Verification: code-level only, NOT verified on device/build.

## 13. Huashu Design skill, installed BY DEFAULT

- Bundle `app/src/main/assets/skills/creative/huashu-design/`:
  SKILL.md (15,558 bytes) + `references/` (38 .md files, 636,299
  bytes) + `LICENSE-HUASHU.txt` (MIT, author alchaincyf) — 708K
  total. Original filenames kept; attribution kept. Heavy assets
  (style specimens, showcases, sfx/bgm) are NOT in the APK.
- `HERMES-ASSET-MANIFEST.txt` (2026-10-30 snapshot, 265 files,
  ~39.9 MB: style specimens 173 + showcases + sfx/bgm 43) is the
  source of truth for the on-demand download; `HuashuDownload.kt`
  adds a download row in the SkillsScreen editing view for
  huashu-design only (sandbox curl loop into `/root/huashu-samples/`,
  size-match skip, live tail + downloaded/failed counts, failures
  listed honestly).
- Agent wiring: `DESIGN_SKILL_ROUTING` in `Agent.kt` — design requests
  follow the Huashu flow (6 preview candidates → user picks 3 →
  polish); HTML via Browser preview + ZIP export + Spec-first
  checklist. Desktop `npx skills add` NOT used.
- PPTX: NOT claimed — "HTML deck first, editable PPTX needs a
  verified converter" (html2pptx.js+pptxgenjs or python-pptx, neither
  bundled/verified).

## 14. Seven design repos (text/skill parts only, all ON by default)

1. UI UX Pro Max (nextlevelbuilder/ui-ux-pro-max-skill, MIT, 839K):
   skill text + colors.csv (192 palettes) + google-fonts.csv +
   catalog-summary.json; the design flow offers palettes/font pairs
   as choices for every design output.
2. Impeccable (pbakaus/impeccable, Apache-2.0, 457K): skill text +
   reference/; the critique/audit pass is instruction-level (the
   agent runs it before delivery), NOT a separate blocking validator.
3. Framelink (GLips/Figma-Context-MCP, MIT): ONE MCP entry
   `framelink-figma` in the Hub (stdio `npx -y figma-developer-mcp`,
   env `FIGMA_API_KEY=${FIGMA_API_KEY}`, enabled=false until the
   user's own key is entered, "needs key" state). Figma link →
   get_figma_data → Hermes-style code; else direct Figma REST with
   the user's key. Never faked.
4. React Bits (reactbits.dev): web-prototypes-only via CDN/import
   with attribution; never native Android components.
5. GSAP skills (greensock/gsap-skills, MIT, ~96K, 8 SKILL.md +
   llms.txt): generated HTML animations use GSAP (free, including
   plugins) via CDN, following those skills.
6. HyperFrames (heygen-com/hyperframes, Apache-2.0, 134K, no
   renderer/assets/scripts): workflow docs/templates bundled; HTML
   preview works now; video rendering only after a verified sandbox
   Chromium+ffmpeg check (`command -v`), else honestly not
   available on-phone.
7. Blender MCP (ahujasid/mcp-for-blender, MIT): Hermes-authored
   script-generation guidance only (`blender-scripts/SKILL.md`); no
   live Blender control is claimed on-phone; scripts are written for
   desktop Blender.
- Attribution files `LICENSE-UIUXPROMAX.txt`, `LICENSE-IMPECCABLE.txt`,
  `LICENSE-GSAP.txt`, `LICENSE-HYPERFRAMES.txt` sit next to the bundles.

## 15. MCP Hub (registry, defaults, custom install flow)

- `McpServer` extended (displayName, cwd, autoStart, builtin,
  allowedTools/deniedTools, timeoutSec, lastError; Hub extras in JSON
  only when non-default; `fromJson` defaults autoStart=true when
  absent, enabled=true when absent).
- `McpManager`: Hub seeding (incl. framelink-figma, marker
  `mcp/.hub-defaults-v1`), autoStart gate in `connectAllAsync`,
  allow/deny filtering in `tools()`/`allDefs()` + enforcement in
  `callTool()` (the generic `mcp` tool cannot bypass it), lastError
  persistence only on change, stdio env placeholder resolution with
  a clear missing-variable error, timeout wiring, `diagnostics(id)`.
- `StdioTransport` cwd parameter (shell-quoted, fallback /root; proot
  `-w` stays /root so a bad cwd degrades safely).
- `McpHubScreen.kt` (NEW): list grouped Built-in/Custom (name,
  transport badge, status dot, tool count, enabled switch); Add with
  validation + Test-&-save gating auto-start + Save draft; Import
  parsing `mcpServers` JSON per-key with skipped-names reporting;
  Export custom-only via DownloadsExporter with secrets redacted to
  "••••••"; Detail with real tool list + per-tool toggles, Restart/
  test, Logs (stdio stderr tail, last 1500 chars; remote honestly
  "no process logs"), health + last error, Edit/Duplicate/Delete
  with confirmation; BackHandler + explicit Back. Wired as
  `Dest.Mcp` → Hub, in the Development drawer group, SettingsHub row
  renamed "MCP Hub", `ToolsPanel` "Open MCP Hub" row. Old
  `ui/McpScreen.kt` deleted (only MainActivity referenced it).
- Honest limits: no EncryptedSharedPreferences dependency — MCP
  secrets live in servers.json + SharedPreferences, unencrypted like
  provider keys. Guest env is bounded (`env -i` + base + user
  Environment + per-server env; shared Environment vars are visible
  to all stdio servers by design). No separate per-call approval
  dialog for MCP tools (gating = toolset toggle + per-tool allow/deny
  + enabled switch). Transport-level serialization + per-server
  connect lock, not full call serialization. The Authority block asks
  before destructive/external actions; there is no per-tool hook on
  `McpTool.run`.

## 16. REA reverse engineering (ON by default)

- Bundle `app/src/main/assets/skills/reverse/reverse-engineer-anything/`
  (SKILL.md + 7 references + `LICENSE-REA.txt`, MIT, 155,594 bytes);
  the ~133 MB repo is NOT bundled, nor any desktop bridge/binary.
- `agent/ReaMode.kt`: trigger "reverse engineer"/"reverse-engineer"/
  "مهندسی معکوس"; magic-byte-first identification (ELF/APK/PE-.NET/
  JS/Electron-asar/ZIP/unknown); sandbox command sets per target
  (file/strings/readelf/objdump/nm; APK manifest + dex strings; JS
  imports/strings; asar via npx); `ensureBinutilsCommand` via apk;
  `tryDecompilersCommand` genuine apktool/jadx attempt with verbatim
  output; report skeleton behavior → strings/symbols → code
  structure → data + unknowns + desktop steps.
- `ui/ReaPanel.kt` in Coding: Identify / Run analysis / Install
  binutils / Try apktool-jadx / Export analysis package (ZIP via
  DownloadsExporter: metadata + evidence + report + README).
- `REA_MODE_ROUTING` in `Agent.kt`: every finding needs evidence +
  limitation/confidence; unverified = hypothesis; local-only, nothing
  uploaded. Ghidra headless (desktop Java + GBs of disk) and Hopper
  (macOS commercial) are NOT on-phone and never blurred: the UI says
  which step ran on-phone and which needs desktop.
- Per-target tool matrix: ELF → binutils (apk install if missing);
  APK → manifest/dex strings, apktool/jadx attempted, decompilation
  otherwise desktop; Electron/JS → asar unpack + JS analysis where
  tools exist; .NET → metadata/strings analysis, same honesty rule.

## 17. Copilot harness concepts + Foundry engine option

- Third engine `copilot` in the selector chips, Settings Engine picker,
  and ModelPicker note. `agent/CopilotHarness.kt` = REMOTE STUB (sends
  nothing): the Foundry Copilot-harness invocation surface — exact
  project-endpoint shape + Responses API payload for a harness agent
  — was not verified from official Microsoft/Foundry docs for direct
  app use, despite docs describing portal/azd/SDK/REST authoring and
  Responses API on a project endpoint. Selecting Copilot falls back
  to native, never a fake run.
- Settings fields `foundryEndpoint`/`foundryApiKey` (user's own BYOK,
  SharedPreferences unencrypted like other keys). Billing note on the
  entry: "Public preview — harness runtime free until 2026-11-30,
  consumption billing from 2026-12-01 (model inference charged
  separately)".
- Harness concepts adopted into the NATIVE engine for real:
  `agent/TaskRouter.kt` (classify CODE/DESIGN/RESEARCH/GENERAL;
  suggests only on coder/deepseek markers, else null — no invented
  routing; suggestion is a tap-to-use chip, user choice wins) +
  `taskRouting` setting default ON; `agent/RunCheckpoint.kt`
  (`$HERMES_HOME/runs/checkpoint.json`: session/turns/last user/last
  assistant/timestamp; resume fills the composer with a summary
  prompt, does NOT replay tool calls); `HarnessControls` in ChatScreen
  (run info row: engine · provider · model · thinking + expandable
  details: Spec-first, task routing, enabled toolset count, approval
  note; Copilot stub note when selected; routing chip; "Resume
  interrupted run" button only on empty session).
- Engine setting is label/selection only in chat (native agent always
  runs; Pi only via explicit button) — Copilot fallback is consistent
  with that pattern.

## 18. Anthropic Claude repos (BY DEFAULT where implementable)

1. prompt-eng-interactive-tutorial (NO license shown): NO chapters
   copied. `ui/PromptSchoolScreen.kt` + `Dest.Learn` ("Prompt school",
   drawer App group) — 9 lessons, Hermes' own wording/exercises
   (structure only; original linked:
   https://github.com/anthropics/prompt-eng-interactive-tutorial);
   "Practice in chat" fills the composer + opens chat.
2. claude-cookbooks (MIT, ~228 MB NOT bundled): `assets/skills/
   reference/cookbook-patterns/` (40,962 bytes: Hermes-written index
   SKILL.md + verbatim RAG README + research_subagent/lead/citations
   prompts + agents README + `LICENSE-COOKBOOKS.txt`). Notebooks NOT
   bundled — tool-use/vision/caching covered by Hermes-written summary
   guides pointing at original notebook paths. LIVE: sub-agents via
   DelegateTool, vision toolset, tool-use loop, RAG as local
   SkillStore/MemoryStore retrieval (no embedding vector store).
   REFERENCE-ONLY: prompt caching — no `cache_control` in LlmClient.
3. claude-quickstarts (MIT, ~5 MB): `assets/quickstarts/<21 slugs>/`
   (README.md verbatim 187,543 bytes + Hermes-written RUN.md each);
   `coding/QuickstartCatalog.kt`: 21 found = 9 top-level + 12
   managed-agents sub-apps, 17 creatable, 4 skipped —
   browser-use-demo + computer-use-demo (Docker), computer-toolset
   (VNC), managed-agents-self-hosted-sandboxes (sandbox infra). All
   need the user's own ANTHROPIC_API_KEY. IdePanel "Quickstart
   templates" section creates `/root/projects/<slug>` + sets it as
   the current project.
4. skills (NO license shown): NOT bundled wholesale. SkillsScreen
   "Import Anthropic skill" (raw URL / github blob URL /
   `owner/repo:name` shorthand → raw URL; OkHttp fetch; frontmatter
   name+description validation; writes `skills/<safe-name>/SKILL.md`).
   Limitation: only SKILL.md is fetched, no references/scripts/
   assets. Format documented in the Skills Hint.
5. claude-agent-sdk-python (MIT, ~2.4 MB): Python agent template in
   IdePanel (Hermes-written `agent.py` sample using the
   `claude_agent_sdk` query pattern + requirements.txt + README
   stating the sample is NOT verified against an installed SDK —
   adjust to the installed version's docs); "Install
   claude-agent-sdk (sandbox pip)" attempts a real pip install +
   import check.
6. claude-code (proprietary, NOT bundled/copied): Claude Code CLI
   entry in IdePanel — Install = sandbox `npm install -g
   @anthropic-ai/claude-code` attempt, Check = `claude --version`;
   needs the user's own Claude account (interactive login in the
   terminal), else "needs your Claude account" state.
7. claude-code-action (MIT): `@claude` workflow button in IdePanel
   writes a Hermes-written `.github/workflows/claude.yml` (using
   `anthropics/claude-code-action@v1`) into the project only — the
   user reviews/commits to their own repo; required GitHub secret
   ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN documented, never
   stored by Hermes, never pasted in chat; input names per the action
   README, re-check at commit time.
8. Opus prompt guide: Opus card in ModelPicker (shown when
   `settings.model` contains "opus"): Hermes' own summary of Opus
   prompting best practices + "Official guidance: docs.anthropic.com"
   text, nothing copied.

## 19. Team mode, MCP-15, components, ThinkRail, Film, Xerj, Token saver

### 19a. Team mode (Sno two-agent team + ECC engineering team, ON by default, ACTIVE)
- Settings `teamMode` default true + `quotaFallback` default true.
- `TEAM_MODE_ROUTING` (Agent.kt, injected when teamMode ON): build
  tasks via `delegate_task` wait=true — planner → researcher
  (research-first: docs/code before writing) → implementer → tester
  (real test runs; testing skill triggers them) → reviewer (fresh
  session brief = deliverable + original request + checklist ONLY,
  never builder history; prefer a `model_choice` whose provider
  differs from the builder's when the user has one configured, else a
  different model same provider — and say which) → security auditor
  (security skill triggers the pass). On errors the implementer fixes
  + the reviewer re-checks ONCE; only then is the final presented.
  Non-build chat stays single-agent. Honest limit: `delegate_task`
  offers only same_as_me/default_model/sub_model, NOT an arbitrary
  provider picker, and sub-agents cannot delegate further (depth ≥ 1
  blocked).
- Quota handoff (ChatViewModel.runTurn catch, max 3 hops): on a
  429/quota marker (`QuotaFallback.looksLikeQuota`: 429/quota/
  FreeUsageLimitError/rate limit/RESOURCE_EXHAUSTED/
  insufficient_quota), save a checkpoint, switch provider via
  `modelStore` (`QuotaFallback.nextProvider`: different baseUrl with
  a key, free first), retry the same turn once per hop, and post a
  takeover message naming the new provider. No fallback → post
  "quota reached, no fallback provider configured" and stop honestly.
- Nightly learning (approval-gated): `RulesStore` (`$HERMES_HOME/
  rules/`: proposed.json [{id, rule, reason}], approved.md
  (prompt-injected up to 4000 chars), rejected.json (never
  re-proposed); `briefText`, `approve(n)`, `reject(n)`,
  `NIGHTLY_PROMPT` drafts only, never activates). Seeded once in
  `HermesRuntime.startOptionalServices` (job "Nightly learning",
  `0 3 * * *`, deliver local, marker-guarded). Morning brief card in
  ChatScreen (Approve/Reject buttons per rule) + `/rules` command
  (list/approve/reject) in Commands.kt.
- ECC index skill `assets/skills/reference/ecc-index/` (MIT;
  Hermes-written index SKILL.md, 89 lines: 47 canonical skills under
  `.agents/skills/` with descriptions + `ecc:<name>` fetch paths +
  raw URLs + behaviour triggers; honesty note: the post cites 311
  skills/71 agents, the snapshot has 47 canonical skills, 1,068
  SKILL.md files repo-wide incl. client mirrors, 33 `.kiro/agents/`
  files; full texts fetched on demand, never bundled; Hermes' six
  team roles are Hermes' own wording, not copied agent files;
  `LICENSE-ECC.txt`). SkillsScreen resolves `ecc:<name>` →
  `raw.githubusercontent.com/affaan-m/ECC/main/.agents/skills/<name>/
  SKILL.md`. Skills that are used change behaviour via the team
  routing (security skill → security pass, testing skill → test runs).
- Project memory: IDE panel shows SPEC.md + learned rules
  ("Project memory"); lessons approved via nightly learning apply to
  the next project through the prompt-injected approved.md.

### 19b. MCP-15 + /reload-mcp
- `mcp/McpCatalog15.kt` (configuration entries only, no server
  software bundled; marker `mcp/.catalog15-v1`, never resurrects
  deletions; seeded from `McpManager` init). Context7 + Framelink
  already seeded elsewhere (not duplicated) count toward 15.
  VERIFIED commands: chrome-devtools (`npx
  chrome-devtools-mcp@latest`, needs desktop Chrome — phone uses the
  built-in Browser tools instead), playwright-mcp wire (`npx
  @playwright/mcp@latest`, phone equivalent = built-in Browser tools
  row enabled by default, wire entry off on-phone), serena (`uvx
  --from git+https://github.com/oraios/serena serena start-mcp-server
  --context ide-assistant --project /root/projects`, enabled +
  autoStart, no key needed), n8n-mcp (`npx n8n-mcp`, env
  `MCP_MODE=stdio` + `N8N_API_URL`/`N8N_API_KEY` placeholders, enabled
  + autoStart in docs mode, management tools off until an instance
  URL+key are set, never pointed at production workflows),
  toolbox-databases (`toolbox --stdio --tools-file tools.yaml`, needs
  the user's own database + tools.yaml + binary, off until
  configured, no shared database).
  UNVERIFIED/DISABLED (no command invented, connected=false):
  codebase-memory, github-mcp (needs the user's own GitHub token
  anyway; read-only github-readonly default covers read-only work),
  context-mode (license unclear, entry only), code-review-graph,
  xiaohongshu-mcp (needs manual setup; cookies NEVER extracted; only
  a server-documented user-entered key/cookie field with a risk
  warning, else off), blender-mcp (needs desktop Blender; bridge URL
  to fill; phone equivalent = blender-scripts skill), unity-mcp
  (needs desktop Unity; well-known CoplayDev/unity-mcp identified but
  bridge command unverified), apify-mcp (needs the user's own Apify
  token).
- `/reload-mcp` chat command (Commands.kt def + branch;
  `McpManager.reloadAll()`: close all connections, reconnect each
  enabled server sequentially, report per server: connected with N
  tools / error with real error text / needs sign-in / disabled).
  Works from the chat composer slash handling (Reply → Info item).

### 19c. Components flow (ON by default)
- `ui/DesignWorkspace.kt` (NEW), shown inside the IDE panel:
  "Browse component library" sets `rt.browser.url =
  https://uberdesigner.ai` and navigates to the existing Browser
  screen (user's own free/Pro account; Hermes never stores
  uberdesigner credentials, never bypasses the 3/day free limit or
  the Pro paywall — those limits are the service's own, and the UI
  says so). "Paste a component prompt" fills the chat composer with
  the pasted text (the user's own input, not a copied library); the
  agent treats it as the style instruction through the existing
  design pipeline (Huashu flow, React Bits web-only, GSAP, UI UX Pro
  Max palettes/fonts). Hermes' OWN starter prompts (Hermes' wording,
  not copied from uberdesigner) for button, card, navbar, hero,
  pricing table, modal, form, dashboard sidebar — tap fills the
  composer. uberdesigner's library/prompts are NOT copied, scraped,
  or bundled anywhere in this build.

### 19d. ThinkRail concepts in the IDE (ON by default)
- Embedded Pi session view in `IdePanel` ("Run Pi here": Pi harness
  with the current project as cwd, same SkillStore skills and MCP Hub
  servers, output/checkpoints shown in the panel). No separate skill
  set; no desktop `curl install.sh | bash`; desktop IDE not claimed
  to run on Android.
- Living SPEC.md: `agent/SpecFile.kt` — created from the bundled Spec
  Kit spec template + user intent (`rt.app.assets`
  `coding/spec-kit/spec-template.md`); agent reads it first every
  session and updates it after each task (routing added to the
  spec-first block in Agent.kt); "View SPEC.md" + "Create SPEC.md"
  actions in the panel; lives in the project folder, travels with ZIP
  export and git.
- Worktree per task: `agent/Worktrees.kt` — `git worktree list
  --porcelain` parsed; create under `/root/projects/.worktrees/
  <task>` + branch `task/<task>`; panel lists worktrees with branch/
  status; "Merge into main working copy" (merge --no-ff, conflict
  stop + honest conflict report + merge --abort, never
  auto-resolved); "Discard worktree" (two-tap confirmation, branch
  deleted too).
- One-click PR + CI: "Create pull request" pushes the branch (user's
  own GitHub token from Settings, user's own repo) and opens a PR via
  the GitHub API; "CI status" shows the latest workflow run for the
  branch via the GitHub API. No remote or no token → the buttons say
  exactly that instead of faking a PR.
- About note in the panel: ThinkRail has no account of its own and
  neither does this — everything runs on the user's own provider
  credentials already in Hermes.

### 19e. Lemo-Opuscar Film mode (ON by default)
- Bundle `app/src/main/assets/skills/creative/lemo-film/`:
  Hermes-written SKILL.md + `styles-index.md` (the repo's generated
  style index, 8,426 bytes, verbatim) + `LICENSE-LEMO.txt` (MIT,
  Copyright 2026 LemoLab, verbatim; style content CC BY 4.0 per the
  repo's badge). The 152 MB repo and its demo films are NOT bundled;
  desktop `claude plugin marketplace add/install` NOT used.
- Style count note: the repo lists 66 ready styles — the circulating
  post said 59; the repo's own count wins (verified 2026-10-30:
  styles/ has 67 entries minus the `_template` folder = 66; the
  README itself says "66 ready to use"). The panel parses the bundled
  index (display name + folder + demo) with a fallback list of the 66
  verified folders.
- `agent/FilmRender.kt`: style library (pick a style OR describe
  your own; custom styles saved to Hermes' own MY-STYLES file
  `$HERMES_HOME/my-styles.md`); full style prompts fetched on demand
  from raw.githubusercontent.com into the project (never bundled);
  pipeline story + style → scene-by-scene HTML/GSAP code (Huashu
  flow, GSAP skills, React Bits where fitting) → render. Rendering is
  ATTEMPTED in the Alpine sandbox: `command -v` check for
  Chromium+ffmpeg, `apk add --no-cache chromium ffmpeg` if missing,
  headless screenshot per scene → ffmpeg mux (+ the user's audio
  file when given) → film.mp4. Music: the sandbox music tooling the
  repo documents (requirements-music) or the user's own audio file;
  narration: on-device TTS (requirements-voice) or the user's own
  recording — never a fake music tool. If any render step genuinely
  cannot run, the complete coded film (HTML scenes + storyboard +
  narration script + music plan) is still delivered via the existing
  ZIP export, stating exactly which step needs desktop. Entry: design
  workspace panel + chat trigger "make a film" (routing in Agent.kt).
- Verification: code-level only. No render has run here (no sandbox
  on this VM runs the Alpine toolchain); the render path reports its
  own success/failure honestly at runtime.

### 19f. Xerj local search (ON by default, ACTIVE)
- `agent/XerjSetup.kt`: the desktop `curl https://xerj.org/get | sh`
  installer is NOT used. The Alpine sandbox installs Xerj from the
  verified musl release tarball: v1.0.0-rc.100,
  `xerj-1.0.0-rc.100-aarch64-unknown-linux-musl.tar.gz`
  (30,887,623 bytes), downloaded from GitHub releases and
  checksum-verified against the release .sha256 at install time (a
  mismatch aborts). CLI surface verified from the repo's own README:
  `xerj autoindex <folder>`, `xerj search "query"` (ranked passages,
  file:line), `xerj mcp`, `--data-dir`. Data dir `/root/.xerj` lives
  OUTSIDE `/root/projects`, so the index never indexes its own
  storage (the repo's own warning). Local-only: no vector database,
  no external service, nothing uploaded.
- Sandbox setup (HermesRuntime.startOptionalServices, default ON,
  best-effort): install the binary, then index `/root/projects`
  (code, docs, logs, PDFs). Any failure leaves the BM25 fallback live
  and is logged, not hidden.
- MCP Hub entry `xerj` (marker `mcp/.xerj-v1`, never resurrects a
  user deletion): stdio `xerj mcp`, enabled=true, builtin=true,
  autoStart=true. Until the binary is installed it honestly reports
  its connect error.
- Agent search wiring: `SearchFilesTool` prefers Xerj for plain-word
  queries when the binary is installed (returns Xerj's ranked
  file:line output with source "xerj"); otherwise it runs the UPGRADED
  BM25 fallback — two-pass BM25 (k1=1.2, b=0.75) over matched files,
  line-numbered snippets only, never whole files — and the agent
  routing tells the model to prefer Xerj MCP snippets when that
  server is connected. "Search open-source references": IdePanel
  action indexes a user-cloned repo dir the same way.
- Verification: release asset + SHA file existence + CLI surface
  verified from GitHub (2026-10-30). The binary has NOT run here (no
  Alpine sandbox on this VM); the live path on a fresh install is
  therefore the BM25 fallback until the sandbox install succeeds, and
  the UI reports exactly which path is live.

### 19g. Headroom token compression ("Token saver", ON by default, ACTIVE)
- `agent/TokenSaver.kt` (Hermes' own code following Headroom's
  approach; Headroom is Apache-2.0, none of its code is bundled):
  before any prompt is sent, tool results are compressed for MODEL
  CONTEXT ONLY — JSON minification (string-aware), comment stripping
  for file/search results (string-aware), repeated-line dedup (3+
  identical lines → one + marker), bounded truncation with an
  explicit "[truncated, N lines omitted]" marker. Wired in Agent.kt's
  tool-result loop (loop detection still runs on the uncompressed
  text) and gated by `settings.tokenSaver` (default ON, Settings →
  Chat toggle).
- Safety (enforced in code, not just documented): compression NEVER
  touches files the agent writes; error lines (error/exception/
  failed/panic/traceback/…) are NEVER dropped; the last 100 lines of
  a log are NEVER dropped; every cut is marked so the model knows
  content was cut — nothing is silently removed.
- Per-run readout: ChatViewModel posts after each finished agent run
  "Token saver: ~X → ~Y tokens (saved ~Z, P%)" (estimates = chars/4;
  only when savings > 0). The compressed result is also what the
  user sees in the tool-result display, so "what was compressed" is
  visible in chat itself.
- Headroom proxy option: `XerjSetup.headroomProxyAttempt` (kept next
  to the sandbox setup code) attempts `pip install headroom-ai`
  (base package, not [all] extras) + import check in the sandbox.
  Status: install-steps-only — the Hermes-native pipeline is the live
  path; the proxy is never claimed to run unless that check reports
  success.
- MEASURED savings (Hermes' own pipeline, NOT Headroom's claims):
  `scripts/tests/test_token_saver.py` mirrors the Kotlin rules
  exactly (the VM has no kotlinc, so the Kotlin file cannot be
  executed here — the test file says so) and measures on three
  samples: JSON blob 3570 → 1393 tokens (60% saved, value-identical
  after parse), code file (TokenSaver.kt itself) 1483 → 1049 tokens
  (29%), log with repeats + errors + tail 8629 → 4660 tokens (45%),
  with assertions that error lines, the log tail, and truncation
  markers survive. Headroom's published figures (20% coding, 60–95%
  JSON) are THEIR claims, attributed to Headroom, not Hermes results.

### 19h. Default-state audit (standing rule: everything ON by default, ACTIVE)
Audited 2026-10-30 against the code; every item below is default-ON
with a Settings toggle to turn it off (fixing anything previously
left opt-in or install-only):
- 9Router: `routerAutoStart` default true + `autoStartIfNeeded()`
  called at startup (HermesRuntime) — fresh installs get the router
  without hunting in Settings. Detail page remains Settings → Local
  9Router.
- Spec Kit: `specFirst` default true; composer chip + Coding checklist.
- Team mode: `teamMode` default true (chat toggle + routing); quota
  fallback `quotaFallback` default true.
- Suggested replies: `suggestedReplies` default true.
- Design skills: bundled skills install on first run and SkillStore
  defaults every skill to enabled (enabled=true unless explicitly
  disabled) — Huashu, UI UX Pro Max, Impeccable, GSAP, HyperFrames
  docs, Blender scripts, lemo-film, cookbook-patterns, ecc-index are
  all live in the agent prompt when present.
- MCP default servers: entries default enabled=true / autoStart=true
  when absent (McpConfig.fromJson); servers needing a key stay
  honestly "needs key" (framelink-figma enabled=false until a key;
  xerj enabled+autoStart but errors until its binary is installed).
- REA / Film / IDE modes: present by default (ReaPanel on the Coding
  screen, DesignWorkspacePanel with Film inside the IDE panel, IDE
  panel in Coding) — not hidden in submenus.
- Copilot engine: listed by default in the engine selector with its
  preview/billing note (stub until the user's own Foundry account is
  configured).
- Xerj: install + project indexing at startup (default ON,
  best-effort); MCP entry enabled by default.
- Token saver: `tokenSaver` default true, active in every agent run.
- Components flow: present by default in the design workspace.

## FINAL VERSION

### What is in this version
Everything in §§1–19 above, in one working tree, uncommitted, at
`/home/hatch/workspace/hermes-mobile-audit` (HEAD still `06eb748`,
v2.6.3; 25 tracked files modified — 1204 insertions / 264 deletions —
plus 1 deleted file, new Kotlin files, new skill bundles, quickstarts,
and this report; full list under "Files changed" below). In short:
9Router 0.5.112 + wreq-js (bundle + on-device ensure) as a default
top-level section with auto-start; the full provider catalog with auto
backend URLs, login/key actions, and automatic model fetch (Nous,
OpenRouter PKCE, 9Router/OpenCode free, Z.ai key-only stub, DeepSeek,
Gemini, Mistral, Groq, OpenAI, Anthropic, xAI, Ollama, LM Studio,
Custom); Thinking + Engine selectors; regrouped SettingsHub with
profile row and working Back buttons; Pi harness + DeepSeek thinking
mapping in both engines; Spec Kit default ON; "Tools on this phone"
panel; Z.ai login stub; first-page permissions batch (6 declared
runtime permissions, no SMS); Android IDE by default; Playwright-style
browser tools on Hermes' own BrowserManager/WebView; suggested replies
default ON; Huashu + 7 design repos + lemo-film (66 styles) + REA +
Anthropic repos (Prompt school, cookbooks, quickstarts, skill import,
Python SDK template, Claude Code CLI entry, @claude workflow, Opus
card); Copilot engine (stub) + real native-engine harness concepts
(task routing, checkpoints/resume, run controls); MCP Hub with
registry/custom install flow + 15-server catalog + /reload-mcp; Team
mode (builder → reviewer + specialists, quota handoff, nightly
learning with approval); ECC index skill; Components flow; ThinkRail
concepts (Pi in IDE, living SPEC.md, worktree per task, PR + CI);
Xerj local search (binary install + indexing + MCP entry + BM25
fallback); Token saver compression (default ON, per-run readout).
Every feature is ON by default and ACTIVE (§19h audit), each with a
Settings toggle to turn it off.

### What was verified
- Python unit tests: `python3 -m unittest discover -s scripts/tests`
  — 15 tests, OK (0.307s, re-run at final checkup, 2026-10-30),
  including the 3 new token-saver tests with measured savings
  (JSON 60%, code 29%, log 45%) and safety assertions.
- npm registry (2026-10-30): 9router 0.5.112 exists; wreq-js exists.
- Official docs checked by fetch/search: OpenRouter OAuth PKCE
  (authorize + exchange URLs, headless mode, error codes); Z.ai
  docs (API-key creation only — no third-party OAuth endpoint found,
  hence the stub); ZCode docs (in-client sign-in only, not reusable);
  Xerj release v1.0.0-rc.100 (musl aarch64 tarball 30,887,623 bytes +
  .sha256) and CLI surface from its README; Lemo styles/ directory
  (67 entries − _template = 66) and generated styles index; ECC
  snapshot counts (47 canonical skills, 1,068 SKILL.md files
  repo-wide, 33 .kiro/agents/ files).
- Android SDK on this VM (final retry, 2026-10-30): Temurin JDK 17
  verified (17.0.21); platform android-35 installed from the kept
  `/tmp/platform-35.zip` (valid zip, 11,162 entries, android.jar
  present); build-tools 35.0.0 downloaded via curl with resume from
  dl.google.com using the EXACT repository filename
  (`build-tools_r35_linux.zip`, 61,958,799 bytes) and SHA-1-verified
  against the repository index
  (2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc — match); Gradle 8.10
  distribution downloaded via curl (130 MB, valid zip). So the SDK
  itself is now complete on this VM — the remaining blocker is the
  Gradle daemon handshake (below), not the SDK.
- Code-level verification of every claim in this report against the
  final diff: router auto-start wiring (HermesRuntime →
  startOptionalServices → autoStartIfNeeded), Dest.Router + goBack(),
  specFirst/teamMode/quotaFallback/suggestedReplies/tokenSaver
  defaults true, Thinking/Engine chips, SettingsHub groups, DeepSeek
  thinking mapping, catalog panel wiring in both Providers places,
  IDE panel wiring, `fill` action, RulesStore fields + approve/reject
  signatures, CronJob args, quota-retry catch, McpServer fields,
  Sandbox.exec defaults, Worktrees auth-header quoting (fixed during
  this checkup: token no longer single-quoted inside a double-quoted
  header). No real secret values in the diff (only placeholder key
  hints such as "sk-or-…").
- Deliverables in this directory: `hermes-mobile-final.patch` (full
  diff vs main, including new files) and
  `hermes-mobile-changed-files.zip` (only the changed/new app files,
  not the whole repo, not AUDIT_NOTE.md). Patch applicability was
  checked with `git apply --check --reverse` against this tree.

### What is NOT verified (do not claim otherwise)
- Android build: NOT built on this VM. The final retry (2026-10-30)
  got further than before — SDK platform + build-tools + Gradle are
  all installed — but every Gradle invocation fails at the daemon
  handshake with "Could not receive a message from the daemon"
  (4 attempts: plain, --info, background, proxy-env-unset). The
  daemon itself starts and listens on 127.0.0.1 (verified in its own
  log), loopback TCP works from Python in the same environment, yet
  the Gradle client never completes the handshake and then stops the
  daemon. No APK, no green Gradle run, no testDebugUnitTest. This is
  an environment failure, not a code verdict: NOTHING about the
  Kotlin changes has been compiled. Compose rendering, Pi runs,
  DeepSeek thinking retries, Router screen, Tools panel, IDE panel,
  catalog panel, OpenRouter PKCE exchange, provider fetches with real
  keys, MCP-15 connections, Team/Quota/Rules flows, suggested-replies
  chips, Huashu loading/download, Hub screens, Components/Film/Xerj/
  Token-saver on-device behaviour are all unverified and must be
  checked by the project's CI Build APK + on-device testing.
- Z.ai login is a STUB: it provisions nothing and sends no credential
  anywhere. A real Z.ai login needs a verified official endpoint that
  does not exist in the published docs today.
- OpenRouter PKCE: authorize + exchange URLs and the headless mode are
  verified from official docs, but the exchange response field is
  parsed tolerantly (see §11) and the whole flow has never run
  end-to-end here.
- Copilot/Foundry: remote call is a STUB (sends nothing); the
  project-endpoint + Responses payload shape for a harness agent was
  not verified from official docs for direct app use.
- Xerj binary, HyperFrames/Chromium+ffmpeg rendering, apktool/jadx,
  binutils installs, and the headroom-ai pip package have NOT run
  here (no Alpine sandbox on this VM); each reports its own live
  state honestly at runtime, with the documented fallback live.
- Token-saver percentages are measured on a Python mirror of the
  Kotlin rules (no kotlinc on this VM); on-device numbers come from
  the per-run chat readout.

### Conflicts between recorded user preferences and this implementation (needs a parent/user decision)
- SMS in the permissions batch: a MEMORY preference recorded
  2026-10-30 says the first-page button should grant "all permissions
  the app wants at the outset, including SMS and browser". This build
  does NOT do that, on purpose: Hermes neither declares nor uses any
  SMS permission (checked in AndroidManifest.xml), and the assigned
  task explicitly forbade adding SMS unless declared and used.
  Requesting an undeclared permission is a no-op on Android, and
  declaring SMS with no SMS feature would mislead the user and risk
  store-policy trouble. "Browser" is likewise not an Android
  permission (INTERNET is install-granted). If SMS features are
  genuinely wanted, that is a separate feature decision (real SMS tool
  + manifest declaration + justification), not a button change.
- Z.ai login "like Nous": the same MEMORY preference asks for a
  proper, valid, self-connecting Z.ai login via Chrome. That cannot be
  built from the published docs today (see §10a/§11): no official
  third-party OAuth endpoint exists to point Chrome at. The stub keeps
  the Chrome Custom Tab + return-link pattern ready for the day an
  endpoint is verified or supplied.
- Ghostide / Playwright "features and code": the MEMORY preference
  mentions drawing on those repos "for features and code". This build
  implements their FEATURES/concepts only, in Hermes' own code, and
  copies no code — per the assigned task (Ghostide is GPL-3.0; copying
  it into Hermes would be a licensing problem) and per the Playwright
  scope (MCP tool concepts on Hermes' own browser host, Playwright
  itself not bundled).

### Exact next step to get an installable APK
Do NOT expect an APK from this VM. After review, push this tree (or
apply `hermes-mobile-final.patch` to a clean checkout of main) and let
the project's normal GitHub Actions "Build APK" workflow build it —
that workflow is the project's verified build path and its last run
was green. Then install the APK on a device and check, in order:
Router screen + wreq-js status, catalog provider select + auto-fetch
with a real key, OpenRouter PKCE login, Pi install/run, DeepSeek
thinking retry, Spec-first behaviour, IDE skeleton + Build APK
SDK-absent state, Browser tools row, Hub screens, suggested-replies
chips, Huashu skill loading + download, MCP-15 connections, Team
mode (builder → reviewer + quota handoff with a second provider),
nightly-learning brief + /rules approve/reject, Components browse +
paste + starters, ThinkRail (Pi in IDE, SPEC.md view, worktree
create/merge/discard, PR/CI missing-state messages), Film (style
pick + coded film + render attempt result), Xerj status/install/
index + BM25 fallback, and the Token saver readout after a run.
Nothing here is released or tagged.

## Files changed

- `scripts/bundle_prerequisites.sh` — 9router 0.5.112 + wreq-js install.
- `app/src/main/java/com/ersaz/hermesmobile/router/NineRouter.kt` —
  wreq-js status/ensure/check, called from installAndSetup.
- `app/src/main/java/com/ersaz/hermesmobile/ui/RouterScreen.kt` (NEW) —
  top-level 9Router section.
- `app/src/main/java/com/ersaz/hermesmobile/MainActivity.kt` —
  Dest.Router, drawer "AI router" group, TopAppBar Back action.
- `app/src/main/java/com/ersaz/hermesmobile/llm/LlmClient.kt` — zai
  preset; DeepSeek thinking mapping + one retry without thinking.
- `app/src/main/java/com/ersaz/hermesmobile/ui/ModelPicker.kt` —
  Thinking chips, Engine chips, AddProviderDialog (presets + fetch).
- `app/src/main/java/com/ersaz/hermesmobile/ui/SettingsHub.kt` —
  profile row, regrouped categories, new Engine category.
- `app/src/main/java/com/ersaz/hermesmobile/ui/SettingsScreen.kt` —
  Engine detail (Picker, Pi check/install, Spec-first toggle).
- `app/src/main/java/com/ersaz/hermesmobile/core/Settings.kt` —
  `engine`, `specFirst`.
- `app/src/main/java/com/ersaz/hermesmobile/agent/PiHarness.kt` (NEW) —
  Pi install/status/run + thinking mappings.
- `app/src/main/java/com/ersaz/hermesmobile/agent/Agent.kt` —
  Spec-first prompt block.
- `app/src/main/java/com/ersaz/hermesmobile/ui/ChatScreen.kt` —
  composer "Spec first" chip + engine label.
- `app/src/main/java/com/ersaz/hermesmobile/ui/CodingScreen.kt` —
  project-name start (create-or-reuse); ToolsPanel inserted.
- `app/src/main/java/com/ersaz/hermesmobile/ui/ToolsPanel.kt` (NEW) —
  "Tools on this phone" panel on the Coding screen (see §9).
- `app/src/main/java/com/ersaz/hermesmobile/ui/ZaiLoginCard.kt` (NEW) —
  Z.ai login stub card (see §10a).
- `app/src/main/java/com/ersaz/hermesmobile/ui/GrantAllPermissions.kt` (NEW) —
  first-page batch permission button (see §10b).
- `app/src/main/java/com/ersaz/hermesmobile/ui/IdePanel.kt` (NEW) —
  Android IDE panel in Coding (see §10c).
- `app/src/main/java/com/ersaz/hermesmobile/browser/BrowserTool.kt` —
  `fill` action added (see §10d).
- `app/src/main/java/com/ersaz/hermesmobile/ui/BrowserScreen.kt` —
  Snapshot / Wait 2s / Screenshot tools row (see §10d).
- `app/src/main/java/com/ersaz/hermesmobile/ui/OnboardingScreen.kt` —
  Z.ai card + Permissions section added.
- `app/src/main/java/com/ersaz/hermesmobile/ui/SettingsScreen.kt` —
  Z.ai card in the Models section when provider == "zai".
- `app/src/main/java/com/ersaz/hermesmobile/ui/CodingPanels.kt` —
  web preview, ZIP export, Pi harness section.
- `app/src/main/java/com/ersaz/hermesmobile/browser/BrowserManager.kt` —
  `previewHtml()` for the existing Browser screen.
- `AUDIT_NOTE.md` (pre-existing local audit note, not part of the app).
- NEW since §§1–11: `agent/QuotaFallback.kt`, `agent/RulesStore.kt`,
  `agent/RunCheckpoint.kt`, `agent/TaskRouter.kt`,
  `agent/CopilotHarness.kt`, `agent/ReaMode.kt`, `agent/SpecFile.kt`,
  `agent/Worktrees.kt`, `agent/FilmRender.kt`, `agent/XerjSetup.kt`,
  `agent/TokenSaver.kt`, `mcp/McpCatalog15.kt`,
  `llm/ProviderCatalog.kt`, `auth/OpenRouterAuth.kt`,
  `coding/QuickstartCatalog.kt`, `ui/SuggestedReplies.kt`,
  `ui/McpHubScreen.kt`, `ui/ProviderCatalogPanel.kt`,
  `ui/PromptSchoolScreen.kt`, `ui/HuashuDownload.kt`,
  `ui/ReaPanel.kt`, `ui/DesignWorkspace.kt`,
  `scripts/tests/test_token_saver.py`.
- NEW bundles: `assets/skills/creative/lemo-film/` (SKILL.md +
  styles-index.md + LICENSE-LEMO.txt), `assets/skills/reference/`
  (ecc-index, cookbook-patterns), `assets/skills/reverse/`,
  `assets/skills/creative/` (huashu-design, ui-ux-pro-max,
  impeccable, gsap-*, hyperframes, blender-scripts),
  `assets/quickstarts/` (21 slugs).
- MODIFIED since §§1–11: `HermesRuntime.kt` (nightly-learning seed,
  Xerj setup), `agent/Agent.kt` (team/design/film/search/token-saver
  routing, spec-first SPEC.md rule, tool-result compression),
  `core/Settings.kt` (teamMode, quotaFallback, suggestedReplies,
  tokenSaver), `mcp/McpManager.kt` (xerj seed, catalog-15 seed),
  `mcp/McpConfig.kt`, `mcp/McpTransport.kt`,
  `agent/tools/FileTools.kt` (Xerj preference + BM25 ranking),
  `ui/ChatScreen.kt` (team toggle, morning-brief card, harness
  controls), `ui/ChatViewModel.kt` (quota retry, token-saver
  readout), `ui/IdePanel.kt` (ThinkRail + Xerj + design workspace
  sections), `ui/Screens.kt` (ecc: shorthand, SectionTitle/Hub
  wiring), `ui/SettingsScreen.kt` (token-saver toggle),
  `commands/Commands.kt` (/reload-mcp, /rules).
- DELETED: `ui/McpScreen.kt` (replaced by McpHubScreen; only
  MainActivity referenced it).

## Verification — what was actually run

- npm registry check (2026-10-30): 9router latest = 0.5.112 (pinned
  version exists); wreq-js exists on npm. Pi docs check: CLI package
  `@earendil-works/pi-coding-agent`, `pi --print`, `--thinking`
  off/minimal/low/medium/high/xhigh, `npm install -g --ignore-scripts`.
  z.ai docs check: API-key flow + Coding Plan endpoint separation are
  documented; no verified third-party account-login protocol → login
  NOT implemented (see §3).
- Android SDK setup on this VM: Temurin JDK 17 installed and verified
  (`java -version` → 17.0.21). Android cmdline tools downloaded, but
  `sdkmanager` could not fetch the remote repository in this VM (Java
  proxy-tunnelling failure, repeated with explicit proxy settings), so
  `platforms;android-35` / `build-tools;35.0.0` could not be installed
  here. Direct package downloads via curl were also slow/incomplete
  at the time of writing.
- Gradle: NOT run — no Android SDK platform/build-tools on this VM, so
  `./gradlew testDebugUnitTest` / `assembleDebug` could NOT be verified
  here. No green build is claimed.
- Python unit tests: `python3 -m unittest discover -s scripts/tests`
  — run 2026-10-30: **12 tests, OK** (0.327s). This covers the repo's
  Python scripts only, not the Kotlin app.
- Tools-on-phone audit (§9): code-level only — every toolset/tool was
  traced to its backing class in ToolRegistry and its implementation
  file; no tool was run on a device, and the new ToolsPanel's Compose
  rendering is likewise unverified until a CI/on-device build.
- §10 verification (2026-10-30): z.ai endpoints checked against
  official docs.z.ai + zcode.z.ai search results (see §10a) — no
  third-party OAuth endpoint found, hence the stub; AndroidManifest
  permission list read directly (6 runtime permissions in the batch,
  no SMS declared/used); IDE, permission button, Z.ai card, and
  Browser tools row are code-level only — Compose rendering, logcat
  output, sandbox git/gradle runs, and snapshot/screenshot buttons are
  NOT verified on a device or in a Gradle build. Python unit tests
  re-run after §10: 12 tests, OK.
- Code review only for the Kotlin changes (read every file changed;
  checked imports, signatures, and the existing flows they reuse).
  Compose runtime behaviour, Pi runs, DeepSeek thinking retries, and
  the Router screen on a real device are NOT verified and must be
  checked by the project's normal CI build (GitHub Actions Build APK)
  and on-device testing before any release.
- §§12–19 verification (2026-10-30): Python tests re-run after every
  new scope — final run 15 tests OK. Token-saver measurement script
  added (see §19g). Xerj release + CLI verified via GitHub API/README
  fetch; Lemo styles verified via GitHub API directory listing +
  generated index fetch; ECC counts verified via repo snapshot notes.
  Final checkup re-read the complete diff (25 modified tracked files
  + 1 deletion + all new files) and fixed one real bug found in it
  (Worktrees GitHub auth header quoting). The Android build retry
  with a different download approach (curl with resume from
  dl.google.com, exact repository filenames from repository2-1.xml,
  SHA-1 verification) installed platform android-35, build-tools
  35.0.0, and Gradle 8.10 on this VM, but Gradle still fails at the
  daemon handshake ("Could not receive a message from the daemon",
  daemon starts + listens, loopback TCP proven working, proxy-env
  unset retry identical) — recorded exactly, not faked.

## Honest limits (do not over-claim)

- OpenCode free-tier daily quota is upstream; wreq-js does not remove it.
- Providers need the user's own API key/account; free models listed are
  only the ones that key can actually list.
- Pi harness needs Pi's own provider configuration in the sandbox;
  DeepSeek+Pi and Pi+thinking levels are offered, not guaranteed, and
  Hermes' per-tool approval does not intercept Pi-internal tool calls.
- Provider keys live in app-private SharedPreferences, unencrypted at
  rest, like all existing provider keys in this app.
- Local web preview renders a single index.html document; linked local
  assets are not served (no new backend was added).
- Tools on phone (§9): all phone equivalents are code-level verified
  only — no tool was executed on a device here. Desktop GUI automation
  and image/video generation were deliberately NOT added (the former
  cannot run on Android, the latter has no verified provider endpoint
  in this app); the Shizuku phone toolset is the Android-UI equivalent,
  not a desktop-GUI one. Cron jobs run while the app/service is alive;
  behaviour across a reboot was not verified here.
- §10 limits: Z.ai automatic login is a STUB — it provisions nothing
  and sends no credential anywhere, because no official third-party
  OAuth endpoint for Hermes exists in the published docs; the working
  Z.ai path is the user's own API key via the preset. The permissions
  button cannot grant special settings access (All-files, install
  apps, battery exemption) — those need their own Settings screens.
  The IDE's Build APK runs only when a real SDK + gradlew are present
  and otherwise honestly reports the missing piece; IDE template
  plugin versions may need adjusting to the device's SDK. Logcat shows
  only this app's own lines (Android restriction). No separate
  submit-gate exists for agent browser actions — agent browser use is
  governed by the tool loop + the "browser" toolset toggle, and user
  taps in the Browser screen are the user's own approval. Playwright
  itself is not bundled and no Playwright compatibility beyond tool
  names is claimed.
- Nothing in this report was pushed, released, or tagged. A release
  still needs the CI Build APK run and on-device checks.
- §§12–19 limits: suggested replies are keyword rules, not model
  judgments, and never auto-send. MCP-15 entries are configuration
  only — a server is "connected" only after a real init/test; most
  catalog-15 entries are unverified/disabled until their key/binary/
  bridge exists (see §19b table). Team reviewer cross-provider
  routing is bounded by delegate_task's model_choice options
  (same_as_me/default_model/sub_model) — a truly arbitrary provider
  pick is not available, and sub-agents cannot delegate further.
  Nightly rules are drafts only until the user approves them.
  Components: uberdesigner's library/prompts are never copied,
  scraped, or bundled; its free/Pro limits are that service's own.
  ThinkRail: desktop IDE does not run on Android; PR/CI need the
  user's own token + remote or they say so. Film: 66 styles (not 59);
  rendering is attempted, not guaranteed — the coded package is the
  guaranteed deliverable. Xerj: binary unproven on this VM; BM25
  fallback is the live path until the sandbox install succeeds.
  Token saver: percentages are estimates (chars/4) and mirror-measured
  here, not on-device Kotlin measurements; the proxy option is
  install-steps-only. Copilot: stub, sends nothing, until a verified
  Foundry call shape exists and the user's own endpoint/key are set.
