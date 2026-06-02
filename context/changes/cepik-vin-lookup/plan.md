# CEPiK VIN Lookup Implementation Plan

## Overview

Add in-app vehicle history from `historiapojazdu.gov.pl` to the analysis result. When the listing provides enough identifying data (VIN + registration plate + first registration date), AutoSkanerAI queries the Polish CEP registry and surfaces owner count, mileage stamps from technical inspections, and significant damage records alongside the LLM analysis. Missing inputs are surfaced as seller questions. The feature degrades gracefully on failure — it never blocks the main analysis.

## Current State Analysis

- `ExtractedData.java:18` — `Boolean vinPresent` only; no VIN string, no plate, no first registration date
- `AnalysisPrompt.java:36` — LLM schema has `"vinPresent": <boolean|null>`; model never asked for actual values
- `AnalysisResponse.java:3` — three fields only: `fetchStatus`, `fetchFailureReason`, `analysis`
- `analysis.models.ts:61-65` — `AnalysisResponse` interface mirrors Java; no enrichment fields
- `AnalysisController.java:22-36` — synchronous: fetch → analyze → return; no enrichment step
- `analysis-result.component.html:44` — renders `vinPresent` as boolean; no CEPiK section
- No `CepikResult`, `CepikStatus`, `MileageStamp`, `DamageRecord` types exist anywhere

## Desired End State

User pastes a listing that contains a VIN, registration plate, and first registration date. After the LLM analysis renders, a "Historia CEPiK" panel appears below showing: number of owners, mileage readings from inspections, and significant damage records with guardrail copy. If any of the three inputs is absent (e.g. no plate in listing text), the missing fields are requested via seller questions and the CEPiK panel shows which data is pending. If the registry call fails, the panel shows "Weryfikacja CEPiK niedostępna" with a manual `historiapojazdu.gov.pl` link.

### Key Discoveries

- `historiapojazdu.gov.pl` draws from CEP database; exposes owners, mileage, damage — **no public REST API**, session-based only (moj.gov.pl XSRF + Nf_wid flow)
- `api.cepik.gov.pl` is a public REST API from the same database but exposes only technical/registration data — no owners/mileage/damage. Useful as a fallback to retrieve `firstRegistrationDate` when missing from listing text
- `api.cepik.gov.pl` requires `wojewodztwo` (voivodeship code) + date range as mandatory params; VIN alone not accepted — must scan all 16 voivodeships in parallel with `CompletableFuture.anyOf()` early exit
- `api.cepik.gov.pl` uses legacy TLS cipher suites — requires custom `SSLContext` in Java
- Session scraping flow (Python reference: `mtatko/cepik-vehicle-history-client`): `GET moj.gov.pl` → `POST NF_WID` → `POST vehicle-data` → `POST timeline-data` → `GET close`
- `szkody istotne` (significant damage) = insurer-reported damage to chassis/brakes/steering only; absence is not clean — never display "brak wypadków"
- S-05 (`market-price-context`) adds `marketPriceContext` to `AnalysisResponse` in parallel; shared base commit on `main` eliminates the only merge conflict point

## What We're NOT Doing

- No `api.cepik.gov.pl` data shown to the user — it is used only as a fallback to fill in `firstRegistrationDate`; its 60+ technical fields are not surfaced
- No CEPiK 2.0 B2B formal API (requires institutional agreement; currently driver-license only)
- No moj.gov.pl Profil Zaufany login — the unauthenticated session flow gives enough for registered Polish vehicles
- No caching of CEPiK results between requests — MVP scale does not require it
- No `historiapojazdu` PDF download or rendering
- No ownership history timeline (dates of each transfer) — only total owner count

## Implementation Approach

Six phases in dependency order. Phase 1 lands on `main` before branching, eliminating S-04/S-05 merge conflicts. Phases 2–6 implement the feature end-to-end: extend the LLM extraction schema, build the two backend services (public API gap-fill + session scraping), wire the controller orchestration, and add the frontend panel.

## Critical Implementation Details

**Session cookie handling:** `moj.gov.pl` sets multiple cookies on the initial GET. The Spring `RestClient` does not persist cookies automatically — a `CookieStore` or manual header threading is required across the three POST calls. Each request must forward the `JSESSIONID`, `XSRF-TOKEN`, and `NF_WID` cookies received from the previous response.

**`CompletableFuture.anyOf()` cancel pattern:** Firing 16 voivodeship scans and taking the first non-empty result requires explicit cancellation of the remaining futures after a result is found. Without cancellation, 15 HTTP calls complete silently in the background wasting connections. Use a `List<CompletableFuture<...>>` and call `cancel(true)` on the remainder after the winner resolves.

**SSL legacy ciphers:** `api.cepik.gov.pl` handshake will fail with Java's default `SSLContext`. The `SimpleClientHttpRequestFactory` does not accept a custom `SSLContext` directly — use `HttpComponentsClientHttpRequestFactory` (Apache HttpClient 5, already available via Spring Boot's `spring-boot-starter-web` transitive dep) which accepts a custom `SSLConnectionSocketFactory`.

---

## Phase 1: Shared Schema Base Commit

### Overview

Introduces `CepikResult` and related types, stubs both `cepikResult` and `marketPriceContext` as nullable fields on `AnalysisResponse`, and mirrors the change in TypeScript. Lands on `main` before either S-04 or S-05 branches, so neither branch touches `AnalysisResponse` again.

### Changes Required

#### 1. New domain records — CEPiK types

**Files:**
- `backend/src/main/java/com/example/autoskaner_ai/analysis/CepikStatus.java`
- `backend/src/main/java/com/example/autoskaner_ai/analysis/MileageStamp.java`
- `backend/src/main/java/com/example/autoskaner_ai/analysis/DamageRecord.java`
- `backend/src/main/java/com/example/autoskaner_ai/analysis/CepikResult.java`

**Intent:** Define the domain types that represent a completed (or failed) CEPiK lookup, a single mileage reading from a technical inspection, and a single significant-damage event.

**Contract:**
```java
public enum CepikStatus { FOUND, NOT_FOUND, LOOKUP_FAILED, MISSING_INPUTS }

public record MileageStamp(String date, Integer mileageKm) {}

public record DamageRecord(String date, String description) {}

public record CepikResult(
    CepikStatus status,
    String vin,
    String firstRegistrationDatePl,
    String deregisteredDate,
    String originCountry,
    Integer ownerCount,
    List<MileageStamp> mileageStamps,
    List<DamageRecord> damageRecords,
    String lookupUrl,
    Instant fetchedAt
) {}
```
All fields nullable except `status` and `fetchedAt`. `lookupUrl` is always set to the `historiapojazdu.gov.pl` manual-check URL regardless of status.

#### 2. New domain record — MarketPriceContext stub

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/MarketPriceContext.java`

**Intent:** Stub the S-05 type so `AnalysisResponse` can reference it in this shared commit without S-05 needing to land first.

**Contract:**
```java
public record MarketPriceContext(
    Integer minPricePln,
    Integer medianPricePln,
    Integer maxPricePln,
    Integer sampleSize,
    String queryUrl,
    Instant fetchedAt
) {}
```

#### 3. Extend `AnalysisResponse`

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisResponse.java`

**Intent:** Add both enrichment fields as nullable components. Update all three factory methods to pass `null` for both new fields — callers (the controller) will supply real values once the respective features are implemented.

**Contract:** Record gains two new trailing components: `CepikResult cepikResult` and `MarketPriceContext marketPriceContext`. Factory methods `ok()`, `text()`, `urlFailed()` each gain two `null` arguments.

#### 4. Update TypeScript models

**File:** `frontend/src/app/shared/models/analysis.models.ts`

**Intent:** Mirror the Java schema extension so the Angular app compiles cleanly and future components have typed access to both enrichment fields.

**Contract:** Add to the file:
```typescript
export type CepikStatus = 'FOUND' | 'NOT_FOUND' | 'LOOKUP_FAILED' | 'MISSING_INPUTS';

export interface MileageStamp { date: string; mileageKm: number | null; }
export interface DamageRecord { date: string; description: string; }

export interface CepikResult {
  status: CepikStatus;
  vin: string | null;
  firstRegistrationDatePl: string | null;
  deregisteredDate: string | null;
  originCountry: string | null;
  ownerCount: number | null;
  mileageStamps: MileageStamp[] | null;
  damageRecords: DamageRecord[] | null;
  lookupUrl: string | null;
  fetchedAt: string;
}

export interface MarketPriceContext {
  minPricePln: number | null;
  medianPricePln: number | null;
  maxPricePln: number | null;
  sampleSize: number | null;
  queryUrl: string | null;
  fetchedAt: string;
}
```
Extend `AnalysisResponse` interface with `cepikResult: CepikResult | null` and `marketPriceContext: MarketPriceContext | null`.

### Success Criteria

#### Automated Verification

- Backend compiles: `./mvnw compile` passes with no errors
- Frontend compiles: `npm run build` passes with no type errors
- Existing unit tests unchanged: `./mvnw test` green

#### Manual Verification

- `POST /api/analyses` with a valid listing returns JSON with `"cepikResult": null` and `"marketPriceContext": null` fields present in the response body

---

## Phase 2: CEPiK Input Extraction

### Overview

Extends the LLM extraction schema to capture the actual VIN string, registration plate, and first registration date from listing text. Updates the prompt, parser, mock service, test fixtures, and the frontend data table. This is the prerequisite for any CEPiK call.

### Changes Required

#### 1. Extend `ExtractedData`

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/ExtractedData.java`

**Intent:** Add three new nullable string fields after `vinPresent`. Keep `vinPresent` boolean as-is — it continues to drive the `NO_VIN` risk flag logic; the new `vin` field holds the extracted value when present.

**Contract:** New fields appended to the record: `String vin`, `String registrationPlate`, `String firstRegistrationDate`. All nullable. `firstRegistrationDate` is stored as extracted from the listing (e.g. `"15.03.2018"` or `"2018-03-15"`) — normalisation to ISO happens in the service layer.

#### 2. Add `VinValidator`

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/VinValidator.java`

**Intent:** Validate and normalise a VIN string before it is used in a CEPiK lookup. Normalise first (trim, uppercase, strip spaces and hyphens), then validate format.

**Contract:**
```java
public class VinValidator {
    // Returns normalised VIN if valid, empty Optional if malformed.
    public static Optional<String> normalise(String raw) { ... }

    // Valid: exactly 17 chars, A-H J-N P-Z 0-9 (no I, O, Q)
    private static final Pattern VALID = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
}
```

#### 3. Update `AnalysisPrompt`

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisPrompt.java`

**Intent:** Instruct the LLM to extract the actual VIN string, registration plate number, and first registration date in addition to the existing boolean. Update both examples to show the new fields.

**Contract:** In the `extracted` schema block, replace `"vinPresent": <boolean|null>` with:
```
"vinPresent": <boolean|null>,
"vin": <string|null>,
"registrationPlate": <string|null>,
"firstRegistrationDate": <string|null>
```
Instruction line: `"vin: wyodrębnij pełny numer VIN (17 znaków) jeśli podany. registrationPlate: numer rejestracyjny jeśli podany. firstRegistrationDate: data pierwszej rejestracji w formacie z ogłoszenia."` Both examples updated with realistic values.

#### 4. Update `AnalysisResponseParser`

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisResponseParser.java`

**Intent:** Extend `ExtractedDto` with the three new fields and pass them through `mapExtracted()` to the domain record.

**Contract:** `ExtractedDto` gains `String vin`, `String registrationPlate`, `String firstRegistrationDate`. `mapExtracted()` passes them as the last three arguments to the `ExtractedData` constructor. No validation here — `VinValidator` is called in the service layer.

#### 5. Update `MockAiAnalysisService`

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/MockAiAnalysisService.java`

**Intent:** Extract the VIN string from listing text when present (regex for 17-char uppercase alphanumeric), registration plate (Polish plate regex), and first registration date. Update `computeCompletenessScore` to include `vin` in the present-field count.

**Contract:** VIN regex: `[A-HJ-NPR-Z0-9]{17}`. Polish plate regex (basic): `[A-Z]{2,3}[A-Z0-9]{4,5}`. Date pattern: reuse existing year-pattern as a seed, extract `dd.mm.yyyy` or `yyyy-mm-dd` format. Pass extracted values (or `null`) into `ExtractedData` constructor.

#### 6. Update test fixtures

**Files:**
- `backend/src/test/resources/fixtures/llm/valid-full-response.json`
- `backend/src/test/resources/fixtures/llm/valid-response-with-fence.json`

**Intent:** Add the three new fields to the fixture JSON so parser tests remain valid.

**Contract:** Add to `extracted` object: `"vin": "WBAAM31060GE12345"`, `"registrationPlate": null`, `"firstRegistrationDate": "2018-03-15"`.

#### 7. Update frontend data table

**File:** `frontend/src/app/features/analyzer/components/analysis-result/analysis-result.component.html`

**Intent:** Add three new rows to the "Dane ogłoszenia" table for VIN string, registration plate, and first registration date. Keep the existing `vinPresent` boolean row.

**Contract:** After line 44 (`Numer VIN podany`), add:
```html
<tr><td>Numer VIN</td><td>{{ str(result().extracted.vin) }}</td></tr>
<tr><td>Nr rejestracyjny</td><td>{{ str(result().extracted.registrationPlate) }}</td></tr>
<tr><td>Data 1. rejestracji</td><td>{{ str(result().extracted.firstRegistrationDate) }}</td></tr>
```

#### 8. Update `analysis.models.ts` ExtractedData

**File:** `frontend/src/app/shared/models/analysis.models.ts`

**Intent:** Add the three new fields to the `ExtractedData` interface.

**Contract:** Add after `vinPresent: boolean | null`: `vin: string | null`, `registrationPlate: string | null`, `firstRegistrationDate: string | null`.

### Success Criteria

#### Automated Verification

- `./mvnw test` — parser tests pass with updated fixtures
- `npm run build` — no TypeScript errors
- `./mvnw test -Dgroups=live-llm` — live LLM response includes `vin`, `registrationPlate`, `firstRegistrationDate` fields (nullable)

#### Manual Verification

- Paste a listing containing a VIN (e.g. `VIN: WBAAM31060GE12345`) → `extracted.vin` in response is `"WBAAM31060GE12345"`, data table shows it
- Paste a listing with no VIN → `extracted.vin` is `null`, `NO_VIN` risk flag still present
- Paste a listing with a registration plate (e.g. `WA12345`) → `extracted.registrationPlate` populated

---

## Phase 3: CepikApiService (First Registration Date Lookup)

### Overview

Implements the `api.cepik.gov.pl` public API client used solely to fill in `firstRegistrationDate` when missing from the listing. Scans all 16 voivodeships in parallel, returns the first non-null result, cancels the rest.

### Changes Required

#### 1. `CepikApiConfig`

**File:** `backend/src/main/java/com/example/autoskaner_ai/cepik/CepikApiConfig.java`

**Intent:** Provide a named `RestClient.Builder` bean configured with the legacy TLS cipher suite required by `api.cepik.gov.pl` and a short (8s) read timeout appropriate for a best-effort fallback call.

**Contract:** `@Bean("cepikApiBuilder")` using `HttpComponentsClientHttpRequestFactory` (Apache HttpClient 5). Custom `SSLContext` must enable `TLS_RSA_*` cipher suites disabled by default in Java 17+. Base URL set to `https://api.cepik.gov.pl`. Connect timeout 5s, read timeout 8s.

#### 2. `CepikApiService`

**File:** `backend/src/main/java/com/example/autoskaner_ai/cepik/CepikApiService.java`

**Intent:** Given a normalised VIN, scan all 16 Polish voivodeships in parallel against `/pojazdy` and return the `data-pierwszej-rejestracjiwkraju` attribute from the first non-empty result. Returns `Optional.empty()` on failure or not-found.

**Contract:**
- Voivodeship codes: `02,04,06,08,10,12,14,16,18,20,22,24,26,28,30,32`
- Query per voivodeship: `GET /pojazdy?wojewodztwo={code}&data-od=19800101&data-do={today}&typ-daty=2&tylko-zarejestrowane=false&pokaz-wszystkie-pola=true&filter[numer-vin]={vin}&limit=1`
- Fires 16 `CompletableFuture<Optional<String>>` in parallel. Uses `CompletableFuture.anyOf()` on the non-empty subset. Cancels remaining futures after winner found.
- On any exception per future (timeout, SSL, HTTP error) → that future resolves to `Optional.empty()`.
- Response parsing: JSON:API `data[0].attributes["data-pierwszej-rejestracjiwkraju"]` (format `YYYYMMDD`) → convert to `"YYYY-MM-DD"` for downstream use.
- Total wall-clock bounded by `anyOf` + 8s timeout.

### Success Criteria

#### Automated Verification

- `./mvnw compile` — no errors in new `cepik` package
- Unit test: mock RestClient returns a voivodeship-12 hit → service returns correct date string
- Unit test: all 16 return empty → service returns `Optional.empty()`
- Unit test: 14 throw exceptions, 2 return empty → service returns `Optional.empty()` (no exception propagated)

#### Manual Verification

- Call `CepikApiService` with VIN `WBAAM31060GE12345` against the live API → returns a date string or empty (depending on API availability); no exception thrown regardless of API state

---

## Phase 4: HistoriaPojazduService (Session Scraping)

### Overview

Implements the `historiapojazdu.gov.pl` session flow: establish a session via `moj.gov.pl`, POST vehicle-data and timeline-data requests, parse the response into `CepikResult`. Handles session failure gracefully.

### Changes Required

#### 1. `HistoriaPojazduConfig`

**File:** `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduConfig.java`

**Intent:** Provide a named `RestClient.Builder` bean for the `moj.gov.pl` session calls. Standard TLS (no legacy ciphers needed), 5s connect, 10s read timeout. Configured to follow redirects.

**Contract:** `@Bean("historiaPojazduBuilder")` using `HttpComponentsClientHttpRequestFactory`. Base URL `https://moj.gov.pl`.

#### 2. `HistoriaPojazduSession`

**File:** `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduSession.java`

**Intent:** Encapsulate the stateful session lifecycle: open, query, close. The session is created fresh per lookup — no session reuse.

**Contract:**
- `open()`: `GET /uslugi/engine/ng/index?xFormsAppName=HistoriaPojazdu` → extract `JSESSIONID` and other cookies from `Set-Cookie` headers → `POST` same URL with form body `NF_WID=HistoriaPojazdu:<currentTimeMillis>` → extract `XSRF-TOKEN` from response cookies
- `fetchVehicleData(plate, vin, firstRegDate)`: `POST /nforms/api/HistoriaPojazdu/1.0.17/data/vehicle-data` with JSON body `{"registrationNumber": plate, "VINNumber": vin, "firstRegistrationDate": firstRegDate}` and headers `X-Xsrf-Token`, `Nf_wid`, cookie header threading
- `fetchTimelineData(plate, vin, firstRegDate)`: same body, `POST .../data/timeline-data`
- `close()`: `GET .../close` — called in finally block
- All methods throw `HistoriaPojazduSessionException` (unchecked) on failure; caller wraps in try-catch

#### 3. `HistoriaPojazduParser`

**File:** `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParser.java`

**Intent:** Map `vehicle-data` and `timeline-data` JSON responses to `CepikResult`. Constructs the `lookupUrl` pointing to `historiapojazdu.gov.pl` for the manual-check CTA.

**Contract:**
- From `vehicle-data`: extract owner count (`liczbaWlascicieli` or equivalent key), deregistration status, origin
- From `timeline-data`: extract mileage stamps (date + km from inspection events), damage records (`szkodyIstotne` list with date + description)
- `lookupUrl` = `"https://historiapojazdu.gov.pl"` (static — user must re-enter their details; deep-linking with pre-filled params is not supported by the web form)
- Returns `CepikResult` with `status = FOUND` and `fetchedAt = Instant.now()`
- On missing expected fields: use `null`, never throw

#### 4. `HistoriaPojazduService`

**File:** `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduService.java`

**Intent:** Orchestrate the session lifecycle and return a `CepikResult`. Wraps all failures into `CepikResult{status: LOOKUP_FAILED}`.

**Contract:**
```java
@Service
public class HistoriaPojazduService {
    public CepikResult lookup(String plate, String vin, String firstRegDate) {
        try {
            session.open();
            var vehicleData = session.fetchVehicleData(plate, vin, firstRegDate);
            var timelineData = session.fetchTimelineData(plate, vin, firstRegDate);
            return parser.parse(vehicleData, timelineData, vin);
        } catch (Exception e) {
            log.warn("historiapojazdu lookup failed: {}", e.getMessage());
            return failedResult(vin);
        } finally {
            session.close();
        }
    }
}
```
`failedResult()` returns `CepikResult{LOOKUP_FAILED, vin, null fields..., lookupUrl, Instant.now()}`.

### Success Criteria

#### Automated Verification

- `./mvnw compile` — no errors
- Unit test `HistoriaPojazduParser`: given fixture JSON matching the real response shape → correct `ownerCount`, `mileageStamps`, `damageRecords` values
- Unit test `HistoriaPojazduService`: session throws → `CepikResult.status == LOOKUP_FAILED`, no exception propagated

#### Manual Verification

- With a valid plate + VIN + first reg date from a real car listing → `HistoriaPojazduService.lookup()` returns `status = FOUND` with populated fields (requires live `moj.gov.pl` connectivity)
- With invalid inputs → returns `status = NOT_FOUND` or `LOOKUP_FAILED` gracefully

---

## Phase 5: Controller Orchestration

### Overview

Wires the two CEPiK services into `AnalysisController`. After the LLM analysis, the controller runs the CEPiK enrichment: validates inputs, fills missing `firstRegistrationDate` via the public API if needed, calls `HistoriaPojazduService`, injects missing-field questions into `sellerQuestions`, and returns the complete `AnalysisResponse`.

### Changes Required

#### 1. `AnalysisController`

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java`

**Intent:** After obtaining `AnalysisResult` from the LLM service, run the CEPiK enrichment pipeline and attach the result to `AnalysisResponse`. The enrichment must not throw — any failure returns `LOOKUP_FAILED`.

**Contract:** Inject `CepikApiService` and `HistoriaPojazduService`. After `aiAnalysisService.analyze(...)` returns `result`, call a private `enrichWithCepik(result)` method that:

1. Extracts `vin`, `registrationPlate`, `firstRegistrationDate` from `result.extracted()`
2. Validates VIN via `VinValidator.normalise()` → if invalid/absent → return `CepikResult{MISSING_INPUTS}` and inject `"Proszę podać numer VIN pojazdu"` into sellerQuestions
3. If `registrationPlate` is null → return `MISSING_INPUTS` + inject `"Proszę podać numer rejestracyjny pojazdu"`
4. If `firstRegistrationDate` is null → call `cepikApiService.lookupFirstRegistrationDate(normalisedVin)` → if still empty → return `MISSING_INPUTS` + inject `"Proszę podać datę pierwszej rejestracji pojazdu"`
5. All three present → call `historiaPojazduService.lookup(plate, vin, date)`
6. Return `CepikResult` from step 5 (already handles its own failure → `LOOKUP_FAILED`)

Missing-field question injection: build a new `List<String>` from `result.sellerQuestions()` + any missing-field questions, construct a new `AnalysisResult` with the augmented questions list.

Both code paths (`url`-based and `listingText`-based) go through the same `enrichWithCepik()` helper.

#### 2. `MockAiAnalysisService` — CEPiK mock question

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/MockAiAnalysisService.java`

**Intent:** The existing canned question at line 22 ("Czy numer VIN można zweryfikować w bazie CEPiK?") is now superseded by the automatic injection logic. Replace it with a neutral question to avoid duplication.

**Contract:** Replace `"Czy numer VIN można zweryfikować w bazie CEPiK?"` with `"Czy pojazd ma pełną dokumentację techniczną?"`.

#### 3. Profile guard on CEPiK enrichment

**File:** `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduService.java`

**Intent:** The `mock` profile should not attempt live session scraping. Guard the service with `@Profile("!mock")` and provide a `MockCepikService` stub for the `mock` profile.

**Contract:** `HistoriaPojazduService` annotated `@Profile("!mock")`. New `MockCepikService.java` annotated `@Service @Profile("mock")` implementing the same interface, returning `CepikResult{LOOKUP_FAILED, ...}` immediately. `CepikApiService` similarly guarded.

Extract a `CepikEnrichmentService` interface with method `CepikResult enrich(ExtractedData extracted)` implemented by the real controller helper and the mock stub.

### Success Criteria

#### Automated Verification

- `./mvnw test` — all existing controller tests pass unchanged
- New controller test: mock profile → `cepikResult.status == LOOKUP_FAILED` in response JSON
- New controller test: extracted data missing plate → `sellerQuestions` contains `"Proszę podać numer rejestracyjny pojazdu"`
- `./mvnw test -Dgroups=live-llm` — live response includes `cepikResult` field (any status)

#### Manual Verification

- `POST /api/analyses` with listing text containing no VIN → response has `cepikResult.status = "MISSING_INPUTS"`, seller questions list contains the registration plate and VIN questions
- `POST /api/analyses` with listing text containing VIN + plate + date → `cepikResult.status = "FOUND"` or `"LOOKUP_FAILED"` (depending on live API availability), never an exception

---

## Phase 6: Frontend CEPiK Panel

### Overview

Adds a collapsible "Historia CEPiK" card component below the analysis result. Renders all four states: `FOUND` (full history), `MISSING_INPUTS` (which fields are pending), `NOT_FOUND` (VIN not in registry), `LOOKUP_FAILED` (degraded + manual link).

### Changes Required

#### 1. `CepikResultComponent`

**Files:**
- `frontend/src/app/features/analyzer/components/cepik-result/cepik-result.component.ts`
- `frontend/src/app/features/analyzer/components/cepik-result/cepik-result.component.html`
- `frontend/src/app/features/analyzer/components/cepik-result/cepik-result.component.scss`

**Intent:** A standalone Angular component that accepts `CepikResult | null` as a required input and renders the appropriate state. Collapsed by default; expand toggle for mileage stamps list (which can be long).

**Contract (TypeScript):**
```typescript
@Component({ selector: 'app-cepik-result', standalone: true, ... })
export class CepikResultComponent {
  readonly cepikResult = input.required<CepikResult | null>();
  mileageExpanded = signal(false);
}
```

**Contract (template — key states):**
- If `cepikResult()` is null → render nothing (component not shown)
- `FOUND`: card with:
  - `Liczba właścicieli: {{ cepikResult().ownerCount ?? '—' }}`
  - Last mileage reading (most recent `mileageStamps` entry) + expand toggle for full list
  - Damage section: if `damageRecords` empty → `"Brak zgłoszonych szkód istotnych w CEPiK. Nie wyklucza napraw niezgłoszonych do ubezpieczyciela."` If non-empty → list with date + description per record
  - Link: `<a [href]="cepikResult().lookupUrl" target="_blank">Sprawdź pełną historię na historiapojazdu.gov.pl</a>`
- `MISSING_INPUTS`: card with `"Brak danych do weryfikacji w CEPiK"` + note that questions have been added to the seller questions list
- `NOT_FOUND`: card with `"Pojazd nie znaleziony w CEPiK"` + manual link
- `LOOKUP_FAILED`: card with `"Weryfikacja CEPiK chwilowo niedostępna"` + manual link

#### 2. Wire into `AnalysisResultComponent`

**File:** `frontend/src/app/features/analyzer/components/analysis-result/analysis-result.component.ts`

**Intent:** The existing component accepts `AnalysisResult`; it must now also accept the `CepikResult` so it can pass it to the new child component.

**Contract:** Add `readonly cepikResult = input<CepikResult | null>(null)`. Import `CepikResultComponent`. Add `<app-cepik-result [cepikResult]="cepikResult()" />` after the seller questions section in the template.

#### 3. Wire into `AnalyzerComponent`

**File:** `frontend/src/app/features/analyzer/analyzer.component.ts`

**Intent:** The parent component holds the full `AnalysisResponse`; it passes `cepikResult` down to `AnalysisResultComponent`.

**Contract:** Pass `[cepikResult]="analysisResponse()?.cepikResult ?? null"` to `<app-analysis-result>` in the analyzer template.

### Success Criteria

#### Automated Verification

- `npm run build` — no errors
- `npm run typecheck` — no errors

#### Manual Verification

- With mock profile (no live CEPiK): CEPiK card shows `"Weryfikacja CEPiK chwilowo niedostępna"` with manual link
- With listing missing VIN: card shows `"Brak danych do weryfikacji w CEPiK"`, seller questions include the missing-field prompts
- With listing containing VIN + plate + date (live test): card shows `FOUND` or `LOOKUP_FAILED` depending on API availability
- Mileage expand/collapse toggle works when more than one stamp is present
- Damage copy never says "brak wypadków" — always the guardrail-safe copy

---

## Testing Strategy

### Unit Tests

- `VinValidator`: valid VINs pass, malformed (16 chars, with I/O/Q, spaces, partial) return empty Optional
- `HistoriaPojazduParser`: given fixture JSON → correct field mapping; missing fields → null (no exception)
- `HistoriaPojazduService`: session exception → `LOOKUP_FAILED` result (no exception propagated)
- `CepikApiService`: one voivodeship returns a hit → correct date string; all empty → `Optional.empty()`
- `AnalysisController`: mock enrichment service returns `MISSING_INPUTS` → correct sellerQuestions injection

### Integration Tests

- Controller test (mock profile): full `POST /api/analyses` → response JSON contains `cepikResult` with `status: "LOOKUP_FAILED"`
- Parser test: `valid-full-response.json` parses without error with new `vin` / `registrationPlate` / `firstRegistrationDate` fields

### Manual Testing Steps

1. `SPRING_PROFILES_ACTIVE=mock` → paste any listing → CEPiK card shows `LOOKUP_FAILED` degraded state
2. Listing with no VIN → `cepikResult.status = MISSING_INPUTS`, seller questions include VIN + plate + date requests
3. Listing with VIN only (no plate) → `MISSING_INPUTS`, plate question injected
4. Listing with VIN + plate + date (real car) → with `openrouter` profile → `FOUND` or `LOOKUP_FAILED` from live gov.pl
5. Expand mileage stamps list → shows all historical readings
6. Damage records copy verified: never says "brak wypadków"
7. Manual link opens `historiapojazdu.gov.pl` in new tab

## Performance Considerations

The `CepikApiService` fires 16 parallel HTTP requests (one per voivodeship). With `anyOf()` early exit and an 8s timeout, the worst case is 8s added to the analysis response time — only when `firstRegistrationDate` is missing AND the public API is reachable. When the API is unreliable (current state), all 16 futures time out after 8s before falling through. Consider making this async (return `CompletableFuture<AnalysisResponse>` from the controller) if the 8s worst-case is unacceptable in practice — deferred to post-MVP.

## References

- Research doc: `context/changes/cepik-vin-lookup/research.md`
- S-05 research (enrichment pattern): `context/changes/market-price-context/research.md`
- Python session client reference: `github.com/mtatko/cepik-vehicle-history-client`
- Java CEPiK API reference: `github.com/PiotrMichalowski96/ImportedCars`
- `ListingFetchConfig.java` — RestClient.Builder pattern to follow for `CepikApiConfig`
- `AnalysisResponseParser.java` — parser pattern to follow for `HistoriaPojazduParser`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Shared Schema Base Commit

#### Automated

- [x] 1.1 `./mvnw compile` passes with no errors
- [x] 1.2 `npm run build` passes with no type errors
- [x] 1.3 `./mvnw test` green (existing tests unchanged)

#### Manual

- [x] 1.4 `POST /api/analyses` returns JSON with `"cepikResult": null` and `"marketPriceContext": null` fields present

### Phase 2: CEPiK Input Extraction

#### Automated

- [x] 2.1 `./mvnw test` — parser tests pass with updated fixtures
- [x] 2.2 `npm run build` — no TypeScript errors
- [ ] 2.3 `./mvnw test -Dgroups=live-llm` — response includes `vin`, `registrationPlate`, `firstRegistrationDate` fields

#### Manual

- [x] 2.4 Listing with VIN → `extracted.vin` populated, visible in data table
- [x] 2.5 Listing without VIN → `extracted.vin` null, `NO_VIN` risk flag still present
- [x] 2.6 Listing with registration plate → `extracted.registrationPlate` populated

### Phase 3: CepikApiService

#### Automated

- [ ] 3.1 `./mvnw compile` — no errors in `cepik` package
- [ ] 3.2 Unit test: mock hit on voivodeship 12 → correct date string returned
- [ ] 3.3 Unit test: all 16 empty → `Optional.empty()` returned
- [ ] 3.4 Unit test: 14 exceptions, 2 empty → `Optional.empty()`, no exception propagated

#### Manual

- [ ] 3.5 `CepikApiService` called with known VIN → returns date string or empty without throwing (live test)

### Phase 4: HistoriaPojazduService

#### Automated

- [ ] 4.1 `./mvnw compile` — no errors
- [ ] 4.2 Unit test `HistoriaPojazduParser`: fixture JSON → correct field mapping
- [ ] 4.3 Unit test `HistoriaPojazduService`: session exception → `LOOKUP_FAILED`, no exception propagated

#### Manual

- [ ] 4.4 Valid plate + VIN + date → `status = FOUND` with populated fields (live test)
- [ ] 4.5 Invalid inputs → `NOT_FOUND` or `LOOKUP_FAILED` without exception

### Phase 5: Controller Orchestration

#### Automated

- [ ] 5.1 `./mvnw test` — all existing controller tests pass
- [ ] 5.2 New test: mock profile → `cepikResult.status == "LOOKUP_FAILED"` in response JSON
- [ ] 5.3 New test: missing plate → sellerQuestions contains plate question
- [ ] 5.4 `./mvnw test -Dgroups=live-llm` — response includes `cepikResult` field

#### Manual

- [ ] 5.5 Listing with no VIN → `MISSING_INPUTS`, seller questions contain VIN + plate + date prompts
- [ ] 5.6 Listing with VIN + plate + date → `FOUND` or `LOOKUP_FAILED`, never an exception

### Phase 6: Frontend CEPiK Panel

#### Automated

- [ ] 6.1 `npm run build` — no errors
- [ ] 6.2 `npm run typecheck` — no errors

#### Manual

- [ ] 6.3 Mock profile → CEPiK card shows degraded state with manual link
- [ ] 6.4 Missing VIN listing → card shows `MISSING_INPUTS`, seller questions include prompts
- [ ] 6.5 Live test with VIN + plate + date → `FOUND` or `LOOKUP_FAILED` rendered correctly
- [ ] 6.6 Mileage expand/collapse works
- [ ] 6.7 Damage copy never says "brak wypadków"
- [ ] 6.8 Manual link opens `historiapojazdu.gov.pl` in new tab
