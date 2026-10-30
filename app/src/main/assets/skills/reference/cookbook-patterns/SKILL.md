---
name: cookbook-patterns
description: Claude Cookbook patterns wired into Hermes — RAG, tool use, vision, sub-agents, prompt caching. Index only (Hermes-written); verbatim Anthropic texts are in references/. Load when a task matches one of the five patterns.
---

# Cookbook patterns (Hermes index)

Source: https://github.com/anthropics/claude-cookbooks (MIT).
Bundled verbatim, with attribution in LICENSE-COOKBOOKS.txt:
`references/rag-guide.md` (capabilities/retrieval_augmented_generation/README.md),
`references/research_subagent.md`, `references/research_lead_agent.md`,
`references/citations_agent.md`, `references/agents-readme.md`
(patterns/agents prompts + README). The repo's notebooks
(`tool_use/*.ipynb`, `multimodal/*.ipynb`, `misc/prompt_caching.ipynb`,
`patterns/agents/*.ipynb`) are NOT bundled — they are large JSON
notebooks, not text recipes. The three guides below are Hermes-written
summaries of those notebook patterns, pointing at the original paths;
they are not verbatim recipes.

## Pattern → Hermes status (live vs reference-only)

1. RAG (retrieval-augmented generation) — LIVE as local retrieval.
   Hermes retrieves from its own stores (SkillStore skills,
   MemoryStore MEMORY.md/USER.md, session search) and generates from
   what it retrieved. There is no embedding vector store in this app;
   retrieval is keyword/structured, not semantic. Read
   references/rag-guide.md for the full pattern.
2. Tool use — LIVE. The agent tool loop (terminal, files, browser,
   MCP tools, sub-agents) is Hermes' native tool use. Tool choice,
   parallel calls, and structured output follow the same shape as the
   cookbook's tool_use notebooks (see the Hermes-written guide below).
3. Vision — LIVE. The vision toolset (image input, browser
   screenshots) is built in. The cookbook's multimodal notebooks are
   reference-only (not bundled); the pattern is: attach the image,
   ask a specific question about it, verify against pixels, never
   invent details.
4. Sub-agents — LIVE. DelegateTool spawns depth-limited sub-agents
   (see the Sno team-mode flow for builder/reviewer). Agent prompts:
   references/research_subagent.md, references/research_lead_agent.md,
   references/citations_agent.md.
5. Prompt caching — REFERENCE-ONLY. Hermes' OpenAI-compatible chat
   path does not send Anthropic `cache_control` blocks, and no
   caching layer was found in LlmClient. Do not claim cached prompts
   or cache savings. The pattern (cache stable system/context blocks,
   vary only the final user turn) is documented here for when a
   provider path supports it; the original notebooks are
   misc/prompt_caching.ipynb and misc/speculative_prompt_caching.ipynb.

## Hermes-written pattern guides (summaries, not verbatim)

### Tool use
- One tool per step, arguments explicit; parallel calls only when
  independent. Structured output: ask for JSON, validate fields,
  retry once on parse failure. Never present a tool result you did
  not receive. Original notebooks: tool_use/calculator_tool.ipynb,
  tool_use/extracting_structured_json.ipynb, tool_use/parallel_tools.ipynb.

### Vision
- Attach images at full useful resolution; ask one question per
  image when precision matters; cross-check text in the image
  against the model's reading before acting on it. Original
  notebooks: multimodal/getting_started_with_vision.ipynb,
  multimodal/best_practices_for_vision.ipynb.

### Prompt caching (reference-only)
- Cache the parts that never change (system prompt, long reference
  docs); put the changing request last. Savings apply only on
  providers that support prompt caching — Hermes does not send the
  cache markers today, so no saving is claimed.
