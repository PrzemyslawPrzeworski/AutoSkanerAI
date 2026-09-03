---
change_id: testing-availability-failure-paths
title: Test rollout Phase 2 — availability and failure paths
status: impl_reviewed
created: 2026-09-03
updated: 2026-09-03
archived_at: null
---

## Notes

Open a change folder for rollout Phase 2 of context/foundation/test-plan.md: "Availability and failure paths".

Goal: prove every provider and fetch failure ends in an honest, distinguishable outcome inside the time budget, and that a thin price sample labels itself.

Risks covered:

- Risk #1 (High impact / High likelihood) — User waits out the full analysis and gets nothing back: the provider pool is saturated or a slug was retired, or the request runs past the 30 s budget.
- Risk #5 (Medium impact / High likelihood) — A price sample too thin or too dispersed to mean anything is presented as a market range the buyer trusts.
- Risk #6 (Medium impact / Medium likelihood) — Listing text written to game the analyser (an accident-free assertion, or instructions aimed at the model) produces a reassuring verdict.

Test types planned: unit + integration over a stubbed HTTP edge, plus live-tagged tests only where a real outcome is assertable.

Risk response intent:

- Risk #1: prove a saturated or retired provider ends in a distinguishable, user-visible outcome — never a hang, never a success shape carrying empty content; challenge "a 200 from the provider means we have an analysis" and "a retry always helps"; avoid asserting the retry count instead of the user-visible outcome.
- Risk #5: prove a sample too thin or too dispersed to be a market range is labelled as such rather than displayed as a confident range; challenge "a number came back, so the range is meaningful"; avoid re-deriving the expected median with the production formula.
- Risk #6: prove listing-supplied claims cannot move the deterministic floor that registry facts set; challenge "the model will obviously ignore manipulation"; avoid an eval asserting a specific model wording — non-deterministic and expensive for the signal.

Inherited from Phase 1: §6.2 documents the MockRestServiceServer stubbing seam this phase reuses (bindTo(RestClient.Builder), ordered expectations by default, four gotchas). Two gaps were carried forward into this phase and are in scope: HistoriaPojazduSession's cookie merge and dedupe (extractCookies replaces a same-named cookie rather than appending) and its XSRF extraction — both exercised only incidentally today, neither with dedicated coverage.

Also fold into this phase's plan: §4's "Stack grounding tools" note is wrong. It records Docs and Search as "not available in current session" (checked 2026-08-27), but Context7 and Exa are both available now. The final sub-phase should correct those two lines and re-date them.
