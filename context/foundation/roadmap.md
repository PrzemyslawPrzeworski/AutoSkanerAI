---
project: AutoSkanerAI
version: 1
status: draft
created: 2026-05-25
updated: 2026-09-03
prd_version: 1
main_goal: market-feedback
top_blocker: time
---

# Roadmap: AutoSkanerAI

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Vision recap

AutoSkanerAI compresses used-car listing evaluation from tens of minutes to a few minutes. Given a listing (URL, pasted text, or manual fields), the app extracts key facts, flags risks, generates seller questions, and produces a scored recommendation — helping a private buyer quickly decide whether an offer is worth their time. Primary user is the builder, shaped from personal car-buying experience. 3-week after-hours MVP.

## North star

**S-01: user can paste a URL (or listing text) and receive the full AI analysis** — this is the smallest end-to-end user-visible flow — meaning a complete path from input through backend to rendered output, touching every layer — that proves the core product hypothesis: does AutoSkanerAI actually save time and catch what a human buyer would miss? Placed as early as its Prerequisites allow because everything else only matters if this works.

> If S-01 is good on real Polish listings, the rest of the roadmap is feature expansion. If it's not, priorities shift before more foundations are built.

## At a glance

| ID   | Change ID                 | Outcome (user can …)                                         | Prerequisites    | PRD refs                                          | Status   |
|------|---------------------------|--------------------------------------------------------------|------------------|---------------------------------------------------|----------|
| F-01 | llm-analysis-wiring       | (foundation) LlmAnalysisService calls real LLM API           | —                | FR-004, FR-006, FR-007, FR-008, FR-009            | shipped  |
| F-02 | data-layer-setup          | (foundation) PostgreSQL + JPA + Flyway migrations in place   | —                | FR-010, FR-011, FR-012                            | ready    |
| F-03 | auth-scaffold             | (foundation) login/register wired; protected routes in place | F-02             | FR-010                                            | proposed |
| S-01 | core-analysis-flow        | paste URL or text → receive full AI analysis                 | F-01             | FR-001, FR-002, FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, US-01 | shipped  |
| S-02 | manual-field-entry        | fill in key fields manually → receive full AI analysis       | S-01             | FR-003                                            | shipped  |
| S-03 | save-view-delete-analyses | save an analysis, view saved list, delete entries            | S-01, F-02, F-03 | FR-010, FR-011, FR-012                            | proposed |
| S-04 | cepik-vin-lookup          | see live CEPiK vehicle history alongside analysis            | S-01             | FR-017                                            | done     |
| S-05 | market-price-context      | see comparable market price range alongside analysis         | S-01             | FR-018                                            | done     |

Remaining must-have scope is the chain **F-02 → F-03 → S-03**. Everything else above is merged to `main` and verified against production.

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme                 | Chain                                          | Note                                                                              |
|--------|-----------------------|------------------------------------------------|-----------------------------------------------------------------------------------|
| A      | LLM Analysis Core     | `F-01` → `S-01` → `S-02` / `S-04` / `S-05`   | Delivers the north star (S-01) and all AI input modes. S-04 and S-05 enrich the analysis with external data. Ship S-01 first. |
| B      | Account & Persistence | `F-02` → `F-03` → `S-03`                      | Enables saving analyses. F-02 has no deps — Stream B can start in parallel with Stream A from day one. |

## Baseline

What's already in place in the codebase as of 2026-05-25 (auto-researched + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** partial — Angular 21.2 scaffold; routing empty (`app.routes.ts:1`); no feature components; no UI component library
- **Backend / API:** partial — Spring Boot 4.0.6; `RiskAnalysisController` stub + `AiAnalysisService` interface + `MockAiAnalysisService`; `LlmAnalysisService` stubbed (`UnsupportedOperationException`)
- **Data:** absent — no JDBC driver, JPA, Flyway/Liquibase, entities, or migrations in `pom.xml`
- **Auth:** absent — no Spring Security dep, no JWT/OAuth2, no `User` entity
- **Deploy / infra:** present — Render + Cloudflare Pages live; `render.yaml`, `Dockerfile`, `CorsConfig` in place; auto-deploy on push to `main` wired on both platforms
- **Observability:** partial — Spring Actuator `/health`; SLF4J/Logback available; no Sentry/OTEL/custom metrics

## Foundations

### F-01: LLM analysis wiring

- **Outcome:** (foundation) `LlmAnalysisService` makes real calls to the configured LLM provider (Claude API or OpenAI); `MockAiAnalysisService` returns a realistic structured response matching the locked output schema (data table + equipment breakdown + risk flags + seller questions + per-category scores + verdict label). The response schema is defined and stable before S-01 begins.
- **Change ID:** `llm-analysis-wiring`
- **PRD refs:** FR-004 (extraction), FR-006 (equipment analysis), FR-007 (risk flags), FR-008 (seller questions), FR-009 (recommendation + scores)
- **Unlocks:** S-01 — every analysis feature depends on this being wired; without a real LLM response no slice can be validated on real listings.
- **Prerequisites:** —
- **Parallel with:** F-02
- **Blockers:** —
- **Unknowns:** resolved — neither Claude API nor OpenAI direct. Three profile-switched implementations shipped (`mock`, `bedrock`, `openrouter`); production runs `openrouter`. `bedrock` is dev-only because the only AWS credential source here is a corporate SSO profile issuing short-lived credentials, which cannot back a hosted service.
- **Risk:** retired — the schema was locked before S-01's display layer and has not changed since. **Carried forward:** free-tier OpenRouter slugs are the main production fragility. Two failure axes are handled separately (transient 429/5xx → retry the same model honouring `Retry-After`; permanent 404/400 → skip to the next candidate; 401/403 or malformed shape → fail fast), because an immediate retry against a saturated free pool turned single 429s into 502s on 2026-08-26.
- **Status:** shipped (closed out `bd9b6e3`; later hardening in `64feb46`, `f9e3762`, `315f9b3`, `a8525ee`)

---

### F-02: Data layer setup

- **Outcome:** (foundation) PostgreSQL wired via Spring Data JPA; Flyway manages schema migrations; at minimum an `Analysis` entity with an initial migration exists; H2 in-memory database available for local/test runs.
- **Change ID:** `data-layer-setup`
- **PRD refs:** FR-010, FR-011, FR-012
- **Unlocks:** F-03 (auth needs a `User` entity in the database); S-03 (persisting analyses requires the data layer to be in place).
- **Prerequisites:** —
- **Parallel with:** F-01
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Supabase free-tier project already provisioned (`db.bahoxzvhamktpepmkaft.supabase.co`). HikariCP pool size must be capped at 5 connections (`spring.datasource.hikari.maximum-pool-size=5`) to stay within the 60-connection free-tier limit. If the entity model changes significantly after F-03 starts (e.g., auth adds foreign keys to `User`), a compensating Flyway migration is needed — plan the schema to include a `user_id` column from the start.
- **Status:** ready

---

### F-03: Auth scaffold

- **Outcome:** (foundation) Spring Security wired with JWT (or Spring Security OAuth2); `User` entity in the database; `/api/auth/register` and `/api/auth/login` endpoints live; Angular login/register forms, token storage, and route auth guard in place; unauthenticated requests to `/api/**` return 401.
- **Change ID:** `auth-scaffold`
- **PRD refs:** FR-010 (Access Control: email+password or OAuth, flat user model, analyses tied to authenticated account)
- **Unlocks:** S-03 — analyses must be associated with an authenticated user; without auth there is no "account" to save to.
- **Prerequisites:** F-02
- **Parallel with:** S-01 (once F-02 is done, auth scaffold can be built concurrently with Stream A work on S-01)
- **Blockers:** —
- **Unknowns:**
  - Email+password only vs. include an OAuth provider (Google/GitHub) in MVP? PRD names both as options but doesn't require OAuth at launch. — Owner: user. Block: no (email+password is sufficient for MVP; OAuth can follow as S-NN post-MVP).
- **Risk:** Spring Boot 4 changes some Security auto-configuration defaults vs Boot 3. The existing `CorsConfig` (`WebMvcConfigurer`) must be migrated into the `SecurityFilterChain` bean when Spring Security is added — if not, CORS breaks for authenticated API requests. Address this in the first commit of this foundation.
- **Status:** proposed

## Slices

### S-01: Core analysis flow

- **Outcome:** user can paste a URL to a Polish used-car listing and receive the full analysis — or, if the URL cannot be fetched, fall back to pasting raw listing text — and see: extracted structured data table (make, model, year, price, mileage, fuel, transmission, equipment, service history, accident claims, origin, seller type), equipment breakdown (confirmed / missing / unclear), risk flags with short explanations, list of questions to ask the seller, per-category scores (completeness, equipment, risk, value, overall), and verdict label (worth checking / check after more info / high risk — skip).
- **Change ID:** `core-analysis-flow`
- **PRD refs:** FR-001, FR-002, FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, US-01
- **Prerequisites:** F-01
- **Parallel with:** F-02, F-03 (both can run concurrently — neither depends on S-01 at this stage)
- **Blockers:** —
- **Unknowns:** resolved — URLs are fetched through Jina Reader (`https://r.jina.ai/<url>`), which handles JS rendering and Cloudflare. A failed fetch returns `fetchStatus: "url_failed"` with a null analysis and the frontend shows the text-paste fallback.
- **Risk:** partially retired. Extraction quality on real Polish listings is good — verified against live Otomoto listings; the LLM correctly reads price, mileage, plate, prose dates and equipment. **Carried forward:** the scoring layer trusts the listing's own claims, which is how a car advertised as `bezwypadkowy` scored `risk: 88 / WORTH_CHECKING` while the registry showed a szkoda istotna. Fixed for CEPiK facts by `CepikRiskAdjuster` (2026-08-26), but the general lesson stands: any future enrichment must be folded into the score explicitly, because the LLM scores before enrichment runs and never sees it.
- **Carried forward:** `POST /api/analysis/risk` was to be removed once S-01 shipped. It is still live.
- **Status:** shipped (closed out `2175a70`; later hardening in `e81748c`, `e02135f`, `5f5e733`)

---

### S-02: Manual field entry

- **Outcome:** user can fill in key listing fields manually via a structured form (make, model, year, price, mileage, fuel, transmission, and any additional free-text notes) and receive the same full AI analysis as S-01. **Scope extended 2026-08-26:** the form must also accept **VIN, registration plate, and first registration date**, prefilled from the LLM extraction where available and overriding it where the user types a value.
- **Change ID:** `manual-field-entry`
- **PRD refs:** FR-003, and FR-017 in practice (see below)
- **Prerequisites:** S-01
- **Parallel with:** S-03 (once S-01 is done, manual entry and persistence can be developed concurrently if capacity allows)
- **Blockers:** —
- **Unknowns:** —
- **Why the scope grew:** this entry was written before S-04's research established that vehicle history needs a plate + VIN + first-registration-date triple. **Otomoto encrypts the VIN for logged-out fetches** (verified 2026-08-26 on `toyota-corolla-ID6HG6ZH`: the plate and a prose date come through, `vinPresent: true` with `vin: null`), and no public plate→VIN service exists in Poland. So a URL-only analysis structurally cannot produce a CEPiK result, and S-02 is not a convenience slice — **it is the only legitimate path to making S-04 fire on real listings.** Without it CEPiK works only when a seller happens to type the VIN into the description, roughly 1 listing in 10.
- **Risk:** retired. It was the lowest-risk slice as predicted — the enrichment path keys off `ExtractedData`, so accepting overrides needed no change to CEPiK or market-price logic. The precedence trap was the only real one: a typed value wins, a blank field never nulls a good extraction. `accidentClaim` is deliberately left out of the overrides, because it is the *listing's* claim and `CepikRiskAdjuster` compares it against the registry — a user "correcting" it would erase the contradiction finding.
- **Carried forward:** the "Sprawdź historię pojazdu" follow-up re-runs the whole analysis (~30 s) rather than calling a lookup-only endpoint, because CEPiK findings only reach `scores` / `verdict` through `CepikRiskAdjuster` on the analysis path. A cheaper enrich-only endpoint would need the adjustment moved or duplicated.
- **Status:** shipped (closed out `d259bdc` 2026-08-26; manual mode, overrides and both validation failures verified against production the same day — `fetchStatus: "manual"`, prose date normalised, `marketPriceContext.status=OK`)

---

### S-03: Save, view, and delete analyses

- **Outcome:** user can save an analysis to their account, view a list of all previously saved analyses (showing car name/identifier, analysis date, verdict label, and overall score), and delete a saved analysis.
- **Change ID:** `save-view-delete-analyses`
- **PRD refs:** FR-010, FR-011, FR-012
- **Prerequisites:** S-01, F-02, F-03
- **Parallel with:** S-02 (once all prerequisites are met)
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Has the longest prerequisite chain: F-02 → F-03 → S-03. Start Stream B (F-02, then F-03) early and in parallel with Stream A so it doesn't become the critical path blocker for completing the must-have scope. The save action must handle the unauthenticated case gracefully — either gate the button behind the auth guard or redirect to login inline.
- **Status:** proposed

---

### S-04: CEPiK VIN lookup

- **Outcome (as delivered):** when the LLM extracts **all three** of VIN + registration plate + first registration date, the app opens a fresh `moj.gov.pl` session, queries `historiapojazdu.gov.pl`, and displays the registry's vehicle history alongside the analysis — registry identity (make, model, type, year, province), registration and inspection status, OC validity, owner count, dated mileage stamps, registered significant damages with insurer and damage categories, theft and odometer-rollback markers, and the full event timeline. Any missing or malformed input yields `MISSING_INPUTS` and a seller question; a broken lookup yields `LOOKUP_FAILED`, worded differently from `NOT_FOUND`. The section is always shown, never silently omitted, and **`null` (not checked) renders differently from `[]` (registry reported nothing)**.
- **Change ID:** `cepik-vin-lookup`
- **PRD refs:** FR-017
- **Prerequisites:** S-01 (VIN extraction must be working; analysis result must display before enrichment is added)
- **Parallel with:** S-02, S-05, F-02, F-03
- **Blockers:** —
- **Unknowns:** resolved during research and then again in production. `api.cepik.gov.pl` exposes technical data only and **cannot look up a vehicle by VIN at all** (verified against the live endpoint 2026-08-25: none of the `pojazdy` resource's 68 attributes is a VIN, `filter[numer-vin]` is rejected, `wojewodztwo` is mandatory). So full history required session scraping of `historiapojazdu.gov.pl` with the plate + VIN + first-registration-date triple, past the original "skip session scraping in MVP" decision; see `context/archive/2026-06-02-cepik-vin-lookup/change.md`. Two further contract details only surfaced live: the API version sits in the URL path and rotates (`1.0.17` → `1.1.0`, now discovered from the bootstrap HTML rather than pinned), and `firstRegistrationDate` is accepted only as `yyyy-MM-dd`.
- **Risk:** Government APIs can be slow or unavailable — realised, and worse than expected in a way the original entry did not anticipate. **The `FOUND` branch had never run against a real vehicle until 2026-08-26, and every field name in `HistoriaPojazduParser` was invented.** The registry actually returns `technicalData.basicData` and `timelineData.events[]`; the parser looked for `zdarzenia` / `szkodyIstotne` / `przebieg`, found nothing, and produced `damageRecords: []`, which the UI rendered as "brak zgłoszonych szkód istotnych" for a car carrying a registered szkoda istotna. **The tests passed throughout, because the fixtures were hand-written to match the invented names.** Fixed 2026-08-26 against verbatim captured payloads; fixtures in `src/test/resources/cepik/` must now stay verbatim captures, and no field mapping may be added without a capture showing that name.
- **Carried forward:**
  - Enrichment is still synchronous inside `AnalysisController.buildResponse()` — up to 3 historiapojazdu calls plus the S-05 Jina fetch on one request thread, ~27 s end to end. Async handling remains deferred (impl-review F10).
  - `HistoriaPojazduServiceLiveTest` still only asserts `NOT_FOUND`. A `FOUND` assertion needs a real plate+VIN+date triple, which cannot be committed to a public repo, so the branch that broke stays unguarded by a live test.
  - The registry-vs-listing mileage cross-check lives only in the frontend component and does not feed the score.
- **Lesson:** a green test suite over fabricated fixtures is worse than no test, because it converts "unverified" into "verified" on the status board. The same shape as the live-test rule already in `CLAUDE.md` — a test that tolerates the failure mode it exists to catch is decoration.
- **Status:** done (closed out `d5e0fed` 2026-08-25; `FOUND` path first verified against the live registry 2026-08-26; parser fix `8870d35`, UI fixes `48b32dc`, scoring fix `5b7a3b3`)

---

### S-05: Market price context

- **Outcome:** given extracted make, model, year, and mileage, the app builds a filtered Otomoto search URL, fetches it through Jina Reader, extracts prices from the returned markdown, and surfaces a comparable price range (e.g. "similar cars listed at 35 000–42 000 PLN") so the user can judge whether the listing price is fair, high, or suspiciously low.
- **Change ID:** `market-price-context`
- **PRD refs:** FR-018
- **Prerequisites:** S-01 (extraction must be working; make/model/year/mileage fields feed the query)
- **Parallel with:** S-02, S-04, F-02, F-03
- **Blockers:** none — reuses the existing Jina Reader infrastructure from FR-001; no new API key required. (An earlier draft assumed the Exa search API and an `EXA_API_KEY`; research rejected that in favour of Jina on Otomoto — see `context/archive/2026-06-02-market-price-context/research.md`.)
- **Unknowns:** resolved during research — Otomoto slug mapping, the `### <price>\nPLN` markdown price format, and min/median/max computation are all settled in the plan.
- **Risk:** extraction is regex-based against Otomoto's Jina-rendered markdown. A change in Otomoto's price formatting silently breaks the range and yields `INSUFFICIENT_DATA`; the small-sample caveat (`sampleSize < 3`) must stay visible in the UI so a thin result is never read as a confident range. Verified live 2026-08-26: `status=OK` with `sampleSize=40`, so `PRICE_PATTERN` does match current Otomoto markdown, and the median tracks reality (68 900 against a 72 900 listing).
- **Both carried-forward defects fixed 2026-08-26 (`MarketPriceStatistics`, 12 new unit tests):**
  - **`min`/`max` were contaminated.** A live run returned `min=39900` against `median=82900`, an earlier one `min=22900` for 2017–2021 Corollas. Now trimmed in two passes: a ±3× median band for order-of-magnitude junk (financing instalments render in the same `### <n>\nPLN` block and clear the `1_000..10_000_000` guard), then Tukey's 1.5×IQR fence on samples of 8+ for salvage/wrong-trim listings. `sampleSize` counts the kept prices so the small-sample caveat stays honest; the discarded count is logged.
  - **`median = prices.get(prices.size() / 2)`** is now a real median, averaged over both middle elements on an even sample.
  - Still worth knowing: the trim is statistical, not semantic. It cannot tell a legitimately cheap high-mileage example from a salvage title — it only says "this is not what the rest of this market asks".
- **Status:** done (closed out `51db3fb` 2026-08-25; verified live 2026-08-26)

---

## Backlog Handoff

| Roadmap ID | Change ID                 | Suggested issue title                                         | Ready for `/10x-plan` | Notes                              |
|------------|---------------------------|---------------------------------------------------------------|-----------------------|------------------------------------|
| F-01       | llm-analysis-wiring       | Wire LlmAnalysisService to real Claude/OpenAI API             | shipped               | Merged as `bd9b6e3`; runs `openrouter` in production |
| F-02       | data-layer-setup          | Add PostgreSQL + Spring Data JPA + Flyway to backend          | yes                   | Run `/10x-plan data-layer-setup`. Confirmed unstarted 2026-08-26 — `pom.xml` still has no JPA, Flyway, PostgreSQL or H2 dependency |
| F-03       | auth-scaffold             | Wire Spring Security + JWT; Angular login/register + guards   | no                    | Needs F-02 first. Confirmed unstarted — no Spring Security or JWT dependency |
| S-01       | core-analysis-flow        | Full analysis flow: URL + text paste → AI output on screen    | shipped               | Merged as `2175a70`               |
| S-02       | manual-field-entry        | Manual entry form (incl. VIN / plate / first registration) → same AI analysis | shipped | Merged as `d259bdc`. This is what makes S-04 fire on real listings |
| S-03       | save-view-delete-analyses | Save / view list / delete saved analyses                      | no                    | Needs F-02 + F-03 (S-01 is done)  |
| S-04       | cepik-vin-lookup          | Live CEPiK vehicle history alongside analysis                 | shipped               | Merged as `d5e0fed`; `FOUND` path fixed and verified 2026-08-26 |
| S-05       | market-price-context      | Comparable market price range (Otomoto via Jina Reader)       | shipped               | Merged as `51db3fb`; the two `min`/`median` defects fixed 2026-08-26 |

Not roadmap items, but tracked here so they are not lost — small carried-forward fixes with no slice of their own:

| Fix | Where | Why it matters |
|-----|-------|----------------|
| ~~Wire a frontend test runner~~ | `package.json`, `angular.json` | **Done 2026-08-26.** `@angular/build:unit-test` + vitest + jsdom; `npm test -- --watch=false` runs 26 tests in 3 files. The specs had never executed, and two of them did not compile. No `fakeAsync`/`tick` — the app is zoneless (no zone.js), so `fakeAsync` throws |
| ~~Trim outliers from the market price range; fix the even-sample median~~ | `MarketPriceStatistics` | **Done 2026-08-26.** See S-05 above. Verified by 12 unit tests; not yet re-verified against a live Otomoto page |
| Remove the deprecated `POST /api/analysis/risk` facade | `RiskAnalysisController` | It was to go when S-01 shipped; it is still live and duplicates a subset of `POST /api/analyses` |
| `GET /health` returns 500, not 404 | Actuator config | An uptime probe pointed at it reads as "app broken" rather than "wrong path" |

## Open Roadmap Questions

The PRD shipped with 0 open questions (quality check: accepted 2026-05-24), and slice-level unknowns are tracked in the entries above. Two questions have opened since, both from live operation on 2026-08-26 — neither gates a `/10x-plan` invocation:

- **Is a ~27 s synchronous analysis acceptable for the MVP, or does async/streaming move into must-have scope?** Every enrichment runs on the request thread (impl-review F10 deferred it). It works, but it is close to the limit of what a user will wait through without feedback. — Owner: user. Block: no.
- **Stay on free-tier OpenRouter slugs, or spend a few dollars on a paid model?** The free pool is the main production fragility (retired slugs, saturated pools returning 429) and the largest single component of the 27 s. — Owner: user. Block: no.

## Parked

- **FR-013: Compare listings side by side** — Why parked: PRD nice-to-have; requires multiple saved analyses (S-03) as a prerequisite and adds significant UI complexity; post-MVP.
- **FR-014: Search advisor (budget + requirements → model/trim suggestions)** — Why parked: PRD nice-to-have; independent of the core analysis flow; post-MVP scope signal.
- **FR-015: Personal preferences (budget, required equipment, priorities)** — Why parked: PRD nice-to-have; personalises risk and recommendation output but doesn't change the core value proposition.
- **FR-016: Manual vehicle history report input + interpretation** — Why parked: PRD nice-to-have; adds value alongside S-01 but requires the analysis pipeline to be stable first; post-MVP.
- **No monetisation, no mobile native app, no sharing of analyses between users** — Why parked: PRD §Non-Goals; out of scope for MVP.

## Done

- **S-04: see live CEPiK vehicle history alongside analysis** — Archived 2026-09-03 → `context/archive/2026-06-02-cepik-vin-lookup/`. Lesson: —.
- **S-05: see comparable market price range alongside analysis** — Archived 2026-09-03 → `context/archive/2026-06-02-market-price-context/`. Lesson: —.
