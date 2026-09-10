---
date: 2026-09-10T21:17:42+02:00
researcher: Claude Opus 5 (in-context, no sub-agents)
git_commit: a2d5839e77703e78c11fa5e6816bbc4cf1ef17ab
branch: main
repository: AutoSkanerAI
topic: "Which risk flags outlive the user override that invalidates them, and where the reconciliation belongs"
tags: [research, codebase, analysis, user-overrides, risk-flags]
status: complete
last_updated: 2026-09-10
last_updated_by: Claude Opus 5
---

# Research: which risk flags outlive the user override that invalidates them

**Date**: 2026-09-10T21:17:42+02:00
**Git Commit**: `a2d5839`
**Branch**: `main`

## Research Question

The four questions `change.md` left open: which flags are text-derived but
override-invalidated; where the reconciliation belongs; whether removal is even
the right move; and whether the port contracts are the right home for the rule.

## Summary

**The scope is one flag, and the fix's own justification is already written in the
class that needs to change.** `UserOverrides` reconciles `vinPresent` against a
typed VIN and says why in a comment — *"leaving vinPresent false while showing the
VIN would contradict the panel next to it"* — which is verbatim the argument for
`NO_VIN`. The boolean was reconciled; the flag carrying the same fact was not.

Three findings sharpen the report in `change.md`, and **two of them correct it**:

1. The contradiction is **inside one rendered view**, not between a flag and a
   distant panel. `extracted.vinPresent` is rendered at
   `analysis-result.component.html:98` and `UserOverrides` sets it `TRUE` on a
   typed VIN, so the same result shows `vinPresent: true` in its table and
   "Brak numeru VIN" in its flag list.
2. **`change.md` overstated the four-flag cost.** Flags are not truncated — they
   are collapsed behind an expand link (`analysis-result.component.ts:47`,
   `slice(0, 4)` only while `!riskFlagsExpanded()`, with `showExpandLink` at `> 4`).
   Nothing is lost; it costs a click. But there is a precise version of the worry
   worth keeping, below.
3. **No test pins today's behaviour**, so the fix breaks nothing — which is the
   healthy state §1 rule 4 of `test-plan.md` asks for.

## Detailed Findings

### Q1 — Which flags are in this class? Exactly one.

The flag vocabulary is the prompt's example (`AnalysisPrompt.java:102-106`) plus
what `MockAiAnalysisService.buildRiskFlags` emits (`:145-190`). Four codes exist:

| Flag | Derived from | Overridable? | In this class? |
|---|---|---|---|
| `NO_VIN` | listing text lacks `"vin"` / `"nr identyfikacyjny"` | **yes** — `request.vin()` | **YES** |
| `NO_ACCIDENT_DECLARATION` | `accidentClaim == null` | **no, by design** | no |
| `NO_SERVICE_HISTORY` | text lacks `serwis`/`przegląd`/`olej` | no | no |
| `URGENCY_PRESSURE` | text contains `pilnie`/`okazja`/`wyprzedaż` | no | no |

- `NO_ACCIDENT_DECLARATION` is excluded structurally, not by judgement:
  `UserOverrides` passes `extracted.accidentClaim()` through untouched with a
  comment explaining that letting a user edit it would erase the
  `CEPIK_CONTRADICTS_LISTING` finding. Confirmed — `AnalysisRequest` has no
  accident field to override *with*.
- `NO_SERVICE_HISTORY` self-corrects rather than needing a rule.
  `ManualListing` carries no service-history field; under manual entry
  `ManualListingComposer` renders the typed fields into advert text, so a stated
  history reaches the keyword scan through the text itself.
- **There is no `NO_PLATE` and no `NO_DATE` flag.** Missing plate and date are
  handled only as seller questions (`AnalysisController:101-112`), which is why
  the bug has exactly one instance and not three.

**Caveat that survives the table**: `RiskFlag.code` is a free-form `String`, not
an enum, so a real model can emit any code it likes. A code-based rule can only
match codes we know — it is a mitigation for the documented vocabulary, not a
guarantee over model output.

### Q2 — Where the reconciliation belongs

The seam already exists and already handles flags — it just passes them through:

```java
// AnalysisController.java:157
private static AnalysisResult withExtracted(AnalysisResult result, ExtractedData extracted) {
    if (extracted == result.extracted()) {
        return result;                       // nothing typed -> same instance, no work
    }
    return new AnalysisResult(extracted, result.equipment(), result.riskFlags(),  // <-- here
            result.sellerQuestions(), result.scores(), result.verdict(), result.meta());
}
```

Called at `:77` as the first statement of `buildResponse`, before enrichment. Three
candidate homes, with the real trade:

- **`UserOverrides`** — where the precedent lives, and the reconciliation is
  conceptually part of "what the user typed wins". Cost: it returns
  `ExtractedData` today, so it would need a wider signature or a second method,
  and a class named for overriding *extraction* would start editing *flags*.
- **`withExtracted` / the controller** — the seam is already there and already
  rebuilds the record. Cost: `buildResponse` grows more logic, and the controller
  is already flagged in `repo-map.md` §4 as a risk zone for exactly that.
- **A small dedicated collaborator beside `CepikRiskAdjuster`** — matches the
  existing shape (a deterministic post-pass that reconciles findings against
  something the LLM could not see). Cost: a third place that knows the flag
  vocabulary; the prompt is the first and the mock is the second.

**The duplication is the design cost to weigh, and it is unavoidable in all
three** — any rule keyed on `"NO_VIN"` is a second copy of a string the prompt
owns.

### Q3 — Is removal right, or rewording?

Removal, on the evidence, but the question is real and the answer is not
symmetric with `vinPresent`.

- **For removal**: the flag's own description is *"Brak numeru VIN — nie można
  zweryfikować pojazdu"* — "cannot verify the vehicle". When a VIN was supplied
  and the registry answered `FOUND`, **both clauses are false**. It is not stale
  context, it is a false statement about the current analysis.
- **For rewording**: the flag is evidence about *the listing*, and the listing
  genuinely omitted the VIN. `serviceHistoryMentioned` and `accidentClaim` are both
  treated as facts about the advert rather than the car, deliberately.
- **What tips it**: the `vinPresent` precedent already chose. It did not reword —
  it flipped the boolean to `TRUE` and let the table say a VIN exists, because the
  user typing one *is* the evidence. Keeping the flag while flipping the boolean is
  the inconsistency, so consistency points at removal. A middle path (drop to
  `LOW`, reworded to "the advert omitted the VIN; you supplied it") preserves the
  listing-quality signal, and is worth costing at plan time rather than dismissing.

**The precise version of the four-flag worry** (replacing the overstatement in
`change.md`): `AnalysisResponseParser.withAccidentDeclarationFlag` **appends**
`NO_ACCIDENT_DECLARATION` last, and the frontend collapses after the fourth. So on
a five-flag result the entry pushed behind the expand link is the appended
absence-means-unknown flag — the one guardrail the code, not the model, has the
last word on. A spurious `NO_VIN` occupying a visible slot demotes it. Behind a
click, not deleted; still the wrong ordering of importance.

### Q4 — Do the port contracts own this rule? No.

`AiAnalysisServiceContractTest` cannot host it. The rule is about
*post-override reconciliation*, and `AiAnalysisService.analyze(String)` takes only
listing text — it has no request, no overrides, and returns before
`UserOverrides` runs. The rule is a property of `buildResponse`, one layer up.

It also fails the §6.8 test for a contract property: an implementation is free to
never emit `NO_VIN` at all, so "the flag is absent when a VIN was supplied" holds
trivially for that bean and asserts nothing. The right home is a controller-level
test, next to `UserOverridesTest`.

## Code References

- `backend/.../analysis/UserOverrides.java:38-40` — the `vinPresent` precedent and
  its reasoning
- `backend/.../analysis/UserOverrides.java:60-62` — `accidentClaim` deliberately
  never overridden
- `backend/.../analysis/AnalysisController.java:77` — overrides applied first
- `backend/.../analysis/AnalysisController.java:101-112` — the seller-question
  block that *is* override-aware
- `backend/.../analysis/AnalysisController.java:157-163` — `withExtracted`, the
  existing seam that passes `riskFlags` through
- `backend/.../analysis/MockAiAnalysisService.java:169-175` — the mock's `NO_VIN`
  keyword scan
- `backend/.../analysis/llm/AnalysisPrompt.java:104` — `NO_VIN` in the locked
  schema's example
- `backend/.../analysis/llm/AnalysisResponseParser.java:180-186` — why
  `NO_ACCIDENT_DECLARATION` is appended last
- `frontend/.../analysis-result.component.html:98` — `vinPresent` rendered
- `frontend/.../analysis-result.component.ts:47-50` — the collapse-after-four rule

## Test blast radius

Four places touch `NO_VIN` or `vinPresent`; **none pins the buggy behaviour.**

| Location | What it asserts | Affected by a fix? |
|---|---|---|
| `UserOverridesTest:78` | `vinPresent` becomes true on a typed VIN | no — it is the precedent; the new rule is its sibling |
| `AnalysisControllerTest:222` | `vinPresent` true | no |
| `ListingClaimsCannotMoveTheFloorTest:289` | `NO_VIN` present in a JSON fixture | no — no override in that path |
| `RiskAnalysisControllerTest:44,51` | `NO_VIN` from a hand-built flag | no — the **deprecated** endpoint, no override path |

So the fix is additive: one new assertion, no existing one weakened. Per §1 rule 4
that is the expected shape — the defect was never pinned, which is why it shipped.

## Architecture Insights

**The pattern is "a derived value outliving its input", and the codebase has met
it twice before.** `CepikRiskAdjuster` exists because the LLM scores the listing
before the registry is queried, so `scores` outlive the evidence; the seller-question
block re-reads post-override values for the same reason. Both fixes are the same
shape: a deterministic pass that re-derives after the late input arrives. This bug
is the third instance and the only one still open — and the only one where a
*partial* fix landed, since `vinPresent` was reconciled and the flag beside it was not.

## Open Questions

- Whether to also cover the plate and date, pre-emptively, against a future
  `NO_PLATE` / `NO_DATE` flag — or to leave it at one flag and let the next one
  arrive with its own rule. Adding a vocabulary today that nothing emits is the
  kind of speculative generality §1 warns about.
- Whether the frontend's collapse-after-four should order by severity rather than
  by list position. Out of scope here, but it is the reason a spurious `HIGH`
  costs anything at all — worth its own note if it survives triage.
