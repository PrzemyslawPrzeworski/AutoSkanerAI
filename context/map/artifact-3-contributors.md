# Artifact 3 — Contributors: who to ask, when there is nobody to ask

**Method.** `git log` over the whole history, `HEAD = cdd19fb`, **96 commits**
(2026-05-24 → 2026-09-04). Artifact 1 measured 94 at `fe32646`; the two extra are this
map's own commits, and the totals cross-check exactly against its two eras.

**This artifact's question has no human answer, and that is the finding.** The prompt asks
for key contributors per area and for bot and agent commits to be filtered out. Applied
literally to this repo it returns one name for every area and then filters out 81% of the
work. So the question is reframed, not skipped:

> There is one human. For each area that a newcomer would need to ask about, **what stands
> in for the person who would have answered?**

Where a named artifact stands in, the area is safe. Where nothing does, that is the real
bus factor. §5 is that table and it is the point of this document.

---

## 1. The support line, as the data actually reads

| Role | Identity | Commits |
|---|---|---|
| author **and** committer of every commit | Przemyslaw Przeworski | **96 / 96** |
| bots, CI, dependabot | none | 0 |
| co-author trailers | Claude, four model identities | **78 / 96 (81%)** |
| no trailer | — | 18 (19%) |

| Co-author trailer | Commits | First | Last |
|---|---|---|---|
| Claude Sonnet 4.6 | 24 | 2026-05-24 | 2026-06-02 |
| Claude Opus 4.7 (1M context) | 8 | 2026-05-31 | 2026-06-02 |
| Claude Opus 4.7 | 6 | 2026-05-31 | 2026-06-02 |
| **Claude Opus 5** | **40** | **2026-08-25** | **2026-09-04** |

**The model boundary is the era boundary, exactly.** Every 4.x commit is in Artifact 1's
Era 1 (build), every Opus 5 commit is in Era 2 (verification), and there is not one
overlapping day. 38 agent commits + 1 solo = Era 1's 39; 40 + 17 = Era 2's 57. The two
artifacts were measured independently and agree.

That collapses the contributor question into a question Artifact 1 already answered.
"Which agent wrote this" and "was this written before the code ran against a real
provider" are the same question. So the provenance signal a newcomer needs is not a name —
**it is a date**, and Artifact 1 already gives the rule: anything dated before 2026-08-25
is a claim, not a verified fact.

### What the 18 trailer-less commits are — and are not

They are **not** evidence of unaided human work. The trailer is a commit-message
convention, not an audit trail, and its absence has three different causes here:

| Cause | Commits | Note |
|---|---|---|
| genuinely mechanical / tool output | `0e5c398` scaffold (41 f), `4a2907a` prettier sweep (23 f), `2ca4c6b` + `9742e96` managed rule-block swaps | a generator or a formatter produced the diff; no author to record |
| bookkeeping | 4 `chore(archive): close …`, `09f46f2` epilogue, 3 one-line `docs(roadmap)` / `docs(test-plan)` updates | moving finished plans, updating a status line |
| **trailer simply omitted** | `d259bdc` (S-02, **19 files, 1351 insertions**), `a64a059` (the e2e contract spec, 9 f), `d179b80` + `5388a75` (the quality gates), `77d5df6`, `d5e9a22` | substantial design work; the omission says nothing about how it was produced |

Do not read `git log --author` or trailer presence as a provenance record in this repo. The
reliable provenance signal is the date.

---

## 2. Authorship by area

Per-area commit counts with the share carrying an agent trailer. `%agent` is a lower bound,
for the reason above.

| Area | Commits | Solo | %agent | Models |
|---|---|---|---|---|
| `ctx:changes` | **58** | 2 | 96% | Sonnet 4.6 ×22, Opus 4.7 ×3, 4.7-1M ×8, Opus 5 ×17 |
| `be:tests` | 40 | 3 | 93% | Opus 5 ×19, Sonnet 4.6 ×10, 4.7-1M ×6, 4.7 ×2 |
| `be:analysis` | 30 | 1 | 97% | Opus 5 ×12, Sonnet 4.6 ×8, 4.7-1M ×5, 4.7 ×4 |
| `docs:CLAUDE` | 25 | 6 | 76% | Opus 5 ×15, Sonnet 4.6 ×3, 4.7 ×1 |
| `ctx:foundation` | 23 | 9 | 61% | Opus 5 ×12, Sonnet 4.6 ×1, 4.7 ×1 |
| `be:cepik` | 12 | **0** | **100%** | Opus 5 ×8, Sonnet 4.6 ×4 |
| `fe:features` | 10 | 2 | 80% | Sonnet 4.6 ×4, Opus 5 ×2, 4.7 ×1, 4.7-1M ×1 |
| `fe:shared` | 10 | 2 | 80% | Opus 5 ×4, Sonnet 4.6 ×3, 4.7-1M ×1 |
| `be:fixtures` | 8 | **0** | **100%** | Opus 5 ×6, Sonnet 4.6 ×1, 4.7-1M ×1 |
| `be:common` | 7 | **0** | **100%** | Sonnet 4.6 ×3, 4.7 ×2, 4.7-1M ×1, Opus 5 ×1 |
| `be:market` | 6 | **0** | **100%** | 4.7-1M ×3, Opus 5 ×3 |
| `fe:cmp/analysis-result` | 9 | 1 | 89% | Sonnet 4.6 ×3, Opus 5 ×3, 4.7 ×1, 4.7-1M ×1 |
| `gates` | 7 | 2 | 71% | Opus 5 ×3, Sonnet 4.6 ×1, 4.7 ×1 |
| `fe:cmp/vehicle-data-form` | 3 | 2 | 33% | Opus 5 ×1 |
| `fe:core` | 3 | 2 | 33% | Sonnet 4.6 ×1 |
| `ctx:other` (do-not-edit foundation) | 5 | 5 | 0% | — |

Two readings:

- **`context/changes/` is the largest area in the repo — 58 commits, 60% of all of them.**
  More than the hottest code area by nearly double. Artifact 1's hot-territory table covers
  code and config and does not list it; this line belongs beside that table. It is not
  overhead, it is the mechanism this project uses instead of a colleague, and it is
  maintained at 96%.
- **The four backend packages with zero solo commits — `cepik`, `market`, `common`,
  `fixtures` — are exactly the four whose defects Era 2 found**: invented CEPiK field names,
  the median that was the upper-middle element, the error-envelope A/B, fixtures that had to
  become verbatim captures. Not a causal claim about agents; a note that these areas have
  never had a commit whose reasoning is recorded anywhere but a change doc, so the change
  doc is load-bearing there in a way it is not elsewhere.

---

## 3. Thematic activity, per contributor

The prompt asks for each person's activity grouped by theme, so support can be routed. With
one human the routing is trivial, so the useful version is the human's own thematic split —
what they have and have not personally driven — followed by the same for each agent
identity, since that is what the trailers actually distinguish.

### Przemyslaw Przeworski — every commit

| Theme | Evidence | What this means for asking |
|---|---|---|
| **product intent and business rules** | the absence-means-unknown rule, the VIN-only form decision, the "no accident data ≠ clean" constraint appear as *requirements* in `prd.md` and CLAUDE.md, upstream of any implementation | the one area where the human is the sole source. No artifact derives these; they are given |
| **environment and infrastructure** | Render + Cloudflare wiring, the `SPRING_PROFILES_ACTIVE=mock` discovery, the corporate SSO / Zscaler constraints, the JDK and heap pinning in `.githooks/common.sh` | facts that live only outside the repo. Ask, or you cannot know |
| **verification decisions** | Era 2's character — "is this actually true in production" — plus every solo `docs(roadmap)` status line | the roadmap and `test-plan.md` §8 record the conclusions but not the judgement calls |
| **review of everything else** | committer on 96/96 | so nothing below is unreviewed, and the human is the escalation point for all of it |

### Agent identities, by what they were pointed at

| Identity | Window | Themes it owns | Where its output still stands |
|---|---|---|---|
| **Sonnet 4.6** | 05-24 → 06-02, 24 cmts | the original build: `be:analysis` scaffolding, the Angular app, the error-shape A/B, `be:config` (5 of 9 commits) | `be:common`'s error envelope, and the 31-of-59 frozen Java files Artifact 1 §5 counted |
| **Opus 4.7 (1M context)** | 05-31 → 06-02, 8 cmts | the two enrichment integrations — 3 of `be:market`'s 6 commits, plus `be:cepik` and fixtures | `market/`'s structure, including the `MarketPriceContext`-in-`analysis` misplacement Artifact 2 §1 found |
| **Opus 4.7** | 05-31 → 06-02, 6 cmts | `be:analysis` and `be:common` refinement inside Era 1 | the exception hierarchy |
| **Opus 5** | 08-25 → 09-04, 40 cmts | all of Era 2: verification, the CEPiK field-name correction, the median fix, mutation testing, the quality gates, the M3-L4 e2e spec, the M4-L1 CLAUDE.md split, and this map | everything dated after 2026-08-25 |

The practical use of this table is inverse to how it reads. It does not tell you who to
ask — it tells you **which explanation to distrust**. An `analysis` or `market` comment
written in Era 1 was authored by a model that had never seen the code run against a real
provider, and Era 2 found four of those explanations to be wrong.

---

## 4. Top 5 areas that would need a contributor

From Artifacts 1 and 2 jointly — high change rate, high fan-in, or a coupling no tool proves.

| # | Area | Why a newcomer would need to ask | Evidence |
|---|---|---|---|
| 1 | **`be:analysis`** | 32/59 files (54%), fan-in 26, hot in both eras, holds both integrations' record types, and owns the enrichment-failure policy in a private helper | A1 §2 centre of gravity, 30 commits; A2 §1 obs. 3, §2 |
| 2 | **the `fe:shared/models` ↔ `be:analysis` contract** | the tightest coupling in the repo, with **zero** edges describing it, no compiler on either side, and a drift that already shipped (`88d2658`) | A1 §4 (×1.7, 60%); A2 §1 obs. 4, §4 risk 1 |
| 3 | **`be:cepik`** | behaviour is defined by an external system nobody controls. 100% agent-authored, 0 solo, and Era 2 found its field names were invented | A1 §2 heating 4→10; A2 §2 (fan-out 16 / fan-in 1) |
| 4 | **`be:common`** | fan-in **0** and on every response path. Nothing in the code says so. Mostly Era-1 work (6 of 7 commits), so its rationale is oldest | A2 §1 obs. 1; A1 §4 (86% / 100% co-change) |
| 5 | **`fe:cmp/vehicle-data-form` + `fe:shared/models/vehicle-data.ts`** | the newest code (Era 2 only), it holds the VIN and mileage rules, and it is the **only shipped feature with no plan, research or review record** | §5 below; A2 §4 risk 3 |

---

## 5. What stands in for the person — the actual answer

| Area | Substitute for a colleague | Is it enough? |
|---|---|---|
| `be:analysis` | `context/changes/llm-analysis-wiring/` and `s-01/` (both full chains: plan-brief → plan → review → change), plus `AnalysisControllerTest` at 11 touches | **Yes.** The most-documented area in the repo. Read the change docs before the code |
| the cross-stack contract | `frontend/e2e/market-price-contract.spec.ts` — an executable witness, not prose. `frontend/CLAUDE.md` § "E2E: one spec, on purpose" says why it is the only one | **Yes, and it is the best substitute in the repo:** it fails when the answer changes. Prose cannot do that |
| `be:cepik` | `context/archive/2026-06-02-cepik-vin-lookup/` (research + plan + review) and the **verbatim fixtures** under `backend/src/test/resources/cepik/`. The fixture rule — add no field mapping without a captured payload — is the rule that replaced a person's memory of the API | **Yes.** The fixture *is* the documentation; it is why the invented-field-name defect could be found at all |
| `be:common` | `context/changes/ab-experiment-error-shape.md` — and the co-change data proves it was used: `be:common` + `ctx:changes` co-changed **7 times at 100% confidence**. The error shape was never changed without a change doc | **Yes**, and this is the cleanest case in the repo of a process substituting for institutional memory |
| **`fe:cmp/vehicle-data-form`** | **nothing in the change chain.** See below | **No** |

### The one real gap: S-02 has no change doc

`d259bdc` (2026-08-26) shipped manual field entry: **19 files, 1,351 insertions, the largest
single feature commit in the repo.** There is no `context/changes/s-02/`, and there never
was — no plan-brief, no plan, no research, no review. Every other shipped feature has the
full chain (F-01, S-01, S-04, S-05). It is also one of the trailer-less commits, so nothing
records how it was produced either.

Two things keep this from being alarming, and they are worth stating so the gap is not
overstated:

1. **It is well tested.** The same commit added `UserOverridesTest` (128 lines),
   `ManualListingComposerTest` (77), +117 lines to `AnalysisControllerTest` and +189 to
   `analyzer.component.spec.ts`.
2. **Its reasoning was written down — just not in the change chain.** It went straight into
   CLAUDE.md (+25 lines in that commit), and now lives in `frontend/CLAUDE.md` § "Vehicle
   data form" and `backend/CLAUDE.md` § "Manual entry and user overrides". The VIN-only
   rationale, the three-mode form, the deliberate non-400 for a malformed VIN — all present.

What is genuinely lost is the **alternatives**. A change doc records what was considered and
rejected; a CLAUDE.md section records only what was decided. The one clue that something was
rejected is a sentence in `frontend/CLAUDE.md`: *"An earlier single drawer labelled by field
name read as a pile of optional boxes and was rebuilt for exactly that reason."* That is the
whole record of a redesign. **If S-02's form is ever revisited, expect to re-derive why the
drawer failed.**

Not a task for this lesson. Retrofitting a change doc after the fact would be fiction, and
mapping does not fix what it maps.

---

## 6. Corrections to Artifacts 1 and 2

Written while checking claims for this artifact; all three were mine, and they are the kind
of error the contributor question surfaces because there is nobody to catch it. **Each is
also corrected in place at its source** — a map that knowingly carries a wrong statement is
worse than no map — and recorded here so the correction itself is not lost.

1. **`shared/models` is not type-only.** Artifact 2 §1 calls it "the right shape for a
   type-only module", and `frontend/.dependency-cruiser.cjs`'s header comment calls the
   folder "types and constants, no behaviour". `vehicle-data.ts` exports **8 functions**,
   including `vinError`, `normaliseVin`, `prefillFromExtracted` and `draftToRequest`. The
   Ca=11/Ce=0/I=0% metric is correct and my prose around it was not. The dep-cruiser rule
   still passes because it only forbids reaching into `core`/`features`, which is a weaker
   claim than the comment makes.
2. **"No spec" did not mean "untested", and I nearly wrote that it did.** No spec file
   imports `vehicle-data.ts`. But `analyzer.component.spec.ts` exercises its logic
   substantially through the component — malformed VIN blocks submission, VIN-only
   submission succeeds, `' nmtbz3be40r000000 '` normalises to uppercase and trimmed, prefill
   leaves `vin` empty. The correct finding is narrower and still worth having: **8 pure
   functions are covered only transitively, so their coverage is coupled to the component's
   rendering.** A direct spec would be cheap. This is the same inference Artifact 2 §7 warns
   about for orphans, and I made it one section later.
3. **`context/changes/` belongs in Artifact 1's hot table.** At 58 commits it would top it.

---

## 7. Limits

1. **The prompt's filter, applied honestly, removes most of the repo.** 81% of commits carry
   an agent trailer and none has "wyraźne autorstwo człowieka" in the sense the prompt means
   — a second human reviewer. Filtering them out leaves 18 commits, most of them
   bookkeeping. The reframe in §5 is a substitution, not a substitute; a reader who needs the
   original question answered should know it returned one name.
2. **Trailer presence is not provenance.** See §1. It is a convention, self-reported, and
   demonstrably omitted on substantial commits.
3. **"12 months" is 15 weeks, 12 active days.** No contributor has drifted away, nobody has
   forgotten anything yet, and no bus factor has been tested.
4. **Model identity is not capability evidence.** The Era-1/Era-2 defect pattern is confounded
   completely with *when* the work happened and whether a real provider was reachable.
   Nothing here supports a claim about the models.
5. **`ctx:other` shows 0% agent because it is the do-not-edit foundation** — PRD, tech-stack,
   shape-notes, hand-placed. A 0% there means "not authored here", not "hand-written".
6. **Area attribution is by file path**, the same mapping as Artifact 1, so a commit spanning
   five areas counts once in each. Column totals exceed 96 by design.
