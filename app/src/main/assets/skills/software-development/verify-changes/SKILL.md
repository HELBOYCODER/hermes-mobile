---
name: verify-changes
description: Validate implementation changes before marking work complete, including focused regression checks, build/test commands and explicit runtime verification limits.
---

# Verify changes

1. Read the project's CI and test commands. Choose checks that exercise the changed public behavior.
2. For a bug, reproduce the original failure when the environment permits, make the smallest fix, then rerun the same signal. Distinguish source-inspection evidence from reproduction.
3. Run the focused regression checks and relevant build, type or lint checks. Capture exit status and decisive output. A skipped test is not a pass.
4. Inspect Git diff and status for accidental generated files, credentials and unrelated changes. Check migration and restoration behavior when storage changes.
5. For UI work, inspect the app on an available device/emulator. For Android, discover adb devices first; use logcat and actual UI evidence rather than invented screenshots. If no device or required runtime is available, state the limitation.
6. For services and MCP tools, perform a real protocol request through the executable/transport, not just an import or static file check.
7. Report what changed, exactly what passed or failed, and unverified behavior. A passing catalog request does not verify model inference; a successful build does not verify the on-device UI.
