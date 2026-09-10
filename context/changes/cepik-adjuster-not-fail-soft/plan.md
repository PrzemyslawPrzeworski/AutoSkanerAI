# Guard the risk adjuster, and make the degraded path say so — Implementation Plan

## Overview

`research.md` settled every open question in `change.md`. What is left is one guard, one public
static factory, and two tests.

**The guard goes at the call site, matching its two neighbours.** `AnalysisController.java:95`
becomes a third `degradeOnThrow`, so the three post-analysis steps read alike.

**The degraded value is built by `CepikRiskAdjuster`, not by the controller.** A public static
`unscored(AnalysisResult, CepikResult)` returns the analysis with one prepended `HIGH` flag saying
the registry findings were not folded into the score, and the verdict floored to `NEEDS_MORE_INFO`.
It lives on the adjuster because `applyFloor`, `labelFor` and the three label strings do — and
`labelFor`'s own comment records that those strings are already tracked across three places.
A fourth copy in a caller is the drift that comment exists to prevent.

**No risk cap on the degraded path, on purpose.** Which registry finding fired is precisely what the
throw destroyed, so every cap would be a number nobody computed. The response keeps the model's score
and states in a `HIGH` flag that it is incomplete. That is the direction the root `CLAUDE.md`
requires: unknown reported as unknown, never as a clean result.

**Rejected: degrading `cepikResult` to `LOOKUP_FAILED`.** It would reuse an existing UI path but
delete the damage records the lookup successfully retrieved — `CepikResult`'s class comment requires
every list to go null on a non-FOUND status, so reporting a downstream failure would cost the user
data the registry actually returned.

## Current State Analysis

Three steps run after the analysis is in hand; two are guarded and one is not
(`AnalysisController.java:82-95`). The unguarded one is the only one whose failure changes what the
app *judges* rather than what it *shows*.

**A naive wrap would be a regression, not a fix.** Degrading to the un-adjusted `result` reproduces
the state `CepikRiskAdjuster`'s Javadoc names as the defect it was written for: *"a vehicle with a
registered szkoda istotna came back `risk: 88, verdict: WORTH_CHECKING` while the panel above it
showed the damage."* On the failure path that would be silent — unlike `LOOKUP_FAILED` and
`FETCH_FAILED`, a skipped adjustment has no vocabulary that reaches the UI. This change gives it one.

**No throw is reachable today** (`research.md` § Reachability walks all seven candidates), so the
throw has to be injected — the standing `AnalysisSurvivesEnrichmentFailureTest` already argues for
the market-price guard in its own words. The single near-miss is `flags.addAll(result.riskFlags())`
at `:129` on a null list, which the controller's `withoutFlag` treats as representable and the
adjuster does not.

**The frontend needs no change.** Nothing switches on `flag.code`; `description` and `severity` are
what render, and `HIGH` keeps the new flag inside `visibleFlags()`'s first four.

## Desired End State

A `RuntimeException` anywhere in `cepikRiskAdjuster.apply` yields a 200 carrying the finished
analysis, the full `FOUND` registry panel, a prepended `CEPIK_NOT_SCORED` flag, and a verdict no
weaker than `NEEDS_MORE_INFO` — never a 500, and never a `WORTH_CHECKING` beside a visible szkoda
istotna.

## What We're NOT Doing

- **Not catching inside `apply`.** It would make `apply` total, but it cannot be exercised through
  the controller without mocking the adjuster (which bypasses the inner guard), and it leaves the
  three post-analysis steps looking inconsistent to a reader for no visible reason.
- **Not capping risk on the degraded path.** See Overview.
- **Not adding a log-level parameter to `degradeOnThrow`.** Risk going unscored is a worse event than
  a missing market range, but a shared signature bent for one caller costs more than it buys; the
  `HIGH` flag in the response is the signal that matters. Recorded in `research.md` Open Questions.
- **Not making `AnalysisResult`'s list fields contractually non-null.** That is a port-contract
  change (`test-plan.md` §6.8), and the near-miss at `:129` is recorded rather than fixed.
- **Not touching the third gap** — `AnalysisController` never switching on `CepikStatus`. Still open,
  still in `test-plan.md` §8.
- Not changing the frontend.

## Phase 1: A guard with something to say

### Overview

Add the degraded-value factory to the adjuster, route the call through `degradeOnThrow`, and pin both
the factory's properties and the end-to-end 200.

### Changes Required

- `CepikRiskAdjuster.java` — add a public static `unscored(AnalysisResult result, CepikResult cepik)`:
  returns `result` unchanged when `result` is null or `cepik` is not `FOUND` (nothing was skipped, so
  nothing is claimed); otherwise prepends `new RiskFlag("CEPIK_NOT_SCORED", RiskSeverity.HIGH, …)`
  with Polish copy saying the registry returned data that could not be folded into the score and the
  panel must be read by hand, keeps `result.riskFlags()` after it (null-tolerantly, unlike `:129`),
  leaves `scores` untouched, and returns `applyFloor(result.verdict(), VerdictCode.NEEDS_MORE_INFO)`.
  Javadoc records: why it exists, why no cap, and why it is throw-free by construction — it is a
  `degradeOnThrow` degraded supplier, so a throw inside it propagates as the 500 the guard was added
  to remove.
- `AnalysisController.java` — capture `result` into an effectively-final local (the method reassigns
  it twice; `extracted` at `:80` exists for the same reason) and replace `:95` with
  `degradeOnThrow("cepik-risk-adjustment", () -> cepikRiskAdjuster.apply(analysed, cepikResult),
  () -> CepikRiskAdjuster.unscored(analysed, cepikResult))`. Extend `degradeOnThrow`'s Javadoc: its
  scope paragraph currently justifies covering the two enrichments and excluding the LLM, and now has
  a third member whose failure vocabulary the adjuster supplies rather than a status enum.
- `CepikRiskAdjusterTest.java` — cases for `unscored`: the flag is first and `HIGH`; the model's flags
  follow in order; `scores` is the same instance; `WORTH_CHECKING` is raised to `NEEDS_MORE_INFO`
  while `HIGH_RISK_SKIP` is left alone; a non-`FOUND` `cepik` returns the same instance untouched; a
  null `riskFlags` does not throw.
- `AnalysisSurvivesEnrichmentFailureTest.java` — a third injected throw site: `mock(CepikRiskAdjuster
  .class)` whose `apply` throws, with the `CepikEnrichmentService` returning a `FOUND` result, then
  assert 200, the analysis intact, `cepikResult.status` still `FOUND`, `riskFlags[0].code` is
  `CEPIK_NOT_SCORED`, and `verdict.code` is not `WORTH_CHECKING`. Update the class Javadoc's "what is
  real / what is stubbed" paragraph, which currently lists `CepikRiskAdjuster` as always real.
  A hostile `List<DamageRecord>` is **not** usable here — Jackson serialises `damageRecords` on the
  way out and would trip it after the controller returned, producing a 500 from the serialiser.
- Use the committed synthetic VIN `NMTBZ3BE40R000000` for any VIN a fixture needs. This repository
  is public.

### Success Criteria

#### Automated

- `cd backend && ./mvnw -o test` green; suite count rises from 249 by the number of new cases.
- Deliberate break: remove the degraded supplier's flag (return the un-adjusted `result`) and confirm
  the end-to-end case fails on `riskFlags[0].code`; separately revert `:95` to the unguarded call and
  confirm the same case fails on the status assertion. Restore with `git checkout --`. Two breaks
  because two things are being pinned — that the guard exists, and that it does not degrade silently.

#### Manual

- Local `POST /api/analyses` under `mock` with a well-formed VIN + plate + date: unchanged happy path
  — `cepikResult.status` `FOUND`, `CEPIK_SIGNIFICANT_DAMAGE` present, no `CEPIK_NOT_SCORED`. The
  guard must be invisible when nothing fails.

## Phase 2: Write down the rule and the counts

### Overview

The suite count is printed in three places, and the ledger has a gap to close.

### Changes Required

- Root `CLAUDE.md`, `.githooks/pre-commit`, `.githooks/pre-push` — the backend suite count.
- `backend/CLAUDE.md` — that all three post-analysis steps are fail-soft, and that the adjuster's
  degraded value reports itself rather than skipping quietly.
- `context/foundation/test-plan.md` §8 — a dated entry; annotate the 2026-09-10 carried-forward list
  so only the `CepikStatus` gap remains open.
- `change.md` — `status: implemented`.

### Success Criteria

#### Automated

- `./.githooks/pre-push` green over the whole tree.

#### Manual

- The three printed counts match a real run.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: A guard with something to say

#### Automated

- [x] 1.1 Add `CepikRiskAdjuster.unscored` with the flag, the verdict floor and no cap — 74df4bf
- [x] 1.2 Route `AnalysisController:95` through `degradeOnThrow` with `unscored` as the degraded supplier; extend the guard's Javadoc scope paragraph — 74df4bf
- [x] 1.3 `CepikRiskAdjusterTest` cases for `unscored` — 5 cases, including one asserting the degraded path itself cannot throw on a null flag list or a null verdict — 74df4bf
- [x] 1.4 `AnalysisSurvivesEnrichmentFailureTest` third throw site, end to end; suite green — 255 tests in 28 classes, up from 249 — 74df4bf
- [x] 1.5 Both deliberate breaks fire, then restore — degrading to the un-adjusted analysis: `riskFlags[0].code` came back `HIGH_MILEAGE` (the fixture's own first flag), so the silent degrade is caught; removing the guard: `Status expected:<200> but was:<500>`, the production failure itself. **The first restore was run before staging and reverted the whole controller edit** — the procedure stages before breaking for exactly this reason — 74df4bf

#### Manual

- [x] 1.6 Local `POST /api/analyses` under `mock`: happy path unchanged, no `CEPIK_NOT_SCORED` — `FOUND`, damage record intact, risk capped 65 → 25, verdict `HIGH_RISK_SKIP`, flags `CEPIK_SIGNIFICANT_DAMAGE, CEPIK_CONTRADICTS_LISTING, NO_SERVICE_HISTORY`. The guard is invisible when nothing fails — 74df4bf

### Phase 2: Write down the rule and the counts

#### Automated

- [x] 2.1 Backend suite count in root `CLAUDE.md`, `.githooks/pre-commit`, `.githooks/pre-push` — 249 → 255, still 28 classes (no new spec file) — b7431ae
- [x] 2.2 `backend/CLAUDE.md` — all three post-analysis steps fail-soft, and the degraded value reports itself, in § "Folding registry findings into the score" next to the defect it descends from — b7431ae
- [x] 2.3 `test-plan.md` §8 — dated entry; the 2026-09-10 carried-forward list annotated in place down to the `CepikStatus` gap alone — b7431ae

#### Manual

- [x] 2.4 The three printed counts match a real run — `pre-push` printed "255 tests" and the run reported 255 — b7431ae
