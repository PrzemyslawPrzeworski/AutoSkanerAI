<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: CEPiK VIN Lookup

- **Plan**: context/changes/cepik-vin-lookup/plan.md
- **Scope**: All phases (2–6)
- **Date**: 2026-06-02
- **Verdict**: NEEDS ATTENTION
- **Findings**: 3 critical, 5 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING — 3 documented deviations (all intentional/benign) |
| Scope Discipline | PASS — no out-of-scope additions |
| Safety & Quality | FAIL — 1 concurrency critical, 1 trust-all TLS critical, 1 resource leak |
| Architecture | WARNING — `@Profile` guards missing on 6 beans |
| Pattern Consistency | PASS |
| Success Criteria | WARNING — 2 live-llm items (2.3, 5.4) unchecked due to env constraint |

## Findings

### F1 — HistoriaPojazduSession singleton with mutable session state

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduSession.java:16–127
- **Detail**: `HistoriaPojazduSession` is a Spring `@Component` singleton. It holds mutable instance fields `cookies` (ArrayList), `xsrfToken`, `nfWid`, and `client`. Under concurrent requests both calls hit `session.open()` simultaneously, overwriting each other's cookies/token/client — the session state is corrupted. `fetchVehicleData` / `fetchTimelineData` then race on the wrong session headers.
- **Fix A ⭐ Recommended**: Remove `@Component`; construct a `new HistoriaPojazduSession(builder)` inside `HistoriaPojazduService.lookup()` for each call.
  - Strength: Per-request session is the correct model — the plan explicitly says "The session is created fresh per lookup — no session reuse." The concurrency concern disappears entirely.
  - Tradeoff: Constructor takes the builder; `HistoriaPojazduService` needs to inject the builder, not the session. Small refactor.
  - Confidence: HIGH — plan states this intent explicitly.
  - Blind spot: None significant.
- **Fix B**: Add `synchronized` to `HistoriaPojazduService.lookup()`.
  - Strength: Zero-touch to `HistoriaPojazduSession`; safe immediately.
  - Tradeoff: Serialises all CEPiK lookups globally — throughput degrades to 1 concurrent session. Only acceptable if traffic is very low (single-user MVP).
  - Confidence: HIGH — correct as a stopgap, poor as a permanent solution.
  - Blind spot: Doesn't fix the `builder` mutation race in `open()`.
- **Decision**: FIXED via Fix A — removed @Component from HistoriaPojazduSession; HistoriaPojazduService now constructs per-request via createSession() factory method; test updated with subclass override.

### F2 — Trust-all TrustManager on api.cepik.gov.pl

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/cepik/CepikApiConfig.java:19–27
- **Detail**: `checkServerTrusted` is a no-op — every certificate is accepted. The comment says "legacy TLS cipher suites" but cipher negotiation is unrelated to certificate validation. This opens the cepik.gov.pl API to MITM: an attacker can return fabricated vehicle history data. The API is currently at 0% uptime, so risk is dormant, but this is a government vehicle data endpoint — integrity matters.
- **Fix A ⭐ Recommended**: Diagnose the actual TLS failure first. If it's a self-signed / non-standard CA, import that specific CA cert into a `KeyStore` and create a targeted `TrustManagerFactory` from it. If it's a cipher mismatch (legacy `TLS_RSA_*`), configure `SSLParameters.setCipherSuites()` on the `HttpClient` builder without disabling validation.
  - Strength: Closes the MITM risk class entirely; targets the real problem rather than papering over it.
  - Tradeoff: Requires live testing against the API to identify the actual failure mode — the API is currently unreachable in dev.
  - Confidence: MEDIUM — the exact cipher/cert issue is unconfirmed in dev; may require a `Render` test run.
  - Blind spot: If the API uses a legitimately-expired cert and the plan intentionally accepted that risk, this fix is overly strict — but that choice should be explicit, not implicit.
- **Fix B**: Add `// TODO: replace trust-all before production` comment and track the issue.
  - Strength: Zero-effort stopgap; doesn't block shipping.
  - Tradeoff: The risk persists in production. The API, if it comes back online, will be hit with disabled TLS validation.
  - Confidence: LOW — deferred risks compound.
  - Blind spot: When the API starts returning real data, the MITM window opens silently.
- **Decision**: FIXED via Fix A — replaced trust-all TrustManager with default JVM SSLContext; added comment documenting the JdkClientHttpRequestFactory deviation and instructing how to fix TLS properly if needed on Render.

### F3 — CepikApiService executor never shut down + no @Profile guard

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/cepik/CepikApiService.java:30
- **Detail**: Two independent issues in the same file and same fix scope: (1) `Executors.newVirtualThreadPerTaskExecutor()` is created at construction and never shut down via `@PreDestroy`. (2) No `@Profile("!mock")` annotation — the bean (and its executor) instantiates under the `mock` profile even though it is never called there.
- **Fix**: Add `@Profile("!mock")` to `CepikApiService` and add `@PreDestroy public void shutdown() { executor.shutdownNow(); }`.
  - Strength: Prevents resource leak; mirrors the `MockCepikService` / `RealCepikEnrichmentService` guard pattern already in the codebase.
  - Tradeoff: None — pure addition.
  - Confidence: HIGH — identical pattern used by `MockAiAnalysisService` / `BedrockClaudeAnalysisService`.
  - Blind spot: None significant.
- **Decision**: FIXED — added @Profile("!mock") and @PreDestroy shutdown() to CepikApiService.

### F4 — CepikApiService: no outer deadline on 16-voivodeship scan

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/cepik/CepikApiService.java:44–66
- **Detail**: The poll loop calls `anyOf(...).get()` (blocking) with no outer timeout. Each future has an 8s response timeout, but when all 16 time out sequentially the loop can block the calling thread for up to `16 × 8s = 128s`. The cancel-on-success logic is correct. The plan noted this worst-case and said "total wall-clock bounded by `anyOf` + 8s timeout" — the `anyOf` eliminates the serial wait, but when all futures are genuinely pending (all timing out), the loop removes completed-but-empty futures one batch at a time and keeps iterating.
- **Fix A ⭐ Recommended**: Add a single `CompletableFuture.allOf(futures.toArray(...)).orTimeout(12, TimeUnit.SECONDS)` as an outer deadline, catching `TimeoutException` to cancel and return `Optional.empty()`.
  - Strength: Bounds worst case to 12s regardless of how many voivodeships time out. Consistent with plan's "8s timeout" intent.
  - Tradeoff: None — if the API doesn't respond in 12s it never will.
  - Confidence: HIGH — performance concern is real; fix is non-breaking.
  - Blind spot: None significant.
- **Fix B**: Accept current behaviour for MVP (api.cepik.gov.pl is currently at 0% uptime so the path is never reached).
  - Strength: No code change; defers complexity.
  - Tradeoff: When API recovers, long-timeout requests will block servlet threads.
  - Confidence: LOW — the plan itself flagged this risk; deferring is a known tradeoff.
  - Blind spot: Spring MVC thread pool exhaustion is silent until it happens under load.
- **Decision**: FIXED via Fix A — added 12s outer deadline with TimeoutException handling; all remaining futures are now cancelled on timeout.

### F5 — Missing @Profile guards on all non-mock CEPiK beans

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduService.java, HistoriaPojazduSession.java, HistoriaPojazduParser.java, HistoriaPojazduConfig.java, CepikApiConfig.java
- **Detail**: Plan specified `@Profile("!mock")` on `HistoriaPojazduService`. None of the five listed beans carry that annotation. Under `mock` profile they all instantiate: config beans create HTTP clients and SSL contexts; session/parser/service beans are live singletons. The trust-all SSL context (F2) is created at every app start even in mock/test mode.
- **Fix**: Add `@Profile("!mock")` to `CepikApiConfig`, `CepikApiService` (covered by F3), `HistoriaPojazduConfig`, `HistoriaPojazduSession`, `HistoriaPojazduParser`, `HistoriaPojazduService`.
  - Strength: Matches the plan's stated intent; consistent with `MockAiAnalysisService`'s `@Profile("mock")` guard.
  - Tradeoff: If a future non-mock profile needs to run without live CEPiK (e.g. integration tests with profile `test`), the guard needs refinement — but for now `!mock` is the correct split.
  - Confidence: HIGH — straightforward annotation addition.
  - Blind spot: Need to verify `AutoskanerAiApplicationTests` uses `@ActiveProfiles("mock")` — it does, so this won't break the application context test.
- **Decision**: FIXED — added @Profile("!mock") to HistoriaPojazduService, HistoriaPojazduParser, HistoriaPojazduConfig, CepikApiConfig; also fixed live test profiles to use "openrouter" instead of "mock" so beans are actually wired.

### F6 — HistoriaPojazduSession builder mutation is not thread-safe

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduSession.java:47–63
- **Detail**: `open()` calls `builder.defaultHeader(...)` twice to rebuild `this.client`. `RestClient.Builder.defaultHeader()` modifies the builder's internal mutable header list. If two threads call `open()` simultaneously they corrupt the builder's state (independent of the mutable instance-field race in F1). This is a secondary consequence of the singleton design — fixed by F1's recommended approach.
- **Fix**: Resolved by F1 Fix A (per-request construction). If F1 Fix B (synchronized) is chosen instead, also create a fresh `RestClient.Builder` copy inside `open()` rather than mutating `this.builder`.
  - Strength: Eliminates builder mutation race.
  - Tradeoff: Slightly more object creation per request — negligible.
  - Confidence: HIGH.
  - Blind spot: None significant.
- **Decision**: SKIPPED — resolved by F1 (per-request construction eliminates the shared mutable state entirely).

### F7 — Registration plate value from LLM passed to moj.gov.pl without format validation

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduSession.java:77
- **Detail**: The `plate` parameter in `fetchVehicleData` comes from `ExtractedData.registrationPlate()` — raw LLM output. The VIN is normalised by `VinValidator`; the plate has no equivalent validation before it reaches the `moj.gov.pl` API body. An unexpected value (oversized, containing special chars) could trigger unexpected API behaviour.
- **Fix**: Add a plate format guard in `RealCepikEnrichmentService.enrich()` — validate against `[A-Z0-9 ]{2,10}` (or the Polish plate regex from `MockAiAnalysisService`) before calling `historiaPojazduService.lookup()`. Return `MISSING_INPUTS` if invalid.
  - Strength: Consistent with the VIN normalisation pattern already in `VinValidator`.
  - Tradeoff: May produce false negatives for unusual (foreign) plate formats — acceptable for MVP targeting Polish listings.
  - Confidence: HIGH.
  - Blind spot: None significant.
- **Decision**: FIXED — added PLATE_PATTERN validation in RealCepikEnrichmentService.enrich() before calling historiaPojazduService.lookup().

### F8 — Plan adherence: CepikApiConfig uses JdkClientHttpRequestFactory instead of HttpComponentsClientHttpRequestFactory

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: backend/src/main/java/com/example/autoskaner_ai/cepik/CepikApiConfig.java:1
- **Detail**: Plan specified Apache HttpClient 5 / `HttpComponentsClientHttpRequestFactory` for legacy TLS cipher support. Implementation uses `JdkClientHttpRequestFactory` + `java.net.http.HttpClient`. The deviation was necessary (httpclient5 5.3.x is not in local Maven cache due to Zscaler network restrictions) but was not formally documented in the plan.
- **Fix**: Add a one-line comment in `CepikApiConfig.java` noting the substitution and why (e.g. `// Using JdkClientHttpRequestFactory: httpclient5 5.3.x unavailable in dev env (Zscaler); functionally equivalent for custom SSLContext`).
  - Strength: Creates an audit trail for the deviation without any functional change.
  - Tradeoff: None.
  - Confidence: HIGH.
  - Blind spot: None significant.
- **Decision**: SKIPPED — resolved by F2 (comment already added to CepikApiConfig.java documenting the deviation and why).

### F9 — 2 live-llm test items remain unchecked in Progress

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/cepik-vin-lookup/plan.md: items 2.3 and 5.4
- **Detail**: `- [ ] 2.3 ./mvnw test -Dgroups=live-llm` and `- [ ] 5.4 ./mvnw test -Dgroups=live-llm` are unchecked. The surefire `<excludedGroups>live-llm</excludedGroups>` config in pom.xml is hardcoded and cannot be overridden via `-Dgroups` without also unsetting the exclusion. The live tests themselves exist and pass when run directly (verified during Phase 3/4 implementation).
- **Fix**: Either mark these as accepted environment constraints in the Progress section with a note, or add a Maven profile to pom.xml (`<profile id="live-tests">`) that removes the `<excludedGroups>` config and document `./mvnw test -Plive-tests`.
  - Strength: Either option closes the open checkbox cleanly.
  - Tradeoff: Adding a Maven profile is a small pom.xml change; the acceptance note is zero-effort.
  - Confidence: HIGH.
  - Blind spot: None significant.
- **Decision**: FIXED — added live-tests Maven profile to pom.xml; updated plan.md items 2.3 and 5.4 to reference ./mvnw test -Plive-tests.

### F10 — AnalysisController: CEPiK enrichment blocks servlet thread

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java:55
- **Detail**: `cepikEnrichmentService.enrich()` runs synchronously inside `buildResponse()`. The `RealCepikEnrichmentService` path can make up to 18 blocking HTTP calls (16 CEPiK voivodeship + 2 HistoriaPojazdu). Plan's "Performance Considerations" section acknowledged this and deferred async handling to post-MVP. Noted here for visibility — not a bug, intentional deferral.
- **Fix**: No action required for MVP. When async handling is prioritised post-MVP, wrap `enrich()` in a `CompletableFuture` and join with the analysis call.
- **Decision**: SKIPPED — intentional MVP deferral per plan's Performance Considerations section.
