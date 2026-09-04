---
change_id: analysis-flow-analysis
title: Analysis-only study of the end-to-end analysis flow (M4-L3)
status: complete
created: 2026-09-04
updated: 2026-09-04

archived_at: null
---

## Notes

**This change ships no code.** It exists to hold two artifacts — `research.md` (the flow, its
coverage and its blast radius) and `verification.md` (every structural claim in it put through
`ast-grep`: 22 confirmed, 5 refined, 0 refuted) — a description of the *current* state of the
end-to-end analysis flow.

It also carries three corrections back to their sources, because a map that knowingly holds a
wrong statement is worse than no map: `repo-map.md` §3 (the mock-oracle risk was overstated;
the drift it describes is closed; the REST risk is asymmetric),
`artifact-1-territory.md` §5 (same mock correction) and `backend/CLAUDE.md` (three
historiapojazdu calls is five). See `research.md` §6. Nothing here is a
plan, a commitment, or unimplemented work. If you are looking for something to build,
this is not it; see `context/foundation/roadmap.md`.

Produced for 10xDevs Module 4, Lesson 3 (feature analysis against the repo map), which
asks for a traced flow, a coverage picture, and a blast radius, with every structural
claim verified by `ast-grep` rather than asserted.

**Scope adaptation, recorded so the deviation is visible.** The lesson's prompt targets
"the post-saving flow" — an entry point traced through the layers *to a write and back*.
This repo has no persistence at all (no database; S-03 is future roadmap work), so there
is no write to trace. The substitute chosen, with the user's agreement, is the full
analysis request flow: `POST /api/analyses` from the Angular form through the LLM and both
enrichment integrations and back to the rendered panels. It is the only flow that spans
both stacks, so it crosses the coupling `context/map/repo-map.md` names as risk #1. The
absence of a persistence layer is reported as a finding rather than skipped.

Reads on top of `context/map/repo-map.md` and its three evidence artifacts; it does not
repeat them.
