# Enrichment Honesty (Test Rollout Phase 1) Implementation Plan

## Overview

Rollout Phase 1 of `context/foundation/test-plan.md` defends Risk #2 (a car with a
registered szkoda istotna reads as having no reported damage) and Risk #3 (registry
findings never reach the verdict).

Research established that the 2026-08-26 incident — invented registry field names — is
closed, but the *false-clean shape* survives one layer up, reachable by two different
routes in production code that no test can close. So this phase is a **test-and-fix
phase**: close both routes in `HistoriaPojazduParser`, then prove over verbatim captures
that a real damage reaches the HTTP response body *and* the score and verdict, with
expected values sourced from outside the code under test.

## Current State Analysis

**What already works.** `backend/src/test/resources/cepik/` holds two genuine captures
(`vehicle-data-found.json`, `timeline-data-found.json`) carrying a real szkoda istotna at
`timeline-data-found.json:48-65`. `HistoriaPojazduParserTest` asserts date, description,
insurer and category against those bytes. `CepikRiskAdjusterTest` has 14 tests covering
all five caps, both verdict floors, never-raise, four no-op cases and flag ordering, and
`AnalysisControllerTest:173-193` covers the wiring through HTTP with the real adjuster.

**The two production routes to a false-clean panel.**

| Route | Mechanism | User-visible outcome |
|---|---|---|
| A | `HistoriaPojazduParser.java:62-63` returns `CepikStatus.FOUND` with no other exit; `HistoriaPojazduSession.fetchVehicleData`/`fetchTimelineData` yield `null` from `.body(Map.class)` on a 204 or empty 200 | a "found in the registry" panel with no registry data in it |
| B | `damagesFrom` (`:120-131`) returns an empty **non-null** list when no event matched, and `:123` matches `szkoda-istotna` with exact case-sensitive `.equals` | `damageRecords: []`, which the UI renders as "brak zgłoszonych szkód istotnych" — a positive clean claim for a car the registry says is damaged |

Route B is the one with real harm and it is the same vocabulary-drift class as the original
incident, reached without anyone inventing a field name. `readEvents` returning `null` for
an absent `events` node (`:90-94`) is correct and must stay.

**The coverage gap between payload and response.** `HistoriaPojazduServiceTest:14` mocks
the parser away, so `lookup`'s success path (`:44-46`) never executes — swapping its two
payload arguments keeps the whole repo green while producing null identity and null damages
in production. `HistoriaPojazduSession` has no test at all. And `$.cepikResult.status`
(`AnalysisControllerTest:167`) is the only `cepikResult` JSON node asserted anywhere: no
test places a damage record in an HTTP response body, and nothing pins `damageRecords`
serialising as an explicit `null` rather than absent or `[]` — the distinction the frontend
branches on.

**Risk #3's oracle problem.** `CepikRiskAdjusterTest:28` builds its input as
`new CategoryScores(90, 75, risk, 60, (90 + 75 + risk + 60) / 4)` — the production formula
from `CepikRiskAdjuster.java:138` copied into the fixture. Because the input `overall` is
always the mean and capping risk downward always lowers the mean, the
`Math.min(scores.overall(), overall)` never-raise guard at `:141` is **unreachable in all
14 tests**. Separately, `25` is asserted in three places, all mirroring one private
constant, and no test combines two independent registry findings.

**Environment.** Research could not execute the suite. The actual blocker is `JAVA_HOME`
pointing at a Zulu 8 **JRE**; with a JDK 21+ the suite runs green at **132 tests**:

```bash
cd backend && JAVA_HOME="D:/Software/Java/jdk-26.0.1" MAVEN_OPTS="-Xmx1g" ./mvnw -o test
```

## Desired End State

- No route remains by which the registry's silence, or a shift in its event vocabulary,
  presents as a clean history. The parser reports `LOOKUP_FAILED` when it read nothing and
  `null` damages when it cannot recognise the timeline's vocabulary.
- One test drives the committed captures through the real HTTP edge, session, parser and
  enrichment service into an `AnalysisResponse` body, asserting both the damage record and
  the capped score and downgraded verdict.
- `damageRecords` is pinned at the wire in all three states: populated, explicit `null`,
  and `[]`.
- `CepikRiskAdjusterTest` no longer computes any expected value with the formula under
  test, and the never-raise guard is reachable.
- A user who types `WA 12345` gets a registry lookup.
- `test-plan.md` no longer contains claims this phase falsifies, and §6.5 is filled in.

Verify by running the command above: all tests green, count materially above 132, and the
`git diff` on `src/main` limited to `HistoriaPojazduParser` and `RealCepikEnrichmentService`.

### Key Discoveries:

- **The stubbing seam works, verified by a throwaway probe during planning.**
  `MockRestServiceServer.bindTo(historiaPojazduBuilder)` survives both of
  `HistoriaPojazduSession`'s `builder.build()` rebuilds (`:38`, `:54`, `:69`). The probe
  drove the committed captures to `status=FOUND`, `make=TOYOTA` and the `2023-02-07` / `PZU`
  damage on the first attempt. `test-plan.md` §4 and §6.2 are wrong to say no seam exists.
- **The session makes exactly five ordered calls**: `GET` `/uslugi/engine/ng/index?xFormsAppName=HistoriaPojazdu`
  → `POST` the same path (bootstrap HTML) → `POST` `<apiBase>/vehicle-data` → `POST`
  `<apiBase>/timeline-data` → `GET` `<apiBase>/close`. `close()` is in a `finally` block, so
  a stub must expect it.
- **Version discovery is assertable.** The probe served HTML naming `1.2.3` and the data
  calls went to `/nforms/api/HistoriaPojazdu/1.2.3/data`, not `FALLBACK_API_VERSION`.
- **`Set-Cookie: XSRF-TOKEN=…` reaches the `X-Xsrf-Token` header**, and the body keys
  `registrationNumber` / `VINNumber` / `firstRegistrationDate` (`HistoriaPojazduSession.java:85`)
  match with `jsonPath` request matchers.
- **No Jackson customisation exists in `src/main`** and no `default-property-inclusion`
  property is set, so nulls are emitted and the three-way wire distinction is real.
- **The capture's event vocabulary is 10 distinct types across 14 events**:
  `zbycie-i-nabycie`, `zbycie`, `nabycie`, `badanie-techniczne-dodatkowe`,
  `zmiana-wlasciciela`, `szkoda-istotna`, `pierwszy-wlasciciel`,
  `pierwsza-rejestracja-w-polsce`, `dodanie-wspolwlasciciela`, `badanie-techniczne-okresowe`.
  The parser interprets only three of them; the other seven pass through unread. That
  observed set is what the canary in Phase 1 keys off, and it comes from capture bytes
  rather than invention.
- **`VinValidator.normalise:12`** strips `[\s\-]` internally; `RealCepikEnrichmentService:78`
  matches `PLATE_PATTERN` against `plate.strip().toUpperCase()` only. Confirmed asymmetry.
- **Four independent oracles are available** and are the only legitimate sources for
  expected values in this phase: the bytes of the committed captures; the production
  incident (`risk: 88` / `WORTH_CHECKING` for a car with a registered damage); hand
  arithmetic and calendar rules; and the product guardrail *absence ≠ clean*.
  `CLAUDE.md:139-144` is the specification of record, not an independent derivation.

## What We're NOT Doing

- **No frontend work.** `cepik-result.component` has no spec and two rendering gaps
  (`cepikResult === null` renders nothing at all; `mileageStamps === []` renders no row).
  Both belong to §3 Phase 3 and are recorded there, not fixed here.
- **No new `CepikStatus`.** Route A reuses `LOOKUP_FAILED`, so no model, serialisation, or
  Polish UI copy changes.
- **No cookie-merge or XSRF-extraction unit tests.** Exercised incidentally by the
  integration test; dedicated coverage is §3 Phase 2's HTTP-edge work.
- **No live-test changes.** `HistoriaPojazduServiceLiveTest` still asserts `NOT_FOUND` only;
  a `FOUND` assertion needs a real plate+VIN+date triple, which per `roadmap.md:169` cannot
  be committed.
- **No CI wiring.** §3 Phase 4 owns the gate.
- **No async/threading change**, no market-price work, no `ACCIDENT_FREE_CLAIMS` expansion.
  The phrase list has no external oracle; probing unlisted phrasings would only document
  that the matcher is a narrow substring list, which is a product decision, not a defect.

## Implementation Approach

Fix first, then prove, then re-oracle, then correct the documents.

Phase 1 closes both false-clean routes in production and establishes the derived-fixture
convention the later phases need. Phase 2 builds the one test that spans the whole gap —
capture bytes through the real network edge to a JSON response body — and asserts Risk #2
and Risk #3 jointly, because they are the same journey. Phase 3 is independent of 1–2 and
repairs Risk #3's oracles in place. Phase 4 corrects `test-plan.md`.

Phase 1 must precede Phase 2 so the integration test asserts fixed behaviour rather than
pinning the unsafe behaviour it exists to catch.

## Critical Implementation Details

**`jsonPath` cannot distinguish an absent key from an explicit `null`.** Spring's
`jsonPath(...).doesNotExist()` and `.value(nullValue())` both pass for a JSON `null`, so
neither can prove that `"damageRecords":null` is on the wire rather than omitted. The
present-and-null assertion in Phase 2 must read the raw body via
`andReturn().getResponse().getContentAsString()` and assert it contains
`"damageRecords":null`. This is the assertion that would fail if someone added a global
`JsonInclude.NON_NULL`, which would silently collapse "unknown" into "clean" without
touching a line of CEPiK code — so it is worth the awkwardness, and needs a comment saying
why it is not a `jsonPath`.

**`MockRestServiceServer` expectations are ordered by default**, which is a feature here:
the order pins that vehicle-data is fetched before timeline-data, and therefore that the two
payload arguments reach `parser.parse` the right way round (`HistoriaPojazduService.java:46`).
Swapping them today keeps the entire repo green.

**The stub must respond to `GET <apiBase>/close`.** `HistoriaPojazduService.lookup` closes
the session in a `finally` block, so an unexpected-request failure there will surface as a
confusing `AssertionError` after the assertions have already passed.

**The derived drift fixture changes values, not keys.** The canary keys off event *type
values*, so simulating vocabulary drift means editing `eventType` values — no key is renamed
or introduced, and the derived-fixture rule holds without an exception.

## Phase 1: Close the false-clean routes in production

### Overview

Two guards in `HistoriaPojazduParser` and one normalisation fix in
`RealCepikEnrichmentService`. Establishes the `-derived` fixture convention, and replaces
the assertion that currently ratifies Route A with one that asserts the guardrail.

### Changes Required:

#### 1. Route A — nothing readable is not a found vehicle

**File**: `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParser.java`

**Intent**: When neither payload yielded a readable map, the registry answered but we could
not read it. Report that instead of describing the vehicle as found with every field empty.

**Contract**: In `parse`, after `basic` and `timeline` are resolved (`:52-53`), return
`CepikResult.withoutData(CepikStatus.LOOKUP_FAILED, vin, LOOKUP_URL)` when **both** are
null. `LOOKUP_FAILED` rather than `NOT_FOUND`: a definitive "no such vehicle" arrives as a
404 carrying `HIPO-0002` and is already classified by `HistoriaPojazduService`. One readable
payload and one absent stays `FOUND` — that is the existing, correct behaviour asserted by
`missingTimelineYieldsNullListsNotEmptyOnes`. Log at `warn` with the vin, since silently
degrading is how the original bug hid.

#### 2. Route B — an unrecognised vocabulary is unknown, not clean

**File**: `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParser.java`

**Intent**: If the registry renames or restructures its event types, every `startsWith` /
`equals` check in this class silently stops matching and `damagesFrom` returns `[]`, which
the UI renders as a positive clean claim. Detect that the vocabulary has moved and report
unknown instead.

**Contract**: Add a `KNOWN_EVENT_TYPES` set holding the ten types observed in
`timeline-data-found.json` (listed under Key Discoveries) — a record of what was captured,
not a new mapping, so the "no field name without a capture" rule is respected. In `parse`,
when `events` is non-null but no event carries a type in that set, set both
`damageRecords` and `mileageStamps` to `null` and log at `warn`. An empty `events` list
takes the same branch: no registered vehicle has a timeline with zero events, and treating
that as unknown rather than clean is the direction the guardrail requires. Matching stays
case-sensitive on the exact value, consistent with `:123`.

The existing `timelineWithoutDamageEventsYieldsEmptyListNotNull` case still yields `[]`
because its single `pierwsza-rejestracja-w-polsce` event is recognised — and it becomes a
meaningful test rather than a tautological one, because `[]` now genuinely means "we
recognised the vocabulary and found no damage in it".

#### 3. Plate normalisation, matched to the VIN path

**File**: `backend/src/main/java/com/example/autoskaner_ai/cepik/RealCepikEnrichmentService.java`

**Intent**: A user typing the plate the way it is printed — `WA 12345` — currently gets
`MISSING_INPUTS` and no lookup at all, while the same user typing a spaced VIN is fine.

**Contract**: Add a private `normalisePlate(String)` returning `Optional<String>`, mirroring
`VinValidator.normalise`'s contract: upper-case, strip `[\s\-]` including internal, then
match `PLATE_PATTERN`. Replace the inline check at `:78-81` and pass the normalised value to
`historiaPojazduService.lookup` — the registry's `registrationNumber` field must receive the
cleaned form, not the spaced one. `PLATE_PATTERN` itself is unchanged.

#### 4. Derived-fixture convention

**File**: `backend/src/test/resources/cepik/README.md` (new)

**Intent**: The capture policy currently lives only in prose in `CLAUDE.md` and a test
Javadoc, and it already drifted once — `timelineWithoutDamageEventsYieldsEmptyListNotNull`
composes its input from the parser's own expected key names. Make provenance visible at the
directory.

**Contract**: Two fixture classes. **Verbatim** (`*-found.json`, no suffix) — byte copies of
real responses, only documented redaction of identifying values permitted. **Derived**
(`*-derived.json`) — produced from a named verbatim parent by *deleting a node* or *changing
a value*, never by introducing or renaming a key, with a `_provenance` field naming the
parent and the exact edit. State that a fixture composed key-by-key from the parser's
expectations is prohibited, and why: that is the 2026-08-26 failure in miniature.

#### 5. Derived fixtures this phase needs

**Files**: `backend/src/test/resources/cepik/`

**Intent**: Give the two new guards and the currently-uncaptured cases inputs whose keys all
come from real bytes.

**Contract**: Four files, each with `_provenance`:
- `timeline-data-clean-derived.json` — the szkoda istotna event deleted from
  `timeline-data-found.json`. Drives the genuinely-clean `[]` case.
- `timeline-data-drifted-derived.json` — every `eventType` **value** changed to an
  unrecognised form (e.g. `szkoda-istotna` → `szkoda-istotna-v2`). Drives the canary.
- `timeline-data-rolled-back-derived.json` — `rolledBack` flipped to `true` in
  `odometerReadings`. First captured-shape input for the rollback cap.
- `vehicle-data-lost-derived.json` — `vehicleLost` flipped to `true`. Same for the theft cap.

#### 6. Parser tests for the two guards, and the tautology removed

**File**: `backend/src/test/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParserTest.java`

**Intent**: Assert the new honest behaviour, and stop asserting the old unsafe behaviour as
if it were the requirement.

**Contract**: Rewrite `nullPayloadsDoNotThrowAndCarryNoData` — its
`assertThat(result.status()).isEqualTo(FOUND)` is lifted straight from the hardcoded `:63`
and ratifies Route A; it now asserts `LOOKUP_FAILED` with null lists. Rebuild
`timelineWithoutDamageEventsYieldsEmptyListNotNull` on `timeline-data-clean-derived.json`
instead of its composed map. Add: a drifted timeline yields `null` damages **and** null
mileage, not `[]`; an empty `events` list yields `null`; a single readable payload still
yields `FOUND` (guarding that the Route A fix did not widen). Each new test names the rule
or incident it defends, per the file's existing convention.

#### 7. Plate normalisation tests

**File**: `backend/src/test/java/com/example/autoskaner_ai/cepik/RealCepikEnrichmentServiceTest.java`

**Intent**: Prove the spaced plate reaches the registry cleaned, and that the existing
short-circuits still short-circuit.

**Contract**: Parameterised: `WA 12345`, `wa12345`, ` WA-12345 ` all reach
`lookup("WA12345", …)`. `malformedPlateShortCircuits` stays; add a case proving whitespace
stripping did not make a genuinely malformed plate acceptable (e.g. `W A 1` must still be
`MISSING_INPUTS`).

### Success Criteria:

#### Automated Verification:

- Suite green with no regressions: `cd backend && JAVA_HOME="D:/Software/Java/jdk-26.0.1" MAVEN_OPTS="-Xmx1g" ./mvnw -o test`
- `HistoriaPojazduParserTest` and `RealCepikEnrichmentServiceTest` pass with the new cases
- `git diff --stat src/main` shows changes only in `HistoriaPojazduParser.java` and `RealCepikEnrichmentService.java`
- Every file in `src/test/resources/cepik/` is either unsuffixed-verbatim or `-derived` carrying a `_provenance` field

#### Manual Verification:

- Each derived fixture differs from its named parent only by deleted nodes or changed values — diff it against the parent and confirm no key was added or renamed
- The canary's known-type list matches the ten types actually present in `timeline-data-found.json`, with no invented entry
- A `mock`-profile run of the app still starts and returns an analysis (the parser is `@Profile("!mock")`, so this only confirms nothing else broke)

**Implementation Note**: After completing this phase and all automated verification passes,
pause here for manual confirmation from the human before proceeding.

---

## Phase 2: Captures → HTTP response, and the wire contract

### Overview

The test that spans the gap nothing currently covers: committed capture bytes, through the
real HTTP edge, session, parser and enrichment service, into an `AnalysisResponse` body
carrying both the damage record and the downgraded verdict. Plus the three-way
`damageRecords` wire contract and the session's version-discovery branch.

### Changes Required:

#### 1. The payload → verdict integration test

**File**: `backend/src/test/java/com/example/autoskaner_ai/cepik/CepikDamageReachesTheResponseTest.java` (new)

**Intent**: Prove Risk #2 and Risk #3 as one journey. Nothing in the repo joins the parser
(which can read a capture) to the controller (which can serialise a hand-built
`CepikResult`), and that missing join is where a swapped argument, a renamed request key, or
a rotted API version hides behind a green suite.

**Contract**: Mock only at the network edge, per §6.2's binding policy. Build a
`RestClient.Builder` with `baseUrl("https://moj.gov.pl")`, bind a `MockRestServiceServer` to
it, and construct the real chain: `HistoriaPojazduSession` (via
`HistoriaPojazduService(builder, new HistoriaPojazduParser())`) → `RealCepikEnrichmentService`
→ `AnalysisController` through `MockMvcBuilders.standaloneSetup`, matching the existing
`AnalysisControllerTest` idiom. `AiAnalysisService` is a `mock(...)` returning a fixed
`AnalysisResult` that claims `bezwypadkowy`; `ListingFetchService` and
`MarketPriceEnrichmentService` are mocks.

Five ordered expectations as listed under Key Discoveries. The bootstrap-HTML response must
name a version *other than* `FALLBACK_API_VERSION` so the assertion proves discovery rather
than coincidence, and must carry `Set-Cookie: XSRF-TOKEN=…`. The two data expectations
assert `X-Xsrf-Token` and the `registrationNumber` / `VINNumber` / `firstRegistrationDate`
body keys, and respond with the verbatim captures.

Assertions on one response, all four oracles:
- `$.cepikResult.damageRecords[0].date` = `2023-02-07`, `.insurer` = `PZU`,
  `.categories[0]` = `Uszkodzenie elementów układu nośnego` — capture bytes
- `$.cepikResult.make` = `TOYOTA` — capture bytes, and the assertion that fails if the two
  payload arguments are swapped
- `$.analysis.riskFlags[0].code` = `CEPIK_SIGNIFICANT_DAMAGE`,
  `[1].code` = `CEPIK_CONTRADICTS_LISTING` — the registry-flags-first rule
- `$.analysis.verdict.code` = `HIGH_RISK_SKIP` — the production incident: this response was
  `WORTH_CHECKING`
- `$.analysis.scores.risk` strictly below the LLM's input risk, and `$.analysis.scores.overall`
  likewise — `overall` is what the UI leads with and is unasserted at the API layer today
- `server.verify()`

#### 2. The three-way `damageRecords` wire contract

**File**: same as above

**Intent**: The frontend renders `null` as unknown, `[]` as "registry reported nothing", and
a populated list as findings. That distinction survives only because `src/main` has no
Jackson customisation, and nothing anywhere protects it.

**Contract**: Three cases through the real chain.
- **Populated** — covered by the test above.
- **Explicit null** — serve empty 200s for both data calls so Phase 1's Route A guard yields
  `LOOKUP_FAILED`; assert `$.cepikResult.status` = `LOOKUP_FAILED` and assert the **raw body
  string** contains `"damageRecords":null` (see Critical Implementation Details for why not
  `jsonPath`). Also asserts the new guard end to end.
- **Empty** — serve `timeline-data-clean-derived.json`; assert
  `$.cepikResult.damageRecords` is an empty array, and that `$.analysis.verdict.code` is
  untouched, since a clean registry report must move nothing.

#### 3. `NOT_FOUND` driven by a real 404 body

**Files**: `backend/src/test/resources/cepik/not-found-hipo-0002.json` (new), same test class

**Intent**: `indicatesVehicleNotFound` string-sniffs the cause chain for `HIPO-0002`, but
`HistoriaPojazduServiceTest` writes that string itself using the same literal as
`NOT_FOUND_CODE`. The real coupling — that a 404 wrapped by `HistoriaPojazduSession`
actually carries the code where the service can find it — is asserted only in the live test.

**Contract**: Commit the observed 404 body (`VALIDATION_ERROR_MSG` /
`VALIDATION_ERROR_CODE`) as a fixture, respond to the vehicle-data expectation with
`withStatus(NOT_FOUND).body(...)`, and assert `$.cepikResult.status` = `NOT_FOUND` with
`damageRecords` explicitly null. This is the first test in which the wrapping is real rather
than authored.

#### 4. Session API-version discovery vs fallback

**File**: `backend/src/test/java/com/example/autoskaner_ai/cepik/HistoriaPojazduSessionTest.java` (new)

**Intent**: `FALLBACK_API_VERSION` is the successor to the literal that rotted from `1.0.17`
to `1.1.0` and broke production silently. The class has no test at all.

**Contract**: Two tests. Bootstrap HTML naming a version → the data call goes to that
version's path. Bootstrap HTML *not* naming one → the call goes to `FALLBACK_API_VERSION`'s
path, which is the branch no test has ever executed. Cookie merge, XSRF extraction and body
keys are covered incidentally by the integration test and are otherwise §3 Phase 2's scope.

#### 5. Un-mock the service's success path

**File**: `backend/src/test/java/com/example/autoskaner_ai/cepik/HistoriaPojazduServiceTest.java`

**Intent**: All five existing tests drive the session to throw, so `lookup`'s success path
never runs and `verify(session).close()` on success is unasserted.

**Contract**: Add one test on the existing `createSession()` seam with a real
`HistoriaPojazduParser` and stubbed session returns, asserting `FOUND` with the damage
present and `verify(session).close()`. The five failure-classification tests keep their
mocked parser — that is the right seam for those. Note in a comment that argument-order
coverage lives in the integration test, since a mocked session cannot catch a swap.

### Success Criteria:

#### Automated Verification:

- Suite green: `cd backend && JAVA_HOME="D:/Software/Java/jdk-26.0.1" MAVEN_OPTS="-Xmx1g" ./mvnw -o test`
- `CepikDamageReachesTheResponseTest`, `HistoriaPojazduSessionTest` and the extended `HistoriaPojazduServiceTest` pass
- Mutation check — swap the two payload arguments at `HistoriaPojazduService.java:46`, confirm the integration test **fails**, revert
- Mutation check — change `DAMAGE_EVENT_TYPE` to a wrong literal, confirm the drifted-vocabulary and integration tests **fail** rather than reporting `[]`, revert
- Mutation check — set `spring.jackson.default-property-inclusion=non_null`, confirm the present-and-null assertion **fails**, revert

#### Manual Verification:

- The stubbed bootstrap HTML names a version other than `1.1.0`, so version discovery is genuinely proven
- The `not-found-hipo-0002.json` body matches what the registry actually returned, not a re-typing of `NOT_FOUND_CODE`
- Read the integration test as a stranger: it should be obvious which assertion each of the four oracles grounds

**Implementation Note**: After completing this phase and all automated verification passes,
pause here for manual confirmation from the human before proceeding.

---

## Phase 3: Re-oracle the risk adjuster

### Overview

Risk #3 is the best-covered behaviour in the codebase and has the weakest oracles. Remove
the last place the suite computes an expected value with the code under test, reach the
never-raise guard, express the caps' severity ordering as behaviour, and add the missing
interaction cases. Independent of Phases 1–2.

### Changes Required:

#### 1. Stop re-deriving production's mean-of-four

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/CepikRiskAdjusterTest.java`

**Intent**: The fixture at `:28` copies `CepikRiskAdjuster.java:138`'s formula, so a test
that should detect the formula changing cannot, and the never-raise guard is unreachable.

**Contract**: `analysis(...)` takes `overall` from a hand-computed lookup rather than
computing it. Keep the existing three-argument signature so the 14 call sites are untouched,
and add a four-argument overload taking an explicit `overall` for the never-raise case. The
`default -> throw` matters: it forces the next author to do the arithmetic rather than
reaching for the formula.

```java
// Hand-computed, not derived. The class under test owns the mean-of-four rule; a fixture
// that re-implements it cannot detect that rule changing — which is why the never-raise
// guard at CepikRiskAdjuster:141 was unreachable by all 14 tests before this.
private static int overallFor(int risk) {
    return switch (risk) {
        case 88 -> 78;   // (90 + 75 + 88 + 60) / 4
        case 60 -> 71;   // (90 + 75 + 60 + 60) / 4
        case 10 -> 58;   // (90 + 75 + 10 + 60) / 4
        default -> throw new IllegalArgumentException(
                "compute the overall for risk " + risk + " by hand — do not copy the formula");
    };
}
```

#### 2. Reach the never-raise guard

**File**: same

**Intent**: `Math.min(scores.overall(), overall)` at `:141` fires exactly when the model
returns an `overall` inconsistent with its own four categories — which is what LLM output
does, and is the only shape no existing test supplies.

**Contract**: A model result with `risk: 88` and an explicitly low `overall` (e.g. `40`),
plus a registry damage. Risk caps to 35; the recomputed mean is 65; `overall` must remain
40. Assert `overall` is 40 with an `.as(...)` note that the model's own lower judgement
stands. Both expected values are hand arithmetic.

#### 3. The cap ordering as behaviour, with one pinned copy of the integers

**File**: same

**Intent**: The magnitudes have no oracle outside the implementation, but the *severity
ordering* they encode does — it follows from the product's stated reasoning. Separate what
the suite proves from what it merely freezes, and end the three-copies-of-`25` problem.

**Contract**: One test runs the adjuster five times with a single registry fact each and
asserts the resulting risk values are **strictly increasing** in the documented severity
order: theft < rollback < contradicted claim < damage < no OC policy. No magnitude appears.
One separate test names the five integers, with a comment stating plainly that it is
change-detection only and that a cap wrong by design is not falsifiable by it — that comment
is the honest part. Remove the duplicate `25` from the other tests in this class; the copy in
`AnalysisControllerTest:190` is superseded by Phase 2's relational assertion and becomes a
relative one.

Also assert the two documented asymmetries as behaviour: damage alone floors at
`NEEDS_MORE_INFO` and not `HIGH_RISK_SKIP` (a properly repaired damage can be a fair
purchase), and a missing OC policy moves no verdict at all. Both follow from the product
guardrail, not from reading the code.

#### 4. Cap interaction

**File**: same

**Intent**: Every multi-fact test today sets one flag; only damage and contradiction
co-occur. The harsher-fact-wins rule is unverified for independent findings.

**Contract**: Two co-occurrence tests asserting the harsher fact wins without naming a
magnitude — theft plus damage lands where theft alone lands, rollback plus no-OC lands where
rollback alone lands — and one asserting that several facts accumulate their flags in the
response rather than the harshest suppressing the rest. The rollback and theft inputs can
now come from the Phase 1 derived fixtures driven through the parser, rather than a hand-set
`Boolean.TRUE`, in a companion test in the `cepik` package.

### Success Criteria:

#### Automated Verification:

- Suite green: `cd backend && JAVA_HOME="D:/Software/Java/jdk-26.0.1" MAVEN_OPTS="-Xmx1g" ./mvnw -o test`
- `grep -c` for the mean-of-four expression in `CepikRiskAdjusterTest` returns 0
- Each of the five cap literals appears exactly once across the whole test tree
- Mutation check — change `CAP_SIGNIFICANT_DAMAGE` from 35 to 15, confirm the ordering test **fails** (it now crosses `CAP_CONTRADICTED_CLAIM`), revert
- Mutation check — delete the `Math.min` at `CepikRiskAdjuster.java:141`, confirm the new never-raise test **fails**, revert

#### Manual Verification:

- Read the pinned-integers test: its comment should leave no reader thinking the values are proven
- Confirm the ordering test would still pass if all five caps were shifted by a constant — that is the point, and it should be a conscious property rather than an accident

**Implementation Note**: After completing this phase and all automated verification passes,
pause here for manual confirmation from the human before proceeding.

---

## Phase 4: Backport the corrections to `test-plan.md`

### Overview

`test-plan.md` opens with "Read before writing any new test" and currently carries claims
this phase falsifies. Correct them and fill in the sections whose completion is Phase 1's
job by the plan's own design.

### Changes Required:

#### 1. Correct the stack and cookbook claims

**File**: `context/foundation/test-plan.md`

**Intent**: §4's HTTP-mocking row says "none yet — see §3 Phase 2" and §6.2 says the
stubbing seam "does not exist yet". `MockRestServiceServer` ships with `spring-test:7.0.7`,
three test classes already use it, and the CEPiK edge is now the fourth.

**Contract**: Rewrite the §4 row to name `MockRestServiceServer` with its version and the
`bindTo(RestClient.Builder)` idiom. Replace §6.2's TBD with the real pattern: bind to the
builder, expect the ordered call sequence, respond with verbatim captures, `server.verify()`
— and the two gotchas (`close()` in a `finally` block; `jsonPath` cannot distinguish absent
from null). The accurate statement is not "no seam" but "the seam existed and the CEPiK edge
was the one place it was never used."

#### 2. Re-point Risk #2's guidance at the residual seam

**File**: same

**Intent**: Risk #2's Must-challenge and Anti-pattern columns describe the invented-field-names
failure that commit `8870d35` closed. A reader following them today defends a closed door.

**Contract**: Keep the "What would prove protection" column — it is still exactly right.
Change Must-challenge to the empty-list inference ("an empty damage list means the registry
reported nothing") and the Anti-pattern to pinning a false-clean shape as expected
behaviour, noting that fixtures composed to match the parser remain prohibited by §6.5.
Leave the Source column alone: it cites evidence, not anchors.

#### 3. Fill §6.5 and §6.7, flip §3

**File**: same

**Intent**: §6.5 reads "TBD — see §3 Phase 1" and this is that phase.

**Contract**: §6.5 gets the verbatim/derived fixture split and the `_provenance` rule from
Phase 1's `README.md`, the null-vs-empty-vs-populated invariant with the vocabulary canary
as its enforcement point, and the four-oracle list with the note that `CLAUDE.md` is the
specification of record and not an independent derivation. §6.7 records the phase's outcome,
including that the two production routes were closed rather than pinned. §3's Phase 1 row
moves to shipped with the change folder named. Correct §3's ordering rationale: Phase 2 no
longer follows Phase 1 because a seam is missing — it follows because the CEPiK edge was the
highest-risk consumer of that seam and is now done. Bump §8's dates.

#### 4. Record the deferred findings

**File**: same

**Intent**: Phase 1 found two frontend rendering gaps and left cookie/XSRF coverage open.
Losing them between phases is how they become the next incident.

**Contract**: Under §3 Phase 3, note that `cepik-result.component` has no spec, that
`cepikResult === null` renders nothing at all (no heading, no disclaimer), and that
`mileageStamps === []` renders no row — all three are Phase 3 inputs. Under §3 Phase 2, note
that `HistoriaPojazduSession`'s cookie merge/dedupe and XSRF extraction remain uncovered.
Also record that the frontend-only registry-vs-listing mileage tolerance
(`max(2000 km, 5%)`, registry-higher only) has no backend counterpart and does not feed the
score, per `CLAUDE.md`.

#### 5. Update the change identity

**File**: `context/changes/testing-enrichment-honesty/change.md`

**Contract**: `status: complete`, `updated: <the day it lands>`. The roadmap carries no item
with this Change ID, so no roadmap edit applies.

### Success Criteria:

#### Automated Verification:

- No occurrence of "none yet" or "does not exist yet" remains in §4 or §6.2 of `test-plan.md`
- No "TBD" remains in §6.2 or §6.5
- `git diff` for this phase touches only `context/`

#### Manual Verification:

- Read §6.2 and §6.5 as someone about to write a registry test: they should be sufficient without opening this plan
- Risk #2's row describes a failure mode that is actually still open
- §3's ordering rationale no longer rests on a claim known to be false

---

## Testing Strategy

### Unit Tests:

- **Parser guards** — both payloads unreadable → `LOOKUP_FAILED`; drifted vocabulary → null
  damages *and* null mileage; empty `events` → null; one readable payload → still `FOUND`;
  recognised vocabulary with no damage → `[]`.
- **Plate normalisation** — spaced, lower-case and hyphenated plates reach the registry
  cleaned; a genuinely malformed plate still short-circuits with no outbound call.
- **Adjuster oracles** — the five caps strictly increasing in severity order; both
  asymmetries; never-raise reached via an inconsistent input `overall`; harsher-fact-wins on
  two co-occurrence pairs; flag accumulation; the five integers pinned once as declared
  change-detection.
- **Session** — API version discovered from the bootstrap HTML, and the fallback branch.

### Integration Tests:

- Committed captures → stubbed HTTP edge → real session, parser and enrichment service →
  `AnalysisController` → response body carrying the damage record, the registry flags first,
  a capped risk, a reduced `overall` and `HIGH_RISK_SKIP`.
- The same chain in three more shapes: empty payloads → `LOOKUP_FAILED` with
  `"damageRecords":null` on the wire; a real 404 body → `NOT_FOUND`; the clean derived
  timeline → `[]` with the verdict untouched.

### Manual Testing Steps:

1. Run the suite and record the new test count against the 132 baseline.
2. Run each mutation check listed in the phases; every one must turn a test red. A mutation
   that leaves the suite green means the test does not defend what it claims to.
3. Diff each `-derived` fixture against its named parent; confirm only deletions and value
   changes, no key added or renamed.
4. With `SPRING_PROFILES_ACTIVE=mock`, start the backend and run one analysis through the UI
   to confirm nothing in the request path regressed. The `mock` profile cannot reach the
   damage path (`MockCepikService` never returns `FOUND`), so this is a smoke check only.
5. Read the two production diffs against the guardrail in `CLAUDE.md:7-8`: neither guard may
   make a *more* confident claim than before, only a less confident one.

## Performance Considerations

None. Every test added is in-process with a stubbed network edge; the two production guards
are a set membership check over at most a few dozen events and a null check, both on a path
that already makes three HTTP calls to the registry.

## Migration Notes

The parser change alters observable API behaviour in one direction: responses that were
`FOUND` with an empty damage list may now be `LOOKUP_FAILED`, or `FOUND` with
`damageRecords: null`. Both are statuses and shapes the frontend already handles — that is
what makes `LOOKUP_FAILED` the right reuse rather than a new status. The direction of change
is always toward less confidence, never more, so no consumer can be shown a stronger claim
than before.

## References

- Research: `context/changes/testing-enrichment-honesty/research.md`
- Charter: `context/changes/testing-enrichment-honesty/change.md`
- Test plan: `context/foundation/test-plan.md` §2 Risks #2/#3, §3 Phase 1, §6.2, §6.5
- Seam idiom to copy: `backend/src/test/java/com/example/autoskaner_ai/analysis/ListingFetchServiceTest.java:33`
- Standalone MockMvc idiom: `backend/src/test/java/com/example/autoskaner_ai/analysis/AnalysisControllerTest.java:48-53`
- The committed szkoda istotna: `backend/src/test/resources/cepik/timeline-data-found.json:48-65`
- Commit `8870d35` (the 2026-08-26 fix), `e616ea2` (last commit with fabricated fixtures), `5b7a3b3` (adjuster introduced after the 88/WORTH_CHECKING incident)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Close the false-clean routes in production

#### Automated

- [x] 1.1 Suite green with no regressions — 4a40ec9
- [x] 1.2 `HistoriaPojazduParserTest` and `RealCepikEnrichmentServiceTest` pass with the new cases — 4a40ec9
- [x] 1.3 `git diff --stat src/main` limited to the two intended files — 4a40ec9
- [x] 1.4 Every cepik fixture is verbatim-unsuffixed or `-derived` with `_provenance` — 4a40ec9

#### Manual

- [x] 1.5 Each derived fixture differs from its parent only by deletions and value changes — 4a40ec9
- [x] 1.6 The canary's known-type list matches the ten types in the capture, with no invented entry — 4a40ec9
- [x] 1.7 A `mock`-profile run still starts and returns an analysis — 4a40ec9

### Phase 2: Captures → HTTP response, and the wire contract

#### Automated

- [x] 2.1 Suite green — 8df36f9
- [x] 2.2 `CepikDamageReachesTheResponseTest`, `HistoriaPojazduSessionTest`, extended `HistoriaPojazduServiceTest` pass — 8df36f9
- [x] 2.3 Mutation check: swapped payload arguments fail the integration test — 8df36f9
- [x] 2.4 Mutation check: a wrong `DAMAGE_EVENT_TYPE` fails rather than reporting `[]` — 8df36f9
- [x] 2.5 Mutation check: global `non_null` inclusion fails the present-and-null assertion — 8df36f9

#### Manual

- [x] 2.6 The stubbed bootstrap HTML names a version other than the fallback — 8df36f9
- [x] 2.7 The `HIPO-0002` fixture body matches what the registry actually returned — 8df36f9
- [x] 2.8 Each assertion's oracle is obvious to a stranger reading the test — 8df36f9

### Phase 3: Re-oracle the risk adjuster

#### Automated

- [x] 3.1 Suite green — ed60e8a
- [x] 3.2 The mean-of-four expression no longer appears in `CepikRiskAdjusterTest` — ed60e8a
- [x] 3.3 Each cap literal appears exactly once across the test tree — ed60e8a
- [x] 3.4 Mutation check: `CAP_SIGNIFICANT_DAMAGE` 35→15 fails the ordering test — ed60e8a
- [x] 3.5 Mutation check: deleting the `Math.min` fails the new never-raise test — ed60e8a

#### Manual

- [x] 3.6 The pinned-integers test's comment leaves no reader thinking the values are proven — ed60e8a
- [x] 3.7 The ordering test would survive shifting all five caps by a constant, deliberately — ed60e8a

### Phase 4: Backport the corrections to `test-plan.md`

#### Automated

- [x] 4.1 No "none yet" / "does not exist yet" remains in §4 or §6.2
- [x] 4.2 No "TBD" remains in §6.2 or §6.5
- [x] 4.3 `git diff` for this phase touches only `context/`

#### Manual

- [x] 4.4 §6.2 and §6.5 are sufficient without opening this plan
- [x] 4.5 Risk #2's row describes a failure mode that is still open
- [x] 4.6 §3's ordering rationale no longer rests on a false claim
