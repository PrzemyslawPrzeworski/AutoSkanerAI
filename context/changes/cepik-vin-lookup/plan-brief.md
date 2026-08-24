# CEPiK VIN Lookup — Plan Brief

> Full plan: `context/changes/cepik-vin-lookup/plan.md`
> Research: `context/changes/cepik-vin-lookup/research.md`

## What & Why

AutoSkanerAI analyses listing text but has no way to verify what the seller says about the car's history. S-04 closes that gap by querying the Polish CEP registry (historiapojazdu.gov.pl) using data extracted from the listing, surfacing owner count, mileage stamps from technical inspections, and significant damage records directly in the app.

## Starting Point

The codebase extracts only `Boolean vinPresent` — a flag, not the value. `AnalysisResponse` has no enrichment fields yet. No CEPiK service, config, or frontend panel exists. S-05 (market price context) will add a parallel enrichment field to the same `AnalysisResponse`, so both changes must coordinate on a shared base commit.

## Desired End State

When a listing contains a VIN, registration plate, and first registration date, the analysis result shows a "Historia CEPiK" panel: number of owners, last mileage reading, and a damage section with guardrail-safe copy. If any input is missing, the corresponding question is injected into the seller questions list. If the registry call fails, the panel degrades gracefully to "niedostępna" with a manual link — the main analysis always renders.

## Key Decisions Made

| Decision | Choice | Why | Source |
|---|---|---|---|
| Data source | `historiapojazdu.gov.pl` session scraping | Only source with owners/mileage/damage; public API has none of these | Research |
| public `api.cepik.gov.pl` role | Fallback to fill missing `firstRegistrationDate` only | Useful secondary value; not shown to user | Plan |
| Missing inputs handling | Inject seller questions, show `MISSING_INPUTS` state | Registration plate rarely in listings; user must ask seller | Plan |
| Failure mode | `LOOKUP_FAILED` + manual link, 5s timeout | Session scraping is unofficial — must degrade gracefully, never block analysis | Plan |
| Schema placement | Nullable field on `AnalysisResponse` (not `AnalysisResult`) | CEPiK is independent of LLM output; same async-enrichment pattern as S-05 | Research |
| S-04/S-05 conflict | Shared base commit on `main` stubs both fields | Eliminates the one guaranteed merge conflict before either branch starts | Plan |
| VIN validation | Normalise (trim/uppercase/strip) + 17-char format check | LLM may return partial VINs; cheap fail-fast before session call | Plan |
| Damage copy | "Brak zgłoszonych szkód istotnych… nie wyklucza napraw niezgłoszonych" | Core business rule: absence ≠ clean | Research / PRD |

## Scope

**In scope:**
- VIN string, registration plate, first registration date extraction by LLM
- `api.cepik.gov.pl` 16-voivodeship parallel scan for missing first reg date
- `historiapojazdu.gov.pl` session scraping: owners, mileage stamps, damage records
- Frontend "Historia CEPiK" collapsible panel with all four states
- Missing-input questions injected into `sellerQuestions`

**Out of scope:**
- `api.cepik.gov.pl` data shown to the user (used only as gap-fill)
- Ownership transfer timeline (dates) — only total count
- CEPiK 2.0 B2B formal API
- Caching CEPiK results
- Async controller response (analysis waits for CEPiK — may add latency; deferred)

## Architecture / Approach

After the LLM analysis returns, `AnalysisController` runs `CepikEnrichmentService`: validates inputs, fires `CepikApiService` if first reg date is missing (16-voivodeship `CompletableFuture.anyOf()` scan against the public API), then calls `HistoriaPojazduService` (moj.gov.pl session → vehicle-data + timeline-data → close). The result is attached to `AnalysisResponse.cepikResult`. Mock profile returns `LOOKUP_FAILED` immediately. Frontend renders a dedicated `CepikResultComponent` for each status.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Shared schema base commit | `CepikResult` types + stub fields on `AnalysisResponse`; lands on `main` | Must land before S-04 or S-05 branches start |
| 2. CEPiK input extraction | LLM extracts VIN string, plate, first reg date; data table updated | LLM quality on extracting plate/date from varied listing formats |
| 3. CepikApiService | Public API first-reg-date fallback; 16-voivodeship parallel scan | `api.cepik.gov.pl` currently 0% uptime; SSL legacy cipher config |
| 4. HistoriaPojazduService | Session scraping full history from historiapojazdu.gov.pl | Unofficial flow; can break on moj.gov.pl updates |
| 5. Controller orchestration | End-to-end wiring; missing-field question injection; mock guard | Session latency adds to analysis response time |
| 6. Frontend CEPiK panel | Collapsible card; all 4 states; guardrail-safe copy | Damage copy must never imply clean history |

**Prerequisites:** Phase 1 must land on `main` before any branch starts. S-01 (core-analysis-flow) is the prerequisite for S-04 per the roadmap — extraction must be working in production before enrichment adds value.

**Estimated effort:** ~3–4 sessions across 6 phases. Phases 3 and 4 carry the most unknowns (external API behaviour); allow extra time for manual testing against live gov.pl.

## Open Risks & Assumptions

- `historiapojazdu.gov.pl` session flow may change without notice — monitor for HTTP 4xx/5xx on session init and update `NF_WID` format if needed
- `api.cepik.gov.pl` is currently unreliable (0% uptime reported) — Phase 3 may be a no-op in practice; the 8s parallel scan adds latency when the API is partially up
- Registration plate is almost never in Polish listing text — `MISSING_INPUTS` will be the most common CEPiK outcome for most users; the seller question injection is the primary value delivery path
- First registration date may be in varied formats in listing text — LLM extraction reliability needs validation on real listings

## Success Criteria (Summary)

- `POST /api/analyses` always returns a `cepikResult` field — never null, never an uncaught exception
- When VIN + plate + date are all present, the CEPiK panel shows live registry data (or graceful failure)
- Damage section copy never says "brak wypadków" or implies clean history
