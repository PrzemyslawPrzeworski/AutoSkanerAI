---
project: AutoSkanerAI
version: 1
status: draft
created: 2026-05-25
updated: 2026-05-25
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
| F-01 | llm-analysis-wiring       | (foundation) LlmAnalysisService calls real LLM API           | —                | FR-004, FR-006, FR-007, FR-008, FR-009            | ready    |
| F-02 | data-layer-setup          | (foundation) PostgreSQL + JPA + Flyway migrations in place   | —                | FR-010, FR-011, FR-012                            | ready    |
| F-03 | auth-scaffold             | (foundation) login/register wired; protected routes in place | F-02             | FR-010                                            | proposed |
| S-01 | core-analysis-flow        | paste URL or text → receive full AI analysis                 | F-01             | FR-001, FR-002, FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, US-01 | proposed |
| S-02 | manual-field-entry        | fill in key fields manually → receive full AI analysis       | S-01             | FR-003                                            | proposed |
| S-03 | save-view-delete-analyses | save an analysis, view saved list, delete entries            | S-01, F-02, F-03 | FR-010, FR-011, FR-012                            | proposed |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme                 | Chain                           | Note                                                                              |
|--------|-----------------------|---------------------------------|-----------------------------------------------------------------------------------|
| A      | LLM Analysis Core     | `F-01` → `S-01` → `S-02`        | Delivers the north star (S-01) and all AI input modes. Ship this first.           |
| B      | Account & Persistence | `F-02` → `F-03` → `S-03`        | Enables saving analyses. F-02 has no deps — Stream B can start in parallel with Stream A from day one. |

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
- **Unknowns:**
  - Which LLM provider to call by default — Claude API (`ANTHROPIC_API_KEY`) or OpenAI (`OPENAI_API_KEY`)? Both keys are in `.env`. Decision affects prompt design and error handling. — Owner: user. Block: no (mock profile available as fallback during development).
- **Risk:** The LLM output schema must be locked before S-01's frontend display layer is built. If the schema changes after S-01 starts, both backend prompt and frontend rendering need rework. Define and freeze the schema as the first deliverable of this foundation.
- **Status:** ready

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
- **Unknowns:**
  - URL fetching fallback UX: when a URL cannot be scraped (Otomoto/OLX block bots), does the app automatically redirect to a text-paste input, or does the user manually switch? — Owner: user. Block: no (can ship with a simple manual-switch UI and improve later).
- **Risk:** This slice IS the product. If LLM extraction quality is low on real Polish listings (hallucinated fields, missed risk flags, wrong verdict), the entire product value is in question. Validate against at least 3–5 real listings before marking done. The output schema from F-01 must be stable before the frontend rendering is built.
- **Status:** proposed

---

### S-02: Manual field entry

- **Outcome:** user can fill in key listing fields manually via a structured form (make, model, year, price, mileage, fuel, transmission, and any additional free-text notes) and receive the same full AI analysis as S-01.
- **Change ID:** `manual-field-entry`
- **PRD refs:** FR-003
- **Prerequisites:** S-01
- **Parallel with:** S-03 (once S-01 is done, manual entry and persistence can be developed concurrently if capacity allows)
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Lowest-risk slice in the roadmap. The analysis backend (F-01 + S-01) is already wired; only the input collection form changes. Can be shipped quickly after S-01. No new backend logic required unless the manual-entry payload shape differs from the text/URL path.
- **Status:** proposed

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

## Backlog Handoff

| Roadmap ID | Change ID                 | Suggested issue title                                         | Ready for `/10x-plan` | Notes                              |
|------------|---------------------------|---------------------------------------------------------------|-----------------------|------------------------------------|
| F-01       | llm-analysis-wiring       | Wire LlmAnalysisService to real Claude/OpenAI API             | yes                   | Run `/10x-plan llm-analysis-wiring` |
| F-02       | data-layer-setup          | Add PostgreSQL + Spring Data JPA + Flyway to backend          | yes                   | Run `/10x-plan data-layer-setup`   |
| F-03       | auth-scaffold             | Wire Spring Security + JWT; Angular login/register + guards   | no                    | Needs F-02 first                   |
| S-01       | core-analysis-flow        | Full analysis flow: URL + text paste → AI output on screen    | no                    | Needs F-01 first                   |
| S-02       | manual-field-entry        | Manual field entry form → same AI analysis as S-01            | no                    | Needs S-01 first                   |
| S-03       | save-view-delete-analyses | Save / view list / delete saved analyses                      | no                    | Needs S-01 + F-02 + F-03           |

## Open Roadmap Questions

No open roadmap questions. PRD shipped with 0 open questions (quality check: accepted 2026-05-24). Slice-level unknowns (LLM provider choice, URL fallback UX, OAuth scope) are tracked in individual slice/foundation entries above and are non-blocking — they do not gate any `/10x-plan` invocation.

## Parked

- **FR-013: Compare listings side by side** — Why parked: PRD nice-to-have; requires multiple saved analyses (S-03) as a prerequisite and adds significant UI complexity; post-MVP.
- **FR-014: Search advisor (budget + requirements → model/trim suggestions)** — Why parked: PRD nice-to-have; independent of the core analysis flow; post-MVP scope signal.
- **FR-015: Personal preferences (budget, required equipment, priorities)** — Why parked: PRD nice-to-have; personalises risk and recommendation output but doesn't change the core value proposition.
- **FR-016: Manual vehicle history report input + interpretation** — Why parked: PRD nice-to-have; adds value alongside S-01 but requires the analysis pipeline to be stable first; post-MVP.
- **FR-017: Live CEPiK / historiapojazdu.gov.pl integration** — Why parked: PRD §Non-Goals explicitly; post-MVP after FR-016 is validated.
- **No monetisation, no mobile native app, no sharing of analyses between users** — Why parked: PRD §Non-Goals; out of scope for MVP.

## Done

(Empty. `/10x-archive` appends entries here — and flips that item's `Status` to `done` — when a change whose `Change ID` matches a roadmap item is archived.)
