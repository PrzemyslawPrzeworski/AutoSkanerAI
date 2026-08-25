---
change_id: cepik-vin-lookup
title: Display CEPiK vehicle history alongside analysis using extracted VIN
status: merged
created: 2026-06-02
updated: 2026-08-25
archived_at: null
---

## Notes

When a VIN is extracted from the listing, query the CEPiK registry and display vehicle history (first registration date, ownership count, mileage stamps, significant damage records) alongside the analysis. If VIN absent or lookup fails — show section as unavailable, never silently omit.

**Key findings from research (see research.md):**

- The codebase only extracts `Boolean vinPresent` — NOT the actual VIN string. First deliverable of S-04 must be extending `ExtractedData` + LLM prompt to extract the actual VIN value.
- Two separate systems: `api.cepik.gov.pl` (public, no auth, technical data only — no accidents/owners/mileage) and `historiapojazdu.gov.pl` (full history, but no public REST API — session scraping only).
- `historiapojazdu` requires all three: registration plate + VIN + first registration date. VIN alone is not enough.
- MVP approach: use public API for registry confirmation (VIN exists, first registration date, deregistration status), plus a "check full history" link to historiapojazdu.gov.pl for the user. Skip session scraping in MVP.
  - **Superseded during implementation (2026-06-02):** session scraping was *not* skipped. Phase 4 shipped `HistoriaPojazduSession` + `HistoriaPojazduService`, which authenticate against `moj.gov.pl` and scrape vehicle + timeline data (commit `e616ea2`). Reason: the public `api.cepik.gov.pl` returns technical data only — no ownership count, mileage stamps, or damage records — so the stated outcome (display vehicle history) was unreachable without it. Consequences: the plate value now needs format validation before leaving the app (impl-review F7), and the session must be constructed per lookup rather than shared (F1). Recorded here because the original decision line above was left standing while the scope grew past it.
- "Unknown not clean" guardrail: empty damage records ≠ clean — only means no damage reported to insurers. UI copy must never say "no accidents found."
- `api.cepik.gov.pl` currently unreliable (0% uptime reported June 2026) — circuit breaker mandatory.
  - **Resolved 2026-08-25 (post-merge verification):** the API is back up, and with a trusted TLS chain the real failure became visible: `api.cepik.gov.pl` exposes **no VIN field at all** (68 attributes on `pojazdy`, none a VIN), rejects `filter[numer-vin]`, requires `wojewodztwo`, and caps the date range at 2 years — so the 16-voivodeship `CepikApiService` VIN scan could never have succeeded, and every request returned HTTP 400. It has been deleted along with `CepikApiConfig` and its tests; a missing first-registration date now short-circuits to `MISSING_INPUTS` and the user is asked for the date. historiapojazdu remains the only real data source and is confirmed working.
- SSL legacy cipher issue in Java — needs custom SSLContext config.
- Schema: add `CepikResult` as nullable field on `AnalysisResponse` (not `AnalysisResult`) — same pattern as S-05 `MarketPriceContext`.
