# LLM Analysis Wiring — Plan Brief

> Full plan: `context/changes/llm-analysis-wiring/plan.md`
> Change identity: `context/changes/llm-analysis-wiring/change.md`

## What & Why

F-01 from the roadmap. Replace the risk-flag-only `AiAnalysisService` with a full `AnalysisResult` contract (extracted data + equipment + risk flags + seller questions + per-category scores + verdict), and wire two real LLM providers behind Spring profiles: **Claude Haiku 4.5 via AWS Bedrock** as production default, **OpenRouter** for experimentation with free/alternative models. This is the foundation S-01 (the north-star slice) depends on — without a stable JSON output schema, the frontend rendering layer has nothing to pin against.

## Starting Point

`AiAnalysisService.analyzeRisks(String) → List<RiskFlag>` is the entire current contract. `MockAiAnalysisService` produces Polish keyword-based heuristics on profile `mock`; `LlmAnalysisService` is a stub throwing `UnsupportedOperationException` on profile `llm`. The single endpoint `POST /api/analysis/risk` returns just risk flags. `pom.xml` has no AWS SDK or HTTP client beyond what WebMVC ships. Spring Boot 4.0.6 + Java 21.

## Desired End State

`POST /api/analyses` with a Polish listing returns a structurally-validated `AnalysisResult` JSON. Three switchable implementations: `mock` (no credentials, deterministic), `bedrock` (production default, Haiku 4.5), `openrouter` (experimentation, Llama free tier by default). Both real providers share an `AnalysisPrompt` (Polish system prompt) and `AnalysisResponseParser` (strict JSON validation). Model is swappable via env var (`BEDROCK_MODEL_ID`, `OPENROUTER_MODEL`) — no code change needed. Legacy `/api/analysis/risk` stays as a deprecated facade until S-01 lands.

## Key Decisions Made

| Decision                            | Choice                                                                                                            | Why (1 sentence)                                                                                                                | Source |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Production LLM provider             | Claude Haiku 4.5 via AWS Bedrock, region `eu-central-1`                                                           | Same region as Render backend → lowest latency; no second vendor; you already pay AWS                                            | Q-01   |
| Experimentation provider            | OpenRouter (single OpenAI-compatible client, configurable model)                                                  | One implementation gives access to 300+ models including free ones; same API shape will work against OpenAI direct later         | Q-01   |
| Profile names                       | `mock`, `bedrock`, `openrouter` (rename existing `llm` → `bedrock`)                                               | Clearer naming once multiple providers exist; the old `llm` name is ambiguous                                                    | Q-01   |
| Provider abstraction layer          | None beyond `AiAnalysisService` interface — Spring `@Profile` is the switch                                       | YAGNI — Spring AI / LangChain4j buy little for one endpoint × two providers and add framework risk                              | Q-01   |
| Verdict labels                      | 3-tier: stable enum codes (`WORTH_CHECKING` / `NEEDS_MORE_INFO` / `HIGH_RISK_SKIP`) + Polish display label        | Tests pin to codes (safe to change copy); UI gets Polish; logs are readable                                                      | Plan   |
| Score scale                         | 0–100 integer per category (completeness, equipment, risk, value, overall)                                        | Familiar; renders as percentage or progress bar; enough granularity without LLM hallucinating false precision                   | Plan   |
| Equipment shape                     | 3-state per item (`CONFIRMED` / `MISSING` / `UNCLEAR`) with optional reasoning note                               | Maps directly to FR-006; the note field captures the LLM's "why" for UNCLEAR items                                              | Plan   |
| Error / retry policy                | One retry on transient (5xx, throttle, timeout, IO); fail-fast on schema violations; 30s per-call timeout         | Single retry covers most flakiness without doubling cost; fail-fast surfaces prompt/schema bugs early instead of papering over   | Plan   |
| Endpoint shape                      | New `POST /api/analyses`; deprecate `/api/analysis/risk` as thin facade until S-01 lands                          | Forward-looking REST naming for S-03's list/save; old endpoint stays unbroken for in-flight experiments                          | Plan   |
| Test strategy                       | Unit-test parser/prompt/controller with mocks; live integration tests behind `@Tag("live-llm")`                   | Fast deterministic CI; live tests opt-in when credentials are present — catches schema drift without flaking the build           | Plan   |

## Scope

**In scope:**
- Full `AnalysisResult` schema and Java record family
- `AiAnalysisService` interface change to `analyze(String) → AnalysisResult`
- Expanded `MockAiAnalysisService` returning the full shape
- `BedrockClaudeAnalysisService` (AWS SDK v2 Converse API) on profile `bedrock`
- `OpenRouterAnalysisService` (Spring `RestClient`) on profile `openrouter`
- Shared `AnalysisPrompt` (Polish system prompt) and `AnalysisResponseParser` (strict JSON validation)
- New `POST /api/analyses` endpoint; legacy `/api/analysis/risk` kept as deprecated facade
- Two new exceptions (`LlmCallException`, `LlmResponseSchemaException`) wired into `GlobalExceptionHandler`
- `application-bedrock.properties` and `application-openrouter.properties`
- Unit tests + live-tagged integration tests
- Structured logging per LLM call (provider, model, latency, token usage)
- `CLAUDE.md` updates (profile rename, endpoint, schema reference)

**Out of scope:**
- Persistence (F-02 / S-03)
- URL fetching from listings (S-01)
- Frontend Angular changes (S-01)
- Auth (F-03)
- Spring AI / LangChain4j abstraction layer (rejected in Q-01)
- Streaming responses
- Render env-var wiring for AWS access keys (deployment task post-merge)
- WireMock-recorded fixtures (live-tagged tests are sufficient)

## Architecture / Approach

```
   AnalysisController (POST /api/analyses)
              │
              ▼
   AiAnalysisService (interface)
              │
   ┌──────────┼──────────────┐
   ▼          ▼              ▼
  mock     bedrock        openrouter
            │                │
            └──┬───┬─────────┘
               │   │
               ▼   ▼
       AnalysisPrompt    AnalysisResponseParser
       (system prompt)   (JSON → AnalysisResult,
                          schema validation)
```

`AnalysisController` is provider-agnostic — Spring picks the active impl by profile. Real providers funnel through the same prompt + parser; only the transport layer differs. New exceptions surface through the existing `GlobalExceptionHandler` per `CLAUDE.md`'s API error shape.

## Phases at a Glance

| Phase                                  | What it delivers                                                                       | Key risk                                                                              |
| -------------------------------------- | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| 1. Schema + interface contract         | Records, interface change, expanded mock, new endpoint                                 | Schema-shape decisions ripple into every later phase — getting it wrong means rework  |
| 2. Shared prompt + parser              | `AnalysisPrompt`, `AnalysisResponseParser`, new LLM exceptions, fixture-based tests    | Parser strictness vs LLM-output messiness — fence-stripping and validation must match |
| 3. Bedrock provider                    | `BedrockClaudeAnalysisService`, `BedrockConfig`, properties, retry/timeout, unit tests | First time we depend on AWS SDK + IAM auth chain; Bedrock model ID format is finicky  |
| 4. OpenRouter provider                 | `OpenRouterAnalysisService`, `OpenRouterConfig`, properties, retry/timeout, unit tests | Free-tier rate limits cause flaky live tests if not handled                           |
| 5. Live tests, observability, docs     | `@Tag("live-llm")` integration tests, structured logging, `CLAUDE.md` updates          | Live-tagged tests must skip cleanly without credentials — failing the build = bad UX  |

**Prerequisites:**
- AWS SSO `przemyslawprzeworski` profile active for local Bedrock testing (already configured in `~/.claude/settings.json`)
- `OPENROUTER_API_KEY` populated in `.env` (rotated key, post-leak)
- No code prerequisites — all foundations in place

**Estimated effort:** ~3–5 after-hours sessions across 5 phases. Phase 1 + 2 are the highest-leverage (schema lock); Phases 3–4 are mechanical once the parser exists; Phase 5 is small.

## Open Risks & Assumptions

- Bedrock model ID format on EU regions (`eu.anthropic.claude-haiku-4-5-20251001-v1:0`) — verified once during implementation; if AWS changes the convention this needs the env var update only
- OpenRouter free-tier rate limits will make live tests flaky on `:free` models — mitigation: `OPENROUTER_TEST_MODEL` env override or a small inter-test delay
- Polish-language extraction quality from Haiku is the implicit assumption; **F-01 only proves wiring**, not output quality — that's S-01's risk to validate against real Polish listings
- Stale memory record claims "JDK 17 only" — `pom.xml` and `CLAUDE.md` confirm Java 21; trust the code

## Success Criteria (Summary)

- A user POSTing a Polish listing to `/api/analyses` under any of the three profiles receives a structurally-valid `AnalysisResult` with all top-level fields populated
- Switching `SPRING_PROFILES_ACTIVE` between `mock` / `bedrock` / `openrouter` is the only change required to swap providers — no code edits
- Switching `BEDROCK_MODEL_ID` or `OPENROUTER_MODEL` swaps the underlying model with no code edits — `meta.model` in the response confirms it
- Default `./mvnw test` passes without any LLM credentials; `./mvnw test -Dgroups=live-llm` exercises real APIs when credentials are present
