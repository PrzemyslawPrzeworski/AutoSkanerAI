---
change_id: market-price-context
title: Surface comparable market price range from Otomoto alongside analysis
status: impl_reviewed
created: 2026-06-02
updated: 2026-06-02
archived_at: null
---

## Notes

Given extracted make, model, year, and mileage, fetch comparable Otomoto listings and surface a price range (min/median/max PLN) so the user can judge whether the listing price is fair, high, or suspiciously low.

**Approach (from research):** Reuse existing Jina Reader infrastructure (`ListingFetchService` pattern) to fetch an Otomoto search URL filtered by make/model/year-band/mileage-band. Extract prices via regex from the markdown response. Zero new dependencies.

**Key open questions before planning:**
- Otomoto URL slug normalisation — LLM-extracted make/model must map to Otomoto slugs (e.g. "Toyota Corolla" → `toyota/corolla`)
- Schema placement: `marketPriceContext` on `AnalysisResponse` (preferred, async-friendly) vs `AnalysisResult`
- Year/mileage window size for comparables (suggest ±2 years, mileage upper bound +30k km)
- Minimum sample size threshold (suggest: return null if < 3 listings found)

**Exa approach superseded:** original plan was to query Exa search API; live testing confirmed Jina on Otomoto search pages works (HTTP 200, clean price data) and costs nothing. Exa/Parse.bot/Apify saved as fallbacks in memory.
