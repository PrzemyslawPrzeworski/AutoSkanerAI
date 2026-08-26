# CLAUDE.md — AutoSkanerAI

AI-powered used-car listing analyzer for the Polish market. 3-week solo MVP. Spring Boot 4.0.6 (Java 21, Maven) + Angular 21.2 (TypeScript, SCSS, npm).

## Key business rules

- Absence of accident data means **unknown**, not clean. Never present missing data as confirmation of clean history — this applies to LLM prompts, API responses, and UI copy.
- The app may only report confirmed accident data from the listing text or a vehicle history report.

## API error shape

All Spring controllers must return errors in this exact shape — no exceptions, no `ProblemDetail`:

```json
{ "status": 400, "error": "Błąd walidacji", "messages": ["field: message"], "timestamp": "2026-05-24T12:00:00Z" }
```

- `ErrorResponse` record lives in `com.example.autoskaner_ai.common`
- `GlobalExceptionHandler` (`@RestControllerAdvice`) in the same package handles: `MethodArgumentNotValidException` (400), `HttpMessageNotReadableException` (400), catch-all `Exception` (500)
- `messages` is `List<String>`; for validation errors format each entry as `"field: message"`
- `timestamp` is `Instant.now()`

## AI service pattern

The AI layer uses a Spring interface with three Profile-switched implementations:

- `AiAnalysisService` — interface defining the contract
- `MockAiAnalysisService` — deterministic mocks, activate with `SPRING_PROFILES_ACTIVE=mock`
- `BedrockClaudeAnalysisService` — Claude Haiku 4.5 via AWS Bedrock, activate with `SPRING_PROFILES_ACTIVE=bedrock`
- `OpenRouterAnalysisService` — any OpenRouter model, activate with `SPRING_PROFILES_ACTIVE=openrouter`

Required env vars: `AWS_PROFILE` (or `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`) for `bedrock`; `OPENROUTER_API_KEY` for `openrouter`. The openrouter key has **no default** in `application-openrouter.properties`, so an unset value fails context startup rather than degrading.

`OpenRouterAnalysisService` retries and falls back along two independent axes, because free slugs fail in two unrelated ways:

- **Transient** (429, 5xx, IO/timeout) — retry the *same* model once, waiting out `Retry-After` (capped at 6 s, and never past the deadline). An immediate retry is useless against a saturated pool; that is what turned single 429s into production 502s on 2026-08-26.
- **Permanent for this model** (404 = slug retired, 400 = request rejected) — skip the retry, go straight to the next candidate in `llm.openrouter.fallback-models`.
- **Fatal** (401/403, or a malformed response shape) — fail immediately. A rejected key rejects every model, and walking the chain only multiplies latency before the same error. Schema failures from `AnalysisResponseParser` also propagate: a prompt/parser mismatch is not fixed by another model.

`llm.openrouter.deadline-seconds` bounds how far the chain walks; it is checked only *between* models, so the primary is always attempted. `AnalysisMeta.model` records the model that actually answered, not the configured primary.

Production runs `openrouter`. `bedrock` is dev-only: the sole AWS credential source here is a corporate SSO profile (`kn.awsapps.com`, role `KN-DevelopmentEngineer`) issuing short-lived credentials, so it cannot back a hosted service — do not copy AWS credentials into Render to work around this.

## API endpoints

- `POST /api/analyses` — canonical endpoint; accepts `{ "listingText": "..." }` or `{ "url": "..." }`, returns `AnalysisResponse { fetchStatus, fetchFailureReason, analysis }`
- `POST /api/analysis/risk` — **deprecated** facade returning only `{ riskFlags: [...] }`; to be removed after S-01 ships

`fetchStatus` values: `"text"` (listing text analysed directly), `"ok"` (URL fetched successfully), `"url_failed"` (fetch failed — `analysis` is null, frontend shows text-paste fallback).

Output schema is locked — see `context/changes/llm-analysis-wiring/plan.md` § "Locked output schema".

## URL fetching

Listing URLs are fetched via **Jina Reader** (`https://r.jina.ai/<url>`), which handles JavaScript rendering and Cloudflare bypass for free. No API key needed.

- `ListingFetchService` prepends `https://r.jina.ai/` to the user-supplied URL
- SSRF protection runs on the user-supplied host before the Jina call
- Read timeout: 30 s (Jina needs time to render the page)
- Dev machines behind corporate proxies (e.g. Zscaler) will see `url_failed` — this is a network constraint, not a bug; production on Render works correctly
- `ListingFetchConfig` — provides `@Bean("listingFetchBuilder") RestClient.Builder`

## Live integration tests

```bash
cd backend && ./mvnw test -Plive-tests        # requires credentials in env
```

Tests are tagged `@Tag("live-llm")` and skipped by default in `./mvnw test`.

- Use the `live-tests` **profile**. `-Dgroups=live-llm` does not work: the base surefire config sets `excludedGroups`, so adding an include just intersects to zero and reports BUILD SUCCESS over 0 tests. The profile flips the `test.excludedGroups` / `test.includedGroups` properties instead.
- Behind a TLS-intercepting corporate proxy the JVM does not trust the injected chain and every outbound call dies with `PKIX path building failed`. Add `-DargLine="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` to use the Windows certificate store.
- `r.jina.ai` may still be blocked by proxy *policy* (403 interstitial, category "General AI and ML Applications") even once TLS is trusted. That fails `MarketPriceFetchServiceLiveTest`, which is intentional — the test asserts `OK` rather than tolerating `FETCH_FAILED`, so a blocked path is visible instead of silently green.
- Live tests must assert real outcomes. Accepting `LOOKUP_FAILED` / `FETCH_FAILED` as a pass makes them useless — they went green for months while all three integrations were failing.

## Monorepo structure

```
backend/    Spring Boot 4.0.6, Java 21, Maven
frontend/   Angular 21.2, TypeScript, SCSS, npm
context/    10xDevs chain artifacts (PRD, tech-stack, shape-notes) — do not edit
```

## Build and run

```bash
# Backend
cd backend && ./mvnw spring-boot:run     # dev server on :8080
cd backend && ./mvnw test                # unit tests

# Frontend
cd frontend && npm start                 # dev server on :4200
cd frontend && npm run build             # production build → dist/
```

## Architecture decisions

- Frontend and backend are separate apps communicating via REST. Configure CORS on the Spring side or proxy `/api` in `angular.json` for dev.
- No database yet — add PostgreSQL (prod) + H2 (dev) when implementing FR-010 (persistence).
- Auth not yet implemented — Spring Security + JWT or OAuth2 planned per PRD.
- CEPiK integration (live vehicle registry queries) is shipped, FR-017 — see "Enrichment services" below.

## Enrichment services

`AnalysisController.buildResponse()` calls two enrichment services synchronously and attaches both to `AnalysisResponse` as nullable fields (`cepikResult`, `marketPriceContext`). Both follow the profile-switched interface pattern of the AI layer: a mock bean under `mock`, the real bean under `@Profile("!mock")`.

- **CEPiK (FR-017)** — `CepikEnrichmentService` / `MockCepikService` / `RealCepikEnrichmentService` in `com.example.autoskaner_ai.cepik`. Needs **all three** of VIN + registration plate + first registration date, all extracted by the LLM; any one missing or malformed yields `MISSING_INPUTS` and `AnalysisController` appends a seller question asking for it. `HistoriaPojazduService` scrapes `moj.gov.pl` with a fresh per-lookup session. Empty damage records mean **no damage reported to insurers**, never "no accidents" — UI copy must respect this, which is why every non-`FOUND` result carries `null` lists rather than empty ones.
  - **`api.cepik.gov.pl` cannot look up a vehicle by VIN.** Verified against the live endpoint 2026-08-25: the `pojazdy` resource returns 68 attributes and **none is a VIN**, `filter[numer-vin]` is rejected with "nie istnieją", `wojewodztwo` is mandatory, and `data-od`/`data-do` are capped at a 2-year span. Do not reintroduce a VIN-keyed date lookup against it — a "fixed" date range would return whichever unrelated car was registered first in that window and feed a wrong date into historiapojazdu.
  - `CepikStatus` distinguishes `NOT_FOUND` (registry answered 404 / `HIPO-0002` — no such vehicle) from `LOOKUP_FAILED` (session or scrape broke). The UI words these differently; never collapse them.
  - **historiapojazdu accepts `firstRegistrationDate` only as `yyyy-MM-dd`** — its `nfv_regex` validator 400s on anything else. `AnalysisPrompt` now asks the LLM for ISO directly, and `RealCepikEnrichmentService.toIsoDate` normalises whatever arrives anyway: numeric Polish forms (`dd.MM.yyyy`, `dd-MM-yyyy`, `dd/MM/yyyy`) plus prose (`12 kwietnia 2022`, genitive and nominative, case-insensitive). Keep both layers — the prompt is a request, not a guarantee. Do not pass the extracted string through raw, and do not write a test that hardcodes an already-ISO date; that is precisely what hid the original bug until a real listing hit production, and a prose date from Otomoto then slipped through the numeric-only fix for the same reason. A value with no day (`kwiecień 2022`) must stay `MISSING_INPUTS` rather than being rounded to the 1st.
  - **Otomoto publishes the plate and first-registration date anonymously but gates the VIN behind login.** Verified 2026-08-26 on `toyota-corolla-ID6HG6ZH`: Jina Reader returned `registrationPlate` and a prose date, and `vinPresent: true` with `vin: null`. So a URL-only analysis can supply two of the three inputs and never the third — `MISSING_INPUTS` on real listings is the expected outcome until S-02 (manual field entry) lets the user type the VIN, which is the single field that unlocks CEPiK.
- **Market price (FR-018)** — `MarketPriceEnrichmentService` / `MockMarketPriceEnrichmentService` / `MarketPriceFetchService` in `com.example.autoskaner_ai.market`. Builds an Otomoto search URL from make/model/year/mileage, fetches it through Jina Reader, regex-extracts prices, returns min/median/max + sample size. Not Exa — see `context/changes/market-price-context/research.md`.

Known tradeoff: both run on the request thread, so one analysis makes up to 3 historiapojazdu calls plus a Jina fetch. Async handling is deferred (impl-review F10).

Verification status (2026-08-26): both paths are confirmed against production, which now runs `SPRING_PROFILES_ACTIVE=openrouter`. A real analysis returned `marketPriceContext.status=OK` with `sampleSize=40` (so `PRICE_PATTERN` does match live Otomoto markdown) and `cepikResult.status=MISSING_INPUTS` for a listing with no VIN/plate/date. historiapojazdu is separately confirmed end-to-end against the live registry.

Two caveats on the market-price range:

- The `min` is not trustworthy. That run returned `min=22900` for 2017–2021 Corollas under 125 000 km, well below any plausible asking price. `PRICE_PATTERN` cannot tell an asking price from a monthly instalment or a damaged-car listing, and the `1_000..10_000_000` guard is far too wide to catch it. The median tracks reality (68 900 against a 72 900 listing); treat `min`/`max` as outlier-contaminated.
- `median = prices.get(prices.size() / 2)` is the upper-middle element, not a median, for even sample sizes.

## Current state

F-01 (LLM analysis wiring), S-01 (core analysis flow), S-04 (CEPiK VIN lookup) and S-05 (market price context) are complete, on `main`, and **verified live in production** as of 2026-08-26 — previously all four were merged but dark, because Render pinned `SPRING_PROFILES_ACTIVE=mock`. `POST /api/analyses` is live under `mock`, `bedrock`, and `openrouter` profiles.

A real analysis takes ~27 s end to end (~16 s LLM + a Jina fetch for the market range), all on the request thread. Free-tier LLM slugs are the main fragility: see `application-openrouter.properties`. PRD is at `context/foundation/prd.md` (FR-001 to FR-018). Next: S-02 (manual field entry) or Stream B (F-02 data layer → F-03 auth → S-03 persistence).

Frontend builds need Node ≥ v20.19 / v22.12 (Angular 21 requirement); `node`/`npm` are not on PATH by default in this environment.

## Deployment

- Backend: Render Web Service (Docker, service `autoskaner-ai-backend`, URL `https://autoskanerai.onrender.com`) — live
- Frontend: Cloudflare Pages (`autoskaner-ai`, URL `https://autoskaner-ai.pages.dev`) — live; auto-deploys on push to `main`
- CI/CD: auto-deploy wired on both platforms (push to `main` triggers deploy)
- GitHub: https://github.com/PrzemyslawPrzeworski/AutoSkanerAI

<!-- BEGIN @przeprogramowani/10x-cli -->

## 10xDevs AI Toolkit - Module 2, Lesson 5

Scale the single-change cycle into parallel work with **worktrees, goal-directed delegation, and multi-session orchestration**:

```
worktree per change -> /goal or claude -p -> PR -> review -> merge
```

The lesson focus is safe throughput: isolated contexts, choosing the right execution mode, and capping parallelism at review capacity.

### Task Router - Where to start

| Skill | Use it when |
| --- | --- |
| **Code isolation** | |
| `git worktree add` | You need a separate working directory for a parallel change. One change per worktree, one fresh agent context per worktree. |
| **Complex changes** | |
| `/10x-implement <change-id> phase <n>` | The change has multiple phases, needs manual gates, or benefits from interactive decision-making during execution. |
| **Simple changes** | |
| `/goal` | You have a clear, bounded task and want goal-directed delegation. The agent works autonomously toward the stated goal with a stop condition. |
| `claude -p` | You want headless execution for a well-defined task. The Ralph Wiggum loop (run, check, retry) is the universal autonomous pattern. |
| **Multi-session orchestration** | |
| Superset / Conductor / Antigravity / VS Code Agent View | You are running multiple agent sessions in parallel and need visibility, coordination, or session management across them. |

### Parallel work rules

- One change per worktree or isolated workspace. One fresh agent context per change.
- Choose interactive `/10x-implement` for complex changes, `/goal` or `claude -p` for simple ones.
- Parallelism is capped by review capacity. More agents without review means more unreviewed code, not higher throughput.
- The quality pain from faster shipping is intentional — it bridges into Module 3 testing gates.

### Lesson boundaries

- Do not reteach interactive `/10x-implement` or `/10x-impl-review`; those are Lessons 2 and 3.
- Do not introduce testing strategy here. The quality pain is the motivation for Module 3.
- Worktrees are a mechanism for isolation, not the topic of a full git tutorial.

### Paths used by this lesson

- `context/changes/<change-id>/` - active change folder
- `context/changes/<change-id>/plan.md` - implementation input for any execution mode

Skills must not write to `context/archive/`. Archived changes are immutable; if a resolved target path starts with `context/archive/`, abort with: "This change is archived. Open a new change with `/10x-new` instead."

<!-- END @przeprogramowani/10x-cli -->
