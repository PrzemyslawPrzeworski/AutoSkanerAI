# Market Price Context Implementation Plan

## Overview

Enrich the analysis result with a comparable market price range fetched live from Otomoto. Given the extracted make, model, year, and mileage, the backend constructs a filtered Otomoto search URL, fetches it via the existing Jina Reader infrastructure, extracts prices via regex, and surfaces min/median/max PLN alongside the analysis so the user can immediately judge whether the listing price is fair, high, or suspiciously low.

## Current State Analysis

- `MarketPriceContext.java` — stub record already created by the S-04/S-05 shared base commit (Phase 1, `2a068d0`); fields: `minPricePln`, `medianPricePln`, `maxPricePln`, `sampleSize`, `queryUrl`, `fetchedAt` — all nullable `Integer` except `fetchedAt`
- `AnalysisResponse.java` — already has `MarketPriceContext marketPriceContext` as a nullable field; factory methods pass `null` for it
- `analysis.models.ts` — `MarketPriceContext` interface and `AnalysisResponse.marketPriceContext` already present
- `ListingFetchConfig.java:11` — `@Bean("listingFetchBuilder")` with 5s connect, 30s read, `Accept-Language: pl-PL` — reusable as-is for Otomoto fetches
- `ListingFetchService.java:30` — `JINA_PREFIX = "https://r.jina.ai/"` — same prefix will be used by `MarketPriceFetchService`
- No `MarketPriceFetchService`, `OtomotoSlugMapper`, or frontend price panel exists yet

## Desired End State

After any listing analysis, a collapsible "Kontekst cenowy" panel appears below the verdict card showing: comparable price range (min–max PLN), median price, number of listings found, and a direct link to the Otomoto search. If the fetch fails or returns fewer than 3 listings, `marketPriceContext` carries a `FETCH_FAILED` or `INSUFFICIENT_DATA` status and the panel shows a graceful degraded state. If make/model is unknown, the panel is omitted.

### Key Discoveries

- `ListingFetchConfig.java:11-22` — `listingFetchBuilder` bean sets `pl-PL` language, 30s read timeout, `X-No-Cache` — correct for Otomoto; can be reused directly via `@Qualifier("listingFetchBuilder")`
- `ListingFetchService.java:101` — URL encoding pattern: `JINA_PREFIX + URLEncoder.encode(rawUrl, UTF_8)` — same pattern for Otomoto search URL
- `ExtractedData.java:6-11` — `make`, `model`, `year`, `mileageKm` are the four inputs; all nullable
- Live test confirmed Jina returns prices as `### 52 300\nPLN` per listing — regex `###\s*([\d\s]+)\nPLN` extracts them reliably
- Otomoto slug format: `https://www.otomoto.pl/osobowe/{make-slug}/{model-slug}?search[filter_float_year:from]=X&search[filter_float_year:to]=Y&search[filter_float_mileage:to]=Z`
- `AnalysisController.java:22-36` — single `@PostMapping` handler; both URL and text paths end with `ResponseEntity.ok(AnalysisResponse.*)` — price enrichment inserted at both call sites

## What We're NOT Doing

- No Exa API, Parse.bot, or Apify — Jina on Otomoto is the chosen approach
- No caching of price results between requests — MVP scale does not require it
- No async/non-blocking enrichment — synchronous call after LLM analysis, same thread
- No price history or trend data — current listings snapshot only
- No `AnalysisResult` schema changes — `marketPriceContext` stays on `AnalysisResponse`
- No LLM prompt changes — price context is derived from Otomoto, not the listing text

## Implementation Approach

Three phases. Phase 1 builds the two new backend classes (`OtomotoSlugMapper` + `MarketPriceFetchService`) that are the core of S-05 — independently testable with no controller changes. Phase 2 wires the service into `AnalysisController` with a mock-profile guard. Phase 3 adds the frontend collapsible panel.

## Critical Implementation Details

**`MarketPriceContext` needs a `status` field.** The current stub record has only data fields. Before Phase 1 can be implemented, the stub must be extended with `MarketPriceStatus status` (enum: `OK`, `FETCH_FAILED`, `INSUFFICIENT_DATA`, `MISSING_INPUTS`). This is an additive change to the Phase 1 base-commit stub — both the Java record and the TypeScript interface need the new field.

**Price regex must handle grouped thousands.** Otomoto formats prices as `52 300` (space as thousands separator). The regex `###\s*([\d\s]+)\nPLN` captures this; the extracted string must have spaces stripped before `Integer.parseInt()`.

**Slug normalisation degrades gracefully.** If make is not in the map, the URL falls back to `https://www.otomoto.pl/osobowe` with only year/mileage filters — broader but still useful. If make is mapped but model slug produces no results (sampleSize < 1 after fetch), the service retries without the model slug before giving up.

---

## Phase 1: MarketPriceFetchService

### Overview

Implements `OtomotoSlugMapper` (make/model → Otomoto URL segments) and `MarketPriceFetchService` (constructs search URL, fetches via Jina, extracts prices, returns `MarketPriceContext`). Also extends the `MarketPriceContext` stub with a `status` field.

### Changes Required

#### 1. Extend `MarketPriceContext` with status

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/MarketPriceContext.java`

**Intent:** Add a `MarketPriceStatus` enum and a `status` field to `MarketPriceContext` so callers can distinguish OK, failed, insufficient data, and missing inputs without null-checking every field.

**Contract:** New sibling enum file `MarketPriceStatus.java` with values `OK, FETCH_FAILED, INSUFFICIENT_DATA, MISSING_INPUTS`. `MarketPriceContext` gains `MarketPriceStatus status` as its first field. All other fields remain nullable.

#### 2. Update TypeScript `MarketPriceContext`

**File:** `frontend/src/app/shared/models/analysis.models.ts`

**Intent:** Mirror the status field addition so the frontend can handle all states.

**Contract:** Add `export type MarketPriceStatus = 'OK' | 'FETCH_FAILED' | 'INSUFFICIENT_DATA' | 'MISSING_INPUTS';` and add `status: MarketPriceStatus` as the first field of `MarketPriceContext`.

#### 3. `OtomotoSlugMapper`

**File:** `backend/src/main/java/com/example/autoskaner_ai/market/OtomotoSlugMapper.java`

**Intent:** Convert LLM-extracted make and model strings to Otomoto URL slugs. Make uses a hardcoded map covering ~50 most common makes on the Polish market. Model is best-effort: lowercase, strip diacritics, replace spaces with hyphens.

**Contract:**
- `Optional<String> makeSlug(String make)` — looks up normalised (trimmed, uppercase) make in the map; returns empty if not found
- `String modelSlug(String model)` — lowercase, `Normalizer.normalize(NFKD)` to strip diacritics, replace spaces with hyphens, strip non-alphanumeric except hyphens
- Map must include at minimum: Audi, BMW, Citroën→citroen, Dacia, Fiat, Ford, Honda, Hyundai, Kia, Mazda, Mercedes-Benz→mercedes-benz, Mitsubishi, Nissan, Opel, Peugeot, Renault, Seat, Skoda→skoda, Suzuki, Toyota, Volkswagen→volkswagen, Volvo, and common Polish-market variants (e.g. "VW"→"volkswagen", "Merc"→"mercedes-benz")

#### 4. `MarketPriceFetchService`

**File:** `backend/src/main/java/com/example/autoskaner_ai/market/MarketPriceFetchService.java`

**Intent:** Given `ExtractedData`, build an Otomoto search URL, fetch via Jina Reader, extract PLN prices from the markdown response, compute min/median/max, and return a `MarketPriceContext`. Handles all failure cases by returning an appropriate status rather than throwing.

**Contract:**
```java
@Service
public class MarketPriceFetchService {
    public MarketPriceContext fetch(ExtractedData extracted) { ... }
}
```

URL construction logic:
- If `make` is null → return `MarketPriceContext{MISSING_INPUTS, ...nulls}`
- Map make to slug via `OtomotoSlugMapper`; if not mapped → return `MarketPriceContext{MISSING_INPUTS, ...nulls}`
- Build base URL: `https://www.otomoto.pl/osobowe/{makeSlug}/{modelSlug}` (omit model segment if model is null)
- Append year filters: `?search[filter_float_year:from]={year-2}&search[filter_float_year:to]={year+2}` (omit if year is null)
- Append mileage filter: `&search[filter_float_mileage:to]={mileageKm+30000}` (omit if mileageKm is null)
- Fetch via Jina: `JINA_PREFIX + URLEncoder.encode(otomotoUrl, UTF_8)` using the injected `RestClient` (built from `listingFetchBuilder`)
- Extract prices via regex `###\s*([\d\s]+)\nPLN`, strip spaces, parse to int
- If fetch throws or response is blank → return `MarketPriceContext{FETCH_FAILED, ..., queryUrl, Instant.now()}`
- If `prices.size() < 1` and model slug was used → retry without model slug (one retry only)
- Compute: `min`, `max`, sorted list median, `sampleSize = prices.size()`
- Return `MarketPriceContext{OK, min, median, max, sampleSize, queryUrl, Instant.now()}`

Note: `sampleSize` may be < 3 — the service always returns whatever was found; the frontend shows a caveat when `sampleSize` is low.

**RestClient injection:** `@Qualifier("listingFetchBuilder")` — same bean as `ListingFetchService`, no new config needed.

#### 5. Unit tests

**File:** `backend/src/test/java/com/example/autoskaner_ai/market/MarketPriceFetchServiceTest.java`

**Intent:** Test price extraction, URL construction, slug normalisation, and failure paths without making real HTTP calls.

**Contract:** Mock the `RestClient` (same pattern as `ListingFetchServiceTest`). Key cases: valid markdown with 5 prices → correct min/median/max; empty response → `FETCH_FAILED`; null make → `MISSING_INPUTS`; unknown make → `MISSING_INPUTS`.

### Success Criteria

#### Automated Verification

- `./mvnw test` — all existing tests green; new `MarketPriceFetchServiceTest` passes
- `./mvnw compile` — no errors in new `market` package
- `npm run build` — no TypeScript errors after status field addition

#### Manual Verification

- Call `MarketPriceFetchService.fetch()` directly with `ExtractedData{make="Toyota", model="Corolla", year=2019, mileageKm=95000}` against live Jina → returns `OK` status with populated price range
- Call with null make → `MISSING_INPUTS` returned, no exception

---

## Phase 2: Controller Wiring

### Overview

Injects `MarketPriceFetchService` into `AnalysisController` and populates `marketPriceContext` on both response paths. Adds a mock-profile guard so `mock` profile returns `MISSING_INPUTS` immediately without hitting Jina.

### Changes Required

#### 1. `MarketPriceEnrichmentService` interface + mock impl

**Files:**
- `backend/src/main/java/com/example/autoskaner_ai/market/MarketPriceEnrichmentService.java`
- `backend/src/main/java/com/example/autoskaner_ai/market/MockMarketPriceEnrichmentService.java`

**Intent:** Wrap `MarketPriceFetchService` behind an interface so the `mock` profile can return a stub result without network calls, mirroring the `AiAnalysisService` / `MockAiAnalysisService` pattern.

**Contract:**
```java
public interface MarketPriceEnrichmentService {
    MarketPriceContext enrich(ExtractedData extracted);
}
```
`MarketPriceFetchService` implements this interface, annotated `@Profile("!mock")`. `MockMarketPriceEnrichmentService` annotated `@Service @Profile("mock")` returns `MarketPriceContext{FETCH_FAILED, ...nulls, Instant.now()}` immediately.

#### 2. Update `AnalysisController`

**File:** `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java`

**Intent:** Inject `MarketPriceEnrichmentService` and call it after the LLM analysis on both the URL and text paths. Pass the result into the `AnalysisResponse` factory methods.

**Contract:** Constructor gains `MarketPriceEnrichmentService marketPriceEnrichmentService`. After `aiAnalysisService.analyze(...)` returns `result`, call `marketPriceEnrichmentService.enrich(result.extracted())` to get `marketPriceContext`. Update `AnalysisResponse.ok(result)` → `new AnalysisResponse("ok", null, result, null, marketPriceContext)` and likewise for the `text` path. `urlFailed` path unchanged (no analysis, no enrichment).

#### 3. Controller test update

**File:** `backend/src/test/java/com/example/autoskaner_ai/analysis/AnalysisControllerTest.java`

**Intent:** Verify `marketPriceContext` appears in the response JSON under the mock profile (expect `status: "FETCH_FAILED"` since `MockMarketPriceEnrichmentService` always returns that).

**Contract:** Add assertion `jsonPath("$.marketPriceContext.status").value("FETCH_FAILED")` to the existing analysis response test.

### Success Criteria

#### Automated Verification

- `./mvnw test` — all tests green including updated controller test
- `./mvnw test -Dgroups=live-llm` — live response includes `marketPriceContext` field with a status

#### Manual Verification

- `POST /api/analyses` with mock profile → response contains `"marketPriceContext": {"status": "FETCH_FAILED", ...}`
- `POST /api/analyses` with openrouter profile + listing text containing make/model/year → `marketPriceContext.status` is `"OK"` or `"FETCH_FAILED"` (depending on Jina/Otomoto availability), never missing from response

---

## Phase 3: Frontend Market Price Panel

### Overview

Adds a collapsible "Kontekst cenowy" card component below the verdict card. Shows min/median/max price, sample size, and a direct Otomoto search link. Handles all four statuses. Wired into `AnalysisResultComponent` and `AnalyzerComponent`.

### Changes Required

#### 1. `MarketPricePanelComponent`

**Files:**
- `frontend/src/app/features/analyzer/components/market-price-panel/market-price-panel.component.ts`
- `frontend/src/app/features/analyzer/components/market-price-panel/market-price-panel.component.html`
- `frontend/src/app/features/analyzer/components/market-price-panel/market-price-panel.component.scss`

**Intent:** A standalone Angular component accepting `MarketPriceContext | null` as a required input. Collapsed by default; expands to show the full price range and Otomoto link. Omits itself entirely when input is null.

**Contract (TypeScript):**
```typescript
@Component({ selector: 'app-market-price-panel', standalone: true, ... })
export class MarketPricePanelComponent {
  readonly marketPriceContext = input.required<MarketPriceContext | null>();
  expanded = signal(false);
}
```

**Contract (template — key states):**
- `null` → render nothing
- `MISSING_INPUTS` → render nothing (make/model unknown, no comparison possible)
- `OK` → collapsed header "Kontekst cenowy: {{ min | number }} – {{ max | number }} PLN" with expand toggle; expanded body shows median, sampleSize, and `<a [href]="marketPriceContext().queryUrl" target="_blank">Zobacz porównywalne ogłoszenia na Otomoto</a>`; if `sampleSize < 3` add note "Mała próbka ({{ sampleSize }} ogłoszeń) — traktuj zakres orientacyjnie"
- `FETCH_FAILED` → compact row "Porównanie cen niedostępne" with Otomoto link if `queryUrl` is present
- `INSUFFICIENT_DATA` → compact row "Brak wystarczających ogłoszeń do porównania"

#### 2. Wire into `AnalysisResultComponent`

**File:** `frontend/src/app/features/analyzer/components/analysis-result/analysis-result.component.ts`

**Intent:** Add `marketPriceContext` as an optional input and pass it to the new child component. Place the panel immediately after the verdict card (before scores) so price context is visible at a glance.

**Contract:** Add `readonly marketPriceContext = input<MarketPriceContext | null>(null)`. Import `MarketPricePanelComponent`. In template, add `<app-market-price-panel [marketPriceContext]="marketPriceContext()" />` immediately after the verdict card section.

#### 3. Wire into `AnalyzerComponent`

**File:** `frontend/src/app/features/analyzer/analyzer.component.ts`

**Intent:** Pass `marketPriceContext` from the `AnalysisResponse` down to `AnalysisResultComponent`.

**Contract:** Pass `[marketPriceContext]="analysisResponse()?.marketPriceContext ?? null"` to `<app-analysis-result>` in the analyzer template.

### Success Criteria

#### Automated Verification

- `npm run build` — no errors
- `npm run typecheck` — no errors

#### Manual Verification

- Mock profile → price panel shows "Porównanie cen niedostępne" (FETCH_FAILED state)
- Listing with unknown make (e.g. listing text with no brand) → price panel absent entirely
- Live test with Toyota Corolla 2019 listing → price panel shows PLN range with sample count and Otomoto link
- Expand/collapse toggle works
- Low sample size caveat shown when sampleSize < 3
- Otomoto link opens correct filtered search in new tab

---

## Testing Strategy

### Unit Tests

- `OtomotoSlugMapper`: BMW → "bmw", Mercedes-Benz → "mercedes-benz", VW → "volkswagen", unknown make → empty, model diacritics stripped correctly
- `MarketPriceFetchService`: mock RestClient returns Jina markdown with 5 prices → correct min/median/max; empty body → `FETCH_FAILED`; null make → `MISSING_INPUTS`; RestClient throws → `FETCH_FAILED` (no exception propagated)

### Integration Tests

- Controller test (mock profile): `POST /api/analyses` → `marketPriceContext.status == "FETCH_FAILED"` in JSON

### Manual Testing Steps

1. Mock profile, any listing → price panel shows `FETCH_FAILED` degraded state
2. OpenRouter profile, Toyota Corolla listing → price panel shows OK with PLN range
3. Listing with no make extracted → price panel absent
4. Click Otomoto link → correct search URL opens in browser
5. Verify expand/collapse works
6. Verify low-sample caveat with a very specific make/model/year combo

## References

- Research: `context/changes/market-price-context/research.md`
- S-04 parallel change: `context/changes/cepik-vin-lookup/plan.md`
- `ListingFetchService.java:30,101-107` — Jina fetch pattern to mirror
- `ListingFetchConfig.java:11-22` — `listingFetchBuilder` bean to reuse
- `MockAiAnalysisService.java` — profile guard pattern (`@Profile("mock")` / `@Profile("!mock")`)

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: MarketPriceFetchService

#### Automated

- [x] 1.1 `./mvnw test` — all existing tests green; new `MarketPriceFetchServiceTest` passes
- [x] 1.2 `./mvnw compile` — no errors in new `market` package
- [x] 1.3 `npm run build` — no TypeScript errors after status field addition

#### Manual

- [x] 1.4 Live call with Toyota Corolla 2019 → `OK` status with populated price range
- [x] 1.5 Null make → `MISSING_INPUTS`, no exception

### Phase 2: Controller Wiring

#### Automated

- [ ] 2.1 `./mvnw test` — all tests green including updated controller test
- [ ] 2.2 `./mvnw test -Dgroups=live-llm` — live response includes `marketPriceContext` field

#### Manual

- [ ] 2.3 Mock profile POST → `marketPriceContext.status == "FETCH_FAILED"` in response JSON
- [ ] 2.4 OpenRouter profile POST with make/model/year listing → `marketPriceContext` present with status

### Phase 3: Frontend Market Price Panel

#### Automated

- [ ] 3.1 `npm run build` — no errors
- [ ] 3.2 `npm run typecheck` — no errors

#### Manual

- [ ] 3.3 Mock profile → price panel shows degraded state
- [ ] 3.4 Listing with no make → price panel absent
- [ ] 3.5 Live test Toyota Corolla → price range shown with Otomoto link
- [ ] 3.6 Expand/collapse works
- [ ] 3.7 Low-sample caveat shown when sampleSize < 3
- [ ] 3.8 Otomoto link opens correct URL in new tab
