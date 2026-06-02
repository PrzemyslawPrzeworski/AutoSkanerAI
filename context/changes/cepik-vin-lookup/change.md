---
change_id: cepik-vin-lookup
title: Display CEPiK vehicle history alongside analysis using extracted VIN
status: implemented
created: 2026-06-02
updated: 2026-06-02
archived_at: null
---

## Notes

When a VIN is extracted from the listing, query the CEPiK registry and display vehicle history (first registration date, ownership count, mileage stamps, significant damage records) alongside the analysis. If VIN absent or lookup fails — show section as unavailable, never silently omit.

**Key findings from research (see research.md):**

- The codebase only extracts `Boolean vinPresent` — NOT the actual VIN string. First deliverable of S-04 must be extending `ExtractedData` + LLM prompt to extract the actual VIN value.
- Two separate systems: `api.cepik.gov.pl` (public, no auth, technical data only — no accidents/owners/mileage) and `historiapojazdu.gov.pl` (full history, but no public REST API — session scraping only).
- `historiapojazdu` requires all three: registration plate + VIN + first registration date. VIN alone is not enough.
- MVP approach: use public API for registry confirmation (VIN exists, first registration date, deregistration status), plus a "check full history" link to historiapojazdu.gov.pl for the user. Skip session scraping in MVP.
- "Unknown not clean" guardrail: empty damage records ≠ clean — only means no damage reported to insurers. UI copy must never say "no accidents found."
- `api.cepik.gov.pl` currently unreliable (0% uptime reported June 2026) — circuit breaker mandatory.
- SSL legacy cipher issue in Java — needs custom SSLContext config.
- Schema: add `CepikResult` as nullable field on `AnalysisResponse` (not `AnalysisResult`) — same pattern as S-05 `MarketPriceContext`.
