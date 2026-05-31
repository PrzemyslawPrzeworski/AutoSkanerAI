# LLM Analysis Wiring — Implementation Plan

## Overview

Replace the risk-flag-only `AiAnalysisService` with a full `AnalysisResult` contract matching FR-004/006/007/008/009 (extracted data, equipment breakdown, risk flags, seller questions, per-category scores, verdict). Wire two real implementations behind Spring profiles: **Claude Haiku 4.5 via AWS Bedrock** as the production default, **OpenRouter** as an experimentation provider for free/alternative models. Both share a provider-agnostic `AnalysisPrompt` and `AnalysisResponseParser`. Locking the JSON output schema is the load-bearing first deliverable — S-01's frontend rendering depends on it.

## Current State Analysis

- `AiAnalysisService` interface has a single method `analyzeRisks(String) → List<RiskFlag>` (`backend/src/main/java/com/example/autoskaner_ai/analysis/AiAnalysisService.java:6`). This is **a small fraction** of what F-01 must deliver — only risk flags, no extraction, equipment, scores, or verdict.
- `MockAiAnalysisService` (`MockAiAnalysisService.java:11`) uses keyword heuristics in Polish on profile `mock`. Activated by `SPRING_PROFILES_ACTIVE=mock`.
- `LlmAnalysisService` (`LlmAnalysisService.java:10`) is a stub throwing `UnsupportedOperationException` on profile `llm`. Will be **renamed** to `BedrockClaudeAnalysisService` on profile `bedrock` per Q-01.
- `RiskAnalysisController` (`RiskAnalysisController.java:11`) exposes `POST /api/analysis/risk`. We add a new `POST /api/analyses` and keep `/risk` working temporarily as a thin facade.
- `pom.xml` has only WebMVC + validation + actuator. We will add `software.amazon.awssdk:bedrockruntime` for Bedrock; OpenRouter uses Spring's built-in `RestClient`.
- `GlobalExceptionHandler` + `ErrorResponse` already exist in `com.example.autoskaner_ai.common`. New LLM exceptions plug into this same shape per `CLAUDE.md`.
- No `application.properties` exists yet — we add one base file plus profile-specific files.
- Java 21, Spring Boot 4.0.6 (note: the prior memory record claiming "JDK 17 only" is stale — the code, `pom.xml`, and `CLAUDE.md` all confirm 21).

### Key Discoveries

- The interface change is the load-bearing decision; everything else flows from it. Adding a new method (instead of replacing) would leave the interface inconsistent and create drift between mock and real impls.
- `MockAiAnalysisService` keyword logic is mostly Polish-language string detection; that work translates well into a fuller mock that returns the expanded shape.
- API error shape in `CLAUDE.md` is non-negotiable: `{ status, error, messages, timestamp }` via `GlobalExceptionHandler`. New `LlmCallException` and `LlmResponseSchemaException` must surface through this same path.
- `CorsConfig` covers `/api/**`; the new `/api/analyses` endpoint inherits it without changes.

## Desired End State

A request to `POST /api/analyses` with a Polish listing (URL, raw text, or manual fields — all flow as `listingText` for now) returns a structured `AnalysisResult` JSON containing extracted data, equipment statuses, risk flags, seller questions, per-category scores (0–100), and a verdict (code + Polish label). Three implementations are switchable via `SPRING_PROFILES_ACTIVE`:
- `mock` — deterministic Polish heuristics (no API calls, no credentials)
- `bedrock` — Claude Haiku 4.5 on AWS Bedrock (production default)
- `openrouter` — any OpenAI-compatible model on OpenRouter (experimentation)

Verification:
- `./mvnw test` passes including controller tests under `mock` profile and pure parser/prompt unit tests
- `./mvnw test -Dgroups=live-llm` (or equivalent) passes against real Bedrock + OpenRouter when credentials are present
- `curl -X POST localhost:8080/api/analyses -d '{"listingText":"..."}'` under each profile returns a complete `AnalysisResult`
- `curl localhost:8080/api/analysis/risk` (legacy path) still returns just the risk-flag list (deprecated)

### Locked output schema (the first deliverable)

```
AnalysisResult {
  extracted: ExtractedData {
    make, model, year, priceAmount, priceCurrency, mileageKm,
    fuel, transmission, originCountry, sellerType,
    serviceHistoryMentioned, accidentClaim, vinPresent
  }
  equipment: List<EquipmentItem { name, status: CONFIRMED|MISSING|UNCLEAR, note? }>
  riskFlags: List<RiskFlag { code, severity: LOW|MEDIUM|HIGH, description }>
  sellerQuestions: List<String>
  scores: CategoryScores { completeness, equipment, risk, value, overall }   // each 0–100 int
  verdict: Verdict { code: WORTH_CHECKING|NEEDS_MORE_INFO|HIGH_RISK_SKIP, label: <Polish display> }
  meta: AnalysisMeta { provider, model, latencyMs, generatedAt }
}
```

Every nullable field in `extracted` is explicitly nullable: missing data is **unknown**, never "clean" (per `CLAUDE.md` business rule).

## What We're NOT Doing

- No persistence (no DB, no saving analyses) — that is F-02 + S-03
- No URL fetching / scraping — S-01 will pass through raw text; URL fetch is part of S-01
- No frontend changes — Angular UI lands in S-01
- No auth — F-03 territory
- No Spring AI / LangChain4j abstraction layer — explicitly rejected in Q-01
- No streaming responses — single-shot request/response for MVP
- No Render env-var wiring for `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` — that's a deployment task for after F-01 merges; local dev uses AWS SSO
- No prompt few-shot library beyond a single canonical example
- No replay/recorded WireMock fixtures — live tests gated by credential presence are sufficient

## Implementation Approach

Five phases, each independently testable:

1. **Schema + interface** — define the contract everyone codes against. Mock implementation matches the new shape end-to-end so the controller and integration tests work without any LLM.
2. **Shared prompt + parser** — extract the provider-agnostic pieces. Pure unit tests with fixture JSON validate the schema.
3. **Bedrock provider** — production default. AWS SDK v2 BedrockRuntimeClient with Converse API.
4. **OpenRouter provider** — experimentation backend. Spring `RestClient` against OpenAI-compatible API.
5. **Live tests + observability + docs** — `@Tag("live-llm")` integration tests for both real providers; structured logs; `CLAUDE.md` updates.

Phases 3 and 4 are independent — could be reordered or parallelized, but the plan presents Bedrock first since it's the production default.

## Critical Implementation Details

**Profile rename `llm` → `bedrock`** — references to `SPRING_PROFILES_ACTIVE=llm` in `CLAUDE.md` and any local `.env` must change in lockstep with the file rename in Phase 3. A live `bedrock` profile with no AWS credentials will fail loudly at first request — that is the desired behavior (vs masking the failure with a fall-through to `mock`).

**Bedrock model ID format on EU regions** — the Haiku 4.5 model ID on `eu-central-1` is `eu.anthropic.claude-haiku-4-5-20251001-v1:0` (note the `eu.` prefix and the `-v1:0` suffix). `BEDROCK_MODEL_ID` env var defaults to this; do not omit either prefix or suffix.

**OpenRouter free-tier rate limits** — free models are rate-limited (a few requests/minute). Live tests against `:free` models must include a small delay or retry-on-429; otherwise CI flakes. Use a non-free fallback model ID in tests if budget allows, controlled by a separate `OPENROUTER_TEST_MODEL` var.

**JSON output discipline** — the prompt must instruct the model to return *only* the JSON object, no prose preamble or trailing markdown fence. The parser must strip a leading ```` ```json ```` fence and trailing ```` ``` ```` if present (Claude occasionally adds them despite instructions); after stripping, parse strictly. A second pass is wasteful — log and fail.

---

## Phase 1: Schema + interface contract

### Overview

Define the `AnalysisResult` record family, refactor `AiAnalysisService` to return it, expand `MockAiAnalysisService` to produce a complete shape, and add a new `POST /api/analyses` endpoint. The legacy `/api/analysis/risk` endpoint stays as a deprecated thin facade returning only the risk-flag list.

### Changes Required

#### 1. New record types — locked schema

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisResult.java` (new)

**Intent**: Top-level record returned by every `AiAnalysisService` impl.

**Contract**: `AnalysisResult(ExtractedData extracted, List<EquipmentItem> equipment, List<RiskFlag> riskFlags, List<String> sellerQuestions, CategoryScores scores, Verdict verdict, AnalysisMeta meta)`. All collections non-null (use `List.of()` for empty); all nested records non-null.

---

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/ExtractedData.java` (new)

**Intent**: Structured facts pulled from the listing.

**Contract**: `ExtractedData(String make, String model, Integer year, BigDecimal priceAmount, String priceCurrency, Integer mileageKm, String fuel, String transmission, String originCountry, String sellerType, Boolean serviceHistoryMentioned, String accidentClaim, Boolean vinPresent)`. Every field is nullable — null means "unknown" (per `CLAUDE.md` business rule). `priceAmount` uses `BigDecimal` to avoid float drift; `accidentClaim` is the listing's literal claim string (not a normalized boolean — "bezwypadkowy", "drobna szkoda", etc.).

---

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/EquipmentItem.java` (new)

**Intent**: One row of equipment with status and optional reasoning.

**Contract**: `EquipmentItem(String name, EquipmentStatus status, String note)` where `EquipmentStatus` is an enum: `CONFIRMED`, `MISSING`, `UNCLEAR`. `note` is required when `status == UNCLEAR`, optional otherwise.

---

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/CategoryScores.java` (new)

**Intent**: Per-category 0–100 integer scores.

**Contract**: `CategoryScores(int completeness, int equipment, int risk, int value, int overall)`. Each is `0 ≤ n ≤ 100`. Validation in the parser: any value outside the range fails the call as a schema violation.

---

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/Verdict.java` (new)

**Intent**: Final recommendation as a stable code + Polish display label.

**Contract**: `Verdict(VerdictCode code, String label)` where `VerdictCode` is an enum: `WORTH_CHECKING`, `NEEDS_MORE_INFO`, `HIGH_RISK_SKIP`. `label` is the Polish display string (`"warto sprawdzić"`, `"sprawdź po doprecyzowaniu"`, `"wysokie ryzyko — pomiń"`). The enum is the contract for tests/logic; the label is the UI string.

---

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisMeta.java` (new)

**Intent**: Provenance and observability metadata for the response.

**Contract**: `AnalysisMeta(String provider, String model, long latencyMs, Instant generatedAt)`. `provider` ∈ `{"mock", "bedrock", "openrouter"}`. Set by the implementation that produced the result.

---

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/RiskFlag.java` (modify)

**Intent**: Tighten `severity` from `String` to a `RiskSeverity` enum so callers can reason about levels.

**Contract**: `RiskFlag(String code, RiskSeverity severity, String description)` where `RiskSeverity` is `LOW`, `MEDIUM`, `HIGH`. Existing `description` field unchanged.

#### 2. Interface refactor

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/AiAnalysisService.java` (modify)

**Intent**: Replace the risk-flag-only method with the full-analysis method. The interface is the contract; everything downstream conforms.

**Contract**: `AnalysisResult analyze(String listingText)`. The old `analyzeRisks` method is removed — callers update to extract `result.riskFlags()` from the new return value.

#### 3. Mock impl expanded

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/MockAiAnalysisService.java` (modify)

**Intent**: Reuse the existing keyword-based Polish heuristics for `riskFlags` and extend with deterministic-but-realistic stub values for the rest of the schema. The mock must be a **realistic** response shape — broken UI dev cycles waste more time than the extra mock work saves.

**Contract**: Implements `analyze(String) → AnalysisResult`. Stays on `@Profile("mock")`. Returns:
- `extracted` — best-effort regex extraction of make/model/year/price/mileage when patterns match; nulls otherwise
- `equipment` — small canned list (e.g. `["klimatyzacja", "tempomat", "ABS"]`) with status derived from keyword presence
- `riskFlags` — existing logic, mapped through the new severity enum
- `sellerQuestions` — canned Polish list of 3–5 questions
- `scores` — derived from a simple deterministic formula (e.g. `risk` decreases per HIGH flag) so tests can pin exact values
- `verdict` — derived from `scores.overall` (≥70 → WORTH_CHECKING, 40–69 → NEEDS_MORE_INFO, <40 → HIGH_RISK_SKIP)
- `meta` — `provider="mock"`, model `"mock-v1"`, real latency, `Instant.now()`

#### 4. New full-analysis controller

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java` (new)

**Intent**: Surface the full analysis at the forward-looking REST path.

**Contract**: `POST /api/analyses`, request body `AnalysisRequest(@NotBlank @Size(max=20000) String listingText)`, response body `AnalysisResult` (200 OK on success). Errors flow through `GlobalExceptionHandler` per `CLAUDE.md`.

---

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisRequest.java` (new)

**Intent**: Request DTO for the new endpoint. Distinct from `RiskAnalysisRequest` to keep the legacy endpoint untouched.

**Contract**: `record AnalysisRequest(@NotBlank(message="listingText: nie może być pusty") @Size(max=20000, message="listingText: zbyt długi tekst (max 20 000 znaków)") String listingText) {}`. Same validation messages as `RiskAnalysisRequest`.

#### 5. Update existing controller test to match refactored interface

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/RiskAnalysisControllerTest.java` (modify)

**Intent**: Phase 1 renames the service method and tightens the `RiskFlag.severity` type; the test must compile against the new signatures.

**Contract**:
- Replace every `aiAnalysisService.analyzeRisks(...)` stub with `aiAnalysisService.analyze(...)`; update the stub return value to wrap flags inside an `AnalysisResult` (return from `MockAiAnalysisService` or construct a minimal `AnalysisResult` with only `riskFlags` populated for the risk-facade tests)
- Replace `new RiskFlag("NO_VIN", "HIGH", ...)` with `new RiskFlag("NO_VIN", RiskSeverity.HIGH, ...)`
- Assertions on `riskFlags[0].severity` must use `"HIGH"` as the JSON string value — Jackson serialises enum names by default, so the response value stays `"HIGH"`; only the Java source literal changes

#### 6. Legacy endpoint becomes a deprecated facade

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/RiskAnalysisController.java` (modify)

**Intent**: Keep `POST /api/analysis/risk` working temporarily so anything pointing at it still gets a risk-flag list. Mark deprecated.

**Contract**: Method calls `aiAnalysisService.analyze(...)` and returns `RiskAnalysisResponse(result.riskFlags())`. Class-level `@Deprecated` with a comment pointing at `/api/analyses`. No new functionality. To be removed once S-01 lands.

### Success Criteria

#### Automated Verification

- `./mvnw test` passes
- New record types compile with no warnings
- Existing tests for `RiskAnalysisController` still pass against the deprecated endpoint
- New controller test for `POST /api/analyses` under `mock` profile asserts the full `AnalysisResult` shape (every top-level field non-null)

#### Manual Verification

- `SPRING_PROFILES_ACTIVE=mock ./mvnw spring-boot:run` starts cleanly
- `curl -X POST -H 'Content-Type: application/json' -d '{"listingText":"BMW 2018 z VIN, bezwypadkowy, klimatyzacja"}' localhost:8080/api/analyses` returns a complete JSON `AnalysisResult` with `meta.provider="mock"`
- `curl -X POST -H 'Content-Type: application/json' -d '{"listingText":""}' localhost:8080/api/analyses` returns the standard 400 error envelope (`status`, `error`, `messages`, `timestamp`)
- `/api/analysis/risk` still returns only `{ riskFlags: [...] }` for backward compatibility

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 2: Shared prompt + parser

### Overview

Extract `AnalysisPrompt` (system prompt + few-shot example, in Polish) and `AnalysisResponseParser` (JSON → `AnalysisResult` with strict schema validation). These two classes are provider-agnostic — Bedrock and OpenRouter impls will both depend on them. Pure unit tests with fixture JSON cover them; no Spring context, no network.

### Changes Required

#### 1. Prompt builder

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisPrompt.java` (new)

**Intent**: Owns the canonical system prompt plus the locked output-schema instruction. Building the user message from the listing text. One source of truth shared by both LLM providers.

**Contract**: Spring `@Component`. Public methods:
- `String systemPrompt()` — returns the static system prompt (Polish, instructs model to extract data, identify risks, generate seller questions, score, verdict; instructs JSON-only output matching the schema; reproduces an abridged JSON schema as text within the prompt).
- `String userMessage(String listingText)` — returns `"Oceń to ogłoszenie:\n\n" + listingText`.

The system prompt **must** include the `CLAUDE.md` business rule: *"Brak danych o wypadkach oznacza nieznane, nigdy nie potwierdzenie braku wypadków."*

#### 2. Response parser

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisResponseParser.java` (new)

**Intent**: Convert raw model text → validated `AnalysisResult`. Handles a leading ```` ```json ```` fence (Claude sometimes adds these despite instructions) by stripping it before parsing. Validates score ranges, enum values, and required fields. Throws `LlmResponseSchemaException` on any violation.

**Contract**: Spring `@Component`. Constructor takes `ObjectMapper` (autowired). Public method `AnalysisResult parse(String rawModelText, String provider, String model, long latencyMs)`. Behavior:
- Strip leading/trailing ```` ``` ```` fences if present
- Parse JSON via Jackson into a private DTO
- Validate: scores in `0..100`, enum codes valid, required fields present (extracted/equipment/riskFlags/sellerQuestions/scores/verdict)
- Build and return `AnalysisResult` with the supplied `meta` fields filled
- On any failure throw `LlmResponseSchemaException` with the parse error and the offending field path

#### 3. New exceptions

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/LlmCallException.java` (new)

**Intent**: Wraps transport-level failures (timeout, 5xx, I/O). Triggers a 502 via `GlobalExceptionHandler`.

**Contract**: `RuntimeException` subclass; constructor `(String message, Throwable cause)`. Handler maps to HTTP 502 with `error: "Błąd usługi LLM"` and the cause message in `messages`.

---

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/LlmResponseSchemaException.java` (new)

**Intent**: Wraps schema-violating model output. Triggers a 502 with a more specific message.

**Contract**: `RuntimeException` subclass; constructor `(String message, String fieldPath)`. Handler maps to HTTP 502 with `error: "Niepoprawny format odpowiedzi LLM"` and `messages: [fieldPath + ": " + message]`.

#### 4. Exception handler additions

**File**: `backend/src/main/java/com/example/autoskaner_ai/common/GlobalExceptionHandler.java` (modify)

**Intent**: Surface the two new LLM exceptions through the standard error envelope from `CLAUDE.md`.

**Contract**: Add `@ExceptionHandler(LlmCallException.class)` → 502 with `error: "Błąd usługi LLM"` and `[ex.getCause().getMessage()]`. Add `@ExceptionHandler(LlmResponseSchemaException.class)` → 502 with `error: "Niepoprawny format odpowiedzi LLM"` and `[fieldPath + ": " + message]`. Existing handlers untouched.

#### 5. Parser unit tests

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/llm/AnalysisResponseParserTest.java` (new)

**Intent**: Lock the schema in tests. Every parse-failure mode has an assertion.

**Contract**: JUnit 5 + plain Jackson `ObjectMapper`. Fixture JSON files under `src/test/resources/fixtures/llm/`:
- `valid-full-response.json` — happy path; assertions on every nested field
- `valid-response-with-fence.json` — wrapped in ```` ```json ... ``` ````; parser strips and succeeds
- `invalid-score-out-of-range.json` — `scores.completeness = 150` → throws `LlmResponseSchemaException`
- `invalid-verdict-code.json` — `verdict.code = "MAYBE"` → throws
- `invalid-missing-field.json` — missing `equipment` → throws
- `invalid-malformed-json.json` — truncated/garbage → throws

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=AnalysisResponseParserTest` passes (all 6 fixtures)
- All fixture JSON files parse with strict mode (no unknown-property tolerance)
- `LlmCallException` and `LlmResponseSchemaException` produce error envelopes that match `CLAUDE.md` shape (verified with a `MockMvc` test invoking the handler directly)

#### Manual Verification

- Reading `AnalysisPrompt.systemPrompt()` aloud, the schema instruction is unambiguous to a fresh reader

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 3: Bedrock provider

### Overview

Implement `BedrockClaudeAnalysisService` on profile `bedrock`. Rename existing `LlmAnalysisService.java` to this new name and update the profile name from `llm` to `bedrock`. Use AWS SDK v2 `BedrockRuntimeClient` with the Converse API. One retry on transient errors; fail-fast on schema violations. 30s per-call timeout.

### Changes Required

#### 1. Maven dependency

**File**: `backend/pom.xml` (modify)

**Intent**: Add the Bedrock runtime SDK.

**Contract**: Add `software.amazon.awssdk:bedrockruntime` (latest stable 2.x release; verify on Maven Central). Pull `software.amazon.awssdk:bom` if multiple AWS SDK modules will be added later — for now a single direct dep is fine.

#### 2. Delete legacy stub

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/LlmAnalysisService.java` (delete)

**Intent**: Replaced by the new file below; keeping the stub around invites confusion.

#### 3. Bedrock implementation

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/BedrockClaudeAnalysisService.java` (new)

**Intent**: Production default LLM client. Calls Bedrock's Converse API with the system prompt + listing message; passes raw response text to the parser.

**Contract**: Spring `@Service` on `@Profile("bedrock")`. Constructor injects `AnalysisPrompt`, `AnalysisResponseParser`, `BedrockRuntimeClient` (configured by `BedrockConfig` below). Reads `${llm.bedrock.model-id}` from properties. Implements `AiAnalysisService.analyze`:
1. Record `t0 = System.nanoTime()`
2. Build `ConverseRequest` with `modelId`, `system: [systemPrompt()]`, `messages: [user: userMessage(listingText)]`, `inferenceConfig.maxTokens=4096`, `temperature=0.2`
3. Try the call with 30s timeout (configured at client level). On `ThrottlingException`, `ServiceUnavailableException`, `IOException`, or any timeout: retry once. On second failure: throw `LlmCallException`.
4. On success, extract `outputMessage.content[0].text` and pass to `parser.parse(text, "bedrock", modelId, latencyMs)`
5. Return parsed `AnalysisResult`

#### 4. Bedrock config

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/BedrockConfig.java` (new)

**Intent**: Build a single `BedrockRuntimeClient` bean with the right region and timeout. Default credential chain (env, profile, instance role) — works for both AWS SSO locally and IAM keys in Render prod with no code change.

**Contract**: `@Configuration` on `@Profile("bedrock")`. Provides one `@Bean BedrockRuntimeClient` configured with `Region.of(awsRegion)` from `${llm.bedrock.region:eu-central-1}` and a 30s API call timeout. Uses `DefaultCredentialsProvider.create()`.

#### 5. Profile-specific properties

**File**: `backend/src/main/resources/application.properties` (existing — no changes)

**Intent**: This file already exists and already sets `spring.profiles.active=${SPRING_PROFILES_ACTIVE:mock}` and `server.port=${PORT:10000}` (10000 is Render's required default). Do NOT recreate or modify this file — the `server.port` default and `frontend.url` property must remain as-is.

---

**File**: `backend/src/main/resources/application-bedrock.properties` (new)

**Intent**: Bedrock-specific settings.

**Contract**:
```
llm.bedrock.region=${AWS_REGION:eu-central-1}
llm.bedrock.model-id=${BEDROCK_MODEL_ID:eu.anthropic.claude-haiku-4-5-20251001-v1:0}
```

#### 6. Bedrock unit test

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/llm/BedrockClaudeAnalysisServiceTest.java` (new)

**Intent**: Verify retry-on-transient and fail-fast-on-schema behavior with a mocked `BedrockRuntimeClient`. No network.

**Contract**: JUnit 5 + Mockito. Mock the client; mock `AnalysisResponseParser`. Cases:
- Happy path → calls client once, returns parsed result, `meta.provider="bedrock"`
- First call throws `ThrottlingException`, second succeeds → result returned
- Both calls throw `ThrottlingException` → throws `LlmCallException`
- Parser throws `LlmResponseSchemaException` → propagates immediately, no retry

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=BedrockClaudeAnalysisServiceTest` passes (4 cases)
- `./mvnw spring-boot:run -Dspring-boot.run.profiles=bedrock` starts without crashing (will fail at first request without credentials, which is correct)
- `./mvnw test` global pass — no Spring context tries to load `bedrock` without credentials in default test runs

#### Manual Verification

- With AWS SSO `aws sso login --profile przemyslawprzeworski` active and `AWS_PROFILE=przemyslawprzeworski`, `SPRING_PROFILES_ACTIVE=bedrock ./mvnw spring-boot:run` then `curl -X POST -d '{"listingText":"<real-Polish-listing>"}' localhost:8080/api/analyses` returns a parsed `AnalysisResult` with `meta.provider="bedrock"` and `meta.model="eu.anthropic.claude-haiku-4-5-20251001-v1:0"`
- Setting `BEDROCK_MODEL_ID=eu.anthropic.claude-sonnet-4-6` in env and restarting → next call uses Sonnet (confirmed in `meta.model`)
- Logs show `provider`, `model`, `latencyMs`, `inputTokens`, `outputTokens` per call

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 4: OpenRouter provider

### Overview

Implement `OpenRouterAnalysisService` on profile `openrouter`. Single OpenAI-compatible client built on Spring's `RestClient` (no extra deps). Same retry/fail-fast contract as Bedrock. Default model `meta-llama/llama-3.3-70b-instruct:free`; override via `OPENROUTER_MODEL`.

### Changes Required

#### 1. OpenRouter implementation

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/OpenRouterAnalysisService.java` (new)

**Intent**: Experimentation backend. Calls OpenRouter's `/v1/chat/completions` (OpenAI-compatible) with the same system prompt + user message; passes raw response to the parser.

**Contract**: `@Service` on `@Profile("openrouter")`. Constructor takes `AnalysisPrompt`, `AnalysisResponseParser`, `@Qualifier("openRouterBuilder") RestClient.Builder openRouterBuilder` (configured by `OpenRouterConfig`), and reads `${llm.openrouter.model}`. Calls `openRouterBuilder.build()` once in the constructor to obtain the `RestClient` used for all calls. Implements `analyze`:
1. Build OpenAI-compatible request body: `{ model, messages: [{role:"system", content: systemPrompt}, {role:"user", content: userMessage}], temperature: 0.2, max_tokens: 4096 }`
2. POST `/chat/completions`. 30s timeout. Single retry on 5xx, 429, IOException, timeout.
3. Extract `choices[0].message.content` and pass to `parser.parse(text, "openrouter", model, latencyMs)`
4. Schema violations from parser propagate immediately (no retry)

#### 2. OpenRouter config

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/OpenRouterConfig.java` (new)

**Intent**: Build a `RestClient` bean preconfigured with base URL, auth header, and timeouts.

**Contract**: `@Configuration` on `@Profile("openrouter")`. Provides `@Bean(name="openRouterBuilder") RestClient.Builder` (NOT a built `RestClient`) configured with:
- `baseUrl(${llm.openrouter.base-url})`
- Default header `Authorization: Bearer ${OPENROUTER_API_KEY}` — fail loudly at startup if `OPENROUTER_API_KEY` is unset (use `@Value` with no default and Spring will refuse to create the bean)
- Default header `HTTP-Referer: https://autoskaner-ai.pages.dev` (OpenRouter requirement for free tier)
- Default header `X-Title: AutoSkanerAI` (OpenRouter analytics)
- `requestFactory` configured for 30s connect/read timeout

**Why Builder not RestClient**: `spring-test:7.0.7` (shipped with Spring Boot 4) only provides `MockRestServiceServer.bindTo(RestClient.Builder)` — there is no `bindTo(RestClient)` overload. Exposing the Builder allows tests to intercept HTTP calls via `MockRestServiceServer.bindTo(builder)` before the service calls `.build()`. `OpenRouterAnalysisService` receives the `@Qualifier("openRouterBuilder") RestClient.Builder` and calls `.build()` once in its constructor.

#### 3. OpenRouter properties

**File**: `backend/src/main/resources/application-openrouter.properties` (new)

**Intent**: OpenRouter-specific settings.

**Contract**:
```
llm.openrouter.base-url=https://openrouter.ai/api/v1
llm.openrouter.api-key=${OPENROUTER_API_KEY}
llm.openrouter.model=${OPENROUTER_MODEL:meta-llama/llama-3.3-70b-instruct:free}
```

#### 4. OpenRouter unit test

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/llm/OpenRouterAnalysisServiceTest.java` (new)

**Intent**: Same coverage shape as the Bedrock test, using `MockRestServiceServer` against the `RestClient`.

**Contract**: JUnit 5 + `MockRestServiceServer`. Bind to the `RestClient.Builder` before constructing the service:
```java
RestClient.Builder builder = RestClient.builder();
MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
OpenRouterAnalysisService svc = new OpenRouterAnalysisService(prompt, parser, builder, model);
```
Cases:
- Happy path → one POST, parsed result, `meta.provider="openrouter"`
- First POST returns 503, second returns 200 → result returned
- Both POSTs return 503 → throws `LlmCallException`
- POST returns 200 with non-JSON body → parser throws `LlmResponseSchemaException`, no retry

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=OpenRouterAnalysisServiceTest` passes (4 cases)
- `./mvnw spring-boot:run -Dspring-boot.run.profiles=openrouter` fails at startup if `OPENROUTER_API_KEY` is unset (proves we're not silently swallowing missing config)

#### Manual Verification

- With `OPENROUTER_API_KEY` set in `.env`, `SPRING_PROFILES_ACTIVE=openrouter ./mvnw spring-boot:run`, then `curl -X POST -d '{"listingText":"<real-Polish-listing>"}' localhost:8080/api/analyses` returns a parsed `AnalysisResult` with `meta.provider="openrouter"` and `meta.model="meta-llama/llama-3.3-70b-instruct:free"`
- Changing `OPENROUTER_MODEL=deepseek/deepseek-chat-v3:free` in env and restarting → next call uses DeepSeek (confirmed in `meta.model`)

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 5: Live tests, observability, docs

### Overview

Add `@Tag("live-llm")` integration tests that hit the real Bedrock and OpenRouter APIs (skipped when credentials are absent). Add structured logging for every LLM call. Update `CLAUDE.md`: profile name change (`llm` → `bedrock`), new `openrouter` profile, new `/api/analyses` endpoint, locked schema reference.

### Changes Required

#### 1. Live integration tests

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/llm/BedrockLiveTest.java` (new)

**Intent**: Confirm the real Bedrock pipeline produces a parseable response. Skipped automatically when AWS credentials are absent.

**Contract**: JUnit 5 with `@Tag("live-llm")`. `@EnabledIfEnvironmentVariable(named="AWS_PROFILE", matches=".+")` OR `@EnabledIfEnvironmentVariable(named="AWS_ACCESS_KEY_ID", matches=".+")` (compose with `@EnabledIf` if both work). Loads the full Spring context with `@ActiveProfiles("bedrock")`. Sends one canned Polish listing fixture; asserts result is non-null and `verdict.code` is one of the three enum values, and `riskFlags.size() ≥ 0`.

---

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/llm/OpenRouterLiveTest.java` (new)

**Intent**: Same as Bedrock, but for OpenRouter. Skipped when `OPENROUTER_API_KEY` is absent.

**Contract**: `@Tag("live-llm")` + `@EnabledIfEnvironmentVariable(named="OPENROUTER_API_KEY", matches=".+")`. `@ActiveProfiles("openrouter")`. Same listing fixture, same assertions. Use `OPENROUTER_TEST_MODEL` env var if set; otherwise default model from properties.

#### 2. Maven Surefire excludes live tests by default

**File**: `backend/pom.xml` (modify)

**Intent**: Default `mvn test` skips `live-llm` so CI without credentials passes; opt-in via `-Dgroups=live-llm`.

**Contract**: Configure `maven-surefire-plugin` with `<excludedGroups>live-llm</excludedGroups>` and document the opt-in command in `CLAUDE.md`.

#### 3. Structured logging

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/BedrockClaudeAnalysisService.java` (modify)

**Intent**: One INFO log line per successful call with structured fields; one WARN with cause on retry; one ERROR on final failure.

**Contract**: SLF4J. Format: `LLM call provider={} model={} latencyMs={} inputTokens={} outputTokens={} listingChars={}` (use Bedrock's `usage` block when present). On retry: `LLM call retry provider={} model={} cause={}`. On final failure: `LLM call failed provider={} model={} cause={}`.

---

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/OpenRouterAnalysisService.java` (modify)

**Intent**: Same logging contract as Bedrock for parity.

**Contract**: As above; OpenRouter's `usage` field maps `prompt_tokens` → `inputTokens`, `completion_tokens` → `outputTokens`.

#### 4. CLAUDE.md updates

**File**: `CLAUDE.md` (modify)

**Intent**: Reflect the profile rename, the new endpoint, and the locked schema. Other agents reading the file should not see the stale `llm` profile name.

**Contract**: In `## AI service pattern` section:
- Replace `LlmAnalysisService` with `BedrockClaudeAnalysisService` and `OpenRouterAnalysisService`
- Replace profile name `llm` with `bedrock`; add `openrouter`
- Add a new `## API endpoints` subsection naming `POST /api/analyses` as the canonical endpoint and noting `/api/analysis/risk` as deprecated
- Add a `## Analysis output schema` subsection linking to `context/changes/llm-analysis-wiring/plan.md` for the locked schema (single source of truth until S-01 documents it elsewhere)
- Add a one-liner: `./mvnw test -Dgroups=live-llm` runs the live integration tests when credentials are present

#### 5. Update tasks-github.md and tasks-linear.md status

**File**: `context/foundation/tasks-github.md`, `context/foundation/tasks-linear.md` (modify)

**Intent**: After F-01 ships, S-01's prerequisites are met. Relabel status references; the `/10x-archive` skill will flip the roadmap row to `done` as part of the merge — this plan does not do that itself.

**Contract**: Defer this to merge time — explicitly NOT done in this phase. (Listed here so the implementer knows it's expected post-merge, not forgotten.)

### Success Criteria

#### Automated Verification

- `./mvnw test` excludes `live-llm` by default; passes
- `./mvnw test -Dgroups=live-llm` runs the live tests when credentials are present; passes
- `./mvnw test -Dgroups=live-llm` skips live tests with a clear "no credentials" message when env vars are missing — does not fail the build

#### Manual Verification

- Logs from a successful `bedrock` call show all structured fields (`provider`, `model`, `latencyMs`, `inputTokens`, `outputTokens`)
- Forcing a retry by temporarily setting an invalid `BEDROCK_MODEL_ID` produces the WARN retry log followed by the ERROR final-failure log
- Reading `CLAUDE.md` from scratch, the profile names and endpoints are coherent — no stale `llm` references remain
- A fresh test against three real Otomoto/OLX listings via `mock`, `bedrock`, and `openrouter` produces three structurally-valid `AnalysisResult` payloads (the *quality* of the LLM output is S-01's concern, not F-01's — F-01 only proves the wiring works)

**Implementation Note**: After completing this phase and all automated verification passes, pause for the final review.

---

## Testing Strategy

### Unit Tests

- `AnalysisResponseParser` — fixture-based; covers happy path, fence stripping, score range, enum validation, missing fields, malformed JSON
- `BedrockClaudeAnalysisService` — Mockito; covers happy path, retry-on-transient, double-failure, fail-fast on schema
- `OpenRouterAnalysisService` — `MockRestServiceServer`; same coverage shape
- `MockAiAnalysisService` — deterministic; pin one fixture listing → exact `AnalysisResult` (catches accidental mock drift)

### Integration Tests

- `AnalysisController` under `@ActiveProfiles("mock")` — full Spring context, real `MockAiAnalysisService`, asserts response shape and validation error envelope
- `BedrockLiveTest` and `OpenRouterLiveTest` — `@Tag("live-llm")`, gated on credential env vars

### Manual Testing Steps

1. `SPRING_PROFILES_ACTIVE=mock ./mvnw spring-boot:run` → curl `POST /api/analyses` with a listing → full `AnalysisResult` with `meta.provider="mock"`
2. Empty `listingText` → 400 with `{ status, error, messages: ["listingText: nie może być pusty"], timestamp }`
3. `SPRING_PROFILES_ACTIVE=bedrock` with AWS SSO active → real listing → `meta.provider="bedrock"`, `meta.model=<haiku-id>`, plausible Polish output
4. Switch `BEDROCK_MODEL_ID` to Sonnet 4.6, restart → response shows new model in `meta`
5. `SPRING_PROFILES_ACTIVE=openrouter` with `OPENROUTER_API_KEY` set → same listing → `meta.provider="openrouter"`, `meta.model=<llama-free>`, plausible output
6. Switch `OPENROUTER_MODEL` to a different free model → response shows new model
7. `/api/analysis/risk` legacy path under any profile returns just the risk-flag list (no breaking change)

## Performance Considerations

- Single-shot, request/response — no streaming for MVP. Acceptable per the PRD's "few seconds" target.
- 30s timeout per call. If the user holds the request, the controller blocks; that's fine for a 3-week MVP. Async + SSE is a future optimization tracked in S-01 unknowns or post-MVP.
- One retry doubles worst-case latency to 60s on transient failure. The browser default fetch timeout is typically much higher; UI can show its own progress message.
- Token counts logged per call so the cost-per-listing claim ($15 per 1k Haiku) is verifiable from production logs, not theoretical.

## Migration Notes

- Profile rename `llm` → `bedrock` requires updating any local `.env` files setting `SPRING_PROFILES_ACTIVE=llm`. No deployed environment uses `llm` yet (the stub never worked in production), so no runtime breakage.
- The legacy `POST /api/analysis/risk` endpoint stays live until S-01 ships, then can be removed cleanly.
- No data migration (no DB yet).

## References

- Roadmap: `context/foundation/roadmap.md` — F-01 entry
- Change identity: `context/changes/llm-analysis-wiring/change.md`
- Q-01 decision: GitHub #7 (closed), Linear AUT-11 (Done)
- API error shape: `CLAUDE.md` § "API error shape"
- Existing AI service pattern: `CLAUDE.md` § "AI service pattern" (to be updated in Phase 5)
- AWS Bedrock Converse API: https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html
- OpenRouter chat completions: https://openrouter.ai/docs/api-reference/chat-completion

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Schema + interface contract

#### Automated

- [x] 1.1 `./mvnw test` passes — fdd4553
- [x] 1.2 New record types compile with no warnings — fdd4553
- [x] 1.3 Existing tests for `RiskAnalysisController` still pass against the deprecated endpoint — fdd4553
- [x] 1.4 New controller test for `POST /api/analyses` under `mock` profile asserts the full `AnalysisResult` shape — fdd4553

#### Manual

- [x] 1.5 `SPRING_PROFILES_ACTIVE=mock ./mvnw spring-boot:run` starts cleanly — fdd4553
- [x] 1.6 `curl POST /api/analyses` with valid body returns a complete `AnalysisResult` with `meta.provider="mock"` — fdd4553
- [x] 1.7 `curl POST /api/analyses` with empty body returns the standard 400 error envelope — fdd4553
- [x] 1.8 `/api/analysis/risk` still returns only `{ riskFlags: [...] }` for backward compatibility — fdd4553

### Phase 2: Shared prompt + parser

#### Automated

- [x] 2.1 `./mvnw test -Dtest=AnalysisResponseParserTest` passes (all 6 fixtures)
- [x] 2.2 All fixture JSON files parse with strict mode
- [x] 2.3 New LLM exceptions produce error envelopes matching `CLAUDE.md` shape

#### Manual

- [x] 2.4 Reading `AnalysisPrompt.systemPrompt()` aloud, the schema instruction is unambiguous

### Phase 3: Bedrock provider

#### Automated

- [ ] 3.1 `./mvnw test -Dtest=BedrockClaudeAnalysisServiceTest` passes (4 cases)
- [ ] 3.2 `./mvnw spring-boot:run -Dspring-boot.run.profiles=bedrock` starts without crashing
- [ ] 3.3 `./mvnw test` global pass — no Spring context tries to load `bedrock` without credentials in default test runs

#### Manual

- [ ] 3.4 With AWS SSO active, `bedrock` profile + curl returns a parsed `AnalysisResult` with `meta.provider="bedrock"`, `meta.model=<haiku-id>`
- [ ] 3.5 Setting `BEDROCK_MODEL_ID=eu.anthropic.claude-sonnet-4-6` swaps model — confirmed in `meta.model`
- [ ] 3.6 Logs show `provider`, `model`, `latencyMs`, `inputTokens`, `outputTokens` per call

### Phase 4: OpenRouter provider

#### Automated

- [ ] 4.1 `./mvnw test -Dtest=OpenRouterAnalysisServiceTest` passes (4 cases)
- [ ] 4.2 `./mvnw spring-boot:run -Dspring-boot.run.profiles=openrouter` fails at startup if `OPENROUTER_API_KEY` is unset

#### Manual

- [ ] 4.3 With `OPENROUTER_API_KEY` set, `openrouter` profile + curl returns a parsed `AnalysisResult` with `meta.provider="openrouter"`, `meta.model=<llama-free>`
- [ ] 4.4 Changing `OPENROUTER_MODEL` swaps model — confirmed in `meta.model`

### Phase 5: Live tests, observability, docs

#### Automated

- [ ] 5.1 `./mvnw test` excludes `live-llm` by default and passes
- [ ] 5.2 `./mvnw test -Dgroups=live-llm` runs live tests when credentials are present
- [ ] 5.3 `./mvnw test -Dgroups=live-llm` skips live tests cleanly when env vars are missing

#### Manual

- [ ] 5.4 Logs from a successful `bedrock` call show all structured fields
- [ ] 5.5 Forcing a retry produces the WARN retry log followed by the ERROR final-failure log
- [ ] 5.6 `CLAUDE.md` re-read from scratch is coherent — no stale `llm` references
- [ ] 5.7 Three real Polish listings tested via `mock`, `bedrock`, and `openrouter` produce three structurally-valid `AnalysisResult` payloads
