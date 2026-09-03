# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-09-03

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the risk
   wins. Do not promote to e2e because e2e "feels safer." Do not put a vision
   model on top of a deterministic visual diff that already catches the
   regression.
2. **User concerns are first-class evidence.** Risks anchored in "the builder
   is worried about X, and the failure would surface somewhere in <area>"
   carry the same weight as PRD lines or hot-spot data.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from documents,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is produced
   by `/10x-research` during each rollout phase. If the plan and research
   disagree about where the failure lives, research is the ground truth.

A fourth rule is inherited from `CLAUDE.md` and is not negotiable here
either: **a test that tolerates the failure mode it exists to catch is
decoration.** Live tests assert real outcomes, never `LOOKUP_FAILED` /
`FETCH_FAILED` as a pass; fixtures under the registry fixture directory stay
verbatim captures.

Hot-spot scope used for likelihood weighting: `backend/src`, `frontend/src`
(docs, fixtures, and build output excluded; 16 commits in the last 30 days —
thin but above the signal floor).

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact × likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (that is
research's job, see §1 principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|---|---|---|---|
| 1 | User waits out the full analysis and gets nothing back — the provider pool is saturated or a slug was retired, or the request runs past the 30 s budget | High | High | interview Q1, Q2; roadmap F-01 "free-tier OpenRouter slugs are the main production fragility"; PRD NFR (no analysis runs longer than 30 s without a visible result or error); roadmap Open Roadmap Questions (~27 s synchronous); hot-spot dir `backend/src/main/java/.../analysis` (10 commits/30d) |
| 2 | The vehicle-history panel shows, or lets the user infer, "no reported damage" for a car the registry says carries a registered significant damage | High | High | interview Q1, Q4; roadmap S-04 Risk + Lesson (field names were invented; the suite stayed green because fixtures were hand-written to match them); PRD guardrail (absence of accident data means unknown, never clean); hot-spot dirs `backend/src/main/java/.../cepik` (11 commits/30d), `frontend/src/app/features/analyzer/components/cepik-result` (6 commits/30d) |
| 3 | Registry findings never reach the verdict, so a car with a registered significant damage still shows a reassuring score and label | High | Medium | roadmap S-01 carried-forward (production returned `risk: 88 / WORTH_CHECKING` for a vehicle with a registered szkoda istotna); interview Q3; hot-spot dir `backend/src/main/java/.../analysis` (10 commits/30d) |
| 4 | "Not checked" and "checked, registry reported nothing" render identically, so an unchecked history reads as a clean one | High | Medium | interview Q4; PRD guardrail; project rules (every non-`FOUND` result carries null lists, never empty ones); hot-spot dirs `frontend/src/app/features/analyzer/components/cepik-result` (6 commits/30d), `frontend/src/app/shared/models` (5 commits/30d) |
| 5 | A price sample too thin or too dispersed to mean anything is presented as a market range the buyer trusts | Medium | High | interview Q1, Q2; roadmap S-05 (`roadmap.md:187` — a live run returned `min=39900` against `median=82900`, an earlier one `min=22900` for 2017–2021 Corollas; "the trim is statistical, not semantic"); hot-spot dir `backend/src/test/java/.../market` (4 commits/30d) |
| 6 | Listing text written to game the analyser — an accident-free assertion, or instructions aimed at the model — produces a reassuring verdict | Medium | Medium | abuse lens, untrusted input (PRD FR-002 accepts raw pasted listing text); roadmap S-01 carried-forward ("the scoring layer trusts the listing's own claims") |
| 7 | The open analysis endpoint is called in a loop, exhausting the free provider quota and the single backend instance | Medium | Medium | abuse lens, resource abuse; PRD Access Control ("unauthenticated visitors cannot access any analysis functionality") against roadmap F-03 status `proposed`; roadmap Open Roadmap Questions (~27 s of request thread per call) |

Risk #7 is scored on the same axes as the rest but is **assigned no rollout
phase**: its protection requires a control that does not exist yet (auth or a
rate limit), so a test written today could only assert current intended
behaviour. It belongs to foundation F-03 plus observability. Authorization
and ownership abuse (IDOR) is deliberately absent from the map for the same
reason — there is no persistence and no account model until F-02 and F-03
land, and the check arrives with slice S-03.

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|---|---|---|---|---|---|
| #1 | A saturated or retired provider ends in a distinguishable, user-visible outcome — never a hang, never a success shape carrying empty content | "A 200 from the provider means we have an analysis"; "a retry always helps" | Where the request deadline is enforced; how transient is separated from permanent; what the client receives in each branch | integration with a stubbed HTTP edge | Asserting the retry count instead of the user-visible outcome |
| #2 | A captured registry payload carrying a significant damage surfaces as a reported damage all the way out to the API response | "An empty damage list means the registry reported nothing" — it equally means the parse silently matched nothing, and the two are indistinguishable downstream | Where a parse miss turns into an empty list rather than an error or a null; which of null / `[]` / populated each status and each unreadable shape produces | unit + integration over verbatim captures | Pinning a false-clean shape as expected behaviour — writing `isEmpty()` where the honest answer is `isNull()`. Fixtures composed to match the parser stay prohibited by §6.5 |
| #3 | A registry damage caps the risk score and downgrades the verdict regardless of what the listing claimed | "The model already scored the risk, so the later adjustment is cosmetic" | Ordering of scoring against enrichment; which statuses are allowed to adjust; that the overall score is never raised | unit | Lifting the ceiling values out of the implementation and calling them the expected result |
| #4 | Three states — not checked, checked with nothing reported, checked with findings — produce three visibly different Polish messages | "An empty list is a safe default for missing data" | The actual response shape per status, and which template branch each one drives | component test (jsdom) | Snapshotting the rendered block instead of asserting the distinction |
| #5 | A sample too thin or too dispersed to be a market range is labelled as such rather than displayed as a confident range | "A number came back, so the range is meaningful" | How sample size and discard count reach the response; what the UI does at the boundary | unit + component test | Re-deriving the expected median with the production formula |
| #6 | Listing-supplied claims cannot move the deterministic floor that registry facts set | "The model will obviously ignore manipulation" | Which parts of the verdict are deterministic and which are model-produced | unit | An eval asserting a specific model wording — non-deterministic and expensive for the signal |
| #7 | No test this rollout. Protection needs a control that does not exist yet; see the note above §2's guidance table | — | — | — | — |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|---|---|---|---|---|---|
| 1 | Enrichment honesty | Prove a real registry damage reaches both the payload and the verdict, over verbatim captures | #2, #3 | unit + integration | complete | `context/archive/2026-08-27-testing-enrichment-honesty` |
| 2 | Availability and failure paths | Prove every provider and fetch failure ends in an honest, distinguishable outcome inside the time budget, and that a thin price sample labels itself | #1, #5, #6 | unit + integration (stubbed HTTP edge), live-tagged where a real outcome is assertable | complete | `testing-availability-failure-paths` |
| 3 | Guardrail rendering | Prove the three history states and the small-sample caveat read differently to a Polish-speaking user | #4, #2 (UI half), #5 (UI half) | component tests (jsdom) | not started | — |
| 4 | Quality gates | Run both suites on PR and push before auto-deploy, keeping live-tagged tests out of the gate | cross-cutting | gates | not started | — |

Ordering rationale: Phase 1 defends the two highest-impact scenarios at the
cheapest layer and does so where churn is highest. Phase 2 covers the
scenario the builder rates most likely (#1) and follows because the registry
edge was the highest-risk consumer of the HTTP stubbing seam — that work is
now done, and §6.2 documents the seam Phase 2 inherits. (This originally read
"needs a stubbing seam that does not exist yet." That was wrong:
`MockRestServiceServer` shipped with `spring-test` all along and three test
classes already used it; the registry edge was simply the one place it never
had been.) Phase 3 is where the product guardrail actually reaches the user,
and the frontend suite is the thinnest part of the codebase. Phase 4 comes
last because a gate over a suite that does not yet cover the top risks locks
in a false floor — but it must land, because `main` auto-deploys to
production on merge and nothing runs the suites today.

Phase 2's row reads `complete` against the live change folder, not an archive
path: the change is finished on `main` (six phase commits, `512f555`…`5de1ce9`)
but not yet run through `/10x-archive`. When it is, the folder becomes
`context/archive/<date>-testing-availability-failure-paths`.

**Carried into Phase 2** — closed. `HistoriaPojazduSession`'s cookie merge and
dedupe and its XSRF extraction now have dedicated coverage in
`HistoriaPojazduSessionTest`, asserted at the mock seam against RFC 6265 §5.4;
the shared-builder cookie leak the coverage exposed is fixed. See §6.7 Phase 2.

**Carried into Phase 3** — the first four were found during Phase 1 and are
UI-side halves of Risk #2 and Risk #4; the last six come from Phase 2 (§6.7)
and were left open rather than pinned:

- `cepik-result.component` has no spec at all.
- `cepikResult === null` renders **nothing** — no heading, no disclaimer. A
  reader cannot distinguish "not checked" from "section absent".
- `mileageStamps === []` renders no row, so a registry that reported no dated
  readings looks identical to one that was never asked.
- The registry-vs-listing mileage tolerance (`max(2000 km, 5%)`,
  registry-higher direction only) lives **only** in the frontend component. It
  does not feed the score, and there is no backend counterpart. Per
  `CLAUDE.md`, if it ever moves into scoring, delete the TypeScript copy
  rather than keeping two.
- **BLOCKING for Phase 3.** `market-price-panel.component` has **no spec**, and
  Phase 2 changed what it renders: the `sampleSize < 3` caveat was replaced by
  three blocks driven by the server's `sampleQuality` and `discardedCount`. No
  Node exists on the machine Phase 2 ran on, so that template edit is reviewed
  and unverified — a broken caveat would ship silently. This is the one carried
  item Phase 3 may not defer again: Phase 2 crossed its own "no frontend change"
  guardrail to make it, on the reasoning that a server field with no reader is
  the failure Phase 6 had just caught in Phase 3. That reasoning only holds if
  the reader is eventually verified. Phase 3 must add a spec covering all four
  arms — `DISPERSED`, `THIN`, `SUFFICIENT`, and a null quality — plus the
  `discardedCount` block, before it does anything else on this list
  (impl-review F4).
- `CepikRiskAdjuster.capRisk` (`CepikRiskAdjuster.java:134`) returns early when
  `risk <= cap` and skips the `overall` recomputation, so a model returning
  `risk: 3, overall: 97` for a car with a registered szkoda istotna keeps both
  numbers on screen.
- `verdict.label` is copied through unvalidated against `verdict.code`
  (`AnalysisResponseParser.java:208`, rendered at
  `analysis-result.component.html:3`), so the result's first line can read
  reassuringly above a floored verdict.
- The accident-claim phrase list (`CepikRiskAdjuster.java:38`) is a substring
  match and not negation-aware, so an honest `"nie jest bezwypadkowy"`
  false-positives into `CEPIK_CONTRADICTS_LISTING` and `HIGH_RISK_SKIP` — the app
  calls a truthful seller a liar.
- `ListingFetchService.java:134` bounds only a *minimum* fetched-body length
  (100 chars), so a URL is the unbounded path into the prompt while pasted text
  is capped at 20 000. Cost and latency scale with whatever the reader returns.
- The 30 s NFR (`prd.md:98`) is asserted by `RequestTimeoutBudgetTest` and
  enforced by nothing: the configured socket timeouts still sum to **295 s**
  worst case (`RequestTimeoutBudgetTest.java:180`, which is the source of this
  figure — read it rather than trusting this line). Phase 3 cannot fix that with
  a test — it needs the deferred async work (impl-review F10) — but the number
  belongs on this list so the next rollout phase does not read the green
  assertion as protection.

Browser-level e2e is deliberately not in this rollout: every risk above is
reachable at unit, integration, or component level, and an e2e layer over a
single-page flow would duplicate Phase 3 at a much higher cost. Revisit when
a risk surfaces that genuinely requires the deployed shape — auth plus token
storage plus a guarded route, once F-03 lands.

## 4. Stack

The classic test base for this project. AI-native tools (if any) carry a
`checked:` date so future readers can see which lines need re-verification.

| Layer | Tool | Version | Notes |
|---|---|---|---|
| backend unit + integration | JUnit 5 via Maven surefire | Spring Boot 4.0.6 | 28 test files, 224 tests (~15 s), across the analysis, cepik, and market packages. `live-llm` is excluded by default; the `live-tests` profile flips the include/exclude properties — `-Dgroups=live-llm` silently intersects to zero |
| frontend unit + component | vitest + jsdom via `@angular/build:unit-test` | Angular 21.2 | 3 spec files, 26 tests, ~2 s. Zoneless: no `fakeAsync` / `tick`; vitest matchers, not jasmine |
| HTTP mocking (backend) | `MockRestServiceServer` (`spring-test`) | 7.0.7 | `MockRestServiceServer.bindTo(RestClient.Builder)`, then `server.verify()`. Used by `ListingFetchServiceTest`, `OpenRouterAnalysisServiceTest`, `MarketPriceFetchServiceTest`, since rollout Phase 1 also `CepikDamageReachesTheResponseTest` and `HistoriaPojazduSessionTest`, and since Phase 2 `LlmFailureReachesTheClientTest` and `AnalysisSurvivesEnrichmentFailureTest`. See §6.2 |
| HTTP mocking (frontend) | `provideHttpClientTesting` + `vi.fn()` service doubles | Angular 21.2 | Already used by the existing specs |
| live integration | JUnit tag `live-llm` + `live-tests` profile | — | Asserts real outcomes only. A proxy 403 on the reader host fails the market-price live test on purpose, so a blocked path stays visible instead of silently green |
| e2e | none — deliberately unscheduled | — | See the note at the end of §3 |
| accessibility | none — not scheduled | — | No risk in §2 depends on it; revisit if one surfaces |
| CI | GitHub Actions, one live workflow only | — | No unit or integration gate on PR or push; see §3 Phase 4 |

**Stack grounding tools:**
- Docs: **Context7 MCP — available**, exercised during rollout Phase 2's research. It grounds framework behaviour, not versions: the versions in the table above still come from the local manifests, the Angular workspace config, and `CLAUDE.md`, which are the authority for what this repo actually resolves; checked: 2026-09-03
- Search: **Exa MCP — available**, and it produced rollout Phase 2's one genuinely external oracle: OpenRouter's published status semantics (408 is a timeout and therefore transient; 402 is insufficient credits and therefore fatal for every model, not permanent for one). That is what the 408/402 routing tests assert against, rather than against the classification tree they were written to correct; checked: 2026-09-03
- Runtime/browser: none — Playwright MCP not available in current session, which is one input into leaving e2e unscheduled; checked: 2026-08-27
- Provider/platform: none — no GitHub, Render, Cloudflare, or Supabase MCP; deploy and log inspection run through REST with keys from the environment, so CI gate wiring in Phase 4 must be authored against the platform docs rather than probed; checked: 2026-08-27

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required for §3 Phase N" means the gate is enforced once that rollout phase
lands; before that, the gate is `planned`.

| Gate | Where | Required? | Catches |
|---|---|---|---|
| compile + typecheck | local, CI after §3 Phase 4 | required after §3 Phase 4 | type and syntax drift; stale specs that no longer compile |
| backend unit + integration | local now, CI after §3 Phase 4 | required after §3 Phase 1 | logic regressions across analysis, cepik, and market |
| frontend unit + component | local now, CI after §3 Phase 4 | required after §3 Phase 3 | guardrail copy regressions and state collapse in the rendered result |
| live integration | local, plus the existing scheduled workflow | never a PR gate | real breakage in the provider, the registry, or the reader edge — kept out of the gate because a third-party outage must not block a merge |
| pre-prod smoke | between merge and production | optional after §3 Phase 4 | environment-specific failures: profile selection, missing environment variables, CORS |

## 6. Cookbook Patterns

How to add new tests in this project. Each sub-section is filled in once the
relevant rollout phase ships; before that, the sub-section reads
"TBD — see §3 Phase N."

### 6.1 Adding a backend unit test

- **Location**: `backend/src/test/java/com/example/autoskaner_ai/<package>/`, mirroring the package under test.
- **Naming**: `<ClassUnderTest>Test.java`.
- **Reference test**: `backend/src/test/java/com/example/autoskaner_ai/market/MarketPriceStatisticsTest.java` — pure-function tests with an independent expected value, including the "caller's list is not mutated" case.
- **Run locally**: `cd backend && ./mvnw test`.

### 6.2 Adding a backend integration test

- **Location / naming**: as §6.1, except a test that spans several classes is named for the behaviour rather than a class under test — `CepikDamageReachesTheResponseTest`, not `<Class>Test`.
- **Reference tests**: `backend/src/test/java/com/example/autoskaner_ai/cepik/CepikDamageReachesTheResponseTest.java` for a full stack behind a stubbed socket; `.../analysis/AnalysisControllerTest.java` for the controller boundary alone.
- **The seam**: build the same `RestClient.Builder` production configures (mirror the `@Configuration` bean minus its request factory), call `MockRestServiceServer.bindTo(builder)`, hand that builder to the real service. The bind survives later `builder.build()` calls, so a class that rebuilds its client mid-session — as `HistoriaPojazduSession` does per cookie refresh — is still stubbed.
- **Pattern**: expect the whole call sequence in order, respond with verbatim captures via `withSuccess(new ClassPathResource(...), APPLICATION_JSON)`, assert the HTTP response body, finish with `server.verify()`.
- **Expectations are ordered by default, and that is useful**: the order is what pins which payload reaches which argument. Swapping `parser.parse`'s two arguments kept the entire repo green until this test existed.
- **Six gotchas, each learned the hard way**:
  - A session closed in a `finally` block still makes a request. Stub it, or an unexpected-request `AssertionError` fires *after* your assertions have passed and reads like a mystery.
  - `jsonPath(...).doesNotExist()` and `.value(nullValue())` both pass for a JSON `null`, so neither can prove a key is present-and-null. Read the raw body: `andReturn().getResponse().getContentAsString()`.
  - `MockMvcBuilders.standaloneSetup` builds its own message converters and never reads `application.properties`. Anything that depends on the application's Jackson configuration needs a booted context — see `CepikResultSerialisationTest`.
  - A bodyless 200 fails inside `RestClient` and lands in your service's catch block, so a status assertion can pass without the branch you meant to exercise ever running. Serve `{}` when you want an *unreadable payload* rather than a *transport failure*.
  - **A null default header is present-with-a-null-value here, not absent.** `HistoriaPojazduSession` installs `X-Xsrf-Token` with whatever it extracted, and with no token that is `null`. Production's request factory coerces it to an empty string on the wire; `MockRestServiceServer` keeps it literally present, so `headerDoesNotExist` fails with *"it exists with values: [null]"*. Assert `headerList(name, contains((String) null))` and note in a comment that this pins the seam's truth rather than the wire's — see `HistoriaPojazduSessionTest.aHandshakeWithNoXsrfCookieWarnsInsteadOfDegradingSilently`.
  - **A failed matcher throws `AssertionError`, which is an `Error`**, so it escapes a `catch (Exception)` in the code under test and surfaces as a test failure instead of being wrapped into that class's own exception. This is what lets header assertions work at all inside a method that swallows failures — and it means widening such a catch to `Throwable` would silently mute every one of them. Say so in the test, because nothing in the production code hints that a test depends on the catch's width.
- **Mocking policy (binding)**: mock at the network edge only, never internal collaborators. Registry fixtures under `backend/src/test/resources/cepik/` follow §6.5; the market-price fixtures under `backend/src/test/resources/market/` follow the same rule for the same reason — see that directory's README.
- Provider and reader *failure-branch* coverage (deadlines, retry-vs-fallback, thin samples) shipped with rollout Phase 2; the pattern lives in §6.4. This section covers the seam.

### 6.3 Adding a frontend component test

- **Location**: next to the component, `<name>.component.spec.ts`.
- **Reference test**: `frontend/src/app/features/analyzer/analyzer.component.spec.ts`.
- **Binding constraints**: no `fakeAsync` / `tick` (the app is zoneless); vitest matchers only (`toBe(true)`, not `toBeTrue()`); use `await fixture.whenStable()` if real async ever appears.
- **Run locally**: `cd frontend && npm test -- --watch=false`.
- Fuller guidance on the three-state rendering pattern: TBD — see §3 Phase 3.

### 6.4 Adding a test for a new API endpoint

- **Test type**: integration, at the controller boundary.
- **Pattern**: assert the response shape *and* the guardrail semantics — which fields are null versus empty, and what a downstream consumer would render from them.
- **Reference test**: `backend/src/test/java/com/example/autoskaner_ai/analysis/AnalysisControllerTest.java`. For an endpoint whose behaviour depends on an outbound call, stub the socket instead of the collaborator — see §6.2.

**The failure branches** (pattern established by rollout Phase 2):

- **Assert the user-visible outcome, never the retry count.** A retry that fired is not a protection; a client that can tell which failure it hit is. `LlmFailureReachesTheClientTest` is the reference: four provider behaviours in, four different Polish `error` headlines out, at the controller boundary with only the provider socket stubbed.
- **Distinguish the causes, and prove the distinction rather than each cause separately.** Before Phase 2 a rejected key, an unusable provider response and an exhausted fallback chain rendered byte-identical 502 bodies. Hold each expected headline as a named constant and add one test asserting the set has no duplicates — comparing two branches directly passes when both are wrong in the same way.
- **A malformed third-party payload is a 502 about them, not a 500 about us.** Four provider-quirk routes (null `message`, null `content`, non-String `content`, unknown enum value) used to escape as the catch-all 500. Each needs its own case; a shared "returns an error" assertion cannot see the difference.
- **Serve `{}` for an unreadable payload** rather than an empty body — §6.2's gotcha, and it bites hardest here, where the branch under test *is* the parse failure.
- **Reach a deadline or budget branch by injecting the budget, not by waiting.** `OpenRouterAnalysisService`'s `deadlineSeconds` is a constructor parameter; `0` reaches the fallback-budget-exhausted branch at zero wall-clock cost. A timing assertion of the shape `elapsedMs >= 900` is *not* evidence a wait happened — it passes equally when the wait was clamped to zero, which is exactly the defect Phase 2 found.
- **A degraded enrichment must not cost a finished analysis.** `AnalysisSurvivesEnrichmentFailureTest`: make the enrichment collaborator throw, then assert a 200 that still carries the analysis and a non-null but degraded context. Keep the negative control in the same class — an *LLM* failure must still be a 502, or the guard has quietly turned every failure into a cheerful 200.
- **Assert the configured budget against the documented NFR.** `RequestTimeoutBudgetTest` reads the timeouts out of the config classes and compares the total against `prd.md`'s 30 s, quoted in a comment as oracle source (4). It exists so a future timeout bump fails the build. It does not claim the NFR is enforced — it is not; see §6.7.

### 6.5 Adding a test for a new enrichment source or registry field

**Fixtures come in exactly two classes.** The distinction is load-bearing; see `backend/src/test/resources/cepik/README.md` for the full convention.

- **Verbatim captures** (`*-found.json`, `not-found-*.json`, no suffix) — byte copies of real responses. The only permitted edit is a documented redaction of an identifying value; the committed VIN is the synthetic `NMTBZ3BE40R000000` because this repo is public. **Add no field mapping without a captured payload showing that field name.**
- **Derived fixtures** (`*-derived.json`) — produced from a named verbatim parent by exactly one of *deleting a node* or *changing a value*. Never by introducing a key, renaming a key, or composing an object. Each carries a top-level `_provenance` naming its parent and the edit, so the claim is checkable with a diff.

**The rule generalises past JSON, and rollout Phase 2 needed it to.** *Third-party payloads must be captured; shapes we own may be composed.* The Otomoto markdown that `PRICE_PATTERN` reads is Otomoto's, rendered by Jina — neither is ours, so a hand-written markdown fixture would be the regex agreeing with itself, and that is the 2026-08-26 failure in a different file format. The `List<Integer>` that `MarketPriceStatistics` consumes **is** ours — an internal parameter, not a wire format — so composing lists to land on the band, fence and median boundaries is correct. Both live under `backend/src/test/resources/market/`; that directory's README carries the capture's provenance.

A **derived fixture can need a guard of its own.** The market capture is LF-only, so it can only ever exercise half of `\r?\n`; its CRLF sibling is derived by one mechanical edit. `core.autocrlf=true` on any clone or CI runner would rewrite both to one style and the pair would silently test one case twice, so both files are pinned `-text` in `/.gitattributes` **and** a test asserts the pair still differs only in line endings. A config file that nothing checks is not a guarantee.

A third class is prohibited: a fixture composed key-by-key from what the parser expects cannot detect a mapping failure, because it *is* that failure one file earlier. That is the 2026-08-26 incident verbatim — the parser looked for `zdarzenia` / `szkodyIstotne` / `przebieg`, names the registry has never returned, and every test passed because the fixtures had been written to match them.

**The invariant: null, empty, and populated are three states, not two.**

- `null` — we do not know. No lookup, a failed lookup, an unreadable payload, or a vocabulary we no longer recognise.
- `[]` — the registry answered and reported nothing. A positive claim.
- populated — findings.

The UI renders `[]` as "brak zgłoszonych szkód istotnych" and `null` as unknown, so collapsing them inverts the product's core guardrail. Enforcement sits in two places: `CepikResult.withoutData` makes every non-`FOUND` status null by construction, and `HistoriaPojazduParser`'s **vocabulary canary** (`KNOWN_EVENT_TYPES`) degrades to null rather than `[]` when the registry's `eventType` values drift out from under the parser. Assert the tri-state at the wire too — see §6.2's `jsonPath` gotcha.

Known gap in the canary: it accepts the vocabulary if *any one* event type is recognised, so a rename of `szkoda-istotna` alone would still yield `[]`. Narrower than the original failure, still a false-clean route.

**Expected values may come from four sources and no others.** Independence from the implementation is the whole point:

1. the bytes of a committed capture,
2. a production incident (e.g. `risk: 88 / WORTH_CHECKING` for a car with a registered szkoda istotna, 2026-08-26),
3. hand arithmetic or calendar rules, written out in a comment,
4. a stated product guardrail — *absence of accident data means unknown, not clean*.

`CLAUDE.md` is the **specification of record, not an independent derivation** — quoting it is source (4), not a fifth oracle. Reading the class under test is never a source. Where a value genuinely has no oracle outside the implementation (the risk-cap magnitudes), pin it in exactly one test and say so in that test's comment; assert the falsifiable property — their ordering — separately. `CepikRiskAdjusterTest` is the reference for that split.

- **Reference tests**: `HistoriaPojazduParserTest` (captures → model), `CepikDamageReachesTheResponseTest` (captures → HTTP response), `RegistryFactsReachTheScoreTest` (derived fixtures → score, with a control on the unmodified capture).
- **Verify with a mutation, not a green run.** Break the thing the test exists to catch and confirm it fails. A test that survives its own mutation is decoration — §1's fourth rule.

### 6.6 Adding a CI gate

- TBD — see §3 Phase 4.

### 6.7 Per-rollout-phase notes

**Phase 1 — Enrichment honesty** (`testing-enrichment-honesty`, shipped 2026-09-01). 132 → 163 backend tests.

The phase set out to *prove* Risks #2 and #3 and found two live false-clean routes in production code on the way, so it **closed them rather than pinning them**. That distinction is the phase's main lesson: a rollout phase that only writes tests will happily freeze the bug.

- `HistoriaPojazduParser` reported `FOUND` with every field empty when neither payload was readable — a "found in the registry" panel with nothing in it, which reads as a clean history. Now `LOOKUP_FAILED`.
- The same parser returned `damageRecords: []` whenever the registry's `eventType` vocabulary drifted. Now a canary (`KNOWN_EVENT_TYPES`) degrades to `null`. Known residual gap in §6.5.
- Separately, `RealCepikEnrichmentService` rejected `"WA 12345"` — the commonest way a correct plate is written — as `MISSING_INPUTS` with no lookup at all, while the same user's spaced VIN was normalised fine.

What the phase proved, and how:

- Captured registry bytes reach both the response payload and the verdict, over a stubbed socket with the whole stack real (§6.2).
- The null / `[]` / populated tri-state survives to the wire, including against the application's own Jackson configuration.
- `NOT_FOUND` is driven by a real 404 wrapped by real code, not by a test writing the exception message it then matches.
- The risk adjuster's oracles no longer include the code under test: the mean-of-four formula copy is gone, which is what had made the never-raise guard unreachable by all fourteen tests that existed.

Method worth reusing: **every phase criterion was a mutation, not a green run.** Six mutations were applied and reverted. One criterion turned out to be unreachable as specified — a raw-body assertion under `standaloneSetup` is blind to `spring.jackson.default-property-inclusion`, because that builder never reads `application.properties` — and only running the mutation revealed it. Writing the criterion as "confirm X fails" rather than "confirm X is covered" is what caught it.

**Phase 2 — Availability and failure paths** (`testing-availability-failure-paths`, shipped 2026-09-03). 163 → 224 backend tests, 23 → 28 test files.

Research found that **nine of the behaviours this phase existed to prove were broken**. Writing tests against them would have pinned the failure mode, so — Phase 1's lesson applied a second time — the phase closed them first and then proved them closed.

Closed, not pinned:

- **A 200 OK could carry a hollow analysis.** `AnalysisResponseParser.validateRequired` null-checked six *containers* while all sixteen leaf fields could be null, and `ScoresDto`'s primitive `int` coerced a null score to `0` — a perfect score for a field the model never returned. Now a schema failure that names the field.
- **Three distinct 502 causes rendered byte-identical bodies**, and four provider-quirk routes (null `message`, null `content`, non-String `content`, unknown enum) escaped as a generic 500 blaming this server for somebody else's payload. Now four distinguishable Polish headlines.
- **The retry could fire with a zero wait.** `retryWait` clamped to the deadline remainder and `sleepQuietly` returned `true` for a non-positive wait — precisely the immediate same-model retry that turned single 429s into production 502s on 2026-08-26. A wait that will not fit the remaining budget now moves down the fallback chain instead.
- **408 and 402 were both misrouted** into the "permanent for this model" catch-all: a timeout skipped a model that would likely have answered, and insufficient credits walked the entire chain on an error guaranteed to repeat. Now 408 retries and 402 fails fast.
- **A finished analysis could be discarded.** `AnalysisController.buildResponse` had no try/catch, so a throw from the slug mapper or the statistics stage turned a completed ~16 s analysis into a 500. Now guarded, and `marketPriceContext` is always present — S-05's stated invariant.
- **The registry session leaked cookies between lookups.** One shared mutable `RestClient.Builder` bean, mutated per session, meant lookup *N*'s `JSESSIONID` was still on it for lookup *N+1*'s bootstrap GET and two concurrent analyses shared one jar. Both enrichments run on the request thread, so that is ordinary load, not a stress case. Now a clone per session — `RestClient.Builder#clone()` copies the request factory, so §6.2's seam survives it.
- **`accidentClaim == null ⇒ NO_ACCIDENT_DECLARATION` was asked of the model and enforced nowhere.** Now enforced in the parser, idempotently, so an *unknown* accident history cannot render as a *silent* one.
- **The thin-sample label had an off-by-one at exactly 3**, and dispersion was computed then thrown away. `MIN_SAMPLE_TO_KEEP = 3` reports a 3-price sample untrimmed while the UI caveat fired on `sampleSize < 3` — so the most contaminated range the pipeline can emit was the one size that showed no caveat at all. `MarketPriceContext` now carries `sampleQuality` (`SUFFICIENT` / `THIN` / `DISPERSED`) and `discardedCount`, decided on the server.
- **`HistoriaPojazduSessionTest`'s own Javadoc was false**, claiming the cookie merge was asserted in `CepikDamageReachesTheResponseTest`. It was asserted nowhere in the suite.

What the phase proved, and how:

- Four provider behaviours reach a client as four different Polish headlines, asserted at the controller boundary with only the provider socket stubbed and the parser, service, controller and exception handler all real (§6.4).
- Retry-versus-fallback is decided against the remaining budget, and the deadline-skip branch runs under test at zero wall-clock cost by injecting `deadlineSeconds=0` rather than waiting.
- The configured socket-timeout total is asserted against `prd.md`'s 30 s, so a future timeout bump fails the build.
- An enrichment throw costs the market range and not the analysis — with the negative control that an *LLM* failure is still a 502, not a cheerful degraded 200.
- The registry cookie header and XSRF token are asserted directly at the seam, with RFC 6265 §5.4 as the oracle rather than a reading of `extractCookies`.
- Listing-supplied claims cannot move the deterministic floor, with a control on an *honest* disclosure so the claim strings do real work rather than passing against an adjuster that flags unconditionally.
- The price regex met real Otomoto bytes for the first time, and the capture turned out to carry real contamination: one price at 21 800 against a median of 79 900, below the ±3× band floor, dropped — so the reported minimum is 40 900 rather than a bargain that does not exist. A composed fixture could not have contained that.

Three method lessons, each of which cost something to learn:

- **A mutation can be a no-op, and only measuring tells you which.** One planned criterion was "delete `\r?` from `PRICE_PATTERN` and watch the CRLF test fail". Measured against both fixtures, deleting it changes the match on neither: `[\d\s]+` already admits `\r` and `replaceAll("\\s", "")` then removes it. `\r?` is unfalsifiable while the character class stays that wide. Two mutations that do bite were substituted — narrow the class to `[\d \n]+`, and normalise the derived fixture to LF. Report the mutation you ran, not the one the plan named.
- **§6.2's third gotcha, demonstrated rather than restated.** Appending `spring.jackson.default-property-inclusion=non_null` failed the booted `MarketPriceContextSerialisationTest` while all fifteen `AnalysisControllerTest` cases stayed green. That is the blind spot, measured — and the reason a serialisation claim needs a booted context.
- **A contract change needs its consumer wired in the same phase.** The plan assigned the new `sampleQuality` field's rendering to its Phase 3; that did not land, so the server field would have been read by nothing and the user-visible hole the work existed to close would have survived a phase built to close it. Caught two phases later. The template is now wired but **reviewed, not verified** — no Node exists on this machine, so the frontend suite could not run; the spec belongs to rollout Phase 3.

Known gaps, left open on purpose. Each would have been *pinned* by a test asserting today's behaviour, which §1 rule 4 forbids, so each is recorded here and carried into rollout Phase 3 instead:

- `CepikRiskAdjuster.capRisk` returns early when `risk <= cap`, skipping the `overall` recomputation. A model returning `risk: 3, overall: 97` for a car with a registered szkoda istotna keeps both numbers.
- `verdict.label` — the model-authored headline, rendered as the result's first line — is never validated against `verdict.code`, so it can read reassuringly next to a floored verdict.
- The accident-claim phrase list is not negation-aware. `"nie jest bezwypadkowy"` — an *honest* seller — still false-positives into `CEPIK_CONTRADICTS_LISTING` and `HIGH_RISK_SKIP`.
- `ListingFetchService` bounds only a *minimum* body length (100 chars), so a URL remains the unbounded path into the prompt while pasted text is capped at 20 000.
- The 30 s NFR is **asserted, not enforced**. The 295 s worst case is still reachable; this phase made it visible and regression-guarded, not impossible. Enforcement is impl-review F10's deferred async work. (The figure was written here as ≈341 s, which was the pre-Phase-2 sum: Phase 2's own removal of the retry clamp took the LLM stage from 156 s to 110 s, and the Phase 7 backport instruction had been drafted before that landed. `RequestTimeoutBudgetTest.java:180` is the number's only source of truth.)

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption changes.

- **The mock profile's own output** — `MockAiAnalysisService` and `MockCepikService` are deterministic stubs that exist to serve other tests; asserting their canned responses proves nothing. Re-evaluate if a mock ever encodes business logic rather than a fixture. (Source: Phase 2 interview Q5.)
- **The deprecated risk endpoint** — slated for deletion since S-01 shipped; testing it entrenches something the roadmap wants gone. Re-evaluate only if the decision to remove it is reversed. (Source: Phase 2 interview Q5; roadmap S-01 carried-forward.)
- **DTO plumbing** — Java records, Angular model interfaces, getters, and straight field mapping with no logic. Re-evaluate for any type that gains defaulting, normalisation, or validation. (Source: Phase 2 interview Q5.)
- **Visual appearance is not excluded, but it is not scheduled** — no risk in §2 is visual-only, so under cost × signal nothing is spent there this rollout. If a layout or z-index failure surfaces, prefer a deterministic diff over a vision model. (Source: Phase 2 interview Q5 — the builder declined to exclude it.)

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-09-03 (§2 Risk #5's Source figures replaced with the ones `roadmap.md:187` actually records — the min/median pairing previously cited there appears in no artifact and had been carried for two rollout phases; §3 Phase 2 flipped to `complete`, its carried-forward item closed, six new items carried into Phase 3; §4 counts and grounding-tool lines corrected — rollout Phase 2)
- Stack versions last verified: 2026-09-03 (backend counts re-counted from a full run: 28 files, 224 tests; `spring-test` 7.0.7 unchanged; frontend untouched since 2026-08-27 and **unrunnable on the current machine** — no Node or npm, so the frontend row is documentation, not a verified figure)
- AI-native tool references last verified: 2026-09-03 (Context7 and Exa are available and were exercised; neither is *in* the stack — they ground it. See §4)

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
