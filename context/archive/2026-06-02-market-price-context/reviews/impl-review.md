<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Market Price Context

- **Plan**: context/changes/market-price-context/plan.md
- **Scope**: All phases (1–3 of 3)
- **Date**: 2026-06-02
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical  3 warnings  4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Price regex breaks on CRLF line endings

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/market/MarketPriceFetchService.java:31
- **Detail**: Pattern `###\s*([\d\s]+)\nPLN` uses hard `\n`. If Jina returns CRLF line endings, the anchor never fires and fetchPrices() returns empty always — silently returning INSUFFICIENT_DATA. Test fixtures use Java text blocks (normalised `\n`), masking the bug. Live curl confirmed `\n` on Linux prod; Windows dev may differ.
- **Fix**: Change `\\nPLN` to `\\r?\\nPLN` in the Pattern.compile call.
  - Strength: One-character change, zero logic impact, tolerates both line-ending styles.
  - Tradeoff: None.
  - Confidence: HIGH
  - Blind spot: Jina may normalise server-side; worth confirming but fix costs nothing.
- **Decision**: FIXED — changed `\\nPLN` to `\\r?\\nPLN`

### F2 — Integer.parseInt silently drops out-of-range price tokens

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/market/MarketPriceFetchService.java:139
- **Detail**: `Integer.parseInt(raw)` on stripped digit string. Overflow throws NumberFormatException caught silently at DEBUG. Regex `[\d\s]+` also matches `0`, yielding min=0 and distorting the price range. Parse as long with sane range guard.
- **Fix**: `long v = Long.parseLong(raw); if (v >= 1_000 && v <= 10_000_000) prices.add((int) v);` — change discard log from DEBUG to WARN.
  - Strength: Eliminates overflow and zero/garbage price pollution.
  - Tradeoff: Filters cars under 1000 PLN — acceptable for Polish used-car context.
  - Confidence: HIGH
  - Blind spot: Lower bound may be wrong for edge cases, but service is used-car-only.
- **Decision**: FIXED — long parse + 1_000–10_000_000 range guard + WARN log on discard

### F3 — MockMarketPriceEnrichmentService returns FETCH_FAILED not a usable OK result

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Pattern Consistency
- **Location**: backend/src/main/java/com/example/autoskaner_ai/market/MockMarketPriceEnrichmentService.java:16
- **Detail**: Mock returns FETCH_FAILED unconditionally. MockAiAnalysisService returns fully populated data. The OK panel branch (price range + expand/collapse) can never be exercised locally or in demos. Plan specified FETCH_FAILED but the codebase convention is for mocks to return realistic success data.
- **Fix A ⭐ Recommended**: Return hard-coded OK result: `new MarketPriceContext(OK, 45_000, 55_000, 70_000, 12, "https://www.otomoto.pl/osobowe/toyota/corolla", Instant.now())`
  - Strength: Matches MockAiAnalysisService pattern; enables local dev of OK UI branch.
  - Tradeoff: Diverges from plan's stated FETCH_FAILED contract; requires updating AnalysisControllerTest assertion.
  - Confidence: HIGH
  - Blind spot: Controller test currently asserts FETCH_FAILED — must update alongside.
- **Fix B**: Keep FETCH_FAILED, add second @Profile("mock&ok-price") bean for OK-state testing.
  - Strength: Preserves stated contract; degraded-state intent is clear.
  - Tradeoff: Adds complexity; developers still can't see OK panel without openrouter.
  - Confidence: MEDIUM
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A — mock returns OK(45k/55k/70k, sampleSize=12); AnalysisControllerTest stub + assertions updated to OK

### F4 — MarketPriceFetchServiceLiveTest wired to wrong profile

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: backend/src/test/java/com/example/autoskaner_ai/market/MarketPriceFetchServiceLiveTest.java
- **Detail**: `@ActiveProfiles("mock")` means `MarketPriceFetchService` (`@Profile("!mock")`) is not registered in the Spring context. `@Autowired MarketPriceFetchService` will fail with NoSuchBeanDefinitionException at wiring time. The test is currently never run (excluded by surefire config), masking this.
- **Fix**: Change `@ActiveProfiles("mock")` to `@ActiveProfiles("openrouter")`.
- **Decision**: FIXED — changed @ActiveProfiles("mock") to @ActiveProfiles("openrouter")

### F5 — INSUFFICIENT_DATA path uses sampleSize=0, other non-OK paths use null

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/market/MarketPriceFetchService.java:76
- **Detail**: Latent inconsistency. Template check `sampleSize !== null && sampleSize < 3` won't fire on INSUFFICIENT_DATA branch today (has its own block), but if template is extended this could silently trigger the small-sample caveat.
- **Fix**: Use `null` for sampleSize on INSUFFICIENT_DATA return, or add a comment documenting the `0` as intentional.
- **Decision**: FIXED — sampleSize changed from 0 to null on INSUFFICIENT_DATA path

### F6 — Unit test mocks only HTTP method, not the target URI

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: backend/src/test/java/com/example/autoskaner_ai/market/MarketPriceFetchServiceTest.java:55
- **Detail**: `mockServer.expect(method(HttpMethod.GET))` matches any GET request. URL construction bugs (wrong slug, missing filters) would not be caught.
- **Fix**: Add `.andExpect(requestTo(containsString("r.jina.ai/https")))` to the key test cases.
- **Decision**: FIXED — added `requestTo(containsString("r.jina.ai"))` + `containsString("toyota%2Fcorolla")` to 5-price test; `containsString("r.jina.ai")` to empty-body test; fixed sampleSize null assertion in retry test
