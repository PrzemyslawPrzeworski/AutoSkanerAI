---
change_id: no-vin-flag-survives-override
title: A user-supplied VIN clears the seller question but not the NO_VIN risk flag
status: preparing
created: 2026-09-10
updated: 2026-09-10
archived_at: null
---

## Notes

Recorded by the agent while verifying manual row 1.6 of `refactor-opportunities`
against a live local server (`SPRING_PROFILES_ACTIVE=mock`, `POST /api/analyses`),
not reported by a user. No fix is decided; this folder exists so the observation
stops living in a session transcript.

**Observed.** A request carrying a well-formed VIN + plate + first-registration
date came back with `cepikResult.status: FOUND` — the registry lookup keyed on the
very VIN that was supplied, returning a `szkoda-istotna`, a mileage stamp and the
identity fields — *and* with `NO_VIN` at `HIGH` in `analysis.riskFlags`, reading
"Brak numeru VIN — nie można zweryfikować pojazdu". The panel says the vehicle was
verified; the flag list says it could not be.

**Why.** `NO_VIN` is built from the listing text before the user's values exist.
`AnalysisController.buildResponse` applies `UserOverrides.apply` at its first
statement — deliberately, so the registry and the market query use what the user
vouched for — but the analysis, flags included, was already produced upstream of
that call. Under `mock` the flag comes from `MockAiAnalysisService.buildRiskFlags`
reading the advert; on the real path it comes from the model, which also only ever
saw the advert (`AnalysisPrompt.java:104` is where `NO_VIN` is asked for). **So
this reaches production — it is not a mock artefact.**

**The sharp part: the same method already gets it right three lines further down.**
The seller-question block reads `result.extracted().vin()` *after* the overrides,
so "Proszę podać numer VIN pojazdu" is correctly suppressed when the user typed
one. Questions are override-aware; flags are not. Whatever the fix is, the
asymmetry inside one method is the thing to explain, and the questions block is
the shape that already works.

**Why it is worth a change rather than a shrug.** The error direction is the safe
one — an extra warning, not a missing one, and it is not an accident-data claim, so
the *absence means unknown, not clean* guardrail is not in play. Two costs anyway:
a flag that contradicts the panel next to it teaches the user to discount the flag
list, and a spurious `HIGH` occupying a visible slot demotes a real finding below
the fold.

**Both of those were sharpened by research (`research.md`), and one of them was an
overstatement — corrected there rather than left standing here:**

- The contradiction is **inside one rendered view**, not between distant panels.
  `UserOverrides` sets `vinPresent` to `TRUE` on a typed VIN and the table at
  `analysis-result.component.html:98` renders it, so the same result reads
  `vinPresent: true` in its table and "Brak numeru VIN" in its flag list.
- Flags are **not truncated** at four, as first written here — they collapse behind
  an expand link (`analysis-result.component.ts:47`). Nothing is lost; it costs a
  click. The precise worry that survives: the parser *appends*
  `NO_ACCIDENT_DECLARATION` last, so on a five-flag result the entry pushed behind
  the link is the absence-means-unknown guardrail — the wrong ordering of
  importance, but not evidence loss.

**All four questions below are now answered in `research.md`** — the headline is
that `UserOverrides` already reconciles `vinPresent` against a typed VIN, with a
comment giving verbatim the argument for clearing `NO_VIN`, so the precedent for the
fix sits three lines above the gap. Scope is **one flag**: no `NO_PLATE` or
`NO_DATE` flag exists, and no test pins today's behaviour. Kept as written, since
what was open at the time is part of the record:

- Which flags are text-derived-but-override-invalidated? `NO_VIN` is the one
  observed. `NO_ACCIDENT_DECLARATION` is deliberately *not* in this class —
  `accidentClaim` is not user-editable on purpose (`backend/CLAUDE.md` §
  "Manual entry and user overrides"), and `CepikRiskAdjuster` compares it against
  the registry. Assume nothing about plate or date flags without checking whether
  any exist.
- Where the reconciliation belongs: in `UserOverrides` (which today only touches
  `ExtractedData`, never `riskFlags`), in a new step beside `CepikRiskAdjuster`, or
  in the controller. A removal that inspects flag *codes* is a second place that
  knows the flag vocabulary, and the prompt is the first — that duplication is the
  design cost to weigh.
- Whether removing a flag is even right, or whether it should be rewritten to say
  the advert omitted the VIN and the user supplied it. Deleting evidence about the
  *listing* to describe the *user's* input may be the wrong trade.
- Whether the port contracts from `refactor-opportunities` (`test-plan.md` §6.8)
  are the right home for the rule, since it must hold of every
  `AiAnalysisService` — or whether it is a controller property and belongs nowhere
  near the port.

**Two neighbouring findings from the same session, deliberately kept out of scope
here** so they are not silently bundled into one change; both are recorded in
`test-plan.md` §8's 2026-09-10 port-contract entry:

- `CepikRiskAdjuster` is called at `AnalysisController:95` *outside*
  `degradeOnThrow`, so an exception inside it discards a complete analysis behind a
  catch-all 500. It is the one enrichment step that is not fail-soft.
- `AnalysisController` never switches on `CepikStatus`.
