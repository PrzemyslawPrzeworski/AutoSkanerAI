---
date: 2026-06-02T00:00:00+02:00
researcher: Przemyslaw Przeworski
git_commit: 315f9b3ec217019a0d540576217e6e76d156fc65
branch: main
repository: PrzemyslawPrzeworski/AutoSkanerAI
topic: "S-05 Market Price Context — integration approach, data sources, and schema extension"
tags: [research, market-price-context, jina-reader, otomoto, exa, listing-fetch, schema]
status: complete
last_updated: 2026-06-02
last_updated_by: Przemyslaw Przeworski
---

# Research: S-05 Market Price Context

**Date**: 2026-06-02T00:00:00+02:00
**Researcher**: Przemyslaw Przeworski
**Git Commit**: 315f9b3ec217019a0d540576217e6e76d156fc65
**Branch**: main
**Repository**: PrzemyslawPrzeworski/AutoSkanerAI

## Research Question

How should S-05 (market price context) be implemented? Specifically:
1. What data source should be used to fetch comparable Otomoto listings?
2. What is the integration pattern in the existing codebase?
3. Where does the market price context slot into the locked output schema?
4. How to extract a price range from fetched data?

## Summary

**Chosen approach: Jina Reader on Otomoto search URLs — zero new dependencies.**

The project already uses Jina Reader (`https://r.jina.ai/`) to fetch individual listing pages via `ListingFetchService`. The same infrastructure can fetch Otomoto search result pages filtered by make/model/year/mileage. A live test confirmed HTTP 200 with clean structured output — prices appear as `### 52 300\nPLN` per listing, with ~32 listings per page. A regex pass extracts the prices; min/median/max gives the price range.

The locked `AnalysisResult` schema has no market price context field yet. S-05 adds a new `MarketPriceContext` record alongside the existing `analysis` in `AnalysisResponse`, or as an optional field on `AnalysisResult` — to be decided during planning.

Two paid fallback services were evaluated (Parse.bot, Apify) and saved to memory for future use if Jina proves unreliable.

## Detailed Findings

### Existing Listing Fetch Infrastructure

**`ListingFetchService`** — `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java`

- Class declaration: line 22–23
- Jina prefix constant: `static final String JINA_PREFIX = "https://r.jina.ai/";` (line 30)
- URL encoding: `String jinaUrl = JINA_PREFIX + URLEncoder.encode(rawUrl, StandardCharsets.UTF_8);` (line 101)
- RestClient call pattern (lines 104–107):
  ```java
  content = client.get()
      .uri(java.net.URI.create(jinaUrl))
      .retrieve()
      .body(String.class);
  ```
- SSRF protection (lines 62–95): DNS lookup with 5s timeout, blocks loopback/site-local/link-local/any-local addresses
- Injected via `@Qualifier("listingFetchBuilder")` constructor injection (line 34–36)

**`ListingFetchConfig`** — `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchConfig.java`

- `@Bean("listingFetchBuilder")` declaration (lines 11–12)
- Connect timeout: 5,000 ms; Read timeout: 30,000 ms (line 14–16)
- Default headers set on the builder (lines 18–22):
  - `Accept: text/plain,text/html,*/*;q=0.8`
  - `Accept-Language: pl-PL,pl;q=0.9,en;q=0.8`
  - `X-No-Cache: true`

**`AnalysisController`** — `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java`

- `ListingFetchService` injected at lines 17–20
- Called at line 25–26 in `POST /api/analyses` handler

**`FetchResult`** — simple record: `(boolean ok, String reason, String text)`

### Jina Reader on Otomoto Search Pages — Live Test Result

Tested: `curl https://r.jina.ai/https://www.otomoto.pl/osobowe/toyota/corolla`

**Result: HTTP 200, clean structured output.** Sample from response:

```
mileage 29 888 km fuel_type Hybryda gearbox Automatyczna year 2022
### 52 300
PLN

mileage 27 500 km fuel_type Hybryda gearbox Automatyczna year 2021
### 56 300
PLN

mileage 81 000 km fuel_type Hybryda gearbox Automatyczna year 2020
### 51 200
PLN
```

Each listing is rendered with: mileage, fuel type, gearbox, year, and price as a `### {number}\nPLN` block. Some listings include an Otomoto-generated label ("Poniżej średniej" = below average, "W granicach średniej" = around average) — useful bonus signal.

**Price extraction regex:** `###\s*([\d\s]+)\nPLN` captures the numeric price (with spaces as thousands separators). Parsed to int after stripping spaces.

### Otomoto Search URL Construction

Otomoto uses a query-string filter scheme:

```
https://www.otomoto.pl/osobowe/{make}/{model}?search[filter_float_year:from]={yearFrom}&search[filter_float_year:to]={yearTo}&search[filter_float_mileage:to]={mileageMax}
```

Example for Toyota Corolla 2018–2020, up to 140,000 km:
```
https://www.otomoto.pl/osobowe/toyota/corolla?search[filter_float_year:from]=2018&search[filter_float_year:to]=2020&search[filter_float_mileage:to]=140000
```

Make and model slugs are lowercase, spaces replaced with hyphens (e.g. `volkswagen`, `golf`, `bmw`, `seria-3`).

**Input fields available from `ExtractedData`:**
- `make` (line 6, `ExtractedData.java`) — maps to URL segment
- `model` (line 7) — maps to URL segment
- `year` (line 8) — use ±2 year window for comparables
- `mileageKm` (line 11) — use ±30,000 km window for comparables

All four fields are nullable (null = unknown). If make or model is null, the query degrades gracefully to category-only (`/osobowe` with year/mileage filters).

### Locked Output Schema — Where Market Price Context Slots In

**Current `AnalysisResult`** (`backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisResult.java`, lines 5–13):

```java
record AnalysisResult(
    ExtractedData extracted,
    List<EquipmentItem> equipment,
    List<RiskFlag> riskFlags,
    List<String> sellerQuestions,
    CategoryScores scores,
    Verdict verdict,
    AnalysisMeta meta
)
```

No `marketPriceContext` field exists. FR-018 is deferred beyond F-01. Two schema extension options for planning:

**Option A — field on `AnalysisResult`** (tighter coupling):
```java
record AnalysisResult(
    ExtractedData extracted,
    ...
    MarketPriceContext marketPriceContext,  // nullable; null if make/model unknown
    AnalysisMeta meta
)
```

**Option B — parallel field on `AnalysisResponse`** (looser coupling, async-friendly):
```java
record AnalysisResponse(
    String fetchStatus,
    String fetchFailureReason,
    AnalysisResult analysis,
    MarketPriceContext marketPriceContext   // fetched separately, after analysis
)
```

Option B maps better to a non-blocking enrichment pattern (analysis returns immediately; price context loads in a second call or async). Aligns with S-04 (CEPiK) which has the same async enrichment pattern.

**Proposed `MarketPriceContext` record:**
```java
record MarketPriceContext(
    int minPricePln,
    int medianPricePln,
    int maxPricePln,
    int sampleSize,        // how many listings were found
    String queryUrl,       // the Otomoto search URL used (for transparency/debugging)
    Instant fetchedAt
)
```

### LLM Provider Wiring — No Changes Needed

S-05 does not touch the LLM layer at all. The market price context is fetched and computed independently of the LLM analysis. The `AiAnalysisService.analyze(String listingText)` interface (`AiAnalysisService.java` line 3–5) stays unchanged.

### Data Source Evaluation

During research, three data source options were evaluated for fetching comparable Otomoto listings:

| Option | Cost | Java integration | Verdict |
|---|---|---|---|
| **Jina Reader on Otomoto search URL** | Free | Reuses existing `ListingFetchService` pattern; zero new deps | **Chosen for MVP** |
| **Parse.bot Otomoto API** | Free 100 req/mo; $30/mo (1k) | Synchronous REST, `RestClient`, no SDK | Fallback if Jina unreliable |
| **Apify `automation-lab/otomoto-scraper`** | ~$0.001/listing | Async (trigger→poll→fetch); more complex | Fallback for bulk/scheduled runs |

**Why Jina over paid services:** The project already uses Jina for listing page fetching. Otomoto search pages are server-rendered (Next.js SSR with embedded data), so Jina's headless rendering delivers clean markdown. Live test confirmed correct price extraction. No new API keys, no new billing, no polling loop.

**Why not Exa (original plan from roadmap/change.md):** Exa has no Java SDK. For Polish market price context, querying Otomoto directly gives more accurate, fresher results than Exa's web index. The change.md was created before this was investigated — the Jina approach supersedes the Exa approach for S-05. Exa remains useful for other research tasks.

## Code References

- `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java:22` — Service class declaration
- `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java:30` — Jina prefix constant
- `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java:101` — URL encoding with URLEncoder
- `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java:104-107` — RestClient GET call pattern
- `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java:62-95` — SSRF protection logic
- `backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchConfig.java:11-22` — Bean config with timeouts and default headers
- `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java:17-20` — ListingFetchService injection
- `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisResult.java:5-13` — Locked output schema record
- `backend/src/main/java/com/example/autoskaner_ai/analysis/ExtractedData.java:5-19` — All 13 extractable fields (make, model, year, mileageKm are key inputs for S-05)
- `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisResponse.java:3` — Top-level response wrapper
- `context/changes/llm-analysis-wiring/plan.md:38-54` — Locked output schema specification

## Architecture Insights

1. **The Jina layer is already an abstraction.** `ListingFetchService` wraps Jina behind a `FetchResult` return type. A `MarketPriceFetchService` can be an identical sibling — same `RestClient.Builder` bean, same Jina prefix, different URL input (Otomoto search URL vs listing URL).

2. **SSRF protection applies to user-supplied URLs only.** Otomoto search URLs are constructed server-side from validated `ExtractedData` fields — no user-supplied hostname, so SSRF checks are not needed on the price context fetch path.

3. **30s timeout is appropriate.** Jina renders JavaScript-heavy pages; 30s is already the established timeout for the listing fetch. Otomoto search pages are SSR so they may actually be faster, but 30s is a safe ceiling.

4. **The `RestClient.Builder` bean is reusable.** The `@Bean("listingFetchBuilder")` already sets `Accept-Language: pl-PL` which is correct for Otomoto. A `@Bean("marketPriceFetchBuilder")` may be identical or reuse the same bean — to decide in planning.

5. **Price range is a derived computation, not LLM output.** Prices extracted from Jina markdown via regex → int parsing → statistical computation. No LLM involvement. This keeps cost at zero for the enrichment step.

6. **Schema extension is additive.** Adding `MarketPriceContext` as a nullable field does not break the existing `AnalysisResult` contract. Frontend can render it conditionally. Backend returns `null` when make/model is unknown or fetch fails.

## Historical Context (from prior changes)

- `context/changes/llm-analysis-wiring/plan.md:38-54` — Locked schema defined here. F-01 is fully implemented (all 5 phases done). Schema extension for S-05 is additive — the plan explicitly notes the schema must be stable before S-01 frontend is built, but doesn't preclude future nullable additions.
- `context/changes/llm-analysis-wiring/plan.md:56` — "every nullable field in `extracted` is explicitly nullable — missing data means unknown, never confirmed clean." Same invariant applies to `MarketPriceContext` — if price context cannot be fetched, return `null`, never fabricate a range.

## Open Questions

1. **Schema placement:** Add `marketPriceContext` to `AnalysisResult` (Option A) or `AnalysisResponse` (Option B)? Option B is cleaner for async enrichment and mirrors the expected S-04 CEPiK pattern — recommend Option B, confirm during planning.

2. **Make/model slug normalisation:** Otomoto uses Polish slugs (e.g. `volkswagen/golf`, `mercedes-benz/klasa-c`). A mapping table or normalisation strategy is needed to convert `ExtractedData.make`/`model` (LLM-extracted, may be in Polish or English) to Otomoto URL slugs. This is a non-trivial mapping that needs handling in the `MarketPriceFetchService`.

3. **Year/mileage window:** What ±window to use around extracted year and mileage for comparables? Suggested defaults: ±2 years, mileage upper bound only (listing's mileage + 30,000 km). Validate against real queries.

4. **Minimum sample size:** What if fewer than 3 listings match the filters? Return `null` or return the range with a caveat? Suggest: return `null` if `sampleSize < 3`.

5. **change.md note update:** The `change.md` currently says "enrich analysis with comparable market price range via Exa search API." The Exa approach is superseded by the Jina/Otomoto approach. Update notes in `change.md` before planning begins.

6. **Otomoto blocking risk:** Jina successfully fetched the search page in testing, but Otomoto may rate-limit or block at higher call volumes. For MVP (1 fetch per analysis), this is not a concern. At scale, caching responses (e.g. by make/model/year-band) reduces pressure.
