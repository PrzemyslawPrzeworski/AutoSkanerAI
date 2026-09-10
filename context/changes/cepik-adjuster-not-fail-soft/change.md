---
change_id: cepik-adjuster-not-fail-soft
title: The one enrichment step that is not fail-soft, and why wrapping it naively would be worse
status: implemented
created: 2026-09-10
updated: 2026-09-10
archived_at: null
---

## Notes

Carried forward from `refactor-opportunities`' 2026-09-10 port-contract work, where it was recorded
in `test-plan.md` §8 as one of three gaps deliberately left open, and kept out of scope for
`no-vin-flag-survives-override` so two findings would not be bundled into one change. It is the
second of those three; the third (`AnalysisController` never switches on `CepikStatus`) stays open.

**Observed.** `AnalysisController.java:95` calls `cepikRiskAdjuster.apply` outside
`degradeOnThrow`, so an uncaught `RuntimeException` inside it discards a completed analysis behind
`GlobalExceptionHandler`'s catch-all 500. Both enrichment calls, immediately above it, are guarded.
`degradeOnThrow`'s own Javadoc states the cost in the first person: enrichment "runs *after* the
analysis is already in hand. An uncaught throw here therefore discards a finished analysis and
answers 500 — the user waited out the whole LLM call to be told the server broke."

**Why now rather than earlier.** Until 2026-09-10 no end-to-end path executed this class at all:
`MockCepikService` returned `LOOKUP_FAILED` unconditionally, and `apply` returns its argument
untouched for every status except `FOUND`. `refactor-opportunities` fixed the mock, so 254 lines of
the most consequential logic in the application are now reachable on the profile every gate runs —
and reachable in production, where its inputs are scraped from a government HTML page.

**No throw is reachable on today's code, and that is not an argument against the guard — it is
the same argument the repo already accepted.** `AnalysisSurvivesEnrichmentFailureTest` says so in
its own words about the market-price guard: neither of the two throw sites it protects "can be
driven to throw through the service's public surface on today's code… That is the point: the guard
is there for the throw nobody predicted, so the throw has to be injected." Checked here rather than
assumed: `damages`, `damage.date()`, `damage.insurer()` and `damage.categories()` are all
null-checked; `String.join` renders a null element as text rather than throwing;
`HistoriaPojazduParser.damagesFrom` cannot produce a null list element; `capRisk` is integer
arithmetic; and `rank`/`labelFor` are exhaustive enum switches in a single Maven module, so adding a
`VerdictCode` breaks the build rather than the runtime.

**The finding that shapes the change: wrapping it naively would recreate the defect the class was
written to prevent.** `degradeOnThrow` needs a degraded value, and the obvious one — the
un-adjusted `result` — is precisely the state `CepikRiskAdjuster`'s Javadoc describes as the bug it
fixed: *"a vehicle with a registered szkoda istotna came back `risk: 88, verdict: WORTH_CHECKING`
while the panel above it showed the damage — the data was on screen but absent from the
judgement."* On the failure path that would be silent, because unlike the two enrichments the
adjuster has **no vocabulary for "this did not work"**. `LOOKUP_FAILED` and `FETCH_FAILED` reach the
UI and change what it tells the user; a skipped adjustment reaches nothing. So a fail-soft adjuster
would trade a loud 500 for a quiet under-report of risk on a car the registry flagged, which is the
wrong direction for the one guardrail this project treats as non-negotiable.

Whatever the fix is, **the degraded path has to say something**. That is the design question, not
whether to add a `try`.

## Open questions

- What the degraded value is. Candidates: report the failure as a risk flag plus a conservative
  verdict floor, keeping the `FOUND` panel so the user still sees the damage; degrade `cepikResult`
  to `LOOKUP_FAILED` and reuse the existing "check the registry by hand" UI path (but that discards
  the damage records the lookup did retrieve); or leave the 500 on the grounds that loud beats
  quiet.
- Whether the degraded path may call back into the class that just threw. `applyFloor`,
  `moreSevere` and `labelFor` are pure and unrelated to the branches that can throw, but running
  more of the failed component's code on its own failure path needs an argument.
- Whether the verdict label strings get a fourth copy. `labelFor` notes they are already "kept in
  step with `MockAiAnalysisService` and `AnalysisPrompt`'s examples".

## Out of scope

- `AnalysisController` never switching on `CepikStatus` — the third gap from the same session,
  still open, still recorded in `test-plan.md` §8.
