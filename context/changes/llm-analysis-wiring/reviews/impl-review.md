<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: F-01 llm-analysis-wiring

- **Plan**: `context/changes/llm-analysis-wiring/plan.md`
- **Scope**: All 5 phases (Phase 1–5 complete)
- **Date**: 2026-06-02
- **Verdict**: APPROVED (with quality follow-ups)
- **Findings**: 0 critical · 3 warnings · 5 observations

## Verdicts

| Dimension | Verdict |
|---|---|
| Plan Adherence | PASS |
| Scope Discipline | PASS (one EXTRA file justified by plan §2.4) |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS (35/35 backend tests pass; live-llm correctly excluded by default) |

## Scope summary

41/41 planned files MATCH plan intent. `LlmAnalysisService.java` correctly deleted in Phase 3. One PARTIAL drift: `application-openrouter.properties` omits a redundant `llm.openrouter.api-key=${OPENROUTER_API_KEY}` line — runtime contract preserved because `OpenRouterConfig` reads the env var directly via `@Value`. One EXTRA file (`LlmExceptionHandlerTest.java`) is justified by Phase 2's success-criteria explicitly calling for a MockMvc test of the new exception handlers.

S-01-modified files (`AnalysisRequest.java`, `AnalysisController.java`, `GlobalExceptionHandler.java`, controller tests) excluded from this F-01-scoped review — the F-01-era versions were verified against `git show fdd4553:<path>`.

Commits in scope: `fdd4553` (p1), `dced73e` (p2), `b2e9174` (p3), `b0ab8ca` (p4), `b6b408f` (p5), `bd9b6e3` (epilogue).

## Findings

### F1 — LLM exception handlers leak raw cause messages to clients

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Security / DataSafety)
- **Location**: `backend/src/main/java/com/example/autoskaner_ai/common/GlobalExceptionHandler.java:36-46` (`handleLlmCall` / `handleLlmSchema`)
- **Detail**: The 502 envelope returns `ex.getCause().getMessage()` (`handleLlmCall`) and `fieldPath + ": " + ex.getMessage()` (`handleLlmSchema`) verbatim to the client. AWS SDK / RestClient cause messages can include request URL, response body fragments, header dumps, and in some failure modes echo bearer tokens reflected by upstream proxies. S-01's commit `e02135f` sanitized the 400 + 500 branches but left these LLM-specific 502 branches untouched.
- **Fix**: Replace cause-message echo with a fixed Polish string for both LLM handlers (e.g. `"Wystąpił błąd usługi LLM. Spróbuj ponownie."`; keep the schema handler's fieldPath but drop the inner message). Log raw cause server-side via SLF4J before returning.
- **Decision**: FIXED — `handleLlmCall` returns fixed Polish string + `log.warn(..., ex)`; `handleLlmSchema` returns only `fieldPath` with raw message moved to a server-side `log.warn`. Test updated to match. 35/35 backend tests pass.

### F2 — OpenRouter retries on schema-shaped failures, not just transport

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (Reliability) + Pattern Consistency
- **Location**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/OpenRouterAnalysisService.java:47-59`
- **Detail**: The retry block catches every `LlmCallException` — but `callApiRaw` / `extractContent` wrap not just transport errors but also "empty choices", "null body", and similar response-shape failures. Plan §3 / §4 said retry on transient transport (5xx, IOException, timeout, throttle). Bedrock retries narrowly on `ThrottlingException | ServiceUnavailableException` only — strict. OpenRouter retries on everything `LlmCallException`-shaped — lenient. Asymmetry is unjustified by API differences and doubles wall-clock on errors that won't change between attempts.
- **Fix A ⭐ Recommended**: Narrow OpenRouter's retry trigger to true transport errors (`RestClientException` + `IOException` + timeout)
  - Strength: Restores parity with Bedrock; matches plan intent; no wasted second call on permanent failures.
  - Tradeoff: Need to introduce an `isRetryable(Throwable)` helper or inspect the wrapped cause inside `LlmCallException`.
  - Confidence: HIGH — the same retry shape works in Bedrock today.
  - Blind spot: Whether OpenRouter ever returns 5xx for genuinely transient queue-pressure scenarios (free-tier rate limits return 429, which IS retryable per plan).
- **Fix B**: Keep the broad catch but document the deviation
  - Strength: Zero code change; quick.
  - Tradeoff: Carries the latency cost on every parser-shaped failure forever; future maintainers will have to re-derive why.
  - Confidence: MEDIUM — works but is not what the plan promised.
  - Blind spot: How frequently the broad path fires in production — no metric distinguishes the two failure classes today.
- **Decision**: FIXED via Fix A — added `isRetryable(LlmCallException)` helper that returns true only for `RestClientException` / `IOException` causes. Added `emptyChoicesResponse_failsWithoutRetry` regression test (36/36 backend tests pass).

### F3 — Prompt example can induce fabricated "bezwypadkowy" status

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (DataSafety)
- **Location**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisPrompt.java:8-84`
- **Detail**: The system prompt states the rule "Brak danych o wypadkach oznacza nieznane, nigdy nie potwierdzenie braku wypadków" once in narrative form, but the embedded few-shot example shows `"accidentClaim": "bezwypadkowy wg sprzedającego"`. LLMs pattern-match on examples; the model can plausibly emit similar wording even when the listing does not state it. The parser is structural, not semantic, so a fabricated "bezwypadkowy" passes through and surfaces in the UI as a confirmed claim — directly violating CLAUDE.md's load-bearing business rule. Risk is highest with weak free-tier OpenRouter models.
- **Fix**: Strengthen the rule with an explicit negative case (`"Jeśli ogłoszenie nie wspomina wypadków, użyj null. Nigdy nie wymyślaj 'bezwypadkowy'."`), AND require a `NO_ACCIDENT_DECLARATION` risk flag whenever `accidentClaim` is null. Add a second few-shot example where `accidentClaim` is null and the flag is present, balancing the existing positive example.
- **Decision**: FIXED — strengthened business rule into a 4-point block (explicit "MUSI być null" / "Nigdy nie wymyślaj"); added mandatory `NO_ACCIDENT_DECLARATION` flag when accidentClaim is null; added balancing few-shot Example 2 (Toyota Corolla, accidentClaim=null + the flag). Both examples now relabeled `PRZYKŁAD 1` / `PRZYKŁAD 2`.

### F4 — application-openrouter.properties drops a planned property line

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `backend/src/main/resources/application-openrouter.properties`
- **Detail**: Plan §4.3 listed three lines; impl has two. The `llm.openrouter.api-key=${OPENROUTER_API_KEY}` line is absent. The runtime contract is preserved because `OpenRouterConfig.java` reads `OPENROUTER_API_KEY` directly via `@Value`, so the property indirection is just unused. No functional impact; flagged for plan-vs-impl completeness only.
- **Fix**: Either add the missing line or update the plan to reflect that the API key is read directly via `@Value`.
- **Decision**: FIXED — added `llm.openrouter.api-key=${OPENROUTER_API_KEY}` to `application-openrouter.properties` for plan-vs-impl symmetry.

### F5 — Bedrock token usage NPE risk on edge-case responses

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/BedrockClaudeAnalysisService.java:60-61`
- **Detail**: `usage.inputTokens()` returns `Integer` (boxed) on the AWS SDK. Code null-checks `usage` itself but autoboxes the individual fields into `int` log args. If Bedrock omits a particular field on edge cases, the unboxing will NPE — converting a successful LLM call into a log-formatting crash that's likely caught by the catch-all and surfaces as a 502.
- **Fix**: Pull individual fields into `Integer` locals and log `-1` as fallback.
- **Decision**: FIXED — `inputTokens`/`outputTokens` now `Integer` locals; `-1` substituted at log time when null.

### F6 — Bedrock catch-all swallows distinct error classes

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/BedrockClaudeAnalysisService.java:53`
- **Detail**: The catch-all `Exception` block converts `ValidationException`, `AccessDeniedException`, `ModelErrorException`, and runtime errors all into a generic `LlmCallException`. Ops can't distinguish credential problems from model-side validation problems from logs.
- **Fix**: Log the SDK exception class name (e.g. `ex.getClass().getSimpleName()`) alongside the message in the WARN/ERROR line so ops can grep.
- **Decision**: FIXED — `exceptionClass={}` added to all three Bedrock log lines (WARN retry, ERROR after retry, ERROR catch-all).

### F7 — DTOs use ignoreUnknown=false; LLMs sometimes add stray fields

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisResponseParser.java` (`ResponseDto`, `EquipmentItemDto`, `RiskFlagDto`, `ScoresDto`, `VerdictDto` — only `ExtractedDto` sets `ignoreUnknown=true`)
- **Detail**: A model that adds a helpful `"explanation"` or `"confidence"` field at any level fails parsing → 502 to user. The required-field validator already explicitly enforces what must be present; tolerating extras costs nothing and makes the wiring more robust to free-tier model quirks.
- **Fix**: Set `ignoreUnknown=true` on all DTOs (apply to `ResponseDto`, `EquipmentItemDto`, `RiskFlagDto`, `ScoresDto`, `VerdictDto`).
- **Decision**: FIXED — flipped all 6 `@JsonIgnoreProperties(ignoreUnknown = false)` to `true` in the parser. Required-field validator still enforces the contract; stray fields are now tolerated.

### F8 — max_tokens=4096 may truncate full Polish AnalysisResult

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Performance / Reliability
- **Location**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/BedrockClaudeAnalysisService.java:80`, `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/OpenRouterAnalysisService.java:119`
- **Detail**: A complete Polish `AnalysisResult` — 5–10 equipment items with notes, 3–5 risk flag descriptions, 5 seller questions, full Polish text — can plausibly exceed 3000 output tokens. Mid-JSON truncation surfaces as `LlmResponseSchemaException` → 502. Fail-safe but user-hostile.
- **Fix**: Bump to 8192; optionally inspect the response's `stop_reason` and surface a more specific error if `"max_tokens"`.
- **Decision**: FIXED — bumped `max_tokens` from 4096 → 8192 on both Bedrock and OpenRouter. Truncation detection deferred.
