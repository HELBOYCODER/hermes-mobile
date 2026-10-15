# Hermes Mobile implementation sequence

Written spec approved2026-10-15. Four plans separate independent systems; no product changes implemented by this planning commit.

1. [Login, model diagnostics and chat](2026-10-15-login-models-chat.md): Tasks1-6; immediate priority and standalone release.
2. [Coding workflow](2026-10-15-coding-workflow.md): Tasks1-4; project specs, context, checkpoints and executable tests.
3. [Media understanding](2026-10-15-media-understanding.md): Tasks1-2; dependency-complete extraction then optional transcription.
4. [Integration packs](2026-10-15-integration-packs.md): Tasks1-2; shared install lifecycle and remote/CI tool support. Pack lifecycle may execute before native/media payload additions if needed; do not maintain a second installer.

## Shared decisions
- Native execution is recommended: one implementer owns auth/runtime/UI interfaces across the first slice; one independent reviewer checks the complete branch. Subagent-driven execution is also available with task-by-task reviews at higher context cost.
- Review/choose execution method before product implementation under the selected Superpowers workflow.
- Spec coverage: A=plan1; B=plan2; C=plan3; D=plan4; upgrade/signing/RTL/voice/regression gates=plan1Task6 and required by every subsequent release.
- Plan self-review complete: file map, neighboring interfaces, exact resource limits and failure-mode owners included. No live account, OpenCode inference, native media, mobile Tree-sitter or emulator result represented as already tested.
- Existing tools/presets/transcript helper and automatic compression are acknowledged. No broad data reset or secret export.
