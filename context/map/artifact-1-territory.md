# Artifact 1 — Territory: where this project actually lives

**Method.** Git history only, `HEAD = fe32646`, whole history (2026-05-24 → 2026-09-04,
94 commits). Noise filtered out of every count: `package-lock.json`, the Maven wrapper
and `.mvn/`, `dist/`, `target/`, `.gitignore` / `.gitattributes` / `.editorconfig` /
`.prettierrc`. 250 distinct non-noise files were touched; 213 are tracked today.

This artifact says **where work happened**, nothing about whether the code is good.
Read it with the two limits in §6.

---

## 1. The shape of the history: two eras and an 84-day gap

The lesson's prompt asks for a per-quarter breakdown. That is not the natural cut here.
The project has **12 active days across 15 calendar weeks**, in two bursts:

| Era | Dates | Commits | Active days |
|---|---|---|---|
| **Era 1 — the build** | 2026-05-24 → 2026-06-02 | 39 | 6 |
| *(gap)* | 2026-06-03 → 2026-08-24 | 0 | **84 days dormant** |
| **Era 2 — the reckoning** | 2026-08-25 → 2026-09-04 | 55 | 6 |

`git log --format=%ad --date=format:'%Y-W%V'` → W21:2, W22:12, W23:25, **nothing**, W35:23, W36:32.

This matters more than any file count. **Every "how does this work" answer older than
2026-08-25 was written before the code ran against a real provider.** Era 2 opens with
`SPRING_PROFILES_ACTIVE=mock` being discovered pinned in production, and the era's
character is verification, not construction: the CEPiK parser's field names turned out
to be invented, the market-price median was the upper-middle element, the pre-edit
prettier hook had been dead since May.

**A date before 2026-08-25 on a doc or a comment is a claim, not a verified fact.**

---

## 2. Hot territory

Areas by number of commits touching them (packages and Angular feature folders, not
top-level dirs — `backend/src/main` alone at 160 was too coarse to be useful):

| Area | Era 1 | Era 2 | Trend |
|---|---|---|---|
| `<root>` (mostly `CLAUDE.md`) | 10 | 24 | **heating up** |
| `backend:analysis` | 18 | 15 | steady — the hottest code area overall (33) |
| `context:foundation` | 3 | 20 | **heating up** (`test-plan.md`, `roadmap.md`) |
| `backend:cepik` | 4 | 10 | heating up |
| `backend:config` (`pom.xml`, properties) | 7 | 3 | cooling |
| `frontend:features` | 6 | 4 | steady |
| `frontend:shared` (the contract mirror) | 4 | 6 | steady |
| `backend:market` | 3 | 6 | steady |
| `backend:test-fixtures` | 2 | 6 | heating up |
| `backend:common` | 6 | 1 | cooling — settled after the error-shape A/B |
| `frontend:cmp/vehicle-data-form` | 0 | 3 | **new in era 2** (S-02) |
| `frontend:e2e` | 0 | 1 | **new in era 2** (M3-L4) |

Top files, all-time:

| Touches | File |
|---|---|
| **25** | `CLAUDE.md` |
| 13 | `context/foundation/test-plan.md` |
| 11 | `backend/.../analysis/AnalysisControllerTest.java` |
| 9 | `context/foundation/roadmap.md`, `frontend/.../analyzer.component.ts`, `frontend/.../shared/models/analysis.models.ts`, `backend/pom.xml`, `backend/.../AnalysisController.java` |
| 8 | `frontend/.../analysis-result.component.html`, `backend/.../RealCepikEnrichmentService.java` |

**One area is missing from the table above**, added after Artifact 3 measured it:
`context/changes/` was touched by **58 commits — 60% of all of them**, which would top this
table by nearly double. It is excluded from "hot territory" here because it is not code and
a change doc is *supposed* to change with every change. Artifact 3 §2 explains why the number
still matters: it is this project's substitute for a colleague.

**`backend:analysis` is the centre of gravity.** It holds the controller, the LLM
providers, the parser and `CepikRiskAdjuster`, and it is the only area that was hot in
both eras. Anything landing there is landing in the busiest room in the house.

---

## 3. The common denominator — and why it was just dismantled

The prompt asks whether one file changes alongside many unrelated areas. There is, and
it is not close:

| File | Touches | Distinct other areas it co-changed with |
|---|---|---|
| **`CLAUDE.md`** | 25 | **20** |
| `frontend/src/app/shared/models/analysis.models.ts` | 9 | 17 |
| `backend/pom.xml` | 9 | 16 |
| `frontend/src/app/app.config.ts`, `app.routes.ts`, `app.ts` | 3 each | 19 / 19 / 17 |

`CLAUDE.md` was touched in **27% of all commits** and co-changed with 20 of the ~25 areas
in the repo. That is the signature of a file every change has to visit — exactly the
monolith M4-L1 diagnosed, now measured rather than asserted. As of `fe32646` it is split
into root (158 lines) + `backend/CLAUDE.md` (150) + `frontend/CLAUDE.md` (67), so the
next 25 touches should distribute across three narrower couplings instead of one wide
one. **That is a prediction this artifact makes and a future refresh can falsify.**

`app.config.ts` / `app.routes.ts` / `app.ts` are a **false positive worth naming**: 3
touches each, but two of those were the initial scaffold (`0e5c398`, 32 files) and the
prettier sweep (`4a2907a`, 23 files). Their spread is an artifact of large commits, not
of coupling. The eight largest commits are `0e5c398` (32), `4a2907a` (23), `fdd4553`
(23), `63cdf6a` (19), `d259bdc` (19), `8870d35` (19), `512f555` (17), `5de1ce9` (16) —
discount any co-change that only appears inside these.

**Every file named in this artifact still exists in the tree** (checked against
`git ls-files`, per the prompt's own warning). Nothing here is coupling advice about a
deleted file.

---

## 4. Real co-changes

Raw co-occurrence favours big areas, so both raw count and **lift** (how much more often
than chance) are given. `conf` is the higher of the two conditional probabilities.

| Pair | n | lift | conf | Reading |
|---|---|---|---|---|
| `fe:analysis-result` + `fe:features` | 6 | **×6.3** | 67% | the result panel and its host component are one unit in practice |
| `<root>` + `be:resources` | 4 | ×4.6 | 44% | profile/property changes always came with a doc change |
| `fe:features` + `fe:shared` | 4 | ×3.8 | 40% | the contract mirror moves with the component that renders it |
| `be:cepik` + `be:fixtures` | 4 | ×3.4 | 50% | the captured-payload rule in action |
| `be:analysis` + `be:fixtures` | 7 | ×2.5 | 88% | ditto |
| `be:analysis` + `be:common` | 6 | ×2.4 | 86% | every new exception lands in the shared error envelope |
| `be:common` + `ctx:changes` | 7 | ×1.8 | **100%** | the error shape was never changed without a change doc — the A/B experiment |
| **`be:analysis` + `fe:shared`** | 6 | ×1.7 | 60% | **the cross-stack contract** — see below |
| `CLAUDE.md` + `gates` | 4 | ×2.1 | 57% | a gate change was always documented |

### The one coupling that has no compiler behind it

`frontend/src/app/shared/models/analysis.models.ts` is a **hand-written mirror** of the
Java records. Nothing enforces the mirror. The history:

- 9 commits touched the model file.
- **6 of those also touched `backend/src/main/java` in the same commit** — the mirror
  was maintained deliberately.
- Of the 3 that did not: `4a2907a` is the prettier sweep (harmless), `8706dd9` is the
  original scaffold, and **`88d2658` ("fix `DamageRecord.description` nullability") is a
  one-line edit to the TypeScript side with no backend change in that commit.**

So the drift this coupling risks is not hypothetical — it has already happened once, in a
commit whose message frames it as a fix. This is the risk `frontend/e2e/market-price-contract.spec.ts`
exists for, and the git history independently confirms the choice.

### Coupling by regeneration vs by hand

The prompt asks that these be separated. **Here they are all hand-edited.** There is no
codegen step in the repo: no OpenAPI generator, no schema-to-TS pipeline, no snapshot
fixtures. `backend/src/test/resources/cepik/*.json` and `market/*.md` change only when a
new payload is *captured*, which is a manual, deliberate act (and is required to be — see
`backend/CLAUDE.md`). So no coupling here is of the cheap "regenerate and move on" kind;
every co-change in the table above cost someone an edit.

---

## 5. Frozen territory

89 of 213 tracked files (42%, `context/archive/` and the Maven wrapper excluded) have not
been touched since before the 84-day gap. In `backend/src/main/java` it is **31 of 59
(53%)**.

**Most of that is settled, not rotten.** A Java record or enum that stopped changing is a
contract that stabilised: `RiskFlag`, `VerdictCode`, `CategoryScores`, `ExtractedData`,
`MarketPriceStatus`. Age is not decay for a type.

Three frozen things are worth a second look:

| Frozen since | What | Why it matters |
|---|---|---|
| 2026-05-24 / 05-31 | `RiskAnalysisController` + `RiskAnalysisRequest` + `RiskAnalysisResponse` + `RiskAnalysisControllerTest` | **Confirmed dead.** `CLAUDE.md` calls `POST /api/analysis/risk` "deprecated, to be removed after S-01 ships"; S-01 shipped. A repo-wide grep finds these four files referenced by *nothing but each other* — no frontend caller, no e2e spec. 4 files and part of the 235-test count, defending an endpoint no client uses. |
| 2026-06-02 | `BedrockClaudeAnalysisService` + `BedrockConfig` | A complete second LLM provider, untouched for three months, that **cannot run in production by design** — the only AWS credential source is a short-lived corporate SSO profile. It is dev-only on purpose, but nothing exercises it, so it is a maintenance surface with no live signal. |
| 2026-06-02 | `MockAiAnalysisService` | Frozen and that is *correct*. Named here only so a future reader does not mistake it for rot. It is content-sensitive (one word moved `overall` 41 → 35), but **no test uses it as an oracle** — the e2e spec asserts no scores and the backend tests use hand-written stubs, so the sensitivity is latent, not live. Corrected 2026-09-04; see `context/changes/analysis-flow-analysis/research.md` §6. |

---

## 6. Limits — what this artifact does NOT tell you

1. **The window is the whole history, not 12 months of steady work.** 94 commits over 12
   active days. A "hot" area here means 15–30 commits, not thousands; ranks are meaningful,
   magnitudes are not, and one busy afternoon can create a hot spot.
2. **Commit counts measure attention, not quality or risk.** `backend:analysis` is hot
   because it is where the product lives *and* because it is where the bugs were. Nothing
   here distinguishes those.
3. **One human author.** There is no bus-factor signal in this data; that question is
   Artifact 3's, and it is largely degenerate for this repo.
4. **No structural evidence at all yet.** Everything above is co-change in time. Whether
   two areas actually *import* each other is Artifact 2 — and note in advance that the
   backend is Java, which `dependency-cruiser` cannot see. Where that happens the answer
   is `unknown`, never "no coupling".
5. **`context/archive/` was excluded** from the frozen-file scan. It is read-only by
   convention, so "untouched" there carries no signal.
