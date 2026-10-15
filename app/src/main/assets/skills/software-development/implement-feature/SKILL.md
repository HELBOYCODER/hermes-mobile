---
name: implement-feature
description: Implement a requested code feature in an existing repository, including scope, architecture, focused edits and relevant validation.
---

# Implement a feature

1. Establish the requested behavior and acceptance examples. Ask only for missing choices that materially affect the implementation; proceed with routine choices.
2. Load explore-repository when unfamiliar with the project. Read local instructions and inspect current changes. Preserve unrelated user work.
3. Name the relevant entry points and the smallest coherent change. Reuse existing components, tokens, error handling and data storage. Check installed dependency versions before using APIs.
4. Implement end-to-end behavior, including loading, empty and failure states where relevant. Keep secrets in Settings → Environment; reference variables rather than printing values or placing them in source.
5. Add a focused behavior test for risky logic or a reproduced defect. Avoid tests that simply repeat the implementation or assert labels.
6. Load verify-changes. Run appropriate checks and inspect the final diff. Do not claim completion from a process starting or a successful build alone when runtime behavior matters.
7. Report the resulting behavior, validation and remaining limits. Do not push, deploy or publish unless the user's request authorizes it.
