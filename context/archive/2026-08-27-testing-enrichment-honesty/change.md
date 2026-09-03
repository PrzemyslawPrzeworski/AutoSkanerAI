---
change_id: testing-enrichment-honesty
title: Test rollout Phase 1 — enrichment honesty (registry damage reaches payload and verdict)
status: archived
created: 2026-08-27
updated: 2026-09-03
archived_at: 2026-09-03T13:08:18Z
---

## Notes

Open a change folder for rollout Phase 1 of context/foundation/test-plan.md: "Enrichment honesty".
Goal: prove a real registry damage reaches both the payload and the verdict, over verbatim captures.
Risks covered:
- Risk #2 (High impact / High likelihood) — The vehicle-history panel shows, or lets the user infer, "no reported damage" for a car the registry says carries a registered significant damage.
- Risk #3 (High impact / Medium likelihood) — Registry findings never reach the verdict, so a car with a registered significant damage still shows a reassuring score and label.
Test types planned: unit + integration.
Risk response intent:
- Risk #2: prove that a captured registry payload carrying a significant damage surfaces as a reported damage all the way out to the API response; challenge "the field mapping is right because the tests are green"; avoid fixtures composed to match the parser — that is the exact 2026-08-26 failure.
- Risk #3: prove that a registry damage caps the risk score and downgrades the verdict regardless of what the listing claimed; challenge "the model already scored the risk, so the later adjustment is cosmetic"; avoid lifting the ceiling values out of the implementation and calling them the expected result.
After creating the folder, follow the downstream continuation rule: suggest /10x-research next unless there is a blocker.
