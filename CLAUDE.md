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
- **Fatal** (401/403/**402**, or a malformed response shape) — fail immediately. A refused account is refused by every model, and walking the chain only multiplies latency before the same error. 402 (out of credits) rides the same `REJECTED_CREDENTIALS` reason and so surfaces as "odrzuciła dane dostępowe" — the remedy is topping up, not rotating the key, so check the logged status before acting on that string. Schema failures from `AnalysisResponseParser` also propagate: a prompt/parser mismatch is not fixed by another model.

`llm.openrouter.deadline-seconds` bounds how far the chain walks; it is checked only *between* models, so the primary is always attempted. `AnalysisMeta.model` records the model that actually answered, not the configured primary.

Production runs `openrouter`. `bedrock` is dev-only: the sole AWS credential source here is a corporate SSO profile (`kn.awsapps.com`, role `KN-DevelopmentEngineer`) issuing short-lived credentials, so it cannot back a hosted service — do not copy AWS credentials into Render to work around this.

## API endpoints

- `POST /api/analyses` — canonical endpoint; accepts `{ "listingText": "..." }`, `{ "url": "..." }`, or `{ "manual": { ... } }`, plus optional `vin` / `registrationPlate` / `firstRegistrationDate` overrides. Returns `AnalysisResponse { fetchStatus, fetchFailureReason, analysis, cepikResult, marketPriceContext }`
- `POST /api/analysis/risk` — **deprecated** facade returning only `{ riskFlags: [...] }`; to be removed after S-01 ships

`fetchStatus` values: `"text"` (listing text analysed directly), `"ok"` (URL fetched successfully), `"manual"` (structured fields, FR-003), `"url_failed"` (fetch failed — `analysis` is null, frontend shows text-paste fallback).

## Manual entry and user overrides (FR-003, S-02)

`ManualListing` carries the structured fields; `ManualListingComposer` renders them into Polish advert-style text so manual mode reuses the S-01 prompt and output schema rather than needing a second one. Composition lives on the server because prompt shape is a backend concern. The composer never fills a blank field with "brak danych" — the model reads a stated "brak historii serwisowej" as a fact about the car and flags it, when all that happened is the user left the box empty.

`UserOverrides.apply` runs in `AnalysisController.buildResponse` **before enrichment**, so the registry lookup and the market-price query use what the user typed. Two rules:

- **A typed value wins** over the extraction, including make/model/year/price/mileage/fuel/transmission. VIN and plate are upper-cased; the date is left verbatim because `RealCepikEnrichmentService` owns date normalisation and a second copy would drift.
- **A blank field never nulls a good extraction.** The frontend form is prefilled from the extraction, so an untouched field means "no opinion".

`accidentClaim` is deliberately **not** user-editable: it is a claim the *listing* makes, and `CepikRiskAdjuster` compares it against the registry. Letting a user "correct" it would delete the `CEPIK_CONTRADICTS_LISTING` finding it exists to raise.

A malformed VIN is not a 400 — it must not throw away an otherwise useful analysis. `RealCepikEnrichmentService` reports `MISSING_INPUTS` and the controller asks for it again. The frontend does check the VIN shape (17 chars, no I/O/Q) before submitting, because a typo otherwise costs a ~30 s analysis whose empty history panel reads as the registry's fault.

The frontend's "Sprawdź historię pojazdu" follow-up re-runs the whole analysis rather than calling a lookup-only endpoint. That is intentional: CEPiK findings only reach `scores` / `verdict` through `CepikRiskAdjuster` on the analysis path.

**The VIN is the only field the UI asks a user to type.** The registry needs VIN + plate + first registration date, but the advert publishes the last two — only the VIN is encrypted for logged-out fetches. So `VehicleDataFormComponent` has three modes with one job each: `vin` (input screen, always visible, in a titled block named by the outcome), `registry` (all three, shown only after a `MISSING_INPUTS` result, prefilled from the extraction), `listing` (make/model/…/notes, behind "I have no link" — they substitute for a missing advert and have nothing to do with the registry). An earlier single drawer labelled by field name read as a pile of optional boxes and was rebuilt for exactly that reason. `missingRegistryFields` names the fields still empty rather than restating that three are required, since an empty field means the advert did not carry it either.

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

## Mutation testing

```bash
cd backend && ./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage
```

PIT, behind the `mutation` profile, off by default and never wired into `./mvnw test`. A green suite answers "did the line run"; PIT answers "would a test notice if the line were wrong". Report: `target/pit-reports/index.html`.

- **A selective gate, not a coverage target.** `mutation.targetClasses` / `mutation.targetTests` are properties with narrow defaults, meant to be overridden per run (`-Dmutation.targetClasses=...`). Runtime scales with the mutant count, not the test count, so point it at the module a change or a `test-plan.md` risk actually touches.
- **Do not chase 100%.** Survivors are questions, not a task list: would a real bug of this shape hurt a user? Add an assertion only when the answer is yes. A test written to kill an *equivalent* mutant — one whose change is unobservable — is a mirror of the implementation and breaks on the next refactor.
- **The PIT version is load-bearing.** The dev JDK is 26 (class file major version 70). `pitest-maven` 1.20.4 cannot parse it and reports `BUILD SUCCESS` with `0/53 killed, 0% line coverage` — a mutation score of zero caused entirely by the tooling. 1.29.10 works. **If the score ever reads 0, suspect the tool before the tests.**
- `live-llm` is excluded, for the same reason it is excluded from `./mvnw test`: a mutant must never be judged by whether somebody's API key worked today.

`MarketPriceStatistics` on 2026-09-04: **89%** (47/53 killed, 95% line coverage, 0 uncovered mutants), up from a first baseline of 81% (43/53, 1 uncovered). The first run found two real gaps, and three tests closed them:

- **`MIN_SAMPLE_FOR_IQR` was unguarded at its own edge.** `kept.size() >= 8` → `> 8` survived: every existing test either had 7 survivors after the band or reached the line with `bandCollapsed` already true, so the size comparison never decided anything. `aSampleOfExactlyEightSurvivorsIsFenced` pins the "or more".
- **Neither arm of the Tukey hinge was pinned, and one had no coverage at all** — no test reached `withoutIqrOutliers` with an odd sample, so `sorted.size() % 2 == 0 ? half : half + 1` could have used either arm unnoticed. Two tests, because the two arms need opposite samples: `anOddSamplePutsItsMedianInBothHalvesWhenComputingTheHinges` kills the negate and `half + 1` → `half - 1` mutants, and `anEvenSampleTakesExactlyHalfIntoEachHinge` kills the `% 2` → `* 2` mutant, which on an *odd* sample selects the same arm as the original and so is unkillable there.

The 6 remaining survivors are left alive deliberately. Five are `ConditionalsBoundary` mutants on the inclusivity of the ±3× band edges (124, 125) and the IQR fence edges (93, 142 ×2): pinning the exact `>=`-vs-`>` of a constant the code's own comment calls wide on purpose mirrors the implementation rather than defending a behaviour. The sixth is genuinely **equivalent** — flipping the `iqr == 0` early return from `sorted` to empty changes nothing, because the caller rejects a too-small fenced list and keeps the untrimmed sample either way, so no test can kill it.

## Monorepo structure

```
backend/    Spring Boot 4.0.6, Java 21, Maven
frontend/   Angular 21.2, TypeScript, SCSS, npm
context/    10xDevs chain artifacts (PRD, tech-stack, shape-notes) — do not edit
```

## Build and run

```bash
# Backend
cd backend && ./mvnw spring-boot:run     # dev server on :10000 (server.port=${PORT:10000})
cd backend && ./mvnw test                # unit tests

# Frontend
cd frontend && npm start                 # dev server on :4200
cd frontend && npm run build             # production build → dist/
cd frontend && npm test -- --watch=false # unit tests (vitest via @angular/build:unit-test)
cd frontend && npm run test:e2e          # e2e (starts both servers itself; see below)
```

The backend port is **10000**, not 8080 — `proxy.conf.json` targets 10000, and so
does the Playwright readiness probe. `/actuator/health` answers 200; a GET on the
POST-only `/api/analyses` answers 500, so do not use it as a probe.

## Local quality gates

Three automated layers, each catching what the one below cannot. There is no CI
yet and `main` auto-deploys to both hosts, so **pre-push is the last gate before
production**, not a pre-filter in front of CI.

| Layer | Trigger | Does |
|---|---|---|
| per-edit | `PostToolUse` on `Write`/`Edit` — `.claude/hooks/post-edit-check.{sh,mjs}` | `prettier --write` the edited `frontend/src` file, then the whole frontend suite for `.ts` / `.html` (6.9 s) |
| pre-commit | `.githooks/pre-commit` | `prettier --check` staged frontend sources, frontend suite, backend suite when Java or `pom.xml` is staged |
| pre-push | `.githooks/pre-push` | backend + frontend suites over the whole tree; for `main` also the production build |

**A fresh clone needs `git config core.hooksPath .githooks`** — the hooks are
versioned but git does not pick them up on its own.

- **Not Lefthook**, despite what the lesson block below names. Lefthook needs a
  root `package.json`, and Cloudflare Pages builds this repo from a
  subdirectory on every push to `main`; a new root manifest is an unverifiable
  risk to a live deploy path for a benefit that is ten lines of shell here.
- **Every layer fails loudly when its own toolchain is missing.** The hook these
  replaced had a trigger, a matcher and a handler but its signal was hard-wired
  to success — `catch → process.exit(0)` inside `2>/dev/null || true`. It was
  dead from May to September because `node` was not on PATH, and nothing said
  so; the symptom finally surfaced as `prettier --check` reporting 23 of 23
  files unformatted. `post-edit-check.sh` exists for exactly one reason: node
  runs the checker, so node cannot report its own absence.
- **Per-edit runs the whole frontend suite, not the matching spec.** Measured:
  `ng test --include <component>.ts` costs 5.9 s against 6.4 s for all 39 tests,
  because the price is the Angular bundle build, not the test count. Scoping
  buys half a second and gives up cross-component breaks. `npx vitest related`
  is not an option at all — plain Vitest has no Angular transform, so it
  collects the specs and dies at `describe(...)`, which is the same obstacle
  that blocks Stryker.
- Backend edits are **not** gated per-edit; `./mvnw -o test` is ~15 s, a
  commit-time cost. It runs offline for speed, so a newly added dependency can
  fail pre-commit on its own — the hook says so when it fails.

See `context/foundation/test-plan.md` §5.1 for the timings and how each path was
verified.

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
  - **`HistoriaPojazduParser` maps only field names observed in a captured response.** Until 2026-08-26 the `FOUND` branch had never run against a real vehicle, and every field name in it was invented (`zdarzenia`, `szkodyIstotne`, `przebieg`, `liczbaWlascicieli`, `BADANIE_TECHNICZNE`) — none exists. The registry returns `technicalData.basicData` for identity and `timelineData.events[]` with `eventDate` / `eventType` / `eventName` / `eventDetails[{name,value}]`; a significant damage is `eventType: "szkoda-istotna"` with details `nazwa ubezpieczyciela` and `kategorie`. The parse silently produced `damageRecords: []`, which the UI rendered as "brak zgłoszonych szkód istotnych" for a car carrying a registered szkoda istotna. The tests behind it passed because the fixtures were hand-written to match the invented names. **Fixtures in `src/test/resources/cepik/` must stay verbatim captures** — capture a new one rather than composing it, and do not add a field mapping without a captured payload showing that name. `deregisteredDate` and `originCountry` are deliberately left null for exactly this reason.
  - Dated mileage comes from the inspection events, not from the registry's own `odometerReadings`, which carry no dates.
  - **The API version in the path is discovered from the bootstrap HTML, not pinned.** `HistoriaPojazduSession` regexes `/nforms/api/HistoriaPojazdu/<version>/` out of the `NF_WID` response; the hardcoded `1.0.17` had rotted to `1.1.0`. `FALLBACK_API_VERSION` is a last resort, not the contract.
  - **Otomoto publishes the plate and first-registration date anonymously but gates the VIN behind login.** Verified 2026-08-26 on `toyota-corolla-ID6HG6ZH`: Jina Reader returned `registrationPlate` and a prose date, and `vinPresent: true` with `vin: null`. So a URL-only analysis can supply two of the three inputs and never the third — `MISSING_INPUTS` on real listings is the expected outcome until S-02 (manual field entry) lets the user type the VIN, which is the single field that unlocks CEPiK.
- **Market price (FR-018)** — `MarketPriceEnrichmentService` / `MockMarketPriceEnrichmentService` / `MarketPriceFetchService` in `com.example.autoskaner_ai.market`. Builds an Otomoto search URL from make/model/year/mileage, fetches it through Jina Reader, regex-extracts prices, returns min/median/max + sample size. Not Exa — see `context/changes/market-price-context/research.md`.

Known tradeoff: both run on the request thread, so one analysis makes up to 3 historiapojazdu calls plus a Jina fetch. Async handling is deferred (impl-review F10).

Verification status (2026-08-26): both paths are confirmed against production, which now runs `SPRING_PROFILES_ACTIVE=openrouter`. A real analysis returned `marketPriceContext.status=OK` with `sampleSize=40` (so `PRICE_PATTERN` does match live Otomoto markdown) and `cepikResult.status=MISSING_INPUTS` for a listing with no VIN/plate/date. The CEPiK `FOUND` path is confirmed against the live registry as of 2026-08-26 — before that date only the `NOT_FOUND` branch had ever been exercised, which is why the parser's fabricated field names went unnoticed. `HistoriaPojazduServiceLiveTest` still only asserts `NOT_FOUND`; a `FOUND` assertion needs a real plate+VIN+date triple, which cannot be committed.

`CepikRiskAdjuster` folds registry findings into `scores` and `verdict` after enrichment, because the LLM scores the listing *before* the lookup runs and so never sees the CEPiK payload. Without it, production returned `risk: 88, verdict: WORTH_CHECKING` for a vehicle with a registered szkoda istotna — the damage visible in the panel and absent from the judgement.

- The adjustment is deterministic, not a second LLM call: a registered structural damage must not be able to score 88 because a model weighed it mildly.
- Risk ceilings, never raises — theft marker 5, odometer rollback 20, damage contradicting an accident-free claim 25, szkoda istotna 35, no OC policy 70. A listing the model already scored lower keeps its score, and `overall` is recomputed as the mean of the four categories but never raised.
- Damage alone floors the verdict at `NEEDS_MORE_INFO`, not `HIGH_RISK_SKIP`: a properly repaired damage with a positive post-repair inspection can be a fair purchase at the right price. A listing that claims `bezwypadkowy` *and* carries a registry damage is a separate, worse finding (`CEPIK_CONTRADICTS_LISTING`) and does force `HIGH_RISK_SKIP`.
- **Only `FOUND` results adjust anything.** `NOT_FOUND` / `LOOKUP_FAILED` / `MISSING_INPUTS`, and a `FOUND` result whose `damageRecords` is null, must leave the score untouched in both directions — same null-is-not-empty rule as above. Tested explicitly.

The registry-vs-listing mileage check currently lives only in the frontend component (`max(2000 km, 5%)` tolerance, registry-higher direction only) and does not feed the score. If it moves into scoring, delete the TypeScript copy rather than keeping two.

The market-price range is trimmed in `MarketPriceStatistics` before it is reported, because the raw regex output is not a set of asking prices. Two passes, because there are two kinds of contamination:

- **A band of ±3× the median** drops order-of-magnitude junk — a monthly financing instalment renders in the same `### <n>\nPLN` block as a price and clears the `1_000..10_000_000` guard easily. An IQR fence cannot catch this: enough junk drags the quartiles down with it, while the median is what junk cannot move. If the band would leave fewer than 3 prices the sample is reported untrimmed — a tight range invented from three survivors that happened to agree is worse than a visibly wide one.
- **Tukey's 1.5×IQR fence** (samples of 8+, skipped when IQR is 0) drops the right-order-of-magnitude-wrong-car cases: salvage titles, other trims. This is what fixes the live `min=39900` against `median=82900`.

`sampleSize` counts the **kept** prices, so the UI's "small sample" caveat describes the listings the numbers actually came from. The discarded count is logged, not returned. The median is a real median — averaged over both middle elements on an even sample, where the old `prices.get(size / 2)` was the upper-middle element.

## Current state

F-01 (LLM analysis wiring), S-01 (core analysis flow), S-04 (CEPiK VIN lookup) and S-05 (market price context) are complete, on `main`, and **verified live in production** as of 2026-08-26 — previously all four were merged but dark, because Render pinned `SPRING_PROFILES_ACTIVE=mock`. `POST /api/analyses` is live under `mock`, `bedrock`, and `openrouter` profiles.

S-02 (manual field entry + user-supplied VIN/plate/date) is implemented; see "Manual entry and user overrides" above.

A real analysis takes ~27 s end to end (~16 s LLM + a Jina fetch for the market range), all on the request thread. Free-tier LLM slugs are the main fragility: see `application-openrouter.properties`. PRD is at `context/foundation/prd.md` (FR-001 to FR-018). Next: Stream B (F-02 data layer → F-03 auth → S-03 persistence).

Frontend builds need Node ≥ v20.19 / v22.12 (Angular 21 requirement); `node`/`npm` are not on PATH by default in this environment.

Frontend tests run on **vitest through `@angular/build:unit-test`** (`test` target in `angular.json`, jsdom — no browser needed). 39 tests in 4 spec files, ~2.5 s. Two things to know:

- **No `fakeAsync` / `tick`.** The app has no zone.js at all (Angular 21 is zoneless by default), so `fakeAsync` throws "zone-testing.js is needed". Adding zone.js only for tests would make tests run under different change-detection semantics than production. Every service call in the specs is a synchronous `of(...)`, so awaiting nothing is correct — if a spec ever needs real async, use `await fixture.whenStable()`.
- **Vitest matchers, not jasmine.** `vi.fn()`, `mockReturnValue`, `toBe(true)` — `toBeTrue()` does not exist and fails to compile, which is how the stale specs were caught.

## E2E: one spec, on purpose

`frontend/e2e/` holds **one** risk spec plus a seed exemplar, and `test-plan.md` §3
records why the layer is that small: every risk in the map is cheaper to reach at
unit, integration, or component level. The one thing no other layer reaches is the
**frontend/backend contract** — `shared/models/analysis.models.ts` is a hand-written
mirror of the Java records, the backend suite asserts its own JSON, the frontend
suite asserts against hand-written doubles, so a field rename ships green with the
panel rendering nothing. That already nearly happened with `sampleQuality`.

- `market-price-contract.spec.ts` compares the DOM against **the same response's own
  JSON**, not against the mock's constants — otherwise it would be a test of
  `MockMarketPriceEnrichmentService`, which `test-plan.md` §7 excludes.
- **Don't assert scores or verdict here.** `MockMarketPriceEnrichmentService` ignores
  its input (always 45000/55000/70000, sample 12), but `MockAiAnalysisService` is
  content-sensitive — one word of listing text moved `overall` from 41 to 35.
- Mocking is **server-side**, via `SPRING_PROFILES_ACTIVE=mock`. It has to be: the
  backend calls the LLM, Jina, and the registry itself, so `page.route()` never sees
  them. Everything the contract risk lives on — HTTP, Jackson, Angular DI, templates
  — stays real.
- **Not wired into any gate.** Two servers plus a browser is the wrong per-edit cost;
  it belongs in CI (§3 Phase 4). Run it by hand when touching the contract.
- Before adding a spec here, read `frontend/e2e/E2E-RULES.md` — the cost × signal
  budget is binding, and "e2e feels safer" is not a reason.
- The Playwright `webServer` command needs `.\mvnw.cmd` on Windows and `./mvnw`
  elsewhere; `cmd.exe` rejects both `./mvnw` and a bare `mvnw.cmd`.
- **Playwright MCP is installed (`--caps=vision`), and the CLI is still the
  default.** Both read the same accessibility tree and emit the same role-based
  locators, so MCP's ~4× token cost buys interactive *exploration*, not better
  tests — reach for it when the app has to be poked at, not to run a spec that is
  already written. Its generated code is not pre-reviewed: it produced
  `getByText('Oceny kategoriiKompletność71%')` for an unnamed wrapper element.
  It writes scratch output to the repo root, hence `.playwright-mcp/` in
  `/.gitignore`. If `claude mcp list` shows it timing out, that is the cold `npx`
  download, not the registration — warm the cache and re-check.
- **Vision found real bugs and still justifies no spec.** See `test-plan.md` §3's
  vision paragraph for the two defects and why one belongs in a component test
  and the other in a deterministic differ. Screenshots under `frontend/vision/`
  are scratch evidence, never fixtures, and are not committed.

## Deployment

- Backend: Render Web Service (Docker, service `autoskaner-ai-backend`, URL `https://autoskanerai.onrender.com`) — live
- Frontend: Cloudflare Pages (`autoskaner-ai`, URL `https://autoskaner-ai.pages.dev`) — live; auto-deploys on push to `main`
- CI/CD: auto-deploy wired on both platforms (push to `main` triggers deploy)
- GitHub: https://github.com/PrzemyslawPrzeworski/AutoSkanerAI

<!-- BEGIN @przeprogramowani/10x-cli -->

## 10xDevs AI Toolkit - Module 3, Lesson 4 (E2E Tests)

**For E2E tests, use the `/10x-e2e` skill.** It is the single source of truth
for the workflow — risk → seed test + rules → generate → review against the five
anti-patterns → re-prompt → verify. The skill's `references/` carry the full
rules, anti-patterns, seed pattern, and prompt-template.

A few hard rules that hold even before you invoke the skill:

- **Locators:** `getByRole` / `getByLabel` / `getByText` first; `getByTestId`
  only when accessibility attributes are ambiguous. Never CSS selectors, XPath,
  or DOM structure.
- **Never `page.waitForTimeout()`.** Wait for state: `toBeVisible()`,
  `waitForURL()`, `waitForResponse()`.
- **Test independence + cleanup.** Each test runs standalone — its own setup,
  action, assertion, and cleanup; unique ids (timestamp suffix) so parallel runs
  and re-runs don't collide.

Two boundaries to keep straight:

- **DOM (snapshot) is the default.** Vision (`--caps=vision`) is a supplement for
  visual-only risks (layout, z-index, animation); for pixel regression prefer
  deterministic tools (`toMatchSnapshot`, Argos, Lost Pixel). VLM model
  selection/cost is a debugging topic (Lesson 5), not testing.
- **Healer helps on selectors, harms on logic.** A changed selector → healer
  re-finds it (route through PR review). A changed business behavior → healer
  masks the bug; that failing-test-to-fix case is Lesson 5.

<!-- END @przeprogramowani/10x-cli -->
