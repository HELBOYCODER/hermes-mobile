---
name: explore-repository
description: Explore a code repository before implementing a feature or diagnosing an unfamiliar project. Use for architecture, entry points, dependency mapping and build/test discovery.
---

# Explore a repository

1. Confirm the project path. Prefer /root/projects/<name>; never assume the current terminal directory is the requested repo.
2. Read repository instructions (AGENTS.md, README and relevant contribution guidance). Inspect Git status before any edits.
3. Use the bundled hermes-code MCP project_overview and search_code tools for bounded discovery. Fall back to terminal rg --files and rg when disconnected; use find/grep if rg is absent on older rootfs installations.
4. Read the actual build manifests, lockfiles and CI definitions. Identify entry points, storage, network boundaries, tests and deployment configuration from files, not filenames alone.
5. Return a short architecture map, concrete build/test commands, and unanswered questions. Do not modify the project for an exploration request.

The local MCP scans /root/projects, omits hidden files and common generated directories, and limits results. Read a specifically required hidden instruction file through read_file; do not infer absence from a bounded scan. Keep credentials in Environment and reference variable names in commands.
