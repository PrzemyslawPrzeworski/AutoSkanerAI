<!-- PLAN-REVIEW-REPORT -->
# Plan Review: LLM Analysis Wiring — Implementation Plan

- **Plan**: context/changes/llm-analysis-wiring/plan.md
- **Mode**: Deep
- **Date**: 2026-05-31
- **Verdict**: REVISE → SOUND (all 4 findings fixed during triage)
- **Findings**: 4 critical, 0 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | FAIL |
| Plan Completeness | FAIL |

## Grounding

6/6 paths ✓ (application.properties exists — surfaced as F2), 4/4 symbols ✓, brief↔plan ✓

## Findings

### F1 — Phase 1 missing step: update RiskAnalysisControllerTest

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 — Success Criteria ("Existing tests still pass")
- **Detail**: Phase 1 renames `analyzeRisks()` → `analyze()` and changes `RiskFlag.severity` from `String` to `RiskSeverity` enum. `RiskAnalysisControllerTest.java:34` stubs `analyzeRisks()` and `:35` passes `"HIGH"` as a string literal — both fail to compile after the change.
- **Fix**: Added explicit sub-step §5 under Phase 1 to update `RiskAnalysisControllerTest`: stub `analyze()` instead of `analyzeRisks()`, replace `"HIGH"` with `RiskSeverity.HIGH`.
- **Decision**: FIXED

### F2 — application.properties already exists; plan's "new file" risks Render regression

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 — §5 "Profile-specific properties"
- **Detail**: `backend/src/main/resources/application.properties` already exists with `server.port=${PORT:10000}` (Render's required default) and `frontend.url` property. Plan described creating it as a new file with `${PORT:8080}`, which would break Render health checks and silently break the `FRONTEND_URL`/CORS env-var override.
- **Fix**: Changed the Phase 3 entry to mark the file as "existing — no changes needed". Only the two new profile-specific files are added.
- **Decision**: FIXED

### F3 — MockRestServiceServer can only bind to RestClient.Builder, not a built RestClient

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness + Architectural Fitness
- **Location**: Phase 4 — OpenRouterConfig + OpenRouterAnalysisServiceTest
- **Detail**: `spring-test:7.0.7` only provides `MockRestServiceServer.bindTo(RestClient.Builder)`. The plan exposed a built `RestClient` bean from `OpenRouterConfig` — the test as described would not compile. No WireMock or alternative test HTTP library in `pom.xml`.
- **Fix A applied**: `OpenRouterConfig` now exposes `@Bean(name="openRouterBuilder") RestClient.Builder`. `OpenRouterAnalysisService` accepts the builder and calls `.build()` in the constructor. Test binds `MockRestServiceServer` to the builder before constructing the service.
- **Decision**: FIXED via Fix A

### F4 — Progress Phase 3 and 4 titles don't match body headings

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: ## Progress section
- **Detail**: Body had `## Phase 3: Bedrock provider (production default)` vs Progress `### Phase 3: Bedrock provider`; same mismatch for Phase 4. `/10x-implement` does exact title matching.
- **Fix**: Trimmed body headings to drop parentheticals — now match Progress headings exactly.
- **Decision**: FIXED
