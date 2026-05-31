---
change_id: llm-analysis-wiring
title: Wire LlmAnalysisService to Claude Haiku 4.5 via Bedrock + OpenRouter for experimentation
status: implementing
created: 2026-05-31
updated: 2026-05-31

plan_reviewed: 2026-05-31
archived_at: null
---

## Notes

F-01 from `context/foundation/roadmap.md`. Unblocks S-01 (`core-analysis-flow`).

**Q-01 decision (closed 2026-05-31, GitHub #7 / Linear AUT-11):**
- Production default: Claude Haiku 4.5 via AWS Bedrock, region `eu-central-1`, model ID `eu.anthropic.claude-haiku-4-5-20251001-v1:0`, configurable via `BEDROCK_MODEL_ID` env var.
- Experimentation provider: OpenRouter (single OpenAI-compatible client, `OPENROUTER_MODEL` env var, default `meta-llama/llama-3.3-70b-instruct:free`).
- Three Spring profiles: `mock`, `bedrock`, `openrouter` (rename existing `llm` profile → `bedrock`).
- Shared, provider-agnostic classes: `AnalysisPrompt` and `AnalysisResponseParser`.
- No Spring AI / LangChain4j abstraction layer (YAGNI).

**First deliverable:** lock the analysis output JSON schema (data table + equipment breakdown + risk flags + seller questions + per-category scores + verdict label). Schema must freeze before S-01's frontend rendering begins — otherwise both backend prompt and frontend display need rework.

**PRD refs:** FR-004 (extraction), FR-006 (equipment analysis), FR-007 (risk flags), FR-008 (seller questions), FR-009 (recommendation + scores).

**Auth setup:**
- Local dev: AWS SSO profile `przemyslawprzeworski` (already configured in `~/.claude/settings.json`).
- Render prod: needs IAM user with `bedrock:InvokeModel` permission on the Haiku model ARN; access keys go to `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` Render env vars.

**Env vars added to `.env` / `.env.example`:** `AWS_REGION`, `BEDROCK_MODEL_ID`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `OPENROUTER_API_KEY`, `OPENROUTER_MODEL`.

**Linked issues:** GitHub #1, Linear AUT-5.
