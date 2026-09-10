# Remove `NO_VIN` once the VIN it describes is verifiable — Implementation Plan

## Overview

`research.md` closed all four of `change.md`'s open questions. What is left is one decision and
about twenty lines of production code:

**The decision, made here: remove the flag, do not reword it.** The case for rewording was to
preserve a listing-quality signal — the advert genuinely did omit the VIN. But that signal already
has a home the fix does not touch: `scores.completeness`, which the model sets from the advert and
which no part of this change reads or writes. Rewording would put a second copy of a signal the
response already carries into the one list a buyer reads for *car* risks, and would leave the
sentence *"nie można zweryfikować pojazdu"* in a response whose `cepikResult.status` is `FOUND`.
Removal costs nothing that is not already reported elsewhere.

**Keyed on a VIN that validates, not on one that is non-blank.** `UserOverrides` sets `vinPresent`
to `TRUE` on any non-blank typed value, so a user who types `ABC` gets `vinPresent: true` and
`cepikResult.status: MISSING_INPUTS`. For that request `NO_VIN`'s description is *true* — the
vehicle cannot be verified — so the flag must stay. The predicate is therefore
`VinValidator.normalise(vin).isPresent()`, which is the predicate the seller-question block at
`AnalysisController.java:102` already uses.

That last point sets the shape of the fix, and it is a narrowing of the three candidate homes
`research.md` listed. The reconciliation does not need a new seam: `buildResponse` already has a
block that re-derives values *after* every late input has landed, and it already rebuilds
`AnalysisResult` at `:112` passing `result.riskFlags()` through untouched. Putting the rule there
means **one predicate feeds both consequences** — ask for the VIN, or drop the flag that says it is
missing — so the asymmetry `change.md` reported is not patched, it is removed. No new class, no
second record rebuild, no second copy of the predicate.

Scope is **one flag**. No `NO_PLATE` or `NO_DATE` flag exists to generalise over, and inventing the
vocabulary today for a flag nothing emits is the speculative generality `test-plan.md` §1 warns
about.

## Current State Analysis

**The bug is three lines apart from its own fix.** `AnalysisController.buildResponse`:

```java
result = withExtracted(result, UserOverrides.apply(result.extracted(), request));   // :77
...
result = cepikRiskAdjuster.apply(result, cepikResult);                              // :95
var vin = result.extracted().vin();                                                // :98
if (vin == null || VinValidator.normalise(vin).isEmpty()) {                         // :102
    augmentedQuestions.add("Proszę podać numer VIN pojazdu");
}
...
AnalysisResult augmented = new AnalysisResult(
        result.extracted(), result.equipment(), result.riskFlags(), ...             // :112-113
```

`:102` is override-aware. `:113` passes the flags through. Both read the same post-override
`ExtractedData`; only one of them uses it.

**Nothing pins today's behaviour** (`research.md` § Test blast radius — four touch points, none
asserts the flag survives an override), so the fix is purely additive and the deliberate-break gate
is the only thing that will prove the new test protects anything.

**`CepikRiskAdjuster` emits five flag codes and none of them is `NO_VIN`** (`CEPIK_VEHICLE_LOST`,
`CEPIK_ODOMETER_ROLLBACK`, `CEPIK_SIGNIFICANT_DAMAGE`, `CEPIK_CONTRADICTS_LISTING`,
`CEPIK_NO_OC_POLICY`), so placing the removal after `:95` cannot fight the adjuster — and it does
cover a `NO_VIN` from any upstream source, mock or model.

**`RiskFlag.code` is a free-form `String`, not an enum.** A code-keyed rule matches the documented
vocabulary and nothing more. That is a mitigation, not a guarantee, and the plan says so where the
code says so.

## Desired End State

`POST /api/analyses` with a well-formed VIN returns no `NO_VIN` in `analysis.riskFlags`, and the
same request with a malformed VIN still returns it. The rule holds whatever produced the flag, and
one predicate in `AnalysisController` decides both the flag and the seller question.

## What We're NOT Doing

- Not rewording the flag, and not adding a `LOW`-severity replacement. See Overview.
- Not touching `UserOverrides`. `vinPresent`-on-non-blank is arguably loose for a typed `ABC`, but
  it is pre-existing, user-visible, and its own decision — widening into it here would bundle two
  changes.
- Not generalising to plate or date. No flag exists.
- Not putting the rule in the port contracts (`test-plan.md` §6.8) — `research.md` Q4: the rule is a
  property of `buildResponse`, and an implementation that never emits `NO_VIN` satisfies it
  vacuously.
- Not fixing the two neighbours recorded in `test-plan.md` §8: `cepikRiskAdjuster.apply` sitting
  outside `degradeOnThrow`, and `AnalysisController` never switching on `CepikStatus`.
- Not reordering the frontend's collapse-after-four by severity (`research.md` Open Questions).

## Phase 1: One predicate, both consequences

### Overview

Extract the verifiability predicate, use it to drop `NO_VIN`, and pin both directions with a new
behaviour-named controller test.

### Changes Required

- `AnalysisController.java` — add a private static `vinIsVerifiable(String)` wrapping
  `VinValidator.normalise(...).isPresent()`. Rewrite the `:102` question guard to use it. In the
  same block, when it answers true, replace `result.riskFlags()` with a copy that drops any flag
  whose `code` equals `NO_VIN`; when it answers false, add the question exactly as today. Return the
  same list instance when nothing is dropped, so the common path allocates nothing.
- Comment the `RiskFlag.code` caveat at the removal, and comment why the predicate is validity and
  not presence (the typed-`ABC` case, where the flag's text is true).
- `SuppliedVinClearsTheNoVinFlagTest.java` (new) — `MockMvcBuilders.standaloneSetup` over the real
  controller with a stubbed `AiAnalysisService` returning a result that carries `NO_VIN` plus two
  other flags, a stubbed `CepikEnrichmentService`, a stubbed `MarketPriceEnrichmentService`, and the
  real `CepikRiskAdjuster`. Four cases:
  1. a well-formed typed VIN → no `NO_VIN`, the other flags untouched and in order, and no
     "Proszę podać numer VIN" question;
  2. a malformed typed VIN (`ABC`) → `NO_VIN` **kept**, and the question asked — the case that
     stops the fix being keyed on non-blankness;
  3. no VIN anywhere → `NO_VIN` kept, question asked;
  4. a VIN the *listing* supplied, no override → no `NO_VIN`, no question. Proves the rule is about
     final state rather than about who typed it.
- Use the committed synthetic VIN `NMTBZ3BE40R000000`. This repository is public.

### Success Criteria

#### Automated

- `cd backend && ./mvnw -o test` green; suite count rises from 245 by the number of new cases.
- Deliberate break: invert `vinIsVerifiable` (`isPresent()` → `isEmpty()`) and confirm cases 1, 2
  and 4 fail; restore with `git checkout --`. A guard inversion is the only edit that can break
  both directions at once, which is why the malformed case exists.

#### Manual

- Local `POST /api/analyses` under `mock` with a well-formed VIN + plate + date: `cepikResult.status`
  is `FOUND` and `analysis.riskFlags` carries no `NO_VIN` — the exact request that produced the
  observation in `change.md`.

## Phase 2: Write down the rule and the counts

### Overview

The suite count is printed in three places and the rule belongs next to the two it generalises.

### Changes Required

- Root `CLAUDE.md`, `.githooks/pre-commit`, `.githooks/pre-push` — the backend suite count.
- `backend/CLAUDE.md` § "Manual entry and user overrides" — that a verifiable VIN clears `NO_VIN`,
  that the predicate is shared with the seller question, and that `vinPresent` is set on
  non-blankness while the flag rule needs validity.
- `context/foundation/test-plan.md` §8 — a dated entry; and remove this bug from the two
  carried-forward neighbours recorded on 2026-09-10, leaving those two open.
- `change.md` — `status: implemented`.

### Success Criteria

#### Automated

- `./.githooks/pre-push` green (backend + frontend + both `packages/` + `terraform/` static).

#### Manual

- The three printed counts match a real run.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: One predicate, both consequences

#### Automated

- [x] 1.1 Add `vinIsVerifiable`, route the seller question through it, drop `NO_VIN` when it answers true
- [x] 1.2 Write `SuppliedVinClearsTheNoVinFlagTest` with all four cases; suite green — 249 tests in 28 classes, up from 245 in 27
- [x] 1.3 Invert `vinIsVerifiable` once, confirm cases 1, 2 and 4 fail, restore; record the count — **all four failed, not three.** The plan under-predicted: inverting also flips case 3, because a `null` VIN becomes "verifiable" and the flag it correctly carries is dropped. Both directions of the guard are pinned, which is what the gate is for

#### Manual

- [x] 1.4 Local `POST /api/analyses` under `mock`: well-formed VIN → `FOUND` and no `NO_VIN` — verified on a live local server, all three cases: well-formed → `FOUND`, `vinPresent: true`, flags `CEPIK_SIGNIFICANT_DAMAGE, NO_ACCIDENT_DECLARATION, NO_SERVICE_HISTORY`, no VIN question; `ABC` → `MISSING_INPUTS`, `vinPresent: true`, `NO_VIN` **kept**; no VIN → `MISSING_INPUTS`, `NO_VIN` kept

### Phase 2: Write down the rule and the counts

#### Automated

- [ ] 2.1 Update the backend suite count in root `CLAUDE.md`, `.githooks/pre-commit`, `.githooks/pre-push`
- [ ] 2.2 `backend/CLAUDE.md` § "Manual entry and user overrides" — the rule and the two predicates
- [ ] 2.3 `test-plan.md` §8 — dated entry; narrow the 2026-09-10 carried-forward list to the two that stay open

#### Manual

- [ ] 2.4 The three printed counts match a real run
