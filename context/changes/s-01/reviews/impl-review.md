<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: S-01 — Core analysis flow

- **Plan**: `context/changes/s-01/plan.md`
- **Scope**: All 4 phases (Phase 1–4 complete + Jina Reader pivot)
- **Date**: 2026-06-01
- **Verdict**: APPROVED (with quality follow-ups)
- **Findings**: 0 critical · 4 warnings · 2 observations

## Verdicts

| Dimension | Verdict |
|---|---|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS (34 backend tests pass; frontend specs written but no test runner configured) |

## Scope summary

24/24 planned files MATCH plan intent. Jina Reader pivot (commit `e81748c`) cleanly applied — `JINA_PREFIX + URI.create()` preserves double-slash; `readTimeout` raised to 30s; browser User-Agent removed (Jina handles headers). No EXTRA, no MISSING, no unapproved DRIFT. The `ListingFetchServiceTest` Cloudflare-HTML case was substituted with an `empty_content` case post-pivot — appropriate. `AnalysisControllerTest` includes 2 EXTRA cases (blank-listingText + non-JSON body), both consistent with validation flow.

Commits in scope: `7fd2591` (p1), `8706dd9` (p2), `b77237e` (p3), `e4e740f` (p4), `2175a70` (epilogue), `e81748c` (Jina pivot).

## Findings

### F1 — GlobalExceptionHandler leaks internal messages to clients

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Security)
- **Location**: `backend/src/main/java/com/example/autoskaner_ai/common/GlobalExceptionHandler.java:32, :52`
- **Detail**: `handleNotReadable` and `handleAll` echo `ex.getMessage()` back to the client in `messages[]`. Jackson parse exceptions and arbitrary runtime errors leak class names, file paths, and stack hints — info that aids attackers and confuses end-users (the UI shows it verbatim via `mapError` → `'Błąd serwera. <jackson detail>'`).
- **Fix**: Replace `ex.getMessage()` with generic Polish strings — `"Nieprawidłowy JSON"` for `HttpMessageNotReadableException`, `"Wystąpił nieoczekiwany błąd"` for the catch-all. Log the raw cause server-side via SLF4J before returning.
- **Decision**: FIXED — sanitized both 400 (`"Nieprawidłowy JSON"`) and 500 (`"Wystąpił nieoczekiwany błąd"`); added SLF4J logger.

### F2 — AnalyzerComponent: rapid submit clicks leak intervals

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: `frontend/src/app/features/analyzer/analyzer.component.html:51-56`, `frontend/src/app/features/analyzer/analyzer.component.ts:91-98`
- **Detail**: The "Analizuj" button has no `[disabled]="loading()"` guard. A second click during a 30s analysis call starts a fresh `setInterval` before the first `stopRotation()` runs, leaking the original timer. It also fires a parallel HTTP request whose late response can clobber the first result. The button visually has no disabled feedback either.
- **Fix**: Add `[disabled]="loading()"` to the Analizuj button. As a belt-and-braces, call `this.stopRotation()` at the top of `startRotation()` so a second invocation can never leak a timer.
- **Decision**: FIXED — added `[disabled]="loading()"` and idempotent `startRotation()`.

### F3 — Non-null assertion masks unexpected null analysis

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: `frontend/src/app/features/analyzer/analyzer.component.ts:69`
- **Detail**: `this.result.set(response.analysis!)` — TypeScript non-null assertion. Backend contract says `analysis` is null only when `fetchStatus == 'url_failed'`, but if the server ever returns `'text'` or `'ok'` with a null analysis (regression, partial failure), the UI silently sets null and renders an empty result page with no error feedback.
- **Fix**: Replace with `if (response.analysis) { this.result.set(response.analysis); } else { this.error.set('Otrzymano niepełną odpowiedź serwera.'); }`.
- **Decision**: FIXED — added explicit null guard with Polish fallback error.

### F4 — URL with query/fragment is silently truncated by Jina path

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (Reliability)
- **Location**: `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java:83-89`
- **Detail**: `String jinaUrl = JINA_PREFIX + rawUrl; URI.create(jinaUrl)`. The raw URL is concatenated unencoded. If a user pastes `https://www.otomoto.pl/oferta/x?utm_source=...&id=42`, the resulting URI is parsed with the entire `?utm_source=...&id=42` attributed to the JINA URL's query, not the embedded otomoto URL's. Jina sees `https://www.otomoto.pl/oferta/x` with the query stripped — wrong listing, or an HTTP 404 from Jina. Tracking-param URLs from social sharing are common; the Toyota Corolla path-only test passed, so this is currently invisible.
- **Fix A ⭐ Recommended**: Percent-encode the rawUrl before concatenation
  - Strength: Tiny edit — `URLEncoder.encode(rawUrl, UTF_8)`. Preserves semantics across all input shapes; doesn't affect the existing successful path-only tests.
  - Tradeoff: Need to verify Jina accepts percent-encoded URLs in its path (their docs confirm yes).
  - Confidence: HIGH — standard URL-in-URL encoding pattern; Jina's public examples use both encoded and unencoded forms.
  - Blind spot: No regression test today covers a query-string URL — add one before/after the fix.
- **Fix B**: Use `UriComponentsBuilder` to set Jina's path-segment safely
  - Strength: More idiomatic Spring; handles edge cases.
  - Tradeoff: More code; need to verify the resulting URI keeps the literal `"https://"` embedded (UCB normalizes too).
  - Confidence: MEDIUM — UCB's normalization is what we worked around with `URI.create()` in the first place.
  - Blind spot: Whether UCB's path-segment escaping breaks Jina.
- **Decision**: FIXED via Fix A — `URLEncoder.encode(rawUrl, UTF_8)` before concatenation. Added `fetch_urlWithQueryString_preservesQueryInJinaCall` regression test (35/35 backend tests pass).

### F5 — ListingFetchService missing structured logging

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java`
- **Detail**: `OpenRouterAnalysisService` and `BedrockClaudeAnalysisService` both log structured fetch events (provider/model/latency/cause). `ListingFetchService` swallows failures into `FetchResult.failed()` with no log line. When prod sees `url_failed`, there's no trail of why — Jina down, SSRF blocked, timeout? Hard to operate.
- **Fix**: Add `private static final Logger log = LoggerFactory.getLogger(...)`. Log fetch attempts (host + jina URL) and outcomes (ok/reason) at INFO/WARN, matching the LLM service log shape.
- **Decision**: FIXED — added SLF4J logger; logs `Listing fetch start/ok/failed` with host, reason, latencyMs, chars matching the LLM service shape.

### F6 — Modern Angular signal patterns underused

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `frontend/src/app/features/analyzer/components/analysis-result/analysis-result.component.ts:15`, getters `verdictClass` / `visibleFlags` / `showExpandLink`
- **Detail**: Uses `@Input({ required: true })` decorator and getter-based derived state. AnalyzerComponent and the rest of the app commit to signals; Angular 21 idiomatic equivalents are `input.required<T>()` and `computed()` — the latter memoizes between change-detection cycles. Not broken — the getters recompute every tick, which is cheap here. Worth flagging so future code stays consistent.
- **Fix**: Migrate to `result = input.required<AnalysisResult>()` and convert getters to computed signals. Defer until/unless this component grows.
- **Decision**: FIXED — migrated to `input.required<AnalysisResult>()`; `verdictClass`, `visibleFlags`, `showExpandLink` are now `computed()`; template uses `result()` and getter calls; spec uses `componentRef.setInput`. Note: frontend type-check pending (no Node on this shell — user runs `ng build` from their interactive terminal).
