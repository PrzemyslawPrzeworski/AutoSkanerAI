# backend/CLAUDE.md — Spring Boot service

Loaded **in addition to** the root `CLAUDE.md`, not instead of it. The business
rules, the monorepo map, the quality gates and the deploy targets are there; what
follows is what only the backend owns. Paths are relative to the repo root
throughout, so they read the same from either file.

## API error shape

All Spring controllers must return errors in this exact shape — no exceptions, no `ProblemDetail`:

```json
{ "status": 400, "error": "Błąd walidacji", "messages": ["field: message"], "timestamp": "2026-05-24T12:00:00Z" }
```

- `ErrorResponse` record lives in `com.example.autoskaner_ai.common`
- `GlobalExceptionHandler` (`@RestControllerAdvice`) in the same package handles: `MethodArgumentNotValidException` (400), `HttpMessageNotReadableException` (400), catch-all `Exception` (500)
- `messages` is `List<String>`; for validation errors format each entry as `"field: message"`
- `timestamp` is `Instant.now()`

## API endpoints

- `POST /api/analyses` — canonical endpoint; accepts `{ "listingText": "..." }`, `{ "url": "..." }`, or `{ "manual": { ... } }`, plus optional `vin` / `registrationPlate` / `firstRegistrationDate` overrides. Returns `AnalysisResponse { fetchStatus, fetchFailureReason, analysis, cepikResult, marketPriceContext }`
- `POST /api/analysis/risk` — **deprecated** facade returning only `{ riskFlags: [...] }`; to be removed after S-01 ships

`fetchStatus` values: `"text"` (listing text analysed directly), `"ok"` (URL fetched successfully), `"manual"` (structured fields, FR-003), `"url_failed"` (fetch failed — `analysis` is null, frontend shows text-paste fallback).

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

Output schema is locked — see `context/changes/llm-analysis-wiring/plan.md` § "Locked output schema".

## Manual entry and user overrides (FR-003, S-02)

`ManualListing` carries the structured fields; `ManualListingComposer` renders them into Polish advert-style text so manual mode reuses the S-01 prompt and output schema rather than needing a second one. Composition lives on the server because prompt shape is a backend concern. The composer never fills a blank field with "brak danych" — the model reads a stated "brak historii serwisowej" as a fact about the car and flags it, when all that happened is the user left the box empty.

`UserOverrides.apply` runs in `AnalysisController.buildResponse` **before enrichment**, so the registry lookup and the market-price query use what the user typed. Two rules:

- **A typed value wins** over the extraction, including make/model/year/price/mileage/fuel/transmission. VIN and plate are upper-cased; the date is left verbatim because `RealCepikEnrichmentService` owns date normalisation and a second copy would drift.
- **A blank field never nulls a good extraction.** The frontend form is prefilled from the extraction, so an untouched field means "no opinion".

`accidentClaim` is deliberately **not** user-editable: it is a claim the *listing* makes, and `CepikRiskAdjuster` compares it against the registry. Letting a user "correct" it would delete the `CEPIK_CONTRADICTS_LISTING` finding it exists to raise.

A malformed VIN is not a 400 — it must not throw away an otherwise useful analysis. `RealCepikEnrichmentService` reports `MISSING_INPUTS` and the controller asks for it again. The frontend also checks the VIN shape before submitting; see `frontend/CLAUDE.md` § "Vehicle data form" for why the UI asks for the VIN and nothing else.

The frontend's "Sprawdź historię pojazdu" follow-up re-runs the whole analysis rather than calling a lookup-only endpoint. That is intentional: CEPiK findings only reach `scores` / `verdict` through `CepikRiskAdjuster` on the analysis path.

## URL fetching

Listing URLs are fetched via **Jina Reader** (`https://r.jina.ai/<url>`), which handles JavaScript rendering and Cloudflare bypass for free. No API key needed.

- `ListingFetchService` prepends `https://r.jina.ai/` to the user-supplied URL
- SSRF protection runs on the user-supplied host before the Jina call
- Read timeout: 30 s (Jina needs time to render the page)
- Dev machines behind corporate proxies (e.g. Zscaler) will see `url_failed` — this is a network constraint, not a bug; production on Render works correctly
- `ListingFetchConfig` — provides `@Bean("listingFetchBuilder") RestClient.Builder`

## Enrichment services

`AnalysisController.buildResponse()` calls two enrichment services synchronously and attaches both to `AnalysisResponse` as nullable fields (`cepikResult`, `marketPriceContext`). Both follow the profile-switched interface pattern of the AI layer: a mock bean under `mock`, the real bean under `@Profile("!mock")`.

- **CEPiK (FR-017)** — `CepikEnrichmentService` / `MockCepikService` / `RealCepikEnrichmentService` in `com.example.autoskaner_ai.cepik`. Needs **all three** of VIN + registration plate + first registration date, all extracted by the LLM; any one missing or malformed yields `MISSING_INPUTS` and `AnalysisController` appends a seller question asking for it. `HistoriaPojazduService` scrapes `moj.gov.pl` with a fresh per-lookup session. Empty damage records mean **no damage reported to insurers**, never "no accidents" — UI copy must respect this, which is why every non-`FOUND` result carries `null` lists rather than empty ones.
  - **`api.cepik.gov.pl` cannot look up a vehicle by VIN.** Verified against the live endpoint 2026-08-25: the `pojazdy` resource returns 68 attributes and **none is a VIN**, `filter[numer-vin]` is rejected with "nie istnieją", `wojewodztwo` is mandatory, and `data-od`/`data-do` are capped at a 2-year span. Do not reintroduce a VIN-keyed date lookup against it — a "fixed" date range would return whichever unrelated car was registered first in that window and feed a wrong date into historiapojazdu.
  - `CepikStatus` distinguishes `NOT_FOUND` (registry answered 404 / `HIPO-0002` — no such vehicle) from `LOOKUP_FAILED` (session or scrape broke). The UI words these differently; never collapse them.
  - **historiapojazdu accepts `firstRegistrationDate` only as `yyyy-MM-dd`** — its `nfv_regex` validator 400s on anything else. `AnalysisPrompt` now asks the LLM for ISO directly, and `RealCepikEnrichmentService.toIsoDate` normalises whatever arrives anyway: numeric Polish forms (`dd.MM.yyyy`, `dd-MM-yyyy`, `dd/MM/yyyy`) plus prose (`12 kwietnia 2022`, genitive and nominative, case-insensitive). Keep both layers — the prompt is a request, not a guarantee. Do not pass the extracted string through raw, and do not write a test that hardcodes an already-ISO date; that is precisely what hid the original bug until a real listing hit production, and a prose date from Otomoto then slipped through the numeric-only fix for the same reason. A value with no day (`kwiecień 2022`) must stay `MISSING_INPUTS` rather than being rounded to the 1st.
  - **`HistoriaPojazduParser` maps only field names observed in a captured response.** Until 2026-08-26 the `FOUND` branch had never run against a real vehicle, and every field name in it was invented (`zdarzenia`, `szkodyIstotne`, `przebieg`, `liczbaWlascicieli`, `BADANIE_TECHNICZNE`) — none exists. The registry returns `technicalData.basicData` for identity and `timelineData.events[]` with `eventDate` / `eventType` / `eventName` / `eventDetails[{name,value}]`; a significant damage is `eventType: "szkoda-istotna"` with details `nazwa ubezpieczyciela` and `kategorie`. The parse silently produced `damageRecords: []`, which the UI rendered as "brak zgłoszonych szkód istotnych" for a car carrying a registered szkoda istotna. The tests behind it passed because the fixtures were hand-written to match the invented names. **Fixtures in `backend/src/test/resources/cepik/` must stay verbatim captures** — capture a new one rather than composing it, and do not add a field mapping without a captured payload showing that name. `deregisteredDate` and `originCountry` are deliberately left null for exactly this reason.
  - Dated mileage comes from the inspection events, not from the registry's own `odometerReadings`, which carry no dates.
  - **The API version in the path is discovered from the bootstrap HTML, not pinned.** `HistoriaPojazduSession` regexes `/nforms/api/HistoriaPojazdu/<version>/` out of the `NF_WID` response; the hardcoded `1.0.17` had rotted to `1.1.0`. `FALLBACK_API_VERSION` is a last resort, not the contract.
  - **Otomoto publishes the plate and first-registration date anonymously but gates the VIN behind login.** Verified 2026-08-26 on `toyota-corolla-ID6HG6ZH`: Jina Reader returned `registrationPlate` and a prose date, and `vinPresent: true` with `vin: null`. So a URL-only analysis can supply two of the three inputs and never the third — `MISSING_INPUTS` on real listings is the expected outcome until S-02 (manual field entry) lets the user type the VIN, which is the single field that unlocks CEPiK.
- **Market price (FR-018)** — `MarketPriceEnrichmentService` / `MockMarketPriceEnrichmentService` / `MarketPriceFetchService` in `com.example.autoskaner_ai.market`. Builds an Otomoto search URL from make/model/year/mileage, fetches it through Jina Reader, regex-extracts prices, returns min/median/max + sample size. Not Exa — see `context/changes/market-price-context/research.md`.

Known tradeoff: both run on the request thread, so one analysis makes up to 3 historiapojazdu calls plus a Jina fetch. Async handling is deferred (impl-review F10).

Verification status (2026-08-26): both paths are confirmed against production, which now runs `SPRING_PROFILES_ACTIVE=openrouter`. A real analysis returned `marketPriceContext.status=OK` with `sampleSize=40` (so `PRICE_PATTERN` does match live Otomoto markdown) and `cepikResult.status=MISSING_INPUTS` for a listing with no VIN/plate/date. The CEPiK `FOUND` path is confirmed against the live registry as of 2026-08-26 — before that date only the `NOT_FOUND` branch had ever been exercised, which is why the parser's fabricated field names went unnoticed. `HistoriaPojazduServiceLiveTest` still only asserts `NOT_FOUND`; a `FOUND` assertion needs a real plate+VIN+date triple, which cannot be committed.

### Folding registry findings into the score

`CepikRiskAdjuster` folds registry findings into `scores` and `verdict` after enrichment, because the LLM scores the listing *before* the lookup runs and so never sees the CEPiK payload. Without it, production returned `risk: 88, verdict: WORTH_CHECKING` for a vehicle with a registered szkoda istotna — the damage visible in the panel and absent from the judgement.

- The adjustment is deterministic, not a second LLM call: a registered structural damage must not be able to score 88 because a model weighed it mildly.
- Risk ceilings, never raises — theft marker 5, odometer rollback 20, damage contradicting an accident-free claim 25, szkoda istotna 35, no OC policy 70. A listing the model already scored lower keeps its score, and `overall` is recomputed as the mean of the four categories but never raised.
- Damage alone floors the verdict at `NEEDS_MORE_INFO`, not `HIGH_RISK_SKIP`: a properly repaired damage with a positive post-repair inspection can be a fair purchase at the right price. A listing that claims `bezwypadkowy` *and* carries a registry damage is a separate, worse finding (`CEPIK_CONTRADICTS_LISTING`) and does force `HIGH_RISK_SKIP`.
- **The accident-free matcher is negation-aware, and deliberately only just.** `ACCIDENT_FREE_CLAIMS` is still substring-matched, but each occurrence is checked against the text immediately before it, so `"nie jest bezwypadkowy"` — an honest seller disclosing the damage — no longer earns `CEPIK_CONTRADICTS_LISTING` and a forced `HIGH_RISK_SKIP`. Before that fix the honest listing scored *worse* than one that said nothing, which is the incentive backwards. Three constraints hold the fix in place: the negation must be **attached** to the phrase (a "nie" anywhere in the claim would be a bypass the seller can type, since they write the advert — `"nie mam nic do ukrycia, auto bezwypadkowe"`); `"nie uczestniczy"` is **not** in `NEGATABLE_CLAIMS` because its own "nie" is the claim; and the two error directions are not equal — a false accusation is unfair to one seller, a missed contradiction reassures a buyer about a registered wreck. When in doubt, flag.
- **The adjuster logs when a denial clears the contradiction.** It is the only trace of a decision that rewrites a verdict, and its absence is why the defect above sat in a test-file comment rather than a bug report (OWASP A10). The claim is seller-supplied text on its way into a log file, so `forLog` strips control characters and bounds the length — a newline in an advert would otherwise forge a log line.
- **Only `FOUND` results adjust anything.** `NOT_FOUND` / `LOOKUP_FAILED` / `MISSING_INPUTS`, and a `FOUND` result whose `damageRecords` is null, must leave the score untouched in both directions — same null-is-not-empty rule as above. Tested explicitly.

One check that looks like it belongs here does not: the registry-vs-listing mileage comparison lives only in the frontend and does not feed the score. If it moves into scoring, delete the TypeScript copy rather than keeping two — see `frontend/CLAUDE.md` § "Vehicle data form".

### Trimming the market-price range

The market-price range is trimmed in `MarketPriceStatistics` before it is reported, because the raw regex output is not a set of asking prices. Two passes, because there are two kinds of contamination:

- **A band of ±3× the median** drops order-of-magnitude junk — a monthly financing instalment renders in the same `### <n>\nPLN` block as a price and clears the `1_000..10_000_000` guard easily. An IQR fence cannot catch this: enough junk drags the quartiles down with it, while the median is what junk cannot move. If the band would leave fewer than 3 prices the sample is reported untrimmed — a tight range invented from three survivors that happened to agree is worse than a visibly wide one.
- **Tukey's 1.5×IQR fence** (samples of 8+, skipped when IQR is 0) drops the right-order-of-magnitude-wrong-car cases: salvage titles, other trims. This is what fixes the live `min=39900` against `median=82900`.

`sampleSize` counts the **kept** prices, so the UI's "small sample" caveat describes the listings the numbers actually came from. The discarded count is logged, not returned. The median is a real median — averaged over both middle elements on an even sample, where the old `prices.get(size / 2)` was the upper-middle element.

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
- **A comma-separated list of test classes in `mutation.targetTests` is the second way to get a false 0%.** `-Dmutation.targetTests=a.b.FooTest,a.b.BarTest` matched nothing and reported `0/61 killed` over `BUILD SUCCESS`; `-Dmutation.targetTests='a.b.*'` on the same code reported 87%. So the rule above generalises: **a score of 0 is a tooling result until proven otherwise** — check the version, then check that the filter selected any tests at all.
- `live-llm` is excluded, for the same reason it is excluded from `./mvnw test`: a mutant must never be judged by whether somebody's API key worked today.

`MarketPriceStatistics` on 2026-09-04: **89%** (47/53 killed, 95% line coverage, 0 uncovered mutants), up from a first baseline of 81% (43/53, 1 uncovered). The first run found two real gaps, and three tests closed them:

- **`MIN_SAMPLE_FOR_IQR` was unguarded at its own edge.** `kept.size() >= 8` → `> 8` survived: every existing test either had 7 survivors after the band or reached the line with `bandCollapsed` already true, so the size comparison never decided anything. `aSampleOfExactlyEightSurvivorsIsFenced` pins the "or more".
- **Neither arm of the Tukey hinge was pinned, and one had no coverage at all** — no test reached `withoutIqrOutliers` with an odd sample, so `sorted.size() % 2 == 0 ? half : half + 1` could have used either arm unnoticed. Two tests, because the two arms need opposite samples: `anOddSamplePutsItsMedianInBothHalvesWhenComputingTheHinges` kills the negate and `half + 1` → `half - 1` mutants, and `anEvenSampleTakesExactlyHalfIntoEachHinge` kills the `% 2` → `* 2` mutant, which on an *odd* sample selects the same arm as the original and so is unkillable there.

The 6 remaining survivors are left alive deliberately. Five are `ConditionalsBoundary` mutants on the inclusivity of the ±3× band edges (124, 125) and the IQR fence edges (93, 142 ×2): pinning the exact `>=`-vs-`>` of a constant the code's own comment calls wide on purpose mirrors the implementation rather than defending a behaviour. The sixth is genuinely **equivalent** — flipping the `iqr == 0` early return from `sorted` to empty changes nothing, because the caller rejects a too-small fenced list and keeps the untrimmed sample either way, so no test can kill it.

`CepikRiskAdjuster` on 2026-09-04: **90%** (55/61 killed, 99% line coverage, test strength 92%), up from 87% / 88% when the negation fix first went green. PIT earned its run here by asking a question the green suite could not: the `if (denied)` guard around the new **log line survived**, which meant nothing would notice the trace firing on the wrong branch or not firing at all. That log exists *because* the defect was unobservable, so leaving it unobserved reproduced the original failure one level up — the two log tests came from that survivor, and the log-injection one pins a security property rather than a wording. Of the six left alive, five are pre-existing and outside the change (`capRisk` / `applyFloor` / `describeDamage` / `moreSevere`, one of them a defensive `b == null` guard no caller can reach), and the sixth is the `<=` on the log-truncation constant.
