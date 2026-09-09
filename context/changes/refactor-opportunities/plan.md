# Refactor opportunity #1: bind the mock beans to the real rule with contract tests — Implementation Plan

## Overview

`research.md` ranks one opportunity first: the three `@Profile("mock")` beans are bound to nothing.
No test names any of them, so the profile that every quality gate and both E2E specs actually run is
the one implementation of each port that cannot fail. One of them has already drifted far enough to
invert the repo's hardest business rule.

This plan writes one parameterised contract test per port over **both** the mock and the real
implementation, asserting the properties that must hold of *any* implementation — chiefly that
absence of accident data never renders as absence of accidents — and fixes the two mocks the
contract catches. Two ports are in scope: `cepik` and the LLM. `market` is deliberately excluded.

Scope is the ranked opportunity **#1 only**. #2 (`C12`/`C2`) and #3 (`C4`) are not planned here.

## Current State Analysis

**No contract-test scaffolding exists.** Across all of `backend/src/test`: zero `abstract class`,
zero `class … extends`, zero `@Nested`. `@ParameterizedTest` exists 4×, all of them in
`cepik/RealCepikEnrichmentServiceTest.java:58,84,100,113` — the test class for the real bean of the
very port this plan binds first. No new tooling, no new dependency.

**`MockCepikService` (20 lines) has zero branches.** It returns
`CepikResult.withoutData(LOOKUP_FAILED, extracted.vin(), LOOKUP_URL)` unconditionally, whatever the
inputs. The real bean's contract is different in two ways that matter: absent or malformed inputs are
`MISSING_INPUTS`, not `LOOKUP_FAILED`, and a well-formed triple produces a `FOUND` result with a real
timeline. Under `mock`, `CepikRiskAdjuster` therefore never receives a `FOUND` result at all.

**`MockAiAnalysisService` (204 lines) contradicts the real program on the accident rule.** It sets
`accidentClaim` from two keywords (`:108-113`) and then decides the
`NO_ACCIDENT_DECLARATION` flag from a *third*, unrelated condition (`:147-153`):

```
!lower.contains("wypadek") && !lower.contains("bezwypadkowy") && !lower.contains("historia")
```

The real program derives the same flag from `accidentClaim` alone, in
`AnalysisResponseParser.withAccidentDeclarationFlag` (`:169-183`), and at `MEDIUM`. The mock emits
`HIGH`.

**The market port is a different situation and is excluded.** `MockMarketPriceEnrichmentService`
already returns a realistic `OK` (45k/55k/70k, sample 12, `SUFFICIENT`) — Era 1 established that
pattern here and never propagated it to `cepik`. It also *never* returns a degraded status, so a
"non-`OK` ⇒ null statistics" property is satisfied vacuously by the mock: cost with no finding behind
it.

### Key Discoveries

- **The `"bezwypadkowy"` half of the report's claim is wrong, and the corrected version is
  sharper.** Suppressing the flag on `"bezwypadkowy"` is *correct* — that branch also sets
  `accidentClaim` non-null, so the real parser would not append either. Solving
  `¬bezwypadkowy ∧ ¬wypadek ∧ ¬kolizja ∧ historia` leaves **exactly one** violating input class: a
  text containing `"historia"` and no accident keyword. `"Pełna historia serwisowa"` is Polish advert
  boilerplate. The mock's guard is right on two keywords by coincidence and wrong on the third.
- **The over-flagging direction is not a contract violation.** `withAccidentDeclarationFlag`
  *appends and never removes*, so a mock that emits the flag on a text mentioning `"kolizja"` — where
  `accidentClaim` is non-null — breaks no port-level property. Only the missing-flag direction and
  the severity do.
- **`CepikRiskAdjuster` is not untested, contrary to how the report's "never executed end-to-end"
  reads.** It has 25 unit tests plus `CepikDamageReachesTheResponseTest`, which drives captured
  registry bytes through the real service and asserts damage → payload → score → verdict in 4 cases.
  What is missing is that the **`mock` profile** — the only profile the gates and the E2E specs run —
  never reaches it. That is the gap this plan closes; "dead code" is not.
- **Three of the LLM port's real-side assertions already exist.**
  `ListingClaimsCannotMoveTheFloorTest` pins the parser's append rule directly
  (`aModelThatOmitsTheMandatoryDeclarationFlagHasItAddedBack`,
  `aModelThatAlreadyEmittedTheFlagIsLeftExactlyAsItWas`,
  `aStatedAccidentClaimDoesNotGetTheMissingDeclarationFlag`). The new value of the LLM contract test
  is therefore entirely in binding the **mock** to the rule the real side already has three tests
  for — not in covering new real-side behaviour.
- **Both cepik beans are constructible without Spring**, so the contract test needs no context:
  `new MockCepikService()` and `new RealCepikEnrichmentService(mock(HistoriaPojazduService.class))`.
- **Neither E2E listing text carries a VIN.** `frontend/e2e/seed.spec.ts:21` and
  `market-price-contract.spec.ts:44` both use the same Toyota Corolla text with no VIN, no plate and
  no date. So a mock that validates its inputs before returning `FOUND` keeps both specs on the
  `MISSING_INPUTS` path they render today — the rewrite is E2E-safe *because* the mock gains a guard,
  not despite it.
- **The real-side LLM driver is already committed.**
  `backend/src/test/resources/fixtures/llm/hollow-all-leaves-null.json` has
  `"accidentClaim": null` (`:14`) and `"riskFlags": []` (`:21`) — exactly the input the property
  needs, with no socket and no new fixture.
- **Two properties a reader would expect are false by design.**
  `RealCepikEnrichmentServiceTest.invalidVinShortCircuitsWithNullVin` asserts `result.vin()` is
  `null` for `"NOT-A-VIN"`, so "the result echoes the requested VIN" cannot be a contract property.
  And `CepikResult.withoutData` stamps `Instant.now()` on every degraded path (`:66`), so
  `fetchedAt` non-null *is* one.

## Definitions

Only `user`- and `product`-origin definitions appear here. Anything the implementation alone asserted
was resolved by decision before this table was written.

| Term | Definition used by this plan | Origin |
|---|---|---|
| the mock beans | `MockCepikService` and `MockAiAnalysisService`. `MockMarketPriceEnrichmentService` is out of scope. | user |
| the LLM port's real counterpart | `AnalysisResponseParser` — not `OpenRouterAnalysisService` / `BedrockClaudeAnalysisService`. The accident rule lives in the parser, which both network beans call; a contract test against a network bean would test a socket. | user |
| a contract property | An assertion that must hold of **every** implementation of a port, expressed once and run against each implementation as a parameter. A property no implementation may violate by design — if a real bean violates a candidate, the candidate is narrowed, not the bean. | user |
| absence of accident data | `ExtractedData.accidentClaim == null`. The product rule: this must yield a `NO_ACCIDENT_DECLARATION` risk flag at `MEDIUM`. | product — `AnalysisPrompt.java:12-17`, `AnalysisResponseParser.java:169-183`, root `CLAUDE.md` § "Key business rules" |
| absence of damage data | A non-`FOUND` `CepikResult` carries `damageRecords` and `mileageStamps` as `null`, never `[]`. An empty list means "the timeline was read and held no damage event"; null means "unknown". | product — `CepikResult` javadoc `:6-18`, `backend/CLAUDE.md` § "Enrichment services" |
| a realistic `FOUND` result | What `MockCepikService` must return when all three inputs are well-formed: `status = FOUND` carrying one `szkoda-istotna` `DamageRecord` and one `MileageStamp`, so the mock profile exercises the shape the registry actually returns. | user |
| well-formed inputs | VIN accepted by `VinValidator.normalise`; plate matching `[A-Z]{2,3}[A-Z0-9]{4,5}` after trim / upper-case / strip of spaces and hyphens; first-registration date **non-blank**. The date axis is deliberately coarser than the real bean's — see "Critical Implementation Details". | user |

## Desired End State

- `CepikEnrichmentServiceContractTest` runs three port-level properties against both implementations
  of `CepikEnrichmentService`, and the real parameter additionally proves no outbound call is made
  for malformed inputs.
- `AiAnalysisServiceContractTest` runs the accident-declaration property against
  `MockAiAnalysisService` and a parser-backed adapter, so `"Pełna historia serwisowa"` can no longer
  lose the flag under `mock`, and the severity agrees with `AnalysisPrompt`.
- `MockCepikService` validates its three inputs and returns a realistic `FOUND` result when they are
  well-formed, `MISSING_INPUTS` with null lists when they are not. `CepikRiskAdjuster` is reachable
  under the `mock` profile, and one test proves it.
- Every documentation artifact that describes the mocks as unbound stubs says what is now true.
- Nothing in `src/main` moves except the two mock beans.

Verification: the backend suite is green with a higher test count than 235, each new contract test
has been observed **failing** for a recorded reason before it was made to pass, and both E2E specs
still pass under `mock`.

## What We're NOT Doing

- **No market-price contract test.** Its mock already returns a realistic `OK` and never returns a
  degraded status, so the natural property holds vacuously.
- **No changes to any real bean.** Where a real implementation violates a candidate property, the
  property is narrowed and the finding recorded — that is what keeps this work additive and
  revertible, which is why it ranked first.
- **No shared input validator extracted.** `MockCepikService` gets its own copy of the plate pattern
  (one line). The duplication is acceptable precisely because the contract test binds both copies to
  the same assertion; extracting a helper would move `src/main` and pull `C6`/`C15` into scope.
- **Unparseable-but-present dates stay out of the contract table.** The six accepted date formats,
  strict resolution, and the Polish prose forms remain `RealCepikEnrichmentServiceTest`'s business
  (its 4 existing `@ParameterizedTest`s). Duplicating that grammar into the mock would be a second
  copy of the one thing `backend/CLAUDE.md` explicitly says must not be duplicated.
- **No fix for `MarketPriceFetchService.missing()`'s null `fetchedAt`** (`C15b`), no `fetchStatus`
  enum, no dead-factory deletion. Different opportunities.
- **No new fixtures, no network, no Spring context** in any test this plan adds.
- **Opportunities #2 and #3, and the 11 unranked candidates** — not in scope.

## Implementation Approach

Four phases, following the incremental path `research.md` proposed, re-cut so that **no phase
requires committing a red suite**. The report's step (b) — "add the mock bean as the second parameter
and watch it fail" — is a deliberate break, and this repo's rule is that a deliberate break is never
committed. So in each of the first two phases the harness, the observed failure and the mock fix land
as **one commit**, with the failure recorded in the plan's Progress notes rather than in history.

1. **Phase 1 — the cepik port.** Build the harness against the real bean, add the mock as the second
   parameter, record the failures, rewrite the mock, commit green.
2. **Phase 2 — the LLM port.** Same mechanism. Two expected mock failures: the `"historia"` input
   class and the `HIGH`/`MEDIUM` severity mismatch.
3. **Phase 3 — reachability.** Prove at the boundary that a `FOUND` result reaches
   `CepikRiskAdjuster` and that a `FOUND` result with null `damageRecords` moves nothing.
4. **Phase 4 — documentation.** The artifacts that describe the old state, plus the suite counts the
   two git hooks print.

Phases 1 and 2 are independent of each other. Phase 3 depends on Phase 1's mock rewrite. Phase 4
depends on all three.

## Critical Implementation Details

**A `FOUND` result has no factory.** `CepikResult.withoutData` covers only degraded paths, and the
only code that builds a `FOUND` result is `HistoriaPojazduParser`, which is driven by test resources
and not callable from a mock bean. So `MockCepikService` must use the full 21-component
`CepikResult` constructor. Expect the file to grow from 20 lines to roughly 55. That is the honest
cost of this phase and it is not a reason to add a factory to `src/main` — a `FOUND` factory with 21
parameters is the constructor.

**Use the synthetic VIN.** This repository is public. `NMTBZ3BE40R000000` is the committed synthetic
value; use it in the mock and in the contract test. Do not introduce a real vehicle's VIN.

**The date axis is narrowed on purpose, and the narrowing is a finding.** The mock treats any
non-blank date as well-formed; the real bean parses six formats with strict resolution. The contract
therefore asserts the date axis only for `null` and blank. Record this in the test's own comment and
in `test-plan.md` §7 — a narrowed property with a written reason is a contract; a narrowed property
with no reason is a hole.

**The LLM contract's two parameters take different inputs, and the test must say so.** The mock
consumes a listing text; the parser consumes model JSON. The adapter is a lambda that ignores the
listing text and returns `parser.parse(hollow-all-leaves-null.json, …)`. That asymmetry is real and
must be stated in a comment rather than hidden behind a shared signature, because a reader who
assumes both parameters see the same input will draw a wrong conclusion from a future failure.

**Both suites must be run through the pinned toolchain.** A bare `./mvnw -o test` dies with
`Invalid maximum heap size: -Xmx12g` — the machine's `JAVA_HOME` is a 32-bit Java 8 JRE. Source the
hook helper first:

```bash
cd /d/projects/10xdevs/AutoSkanerAI && . .githooks/common.sh && cd backend && ./mvnw -o test
```

**Suite counts come from running the suites, never from grep.** `@Test` occurrences count 212 against
a real 235 because `@ParameterizedTest` expands, and the frontend `it(` count reads 64 against a real
51 because `submit(` contains `it(`. Every count written in Phase 4 must be read off a run.

**ASCII only in AssertJ `.as()` descriptions.** Polish listing text in fixtures and in `@ValueSource`
arguments is fine; the description strings are not.

**Contract-test naming deviates from `test-plan.md` §6.1 deliberately.** §6.1 says
`<ClassUnderTest>Test.java`; §6.2 says a test spanning several classes is named for the behaviour.
A port contract is neither: the class under test is the *interface*, and both implementations are
parameters. `<Interface>ContractTest.java` says that in the filename, and Phase 4 adds the rule to
the §6 cookbook so the third one does not invent a fourth convention.

---

## Phase 1: The cepik port contract, and a mock that can fail

### Overview

Bind `MockCepikService` and `RealCepikEnrichmentService` to the three properties that hold of any
`CepikEnrichmentService`, then rewrite the mock until it satisfies them.

### Changes Required

- **New `backend/src/test/java/com/example/autoskaner_ai/cepik/CepikEnrichmentServiceContractTest.java`**
  - **Intent** — express the port's degraded-path rules once, and run them against every
    implementation, so a second implementation cannot quietly mean something different by
    `MISSING_INPUTS` or by an empty list.
  - **Contract** — `@ParameterizedTest` + `@MethodSource` supplying two named implementations:
    `new MockCepikService()`, and `new RealCepikEnrichmentService(mock(HistoriaPojazduService.class))`.
    Three properties, each asserted for both parameters:
    1. Absent or malformed inputs — null VIN, a VIN failing `VinValidator`, null plate, a plate
       failing the pattern, null date, blank date — yield `status == MISSING_INPUTS`.
    2. Any non-`FOUND` result carries `damageRecords == null` **and** `mileageStamps == null`.
       Never `[]`.
    3. `fetchedAt` is non-null on every path, degraded ones included.
  - The real parameter additionally asserts `verifyNoInteractions(historiaPojazduService)` for the
    malformed-input cases, so "malformed inputs never reach the registry" is a statement about the
    outbound call and not only about the returned status.
  - Comments state the two narrowings: the date axis covers `null` and blank only (see Critical
    Implementation Details), and `result.vin()` is not asserted because
    `invalidVinShortCircuitsWithNullVin` makes echoing the VIN false by design.
- **Rewrite `backend/src/main/java/com/example/autoskaner_ai/cepik/MockCepikService.java`**
  - **Intent** — make the profile that every gate and both E2E specs run exercise the shape the
    registry actually returns, so `CepikRiskAdjuster` stops being unreachable under `mock` and the
    mock stops being the one implementation that cannot fail.
  - **Contract** — validate VIN via `VinValidator.normalise`, plate against its own copy of
    `[A-Z]{2,3}[A-Z0-9]{4,5}` (after trim / upper / strip spaces and hyphens), date as non-blank.
    Any failure → `CepikResult.withoutData(MISSING_INPUTS, normalisedVinOrNull, LOOKUP_URL)`,
    which preserves the null-lists rule by construction. All three well-formed →
    a `FOUND` result via the 21-component constructor, carrying one `DamageRecord` of
    `szkoda-istotna` with an insurer and a category, one `MileageStamp`, non-null `fetchedAt`, and
    the synthetic VIN. The class comment explains why the mock now duplicates a validation rule:
    the contract test binds both copies.
- **No production caller changes.** `AnalysisController` already handles every `CepikStatus`, and no
  existing test names a mock bean, so the rewrite has no test fallout to chase. Confirm rather than
  assume.

### Success Criteria

#### Automated Verification

- With the harness written and the mock unchanged, the mock parameter **fails** — record how many
  assertions and on which properties, in the plan's Progress notes. A contract test that is green on
  first run against an unmodified 20-line stub with zero branches has proved nothing.
- After the rewrite, `./mvnw -o test` is green and the backend count is above 235.
- `RealCepikEnrichmentServiceTest` still passes unchanged — the contract test adds properties, it
  does not replace that class's date-format coverage.

#### Manual Verification

- `cd frontend && npm run test:e2e` passes under `mock`. No gate runs Playwright, so this is the only
  place the E2E-safety argument (neither listing text has a VIN, so both stay on `MISSING_INPUTS`)
  gets checked.
- A local `POST /api/analyses` under `mock` with a well-formed VIN + plate + date returns
  `cepikResult.status == "FOUND"` with a non-empty `damageRecords`, and the same request without a
  VIN still returns `MISSING_INPUTS`.

---

## Phase 2: The LLM port contract, and the inverted accident rule

### Overview

Bind `MockAiAnalysisService` to the accident-declaration rule the real program enforces in
`AnalysisResponseParser`, and fix the two ways the mock disagrees.

### Changes Required

- **New `backend/src/test/java/com/example/autoskaner_ai/analysis/AiAnalysisServiceContractTest.java`**
  - **Intent** — express the repo's hardest business rule as a port property, so no implementation of
    `AiAnalysisService` can present absence of accident data as absence of accidents.
  - **Contract** — `@ParameterizedTest` + `@MethodSource` supplying two named implementations:
    `new MockAiAnalysisService()`, and an adapter over `new AnalysisResponseParser(objectMapper)` fed
    `fixtures/llm/hollow-all-leaves-null.json`. One property: when the returned
    `AnalysisResult.extractedData().accidentClaim()` is null, `riskFlags` contains a flag with code
    `NO_ACCIDENT_DECLARATION` at severity `MEDIUM`. Inputs for the mock parameter include the
    boilerplate class the report missed: a text containing `"Pełna historia serwisowa"` and no
    accident keyword.
  - A comment states that the two parameters consume different inputs (listing text vs model JSON)
    and why that is unavoidable for this port.
  - A comment states what is deliberately **not** asserted: the mock emitting the flag for a text
    mentioning `"kolizja"` is over-flagging, and `withAccidentDeclarationFlag` only ever appends, so
    an extra flag violates no port property.
- **Fix `backend/src/main/java/com/example/autoskaner_ai/analysis/MockAiAnalysisService.java`**
  - **Intent** — make the mock's flag decision follow from the same fact the real program uses, so
    the two cannot drift again on a keyword.
  - **Contract** — `buildRiskFlags` decides the `NO_ACCIDENT_DECLARATION` flag from
    `accidentClaim == null`, not from a third keyword scan, and emits it at `MEDIUM` to agree with
    `AnalysisPrompt.java:16` and `AnalysisResponseParser.java:180`. The keyword extraction at
    `:108-113` is unchanged — it is the mock's stand-in for the model and is not the defect.

### Success Criteria

#### Automated Verification

- Before the fix, the mock parameter fails on **two** counts — the missing flag for the `"historia"`
  input class, and `HIGH` where the contract says `MEDIUM`. Record both.
- Invert `AnalysisResponseParser`'s guard at `:170` once (`!= null` → `== null`), confirm the
  contract test's **real** parameter fails, then revert before anything is staged. This is the only
  proof that the real-side parameter is not vacuous — and it is worth doing even though
  `ListingClaimsCannotMoveTheFloorTest` already pins the same rule, because those three tests do not
  tell us the new parameter can see a break.
- `./mvnw -o test` green after the fix and the revert; count above Phase 1's.

#### Manual Verification

- None. This phase has no user-visible surface; the mock's severity change is invisible outside a
  `mock`-profile response body, which Phase 1's manual check already exercises.

---

## Phase 3: Prove the adjuster is reachable, and that null still moves nothing

### Overview

Phase 1 makes a `FOUND` result producible under `mock`. This phase asserts what happens when one
arrives, including the case the mock cannot itself produce: `FOUND` with null `damageRecords`.

### Changes Required

- **Extend `CepikDamageReachesTheResponseTest`** — the existing behaviour-named class that already
  drives the full stack behind a stubbed socket, rather than a new file.
  - **Intent** — close the one shape the four existing cases do not cover, and record that the mock
    profile now reaches the adjuster at all.
  - **Contract** — two new tests:
    1. A `FOUND` result whose `damageRecords` is null leaves `scores` and `verdict` exactly as the
       LLM produced them — the same null-is-not-empty rule as the port property, asserted at the
       response boundary. The nearest existing sibling is
       `anUnreadableRegistryAnswerPutsAnExplicitNullOnTheWire`, which covers a non-`FOUND` status,
       not this one.
    2. The `mock` profile's own `FOUND` result reaches `CepikRiskAdjuster` and caps the score —
       driven by `MockCepikService`'s output, so the assertion breaks if the mock ever stops
       producing a `FOUND` result. This is the test that makes Phase 1's rewrite load-bearing rather
       than incidental.

### Success Criteria

#### Automated Verification

- Both new tests fail if `CepikRiskAdjuster`'s `status != FOUND` guard (`:73`) is widened to admit
  null `damageRecords` — check by inverting it once and reverting.
- `./mvnw -o test` green; count above Phase 2's.

#### Manual Verification

- None.

---

## Phase 4: Documentation, and the counts the hooks print

### Overview

Four artifacts describe the mocks as unbound stubs, and two git hooks print suite sizes that this
change moves. All of them are now wrong.

### Changes Required

- **`context/foundation/test-plan.md`**
  - **Intent** — the file's own re-evaluation trigger has fired, and the freshness ledger should say
    so rather than leaving a reader to notice.
  - **Contract** — §7 bullet 1 currently reads that asserting the mocks' canned responses proves
    nothing, "re-evaluate if a mock ever encodes business logic rather than a fixture". Both mocks
    now do, so the bullet is rewritten to say what is exempt and what is not: a mock's *canned
    values* are still not worth asserting; a mock's *business rule* is asserted through the port's
    contract test. Add the two narrowings from this plan (the cepik date axis; `result.vin()`) as
    written-down holes. §6 gains the `<Interface>ContractTest.java` convention and the
    `@MethodSource`-over-implementations pattern. §8's ledger gets today's date and the new counts.
  - Risk-map rows #2/#3/#4 are this work's risks — mark their coverage rather than leaving them
    open.
- **`context/map/repo-map.md`**
  - **Intent** — the mock-profile risk-zone note is the orientation document's warning about exactly
    the gap this change closes.
  - **Contract** — the note says the mocks are bound by contract tests, names the two test classes,
    and keeps the residual warning that `market` is still unbound and why.
- **`backend/CLAUDE.md`**
  - **Intent** — the § "Enrichment services" description of the cepik mock is now false.
  - **Contract** — record that `MockCepikService` validates all three inputs and returns a realistic
    `FOUND` result with a `szkoda-istotna` on a well-formed triple, that both mocks are bound to port
    contract tests, and that `market`'s mock is deliberately not.
- **Root `CLAUDE.md`, `.githooks/pre-commit:38`, `.githooks/pre-push:28`**
  - **Intent** — a suite size that is printed to the developer on every commit has to be true, or it
    stops being a number anyone reads.
  - **Contract** — replace `235` with the measured count in all three places, read off a run and not
    from grep. Frontend's `51` is untouched by this change.

### Success Criteria

#### Automated Verification

- `./mvnw -o test` prints exactly the count written into the two hooks and the root `CLAUDE.md`.
- Both hooks run green on the real commit and push for this change.

#### Manual Verification

- Read `test-plan.md` §7 and confirm no sentence still claims the mocks are unasserted.
- Confirm the two narrowings are written down somewhere a future reader will find them, not only in a
  test comment.

---

## Testing Strategy

The change is almost entirely tests, so "how it is tested" is mostly "how the tests are proved to
work". Three rules, all from existing repo convention:

- **A new spec that passes on first run has proved nothing.** Each of the four new/extended test
  bodies is observed failing for a stated reason before it is made to pass, and the reason is
  recorded in Progress. Phases 1 and 2 get their failures for free (the mocks really are wrong);
  Phases 2 and 3 additionally invert a real-side guard once, and revert.
- **The deliberate break is never committed.** The report's "add the mock and watch it fail" step is
  not a commit boundary. Harness + observed failure + fix land as one green commit per phase.
- **No network, no Spring context, no new fixture.** Every parameter is directly constructible, and
  the one real-side driver is `fixtures/llm/hollow-all-leaves-null.json`, already committed.

Mutation testing is not part of this plan. PIT is a selective gate here, and the classes this change
touches are a mock bean and test code — the question PIT answers ("would a test notice if this line
were wrong") is the question the contract test itself is.

## References

- `context/changes/refactor-opportunities/research.md` — the exploration this plans against;
  opportunity #1 is `C9`.
- `context/changes/analysis-flow-analysis/research.md` §2.2 — the original observation that a
  business-rule inversion is reachable under `mock`.
- `context/changes/analysis-flow-analysis/verification.md` confirmation #1 — `MockCepikService` has
  no conditional of any kind.
- `context/foundation/test-plan.md` §2 (risk rows 2–4), §6 (unit-test conventions), §7 (what is
  deliberately not tested), §8 (freshness ledger).
- `backend/CLAUDE.md` § "Enrichment services", § "Folding registry findings into the score".
- Root `CLAUDE.md` § "Key business rules" — absence of accident data means unknown, not clean.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: The cepik port contract, and a mock that can fail

#### Automated

- [ ] 1.1 Write `CepikEnrichmentServiceContractTest` with the real bean as the only parameter; suite green
- [ ] 1.2 Add `MockCepikService` as the second parameter; record the failure count and which properties broke
- [ ] 1.3 Rewrite `MockCepikService` — validate three inputs, return a realistic `FOUND`; suite green
- [ ] 1.4 Confirm `RealCepikEnrichmentServiceTest` and every existing backend test still pass unchanged

#### Manual

- [ ] 1.5 `npm run test:e2e` green under `mock` (both specs stay on the `MISSING_INPUTS` path)
- [ ] 1.6 Local `POST /api/analyses` under `mock`: well-formed triple → `FOUND` with damage; no VIN → `MISSING_INPUTS`

### Phase 2: The LLM port contract, and the inverted accident rule

#### Automated

- [ ] 2.1 Write `AiAnalysisServiceContractTest` with the parser-backed adapter as the only parameter; suite green
- [ ] 2.2 Add `MockAiAnalysisService` as the second parameter; record both failures (missing flag on `"historia"`, `HIGH` vs `MEDIUM`)
- [ ] 2.3 Fix `MockAiAnalysisService` — derive the flag from `accidentClaim`, emit `MEDIUM`; suite green
- [ ] 2.4 Invert `AnalysisResponseParser:170` once, confirm the real parameter fails, revert; record the count

### Phase 3: Prove the adjuster is reachable, and that null still moves nothing

#### Automated

- [ ] 3.1 Add the `FOUND`-with-null-`damageRecords` case to `CepikDamageReachesTheResponseTest`
- [ ] 3.2 Add the case driving `MockCepikService`'s own `FOUND` result through `CepikRiskAdjuster`
- [ ] 3.3 Invert `CepikRiskAdjuster:73` once, confirm both new tests fail, revert; record the count

### Phase 4: Documentation, and the counts the hooks print

#### Automated

- [ ] 4.1 Read the final backend suite count off a run; update root `CLAUDE.md`, `.githooks/pre-commit:38`, `.githooks/pre-push:28`

#### Manual

- [ ] 4.2 `test-plan.md` — rewrite §7 bullet 1, add the two narrowings, add the §6 contract-test convention, date §8, mark risk rows 2–4
- [ ] 4.3 `context/map/repo-map.md` — update the mock-profile risk-zone note, keep the residual `market` warning
- [ ] 4.4 `backend/CLAUDE.md` § "Enrichment services" — the cepik mock's new behaviour and both contract tests
