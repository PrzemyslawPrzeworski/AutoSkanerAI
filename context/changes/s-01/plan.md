# S-01: Core Analysis Flow — Implementation Plan

## Overview

Build the first end-to-end user-facing feature: a single-page Angular app where the user pastes a URL or listing text, waits for an AI analysis, and sees a structured result (extracted data, equipment, risk flags, seller questions, scores, verdict). The backend gains URL fetching with SSRF protection and HTML stripping; the frontend is built from scratch (PrimeNG 19, HttpClient, routing, AnalyzerComponent, AnalysisResultComponent).

## Current State Analysis

**Backend** — `POST /api/analyses` is live (F-01). Takes `{ listingText }`, returns `AnalysisResult` directly. `AnalysisRequest` has `@NotBlank listingText` only — no URL field. CORS allows `localhost:4200`. `GlobalExceptionHandler` collects only `FieldError`s, missing class-level constraint messages.

**Frontend** — Angular 21 bare scaffold: empty `app.routes.ts`, no components, no services, no UI library, `HttpClient` not provided. `environment.ts` has `apiUrl: 'http://localhost:10000'`. No devServer proxy configured.

**URL fetching** — All three major Polish portals (Otomoto, OLX, Allegro) run Cloudflare Bot Management. Fetching will succeed on a residential dev IP; on Render's datacenter IPs it will almost always fail with 403 or a JS challenge page. The correct approach is real fetch with graceful auto-fallback to text paste — not a stub.

### Key Discoveries

- `AnalysisController` returns `AnalysisResult` directly; Phase 1 wraps it in `AnalysisResponse` — existing `AnalysisControllerTest` must be updated.
- `GlobalExceptionHandler.handleValidation` collects only `FieldError`s; class-level `@AssertTrue` produces `ObjectError` — handler must include both.
- `MockRestServiceServer.bindTo(builder)` pattern (used in OpenRouter tests) requires the service to call `builder.build()` *after* mock binding — `ListingFetchService` must be structured the same way as `OpenRouterAnalysisService`: accept `RestClient.Builder`, call `.build()` once in constructor; `ListingFetchConfig` provides the pre-configured bean.
- `environment.ts` `apiUrl` must change to `''` (empty string) so Angular uses relative paths proxied in dev, and the prod environment keeps the absolute Render URL.

## Desired End State

User visits `localhost:4200` (dev) or `https://autoskaner-ai.pages.dev` (prod), pastes a URL or listing text, clicks Analizuj, waits through a skeleton loading state, and sees: verdict hero card, category score progress bars, extracted data table, risk flags with severity badges, equipment grid, seller questions list, and analysis meta footer. If URL fetch fails, an inline banner auto-appears and the text paste area is highlighted. Errors (validation, LLM timeout) show as inline messages below the submit button. "Nowe ogłoszenie" resets everything.

Verification: `./mvnw test` passes; `ng build` passes with zero type errors; `curl POST /api/analyses` with `listingText` returns `{ fetchStatus: "text", analysis: {...} }`; full UI flow works end-to-end under `mock` profile.

## What We're NOT Doing

- No Playwright, headless Chrome, or paid scraping API — URL fetch will fail on Render; text paste is the fallback
- No navigation, routes beyond `/` — S-01 is one page
- No auth, guards, or interceptors — F-03 territory
- No persistence — save/view/delete is S-03
- No manual field entry form — FR-003 is S-02
- No Cloudflare Pages `/api` proxy configuration — Angular calls Render directly in production via `environment.apiUrl`
- No URL domain whitelist — SSRF protection blocks private IP ranges; any http/https URL is accepted

## Implementation Approach

Four phases: backend URL fetch wiring → Angular foundation (packages, proxy, service) → AnalyzerComponent (form, loading, fallback, errors) → AnalysisResultComponent (all seven sections). Each phase is independently buildable and testable.

The `AnalysisResponse` wrapper (new in Phase 1) is the contract between backend and frontend. It carries `fetchStatus` (`ok` / `url_failed` / `text`), `fetchFailureReason` (null or a short code), and `analysis` (the full `AnalysisResult`, null on `url_failed`). This keeps the `AiAnalysisService.analyze(String)` interface unchanged.

## Critical Implementation Details

**GlobalExceptionHandler must include ObjectErrors.** The class-level `@AssertTrue` on `AnalysisRequest` produces an `ObjectError`, not a `FieldError`. Update `handleValidation` to stream both: `Stream.concat(bindingResult.getFieldErrors().stream().map(FieldError::getDefaultMessage), bindingResult.getGlobalErrors().stream().map(ObjectError::getDefaultMessage))`.

**`ListingFetchConfig` mirrors `OpenRouterConfig` pattern.** Provides `@Bean("listingFetchBuilder") RestClient.Builder` pre-configured with 5 s connect / 10 s read `SimpleClientHttpRequestFactory` and browser-like User-Agent/Accept headers. `ListingFetchService` constructor takes `@Qualifier("listingFetchBuilder") RestClient.Builder`, calls `.build()` once. Tests bypass the config bean, pass a plain `RestClient.builder()` bound to `MockRestServiceServer`.

**Cloudflare challenge-page detection.** The fetch may return HTTP 200 but with a Cloudflare challenge page (`<title>Just a moment...</title>` or body contains `cf-browser-verification`). This is not parseable listing content — detect it after Jsoup parse and return `FetchResult.failed("blocked")`.

**`environment.ts` apiUrl change.** Change dev `apiUrl` from `'http://localhost:10000'` to `''` so `AnalysisService` builds relative URLs (`/api/analyses`) that are proxied by the devServer. Production `environment.prod.ts` keeps `'https://autoskanerai.onrender.com'`.

**`AnalysisControllerTest` update.** The controller now returns `AnalysisResponse` wrapping `AnalysisResult`. All assertions that currently reach into `AnalysisResult` fields directly (e.g. `$.verdict.code`) must now be prefixed with `$.analysis.` (e.g. `$.analysis.verdict.code`). The `standaloneSetup` constructor call gains a second `@Mock ListingFetchService` argument.

---

## Phase 1: Backend — URL fetch and API response wrapper

### Overview

Add Jsoup, implement `ListingFetchService` (fetch + SSRF + HTML strip), introduce `FetchResult` and `AnalysisResponse` records, extend `AnalysisRequest` with an optional `url` field, update `AnalysisController` to route between URL and text paths, and fix `GlobalExceptionHandler` to emit class-level constraint messages. Update existing controller test for the new response shape.

### Changes Required

#### 1. Jsoup dependency

**File**: `backend/pom.xml`

**Intent**: Add Jsoup for server-side HTML-to-text stripping. No Spring Boot BOM entry exists so version is explicit.

**Contract**: Add `<dependency><groupId>org.jsoup</groupId><artifactId>jsoup</artifactId><version>1.18.3</version></dependency>` in the `<dependencies>` block.

---

#### 2. FetchResult record

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/FetchResult.java` (new)

**Intent**: Value object returned by `ListingFetchService.fetch()`. Carries the outcome so the controller can decide whether to proceed with analysis or return a failure wrapper without throwing.

**Contract**: `record FetchResult(String status, String reason, String text)` with static factories `ok(String text)` → `("ok", null, text)`, `failed(String reason)` → `("url_failed", reason, null)`, and `boolean isOk()`.

---

#### 3. AnalysisResponse record

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisResponse.java` (new)

**Intent**: Wraps `AnalysisResult` with URL fetch metadata so the frontend can distinguish a text-analysis success from a URL-fetch failure without inspecting HTTP status codes.

**Contract**: `record AnalysisResponse(String fetchStatus, String fetchFailureReason, AnalysisResult analysis)` with static factories:
- `ok(AnalysisResult)` → `("ok", null, result)`
- `text(AnalysisResult)` → `("text", null, result)`
- `urlFailed(String reason)` → `("url_failed", reason, null)`

---

#### 4. ListingFetchConfig

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchConfig.java` (new)

**Intent**: Provide a pre-configured `RestClient.Builder` for URL fetching. Keeping configuration outside `ListingFetchService` allows tests to inject a plain builder bound to `MockRestServiceServer`.

**Contract**: `@Configuration` class (no profile restriction). `@Bean(name = "listingFetchBuilder") RestClient.Builder` configured with `SimpleClientHttpRequestFactory` (5 000 ms connect timeout, 10 000 ms read timeout), `User-Agent` header imitating a desktop Chrome browser (`Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36`), `Accept: text/html,...`, `Accept-Language: pl-PL,pl;q=0.9`.

---

#### 5. ListingFetchService

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java` (new)

**Intent**: Fetch a URL, apply SSRF protection, strip HTML to plain text with Jsoup, detect Cloudflare challenge pages, and return a `FetchResult` rather than throwing. All failure modes are captured as `FetchResult.failed(reason)` — never propagated as exceptions to the controller.

**Contract**: `@Service`. Constructor: `(@Qualifier("listingFetchBuilder") RestClient.Builder builder)` → `this.client = builder.build()`. Public method: `FetchResult fetch(String rawUrl)`. Behaviour:
1. Parse `rawUrl` to `URI`; if scheme is not `http`/`https` → return `failed("invalid_scheme")`.
2. Resolve hostname via `InetAddress.getAllByName(host)`; if any resolved address is loopback, site-local, link-local, or any-local → return `failed("ssrf_blocked")`. If `UnknownHostException` → return `failed("unknown_host")`. Wrap the call in `CompletableFuture.supplyAsync(...).get(5, TimeUnit.SECONDS)` to cap the blocking DNS lookup at 5 s; a `TimeoutException` → return `failed("timeout")`.
3. Issue `RestClient` GET. Catch `RestClientException` (timeout, connection refused, 4xx/5xx) → return `failed("blocked")` for 4xx/5xx, `failed("timeout")` for timeout-shaped messages.
4. Parse response body with `Jsoup.parse(html).text()`. If result length < 100 characters → return `failed("empty_content")`. If body contains `cf-browser-verification` or title is `Just a moment...` → return `failed("blocked")`.
5. Return `FetchResult.ok(strippedText)`.

---

#### 6. AnalysisRequest — add url field

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisRequest.java` (modify)

**Intent**: Accept either a URL or listing text. Both fields become individually optional; at least one must be present.

**Contract**: Add `@Pattern(regexp = "https?://.+", message = "url: nieprawidłowy format URL") @Size(max = 2000, message = "url: zbyt długi URL") String url`. Change `listingText` from `@NotBlank` to no not-blank constraint (keep `@Size(max = 20000, message = "listingText: zbyt długi tekst (max 20 000 znaków)")`). Add a class-level method: `@AssertTrue(message = "Wymagane jest podanie url lub listingText") boolean isInputPresent()` returning true when at least one of `url` or `listingText` is non-blank.

---

#### 7. AnalysisController — route URL vs text

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java` (modify)

**Intent**: Dispatch to URL fetch path or text path based on which field is present. Return `AnalysisResponse` in both cases so the frontend always gets the same shape.

**Contract**: Change return type to `ResponseEntity<AnalysisResponse>`. Add `ListingFetchService listingFetchService` constructor parameter. In `analyze()`: if `request.url()` is non-blank → call `listingFetchService.fetch(url)`; on `FetchResult.isOk()` → `aiAnalysisService.analyze(fetch.text())` → return `AnalysisResponse.ok(result)`; else → return `AnalysisResponse.urlFailed(fetch.reason())`. If `listingText` is non-blank → `aiAnalysisService.analyze(text)` → return `AnalysisResponse.text(result)`.

---

#### 8. GlobalExceptionHandler — include ObjectErrors

**File**: `backend/src/main/java/com/example/autoskaner_ai/common/GlobalExceptionHandler.java` (modify)

**Intent**: Class-level `@AssertTrue` violations produce `ObjectError`s not `FieldError`s. The existing handler drops them silently; the client sees an empty `messages` list for the missing-input validation failure.

**Contract**: In `handleValidation`, replace the `getFieldErrors()` stream with `Stream.concat(getFieldErrors().stream().map(FieldError::getDefaultMessage), getGlobalErrors().stream().map(ObjectError::getDefaultMessage)).toList()`. Import `java.util.stream.Stream` and `org.springframework.validation.ObjectError`.

---

#### 9. AnalysisControllerTest — update for new response shape

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/AnalysisControllerTest.java` (modify)

**Intent**: Controller now returns `AnalysisResponse`; all JSONPath assertions that previously targeted top-level `AnalysisResult` fields must be prefixed with `analysis.`. `standaloneSetup` constructor must include the new `ListingFetchService` mock.

**Contract**: Add `@Mock ListingFetchService listingFetchService`. Update `standaloneSetup(new AnalysisController(aiAnalysisService, listingFetchService))`. Prefix all JSONPath expressions: `$.verdict.code` → `$.analysis.verdict.code`, `$.riskFlags` → `$.analysis.riskFlags`, etc. Add one test: `POST /api/analyses` with neither field → 400 with message containing "Wymagane jest podanie".

---

#### 10. ListingFetchServiceTest

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/ListingFetchServiceTest.java` (new)

**Intent**: Cover the key failure modes of `ListingFetchService` without making real network calls.

**Contract**: JUnit 5 + `MockRestServiceServer.bindTo(builder)` pattern. Cases:
- HTTP 200 with listing-like HTML body → `FetchResult.isOk()`, stripped text non-empty
- HTTP 403 → `FetchResult` with `reason = "blocked"`
- HTTP 200 with Cloudflare challenge HTML (contains `cf-browser-verification`) → `reason = "blocked"`
- `RestClientException` (simulated by mock server with error response that triggers exception) → `reason` is not null
- URL with private IP (use `http://192.168.1.1/test`) → `reason = "ssrf_blocked"` (SSRF check fires before any HTTP call)
- URL with `ftp://` scheme → `reason = "invalid_scheme"`

### Success Criteria

#### Automated Verification

- `./mvnw test` passes — all 26 existing tests + new `ListingFetchServiceTest`
- `AnalysisControllerTest` passes with updated JSONPath assertions
- POST `/api/analyses` with no fields → 400 with message `"Wymagane jest podanie url lub listingText"` (verified by updated test)
- `ListingFetchServiceTest` 6 cases all pass

#### Manual Verification

- `curl -X POST localhost:10000/api/analyses -H 'Content-Type: application/json' -d '{"listingText":"BMW 3 2020 vin ABS"}' | grep fetchStatus` → `"text"`
- `curl ... -d '{"url":"http://otomoto.pl/some-listing"}' | grep fetchStatus` → `"url_failed"` or `"ok"` (either is acceptable — portal blocks in prod)
- `curl ... -d '{"url":"http://192.168.1.1/test"}' | grep reason` → `"ssrf_blocked"`
- `curl ... -d '{}' | grep messages` → contains "Wymagane jest podanie"

**Implementation Note**: Pause for manual confirmation before proceeding to Phase 2.

---

## Phase 2: Angular foundation

### Overview

Install PrimeNG 19, configure devServer proxy, wire `HttpClient` and `providePrimeNG` in the app config, define TypeScript interfaces matching the Java record shapes, create `AnalysisService`, clean up the app shell, and add the root route.

### Changes Required

#### 1. Install PrimeNG

**File**: `frontend/package.json` (modify via npm install)

**Intent**: Add PrimeNG component library and its theme presets package. Both are needed for the new CSS-variable-based theming system in PrimeNG 19.

**Contract**: Run `npm install primeng @primeng/themes` in the `frontend/` directory. This adds `"primeng": "^19.x.x"` and `"@primeng/themes": "^19.x.x"` to `dependencies`. Verify compatibility with Angular 21 (`npm info primeng peerDependencies`).

---

#### 2. DevServer proxy

**File**: `frontend/proxy.conf.json` (new)

**Intent**: Forward `/api/*` requests from the Angular dev server to the Spring Boot backend, avoiding CORS during local development.

**Contract**: Create at `frontend/proxy.conf.json`:
```json
{
  "/api": {
    "target": "http://localhost:10000",
    "secure": false,
    "changeOrigin": true
  }
}
```
No `pathRewrite` — Spring Boot routes are already under `/api/**`, so the prefix must be preserved. Adding `pathRewrite: { "^/api": "" }` would forward `/api/analyses` to `/analyses`, which does not exist.

---

#### 3. angular.json — add proxyConfig

**File**: `frontend/angular.json` (modify)

**Intent**: Tell `ng serve` to use the proxy configuration file.

**Contract**: In the `"serve"` target block, add (or create) an `"options"` object with `"proxyConfig": "proxy.conf.json"`. The `"configurations"` block is left unchanged.

---

#### 4. environment.ts — use relative URLs in dev

**File**: `frontend/src/environments/environment.ts` (modify)

**Intent**: Angular `AnalysisService` builds API URLs as `${environment.apiUrl}/api/analyses`. In dev, with the proxy, the URL should be relative (`/api/analyses`), so `apiUrl` must be empty string.

**Contract**: Change `apiUrl: 'http://localhost:10000'` to `apiUrl: ''`. Production `environment.prod.ts` keeps `apiUrl: 'https://autoskanerai.onrender.com'`.

---

#### 5. TypeScript model interfaces

**File**: `frontend/src/app/shared/models/analysis.models.ts` (new)

**Intent**: Single source of truth for all types shared between `AnalysisService`, `AnalyzerComponent`, and `AnalysisResultComponent`. Mirrors the Java record shapes exactly.

**Contract**: Export interfaces: `ExtractedData` (13 nullable fields matching Java record), `EquipmentItem` (`name: string`, `status: 'CONFIRMED'|'MISSING'|'UNCLEAR'`, `note: string|null`), `RiskFlag` (`code`, `severity: 'LOW'|'MEDIUM'|'HIGH'`, `description`), `CategoryScores` (5 number fields), `Verdict` (`code: VerdictCode`, `label: string`), `AnalysisMeta` (`provider`, `model`, `latencyMs: number`, `generatedAt: string`), `AnalysisResult` (7 fields), `AnalysisResponse` (`fetchStatus: 'ok'|'url_failed'|'text'`, `fetchFailureReason: string|null`, `analysis: AnalysisResult|null`), `AnalysisRequest` (`url?: string`, `listingText?: string`). Export type alias `VerdictCode = 'WORTH_CHECKING'|'NEEDS_MORE_INFO'|'HIGH_RISK_SKIP'`.

---

#### 6. AnalysisService

**File**: `frontend/src/app/core/services/analysis.service.ts` (new)

**Intent**: Thin HTTP wrapper over `POST /api/analyses`. The component handles all UI state; the service is pure data-fetching.

**Contract**: `@Injectable({ providedIn: 'root' })`. Inject `HttpClient` via `inject()`. One public method: `analyze(request: AnalysisRequest): Observable<AnalysisResponse>` — posts to `${environment.apiUrl}/api/analyses` and returns the typed observable. No error mapping inside the service — the component handles `HttpErrorResponse`.

---

#### 7. AnalysisService spec

**File**: `frontend/src/app/core/services/analysis.service.spec.ts` (new)

**Intent**: Verify correct HTTP verb, URL, and request body. Verify the service passes `HttpErrorResponse` through unchanged (for the component to map).

**Contract**: Use `HttpClientTestingModule` + `HttpTestingController`. Three cases: successful POST returns `AnalysisResponse` with `fetchStatus: 'text'`; 400 response propagates as `HttpErrorResponse` with `status: 400`; 502 propagates similarly. One case for POST with `url` field verifying request body shape.

---

#### 8. app.config.ts — add providers

**File**: `frontend/src/app/app.config.ts` (modify)

**Intent**: Wire `HttpClient` and PrimeNG theme into the application.

**Contract**: Add `provideHttpClient()` (from `@angular/common/http`). Add `providePrimeNG({ theme: { preset: Aura, options: { darkModeSelector: false } } })` (import `providePrimeNG` from `'primeng/config'`, `Aura` from `'@primeng/themes/aura'`).

---

#### 9. styles.scss — global reset

**File**: `frontend/src/styles.scss` (modify)

**Intent**: Add minimal global styles for layout. PrimeNG 19 injects its theme CSS via Angular DI; no CSS import is needed.

**Contract**: Add `* { box-sizing: border-box; margin: 0; padding: 0; }` and basic body font settings (system font stack, min-height 100vh, background `#f8f9fa`).

---

#### 10. App shell — clean up and add route

**File**: `frontend/src/app/app.html` (modify), `frontend/src/app/app.ts` (modify), `frontend/src/app/app.routes.ts` (modify)

**Intent**: Replace the Angular demo placeholder with a minimal app shell (header + router outlet), and wire the root route to `AnalyzerComponent` (lazy-loaded).

**Contract**:
- `app.html`: Replace demo content with a `<header>` containing the app title ("AutoSkanerAI") and a `<main>` containing `<router-outlet />`. Simple structural layout only — no nav links yet.
- `app.ts`: Keep `RouterOutlet` import; remove the `signal('frontend')` demo state if present; import nothing extra.
- `app.routes.ts`: Add `{ path: '', loadComponent: () => import('./features/analyzer/analyzer.component').then(m => m.AnalyzerComponent) }` and `{ path: '**', redirectTo: '' }`.

### Success Criteria

#### Automated Verification

- `ng build` completes with zero TypeScript or template type errors
- `AnalysisService` spec passes (all 4 test cases)

#### Manual Verification

- `npm start` starts `ng serve` at `localhost:4200` with no console errors
- Navigating to `localhost:4200` shows the app shell (header with "AutoSkanerAI" title; `AnalyzerComponent` placeholder or 404 until Phase 3)
- Network tab (or `curl http://localhost:4200/api/analyses -X POST -H 'Content-Type: application/json' -d '{"listingText":"test"}'`) confirms the proxy forwards to `localhost:10000`

**Implementation Note**: Pause for manual confirmation before proceeding to Phase 3.

---

## Phase 3: AnalyzerComponent — input, loading, fallback, errors

### Overview

Build the main page component. It owns the complete UX state machine: idle (input form) → loading (skeleton + rotating messages) → result (passes `AnalysisResult` to `AnalysisResultComponent`) or error (inline error message) or URL-failure (banner + text paste area highlighted). A "Nowe ogłoszenie" button resets to idle.

### Changes Required

#### 1. AnalyzerComponent

**File**: `frontend/src/app/features/analyzer/analyzer.component.ts` (new)
**File**: `frontend/src/app/features/analyzer/analyzer.component.html` (new)
**File**: `frontend/src/app/features/analyzer/analyzer.component.scss` (new)

**Intent**: Single-page flow: input → loading → result (or error). All state is held in Angular signals. No router navigation — result view replaces input view in-place.

**Contract — TypeScript state signals**:
- `url = signal('')` — URL input value
- `listingText = signal('')` — text paste value
- `loading = signal(false)` — true during HTTP call
- `loadingMessage = signal('Analizuję ogłoszenie...')` — rotates every 7 s during loading
- `fetchFailedBanner = signal<string|null>(null)` — shown when `fetchStatus === 'url_failed'`; contains a human-readable explanation
- `error = signal<string|null>(null)` — shown below submit button; cleared on next submit
- `result = signal<AnalysisResult|null>(null)` — when non-null, result view is shown instead of input form

**Contract — submit logic**:
1. Validate: if both `url` and `listingText` are blank → set `error('Wklej URL lub tekst ogłoszenia')`, return.
2. Set `loading(true)`, clear `error`, `fetchFailedBanner`, `result`.
3. Start loading message rotation interval (7 s, cycles: `'Analizuję ogłoszenie...'` → `'Sprawdzam ryzyko i wyposażenie...'` → `'Generuję rekomendacje...'` → repeat).
4. Call `analysisService.analyze({ url: urlVal || undefined, listingText: textVal || undefined })`.
5. On response: stop interval, `loading(false)`.
   - If `fetchStatus === 'url_failed'` → set `fetchFailedBanner` to `'Nie udało się pobrać ogłoszenia. Wklej treść ręcznie poniżej.'`; leave input form visible.
   - If `fetchStatus === 'ok'` or `'text'` → set `result(response.analysis!)`.
6. On `HttpErrorResponse`: stop interval, `loading(false)`, map to Polish error string via `mapError(err)`:
   - `status 400` → join `err.error.messages` with `'; '`
   - `status 502` → `'Serwis AI jest tymczasowo niedostępny. Spróbuj ponownie.'`
   - other → `'Błąd serwera. Spróbuj ponownie.'`
   Set `error(message)`.

**Contract — reset**: "Nowe ogłoszenie" button sets all signals back to initial values and clears the interval if running.

**Contract — template structure** (when `!result()`):
- URL input (`pInputText`, full width) with label "URL ogłoszenia"
- Text paste textarea (`pTextarea`, always visible below URL input, visually secondary — smaller label "lub wklej treść ogłoszenia") 
- Submit button (`pButton`, label "Analizuj")
- `fetchFailedBanner` signal: if non-null, show a yellow `<p-message severity="warn">` above the textarea
- `error` signal: if non-null, show a red `<p-message severity="error">` below the submit button
- `loading` signal: when true, replace the form with loading skeleton (see below)

**Contract — loading skeleton**: Show a `<p-skeleton>` card structure (one tall block for verdict, 5 thin rows for scores, a wider block for data table) and the current `loadingMessage()` below it in small centered text.

**Contract — template structure** (when `result()` is non-null):
- "Nowe ogłoszenie" `pButton` (secondary style) at the top
- `<app-analysis-result [result]="result()!" />`

---

#### 2. AnalyzerComponent spec

**File**: `frontend/src/app/features/analyzer/analyzer.component.spec.ts` (new)

**Intent**: Cover the key state transitions without a full Spring integration.

**Contract**: Use `TestBed.configureTestingModule` with `HttpClientTestingModule`, mock `AnalysisService` with `jasmine.createSpyObj`. Cases:
- Initial state: URL input and text textarea visible, no error, no result, no banner.
- Submit with both blank → `error` signal contains validation message.
- Submit with text → `loading` becomes true → mock responds with `{ fetchStatus: 'text', analysis: mockResult }` → `result` set, form hidden.
- Submit with URL → mock responds with `{ fetchStatus: 'url_failed', ... }` → `fetchFailedBanner` set, form still visible.
- HTTP 400 → `error` signal contains mapped Polish message.
- HTTP 502 → `error` contains LLM unavailable message.
- "Nowe ogłoszenie" button click → all state reset to initial.

### Success Criteria

#### Automated Verification

- `ng build` passes
- `AnalyzerComponent` spec passes (all 7 cases)

#### Manual Verification

- App loads at `localhost:4200` showing URL input and text textarea
- Submit with both blank → inline error message "Wklej URL lub tekst ogłoszenia"
- Submit with listingText (mock profile running) → skeleton loading state visible for the analysis duration → then `AnalysisResultComponent` placeholder (can be empty div until Phase 4)
- Submit with a URL → loading state → `fetchFailedBanner` yellow message appears above textarea (URL fetch will almost certainly fail in dev)
- "Nowe ogłoszenie" resets the form cleanly

**Implementation Note**: For the manual test of the full happy path, the `AnalysisResultComponent` placeholder from Phase 4 is not yet needed — a simple `<div>Result placeholder</div>` suffices for Phase 3 verification. Pause for manual confirmation before Phase 4.

---

## Phase 4: AnalysisResultComponent — full result display

### Overview

Build `AnalysisResultComponent` which renders all seven sections of `AnalysisResult`. Receives the result as an `@Input()`. All display logic is pure template + minimal component code (expand/collapse for risk flags, null-field formatting).

### Changes Required

#### 1. AnalysisResultComponent

**File**: `frontend/src/app/features/analyzer/components/analysis-result/analysis-result.component.ts` (new)
**File**: `frontend/src/app/features/analyzer/components/analysis-result/analysis-result.component.html` (new)
**File**: `frontend/src/app/features/analyzer/components/analysis-result/analysis-result.component.scss` (new)

**Intent**: Render all seven result sections in reading order. The verdict card is the first thing the user sees; risk flags and seller questions are the practical output users act on. Null extracted fields are shown as "—" so the table is never confusingly empty.

**Contract — input**: `@Input({ required: true }) result!: AnalysisResult`

**Contract — section 1: Verdict hero card**. A full-width `<p-card>` with background color class driven by `result.verdict.code`:
- `WORTH_CHECKING` → green accent (`--p-green-500` or a utility class)
- `NEEDS_MORE_INFO` → orange accent
- `HIGH_RISK_SKIP` → red accent
Content: large `result.verdict.label` text, `result.scores.overall` as a large badge/number, small `result.meta.provider` + `result.meta.model` text.

**Contract — section 2: Category scores**. Five `<p-progressbar>` rows with labels "Kompletność", "Wyposażenie", "Ryzyko", "Wartość", "Łącznie". Each shows `[value]` from the corresponding score field (0–100 integer). Color the bar via `color` attribute or class: ≥ 70 green, 40–69 orange, < 40 red.

**Contract — section 3: Extracted data table**. A two-column HTML table or PrimeNG Table with rows for each `ExtractedData` field. Null values display as `"—"`. Field display names in Polish: Marka, Model, Rok produkcji, Cena, Waluta, Przebieg (km), Rodzaj paliwa, Skrzynia biegów, Kraj pochodzenia, Typ sprzedającego, Historia serwisowa, Deklaracja dot. wypadków, Numer VIN podany. Boolean null → "—", `true` → "Tak", `false` → "Nie".

**Contract — section 4: Risk flags**. For each `RiskFlag` in `result.riskFlags`: a row with a `<p-tag>` (severity badge, color: HIGH=red, MEDIUM=orange, LOW=yellow) + `description` text. If `riskFlags.length > 4`: show only the first 4, with an expand link "Pokaż wszystkie (N)". Signal `riskFlagsExpanded = signal(false)`; toggled by the link; when true, show all flags.

**Contract — section 5: Equipment grid**. CSS grid (2–3 columns) of equipment items. Each item: `name` text + `<p-tag>` status badge (CONFIRMED=green/"Potwierdzono", MISSING=red/"Brak", UNCLEAR=grey/"Niesprecyzowane"). If `note` is non-null, show it as small italic text below the item name.

**Contract — section 6: Seller questions**. Ordered `<ol>` list of `result.sellerQuestions` strings. Section heading "Pytania do sprzedającego".

**Contract — section 7: Meta footer**. Small grey text: `Analiza: {{ result.meta.provider }} · {{ result.meta.model }} · {{ result.meta.latencyMs }}ms · {{ result.meta.generatedAt | date:'short' }}`. Use Angular `DatePipe` to format `generatedAt`.

---

#### 2. AnalysisResultComponent spec

**File**: `frontend/src/app/features/analyzer/components/analysis-result/analysis-result.component.spec.ts` (new)

**Intent**: Verify display correctness for the key conditional branches without a backend.

**Contract**: Use `ComponentFixture` with a mock `AnalysisResult`. Cases:
- Verdict `WORTH_CHECKING` → component host has the green color class
- Verdict `HIGH_RISK_SKIP` → red class
- Null `extracted.make` → table cell shows "—"
- `riskFlags` with 6 items → only 4 shown initially; after expand click all 6 shown
- `riskFlags` with 3 items → no expand link rendered
- `EquipmentItem` with `status: 'MISSING'` → red tag present in DOM
- `sellerQuestions` list rendered with correct count

---

#### 3. Wire AnalysisResultComponent into AnalyzerComponent

**File**: `frontend/src/app/features/analyzer/analyzer.component.ts` (modify)

**Intent**: Import and use `AnalysisResultComponent` in the result section of the template (replacing the placeholder from Phase 3).

**Contract**: Add `AnalysisResultComponent` to the component's `imports` array. In the template's result section, replace the placeholder with `<app-analysis-result [result]="result()!" />`.

### Success Criteria

#### Automated Verification

- `ng build` passes
- `AnalysisResultComponent` spec passes (all 7 cases)

#### Manual Verification

- Full end-to-end flow with mock profile: submit a Polish listing → result renders with all 7 sections populated
- Verdict card shows correct color (green for WORTH_CHECKING, red for HIGH_RISK_SKIP)
- ProgressBar rows render with correct score values
- Risk flag expand/collapse: with mock (which returns ≤ 4 flags), no expand needed — to verify collapse, temporarily inject a mock with 6 flags or test via spec
- Null fields in extracted data show "—" not blank
- Equipment items show correct badge colors
- Full end-to-end flow with bedrock profile: submit a real Polish listing (e.g., "BMW 3 E46 2002, 180000 km, benzyna..."), confirm LLM result renders correctly

**Implementation Note**: Pause for manual confirmation, including the bedrock live test, before marking the change complete.

---

## Testing Strategy

### Unit Tests

- `ListingFetchServiceTest` — 6 cases: fetch OK, 403, Cloudflare HTML, RestClientException, SSRF private IP, invalid scheme
- `AnalysisControllerTest` — updated for `AnalysisResponse` wrapper; add missing-input validation case
- `AnalysisService` spec — HTTP mock: success, 400, 502, URL request body
- `AnalyzerComponent` spec — state machine: 7 cases
- `AnalysisResultComponent` spec — rendering: 7 cases

### Integration Tests

- Existing `@SpringBootTest` suite (26 tests) must continue to pass; they use `mock` profile which is unaffected by URL fetch changes.

### Manual Testing Steps

1. `SPRING_PROFILES_ACTIVE=mock ./mvnw spring-boot:run` + `npm start` → full end-to-end: URL input → text paste fallback → analysis result renders
2. Empty submit → inline validation error
3. Submit valid listing text → loading skeleton visible during wait → complete result with all sections
4. "Nowe ogłoszenie" → form resets
5. Submit with unreachable URL → yellow banner → text paste area focus
6. Submit text with > 20 000 chars → 400 error with field message
7. Kill backend → submit → 500 error message "Błąd serwera"
8. `SPRING_PROFILES_ACTIVE=bedrock` + real listing → result shows `meta.provider=bedrock` and all sections populated from real LLM output

## Performance Considerations

- Analysis takes 5–30 s. The skeleton + rotating message prevents the UX from feeling frozen. Angular's `HttpClient` does not need a manual timeout — the backend imposes the 30 s LLM call timeout.
- PrimeNG components are standalone and tree-shaken. Only imported components land in the bundle.
- No memoization or `OnPush` change detection needed for MVP — the result is rendered once and never mutated.

## Migration Notes

- `AnalysisRequest.listingText` loses `@NotBlank` — any consumer that depended on the 400 "nie może być pusty" message for a blank listingText-only call will now get the class-level message "Wymagane jest podanie url lub listingText" instead. The legacy `RiskAnalysisController` uses `RiskAnalysisRequest` (unchanged), so it is unaffected.
- `POST /api/analyses` response shape changes from `AnalysisResult` to `AnalysisResponse`. No other client currently calls this endpoint, so no migration is required.

## References

- F-01 plan (locked output schema): `context/changes/llm-analysis-wiring/plan.md`
- Roadmap S-01 entry: `context/foundation/roadmap.md`
- API error shape: `CLAUDE.md` § "API error shape"
- PRD: `context/foundation/prd.md` — FR-001, FR-002, FR-004–FR-009, US-01

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Backend — URL fetch and API response wrapper

#### Automated

- [x] 1.1 `./mvnw test` passes — all existing + new ListingFetchServiceTest — 7fd2591
- [x] 1.2 `AnalysisControllerTest` passes with updated JSONPath assertions — 7fd2591
- [x] 1.3 POST with no fields returns 400 with message "Wymagane jest podanie" — 7fd2591
- [x] 1.4 `ListingFetchServiceTest` 6 cases pass — 7fd2591

#### Manual

- [x] 1.5 POST with listingText returns `{ "fetchStatus": "text", "analysis": {...} }` — 7fd2591
- [x] 1.6 POST with URL returns `fetchStatus: "url_failed"` or `"ok"` (either valid) — 7fd2591
- [x] 1.7 POST with private IP URL returns `reason: "ssrf_blocked"` — 7fd2591
- [x] 1.8 POST with neither field returns 400 with "Wymagane jest podanie" message — 7fd2591

### Phase 2: Angular foundation

#### Automated

- [x] 2.1 `ng build` completes with zero type errors
- [x] 2.2 `AnalysisService` spec passes (success response; 400 propagates; 502 propagates; URL request body shape) — spec written, tsc clean; no test runner configured in scaffold (skip-tests scaffolded project)

#### Manual

- [x] 2.3 `npm start` starts at `localhost:4200` with no console errors
- [x] 2.4 `/api` requests proxied to `localhost:10000` (verified via network tab or curl)

### Phase 3: AnalyzerComponent — input, loading, fallback, errors

#### Automated

- [ ] 3.1 `ng build` passes
- [ ] 3.2 `AnalyzerComponent` spec passes (initial state; blank submit error; text submit → result; URL submit → banner; HTTP 400 error; HTTP 502 error; reset button)

#### Manual

- [ ] 3.3 App loads showing URL input and text textarea
- [ ] 3.4 Submit empty form → inline validation error
- [ ] 3.5 Submit with listingText → loading skeleton → result placeholder visible (no error)
- [ ] 3.6 URL fetch failure → yellow banner + textarea focus
- [ ] 3.7 "Nowe ogłoszenie" resets all state

### Phase 4: AnalysisResultComponent — full result display

#### Automated

- [ ] 4.1 `ng build` passes
- [ ] 4.2 `AnalysisResultComponent` spec passes (WORTH_CHECKING green; HIGH_RISK_SKIP red; null field shows "—"; 6 flags collapse to 4; 3 flags no expand link; MISSING badge red; questions count)

#### Manual

- [ ] 4.3 Full result renders with all 7 sections (mock profile)
- [ ] 4.4 Verdict card shows correct color per verdict code
- [ ] 4.5 Risk flag expand/collapse works (verify via spec; mock returns ≤ 4 flags)
- [ ] 4.6 Score progress bars render correct values
- [ ] 4.7 Null extracted fields show "—"
- [ ] 4.8 Full end-to-end with bedrock profile and real Polish listing renders correctly
