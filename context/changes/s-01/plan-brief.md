# S-01: Core Analysis Flow — Plan Brief

> Full plan: `context/changes/s-01/plan.md`

## What & Why

Build the first user-facing feature: a single-page Angular app where the user pastes a URL or listing text, waits for AI analysis, and reads a structured result. This is the north-star slice — it proves the core product hypothesis that AutoSkanerAI saves time and catches what a human buyer would miss.

## Starting Point

F-01 is complete: `POST /api/analyses` accepts `{ listingText }` and returns a full `AnalysisResult` under three profiles (`mock`, `bedrock`, `openrouter`). The Angular frontend is a bare scaffold — empty routes, no components, no UI library, `HttpClient` not wired.

## Desired End State

User visits the app, pastes a URL or listing text, clicks "Analizuj", waits through a skeleton loading state with rotating Polish status messages, and sees: a color-coded verdict hero card, category score progress bars, extracted data table, risk flags with severity badges, equipment grid, seller questions, and an analysis meta footer. If URL fetch fails (expected on Render's datacenter IPs), a yellow banner auto-appears and the text paste area is ready. Errors show inline below the submit button. "Nowe ogłoszenie" resets everything.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| UI library | PrimeNG 19 | Ready-made Table, ProgressBar, Tag, Skeleton — everything S-01 needs; tree-shakeable | Plan |
| devServer proxy | proxy.conf.json → localhost:10000 | Avoids CORS in dev; matches future production path structure | Plan |
| API response shape | New `AnalysisResponse` wrapper | Carries `fetchStatus` so frontend distinguishes URL failure from analysis failure without HTTP status juggling | Plan |
| URL fetch | Real fetch + Jsoup + auto-fallback | Implements FR-001 honestly; failure on Render is expected and handled gracefully | Plan |
| SSRF protection | Block private IP ranges (no domain whitelist) | User accepted any-URL input; private IP blocking prevents internal network probing | Plan |
| Input layout | URL primary, text paste always visible below | Mirrors US-01 flow; fallback path requires no extra click | Plan |
| URL fetch fallback UX | Auto-show yellow banner + highlight textarea | No dead end; user understands what happened and acts immediately | Plan |
| Loading UX | Full-page skeleton + rotating messages | Perceived wait is lower; preview hints at output richness | Plan |
| Results placement | Replace input in-place; "Nowe ogłoszenie" resets | Simple navigation; no route state management needed for MVP | Plan |
| Result layout order | Verdict → scores → data → risks → equipment → questions | Most actionable output first; user can stop reading early on HIGH_RISK_SKIP | Plan |
| Error display | Inline below submit button | Input stays visible; user can retry immediately without re-entering data | Plan |
| Risk flag display | Severity badge + description; collapse if > 4 | Visual hierarchy between HIGH/MEDIUM/LOW; long lists don't overwhelm | Plan |
| Score display | PrimeNG ProgressBar per category | Scannable in one glance; color-coded at ≥70/40–69/<40 thresholds | Plan |
| Testing scope | Backend integration + Angular unit tests | Covers the real failure modes (SSRF, fetch fallback, HTTP error mapping) without E2E infra | Plan |

## Scope

**In scope:** URL input (FR-001), text paste input (FR-002), auto URL-fetch fallback, SSRF protection, Jsoup HTML stripping, `AnalysisResponse` API wrapper, PrimeNG 19 install, Angular devServer proxy, `AnalysisService`, `AnalyzerComponent`, `AnalysisResultComponent` with all 7 sections, backend + Angular unit tests.

**Out of scope:** Manual field entry (FR-003, S-02), persistence (S-03), auth (F-03), Cloudflare Pages `/api` proxy config, E2E tests, Playwright/headless Chrome, paid scraping APIs, route navigation beyond `/`.

## Architecture / Approach

Backend: `AnalysisRequest` gains an optional `url` field; a new `ListingFetchConfig` + `ListingFetchService` handles fetch → SSRF check → HTML strip; `AnalysisController` routes between URL and text paths and returns `AnalysisResponse` wrapping the existing `AnalysisResult`. Frontend: PrimeNG 19 added; Angular devServer proxies `/api` to `:10000`; `AnalysisService` wraps `HttpClient`; `AnalyzerComponent` owns the full UX state machine (idle → loading → result/error/url-failed); `AnalysisResultComponent` is a pure display component receiving `AnalysisResult` as `@Input`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Backend URL fetch + wrapper | `ListingFetchService`, `AnalysisResponse`, extended `AnalysisRequest`, updated controller | `AnalysisControllerTest` JSONPath assertions must be updated for new wrapper shape |
| 2. Angular foundation | PrimeNG, proxy, `HttpClient`, models, `AnalysisService` | `environment.ts apiUrl` must change to `''` or proxy won't work |
| 3. Analyzer input page | Full input UX state machine (idle, loading, fallback, errors) | Loading skeleton must pair with the real result layout (coupling) |
| 4. Result display | All 7 sections of `AnalysisResult` rendered, tested | `DatePipe` needed for `generatedAt`; null-field handling throughout |

**Prerequisites:** F-01 complete (it is), backend running on `:10000`, AWS credentials for bedrock live verification.
**Estimated effort:** ~3–4 sessions across 4 phases.

## Open Risks & Assumptions

- URL fetch will almost always fail on Render's datacenter IPs — text paste is the real production path for now. This is a known trade-off, not a bug.
- PrimeNG 19 + Angular 21 peer dependency compatibility should be verified during `npm install` (Phase 2).
- `ListingFetchService` SSRF check uses `InetAddress.getAllByName()` which does a DNS lookup — a DNS rebinding attack could bypass this. Acceptable risk for a personal MVP.
