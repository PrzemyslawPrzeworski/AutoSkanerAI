# Market Price Context — Plan Brief

> Full plan: `context/changes/market-price-context/plan.md`
> Research: `context/changes/market-price-context/research.md`

## What & Why

AutoSkanerAI analyses listing quality but gives no signal on whether the asking price is fair. S-05 closes that gap: given the extracted make, model, year, and mileage, the backend queries current Otomoto listings and surfaces a comparable price range (min–max PLN, median, sample count) so the user can immediately decide if the offer is priced well or suspiciously off-market.

## Starting Point

`MarketPriceContext` record and its field on `AnalysisResponse` already exist as stubs (committed in Phase 1 base commit `2a068d0`). The factory methods pass `null` for it. The `listingFetchBuilder` RestClient bean is in place and suitable for reuse. No service, slug mapper, or frontend panel exists yet.

## Desired End State

A collapsible "Kontekst cenowy" panel appears below the verdict card on every analysis where make is known. It shows the comparable price range fetched live from Otomoto, sample size, and a direct link to the search. When the fetch fails, it shows a degraded state instead of crashing. When make is unknown, the panel is absent.

## Key Decisions Made

| Decision | Choice | Why | Source |
|---|---|---|---|
| Data source | Jina Reader on Otomoto search URL | Zero new deps; live test confirmed HTTP 200 with clean prices; free | Research |
| Slug normalisation | Hardcoded map for ~50 top makes + lowercase model | Covers ~90% of Polish market volume; simple, zero external calls | Plan |
| Minimum sample | Return range regardless of count; caveat if < 3 | Always show something; user can judge sample size themselves | Plan |
| Fetch failure | `FETCH_FAILED` status field on `MarketPriceContext` | Transparent, consistent with CepikResult pattern | Plan |
| Frontend UX | Collapsible panel (like CEPiK), after verdict card | Consistent with CEPiK panel; high visibility; detail on demand | Plan |
| Schema placement | `AnalysisResponse.marketPriceContext` (already stubbed) | Enrichment independent of LLM output; no changes to locked schema | Research |

## Scope

**In scope:**
- `OtomotoSlugMapper` with ~50 make entries
- `MarketPriceFetchService` (Jina fetch → regex price extraction → min/median/max)
- `MarketPriceStatus` enum added to `MarketPriceContext`
- `MarketPriceEnrichmentService` interface + mock profile guard
- `AnalysisController` wiring on both URL and text paths
- `MarketPricePanelComponent` with expand/collapse and all 4 states

**Out of scope:**
- Exa API, Parse.bot, Apify (fallbacks in memory only)
- Caching of price results
- Async/non-blocking enrichment
- Price history or trends
- LLM prompt changes

## Architecture / Approach

`MarketPriceFetchService` reuses the `@Bean("listingFetchBuilder")` RestClient (already configured for `pl-PL`, 30s timeout, Jina-ready). It constructs an Otomoto search URL from `ExtractedData`, fetches via `r.jina.ai`, extracts prices with a regex, and returns `MarketPriceContext`. The controller calls it synchronously after the LLM analysis and passes the result into `AnalysisResponse`. The frontend renders a collapsible panel placed immediately after the verdict card.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. MarketPriceFetchService | Slug mapper + fetch service + status field on MarketPriceContext; unit tested | Otomoto slug map needs validation against real listing makes from LLM |
| 2. Controller Wiring | Service injected into controller; mock guard; controller test updated | Minimal risk — additive constructor injection |
| 3. Frontend Panel | Collapsible MarketPricePanelComponent; all 4 states; wired into parent components | Panel placement (after verdict) may need layout adjustment |

**Prerequisites:** Phase 1 base commit (`2a068d0`) must be on the branch — already done via rebase.
**Estimated effort:** ~2 sessions across 3 phases.

## Open Risks & Assumptions

- Otomoto may start blocking Jina at higher volumes — MVP (1 fetch/analysis) is safe; at scale, a simple in-memory cache keyed by `{makeSlug}/{modelSlug}/{yearBand}/{mileageBand}` would reduce pressure
- Model slug quality depends on LLM extraction — "Seria 3" → "seria-3" works, but obscure trim names may produce zero results and fall back to make-only search
- `listingFetchBuilder` 30s timeout means price fetch adds up to 30s in the worst case (Jina overloaded) — acceptable for MVP but worth monitoring

## Success Criteria (Summary)

- `POST /api/analyses` always returns a `marketPriceContext` field — never absent, never an uncaught exception
- When make/model/year are extractable from a real listing, the panel shows a PLN range within 30 seconds
- When fetch fails, panel shows a graceful degraded state — not an error, not missing
