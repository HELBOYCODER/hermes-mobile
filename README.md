# Hermes Mobile — Hermes Agent for Android

A native Android port of [Hermes Agent](https://hermes-agent.nousresearch.com/) (Nous Research, MIT):
the self-improving agent with persistent memory, skills, cron, subagents, a
**Bot Mode** roster of named agents and a Telegram/Discord/Slack **messaging gateway**, plus a built-in **Linux sandbox terminal**. You don't need Termux or root: Alpine Linux runs
inside the app through PRoot, the same approach [OpenMinis](https://github.com/OpenMinis/openminis) uses.

Not affiliated with Nous Research. Open source, no Play Store, no analytics SDKs.

## Features

| Hermes feature | On Android |
|---|---|
| Agent loop with tool calling, interrupts, `/steer`, max-iterations summary, auto context compression | ✅ |
| **Sandbox terminal**: Alpine Linux (aarch64) on PRoot, real PTY, tabs, extra-keys row, `apk add …` | ✅ |
| `hermes` CLI **inside** the sandbox (`hermes`, `hermes "prompt"`, `hermes /status`), backed by the app's agent | ✅ |
| Tools: `terminal`, `process`, `read_file`, `write_file`, `patch`, `search_files`, `web_search`, `web_extract`, `memory`, `skills_list`, `skill_view`, `skill_manage`, `todo`, `session_search`, `cronjob`, `delegate_task`, `send_message`, `clarify`, `text_to_speech`, `vision_analyze` + `android_device` (clipboard, notifications, open URL, battery) | ✅ |
| Toolsets you can enable or disable, dangerous-command approvals (`/approve`, `/deny`, `/yolo`) | ✅ |
| Persistent memory: `MEMORY.md` / `USER.md` (§-delimited, char budgets, frozen snapshot), `SOUL.md` persona | ✅ |
| Skills: `~/.hermes/skills/<cat>/<name>/SKILL.md` (agentskills.io format), `/<skill>` invocation, 19 bundled skills | ✅ |
| Sessions: persisted, searchable (`session_search`), resumable, auto-titled, exportable | ✅ |
| Cron scheduler: `30m`, `every 2h`, `0 9 * * *`, ISO time; delivered to a notification or any chat | ✅ |
| **Bot Mode** (port of [Hermes Bot Mode](https://github.com/NousResearch/Hermes-Bot-Mode)): a roster of named bots, each a Hermes profile with its own chat, avatar, SOUL, memory, model pin, toolsets and routines. Groups and group chats, bot-to-bot messaging, `@mention` hand-offs, Active-now strip, duplicate/delete | ✅ |
| **Messaging gateway**: Telegram, Discord, Slack (Socket Mode). Per-chat sessions, DM pairing codes, allowlists, home channels (`/sethome`), tool-progress messages, file and voice delivery | ✅ |
| **MCP servers**: paste a URL, `npx …` command or Claude-Desktop JSON — Hermes connects, detects a login requirement (401), discovers the OAuth server, registers itself and opens the sign-in page, then exposes the tools as `mcp__server__tool`. Streamable HTTP, legacy SSE and sandboxed stdio (auto-installs node/uv). `/mcp` command | ✅ |
| **Sub-agents**: a roster the main model delegates to on its own (3 run at once, extras queue), background results arrive as new messages, `status`/`steer`/`cancel`/`resume`, per-agent model pin or Auto (`same_as_me` / `default_model` / `sub_model`) | ✅ |
| **Browser automation**: real WebView tabs the agent drives by numbered snapshots (click/type/scroll/screenshot/cookies/JS), shown on screen with a login/CAPTCHA hand-over | ✅ |
| Phone tools: calendar, alarms/timers, contacts, location, dialer, app launcher, share file, flashlight, volume, weather | ✅ |
| Voice input (dictation) and read-aloud, queue-while-busy mode, tool-loop detector, markdown images/tables/links, file browser, usage stats, env vars, apk mirrors, backup/restore, in-app logs, text size | ✅ |
| **ThinkingOrbs**: animated, mode-aware thinking indicator (thinking / tool / writing / waiting / error) | ✅ |
| **Sign in with Nous Portal** (device-code OAuth, no API key) with automatic model selection: subscribers get the full catalog, free accounts get the free models | ✅ |
| ~40 slash commands shared by the app, the CLI and the bots (`/help` lists them) | ✅ |
| 14 personalities (`/personality`), 9 skins with their palettes, spinner faces and branding (`/skin`) | ✅ |
| Providers: Nous Portal, OpenRouter, OpenAI, Anthropic, Gemini, xAI, DeepSeek, Groq, Mistral, Ollama/LM Studio, any OpenAI-compatible URL | ✅ |
| Image input (vision), share-to-Hermes from other apps | ✅ |
| Original remote **WebUI** mode (WebView), now with working file uploads | ✅ |
| Accessibility-service screen control, Shizuku, Health Connect, notification-listener, iCloud-style sync, Dynamic Island; WhatsApp, Signal and Email gateways; voice transcription; Docker/SSH/Modal backends; MCP servers | ❌ not ported. They need bridges or daemons that don't fit on a phone. |

## How the sandbox works

* `scripts/fetch_sandbox.sh` downloads Termux's prebuilt **PRoot** with its loader, `libtalloc` and
  `libandroid-shmem`, and the **Alpine minirootfs**. It then rewrites the ELF dependencies with
  `patchelf`, so the binaries load from the APK instead of a Termux install.
* Android 10+ only allows `exec()` from the app's `nativeLibraryDir`. PRoot and its loader therefore
  ship as `lib*.so` files (with `useLegacyPackaging`), and the loader maps guest binaries itself.
* On first launch the rootfs is unpacked into the app's private storage. `HERMES_HOME` is
  `/root/.hermes` inside it, so the shell, the agent tools and the app all see the same files.
* The `hermes` command in the sandbox talks to the app over a localhost bridge
  (`127.0.0.1:8642`, random bearer token).

## Build

Requirements: JDK 17, Android SDK 35, `curl`, `ar`, `patchelf` (`pip install patchelf`).

```bash
./scripts/fetch_sandbox.sh          # PRoot + Alpine rootfs (not committed; the build checks for them)
./gradlew testDebugUnitTest         # unit tests
./gradlew assembleDebug             # → app/build/outputs/apk/debug/app-debug.apk
```

Signed release build:

```bash
export KEYSTORE_PATH=$PWD/release.keystore KEYSTORE_PASSWORD=... KEY_ALIAS=hermes KEY_PASSWORD=...
./gradlew assembleRelease
```

The APK is **arm64-v8a only**, because the sandbox binaries are aarch64. That covers practically
every phone from the last several years.

### GitHub Actions

The *Build APK* workflow runs on pushes to `main`, on tags and on pull requests. It fetches the
sandbox, runs the tests and builds a signed release APK. Add the secrets `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` (see `scripts/make-keystore.sh`). Without them,
a throwaway keystore is used. `HERMES_URL` is optional: it only pre-fills the Remote WebUI screen.
Push a `v*` tag to get a GitHub Release with the APK attached.

## First run

1. **Sign in with Nous Portal** (browser, no key to copy). Free accounts are switched to a free model that
   supports tool calling automatically; subscribers get the full catalog. Or pick another provider and paste a key.
2. **Chat**: talk to Hermes; type `/` for commands.
3. **Terminal**: the sandbox installs itself (about 4 MB). Try `apk add python3`, then run `hermes`.
4. **Bot mode**: tap *New agent* to add bots (name, avatar, SOUL, model…). Tap a bot to chat with it; write
   `@researcher have a look at this` and the active bot hands it off. Put bots in a group and use *Open chat*
   for a shared room. Per-bot *Routines* run on a schedule.
5. **Messaging gateway**: paste a Telegram/Discord/Slack token and switch it on. When an unknown user DMs the
   bot, they get a pairing code; approve it in the app. Send `/sethome` in a chat to make it the
   cron delivery target.
6. Turn off battery optimization (button in Messaging gateway) so the gateway keeps running in the
   background.

## راهنمای کوتاه (فارسی)

* این اپ نسخهٔ بومی اندروید **Hermes Agent** است: حافظهٔ دائمی، اسکیل‌ها، زمان‌بندی (cron)، ساب‌ایجنت، **Bot Mode** (فهرستی از ایجنت‌های نام‌دار با چت، آواتار، شخصیت، حافظه، مدل و روتین جداگانه؛ گروه‌ها، پیام‌دادن ربات‌ها به هم و `@mention`) و **درگاه پیام‌رسان** (تلگرام، دیسکورد، اسلک).
* **ترمینال sandbox** داخل خود اپ است (Alpine Linux روی PRoot) و به Termux یا روت نیازی ندارد. با `apk add` بسته نصب کنید و با دستور `hermes` داخل ترمینال با ایجنت حرف بزنید.
* برای بیلد: اول `./scripts/fetch_sandbox.sh` و بعد `./gradlew assembleDebug`.
* در اولین اجرا می‌توانید با دکمهٔ **Sign in with Nous Portal** (بدون کلید API، فقط تأیید در مرورگر) وارد شوید؛ حساب رایگان خودکار روی مدل‌های رایگان تنظیم می‌شود و حساب اشتراکی کل کاتالوگ را می‌گیرد. یا provider و کلید API را دستی بدهید.
* در بخش **Bot mode** با «New agent» ربات بسازید؛ در چت بنویسید `@researcher این را ببین` تا ربات فعلی کار را به او بسپارد. توکن تلگرام/دیسکورد/اسلک در بخش **Messaging gateway** است.

## License

MIT for this app. It bundles skills and ports prompts, skins and personalities from
NousResearch/hermes-agent (MIT, see `app/src/main/assets/skills/LICENSE-hermes-agent.txt`). It uses
Termux's `terminal-view` and `terminal-emulator` libraries (Apache-2.0). PRoot (GPL-2.0) and Alpine
Linux are downloaded at build time and shipped as separate executables and data.
