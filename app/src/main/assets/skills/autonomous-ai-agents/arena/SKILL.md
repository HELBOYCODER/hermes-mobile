---
name: arena
description: Compare parallel agent solutions and judge evidence.
version: 1.0.0
author: Hermes Mobile
license: MIT
---
# Arena, bundled Hermes skill
## When to use
Load for complex engineering, visual design, debugging, and ambiguous multi-option tasks. Avoid redundant calls for trivial tasks or when no model is configured.
## Execution
1. Define a bounded task and testable acceptance criteria shared by candidates.
2. Use the existing delegate_task tool to start up to three genuinely independent sub-agents with wait=false. More work queues; do not spawn duplicate workers writing the same file.
3. Request distinct candidate solutions with file references, tests, limitations and risks. Use separate branches or isolated workspaces for write tasks.
4. Delegate an independent review only after candidate completion. Rank by correctness (40%), tested evidence (25%), security (15%), usability (10%), maintainability (10%). A failing regression test disqualifies a candidate.
5. Integrate the verified winner, run tests and report evidence. Never claim judge or parallel workers were run without recorded jobs.
6. Require approval for irreversible actions, account access and spending. Never bypass provider authentication or rate limits.
## Runtime limits
Hermes AgentJobs already enforces MAX_CONCURRENT=3 and manages background completion. This skill guides its use; it does not add an always-on service, create credentials or guarantee free requests.
## Provenance
Adapted conceptually from MIT-licensed https://github.com/irr/arena-mode . This is a native Hermes procedure, not the Claude Code plugin.
## Verification
Check the actual job IDs, states, reviewer report and tests before accepting a result.