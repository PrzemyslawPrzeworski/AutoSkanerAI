---
date: 2026-09-08T23:23:28+02:00
researcher: Claude Opus 5 (with Przemyslaw Przeworski)
git_commit: eea799dddfc86bbb4fb4675e4bc30899b4a76bdd
branch: main
repository: AutoSkanerAI
topic: "Which of the recorded structural problems are worth fixing, in what target shape, in what order"
tags: [research, codebase, refactoring, technical-debt, architecture, intentionality, verified]
status: complete
last_updated: 2026-09-08
last_updated_by: Claude Opus 5
verification_commit: eea799dddfc86bbb4fb4675e4bc30899b4a76bdd
verification_note: "28 structural claims checked with ast-grep at eea799d — 25 confirmed, 2 refined, 1 refuted and corrected in place; see § Weryfikacja twierdzeń"
---

# Research: which recorded structural problems are worth fixing, in what shape, in what order

**Date**: 2026-09-08T23:23:28+02:00
**Researcher**: Claude Opus 5
**Git Commit**: `eea799dddfc86bbb4fb4675e4bc30899b4a76bdd`
**Branch**: `main`
**Repository**: AutoSkanerAI

## Research Question

`context/changes/analysis-flow-analysis/research.md` documents this repository's technical
debt and structural risks. It deliberately left one question open: **which of those problems
are worth fixing, in what target shape, and in what order.** This document answers it as an
exploration — no refactor is performed here and no decision is taken. The ranking at the end
is a proposal for a separate planning session.

## Summary

Sixteen candidates were enumerated from the source analysis and explored along three axes:
current shape in the code, intentionality in the history, and migration feasibility against
the safety net that actually exists.

The headline result is that **the candidate everybody names first is not the one to do first.**
`analysis` having four jobs and no service layer (C1) is the largest and most-cited problem,
and it is ranked fourth here, because the seam it needs is currently unguarded: under the only
Spring profile any gate or E2E run activates, `MockCepikService` returns `LOOKUP_FAILED`
unconditionally, so `CepikRiskAdjuster`'s 254 lines and the 25 tests covering them are never
executed end-to-end. Extracting a service from the controller could silently drop the adjuster
call and every gate would stay green.

Three findings shaped the ranking more than any individual candidate:

1. **The fake program and the real program were allowed to diverge, and nothing binds them.**
   Zero tests in `backend/src/test` reference any mock bean; there is no shared base class or
   parameterised contract test anywhere (`grep` for `abstract class`, `@Nested`, `extends `
   across the whole test tree returns nothing). `MockAiAnalysisService` inverts the one business
   rule this app must not get wrong, and emits the resulting flag at the wrong severity. This
   is both the cheapest candidate to act on and the prerequisite for three others.
2. **Rationale quality tracks the era exactly, and Era 1's failure mode is misdescription, not
   silence.** Three separate Era-1 planning documents assert reasons that are checkably false —
   one cites a `@Profile("!mock")` convention that did not exist anywhere in the codebase at the
   time, one rests on a validation method that null-checked six containers while sixteen leaf
   fields could be null, one declares the same record's nullability two different ways 55 lines
   apart. A stated rationale in this repo is not evidence until it is checked.
3. **Era 2's characteristic move is to record a gap rather than close it, and none of those notes
   has a forcing function.** The proof is C12: a deprecation with a written removal trigger that
   fired on 2026-06-01, still live 99 days later, still described in `backend/CLAUDE.md` as a
   current plan. Any plan that comes out of this exploration should assume a note will not fire.

Six numbers or claims in the source analysis were **corrected** by measurement; they are listed
in full under "Corrections to the prior analysis" and will be reconciled in place by the
ast-grep verification pass.

## Method and audit trail

The source analysis records problems under several labels — debt table rows, coverage gaps,
blast-radius notes, map corrections. Every recorded problem was enumerated regardless of its
label, then classified by one test: **does fixing it change code structure?** If yes it is a
candidate for this exploration. If no — a missing test, a documentation gap, a deliberate
product deferral — it is not a candidate, but it is kept as an input to feasibility and cost,
because a missing test is exactly what makes a structural change expensive.

**16 candidates.** C1 `analysis` has four jobs and no seam · C2 both backend package cycles ·
C3 `buildResponse` ordering load-bearing and unenforced · C4 REST contract is a hand-written
mirror · C5 four hand-maintained copies of the LLM shape · C6 `fetchStatus` as a raw `String` ·
C7 cross-boundary duplicated constants · C8 frontend-only mileage rule · C9 mock beans as a
parallel program · C10 `ListingFetchService` has no interface or profile · C11 profile polarity
asymmetry · C12 dead structural surface · C13 non-exhaustive `string` switches · C14 no deadline
architecture · C15 two nullability type-design defects · C16 `panels-do-not-import-each-other`
left failing.

**9 non-candidates, kept as cost inputs.** §4.4 rows 2–3 (the accident flag on the wire; the
`vehicle-data.spec.ts` gap) · the five invertible tests of §4.2 · the happy-side-only branches
of §4.3 · the ~8% verifier coverage and pre-push skipping Playwright · the absence of
persistence (deliberate, FR-010 not started) · `environment.prod.ts`'s "orphan" warning ·
the missing S-02 change document · the three map corrections (already applied) · §4.1's four
`NO` rows (closed 2026-09-08 by `cepik-result.component.spec.ts`).

Three read-only exploration agents ran in parallel: current shape, history and intentionality,
migration feasibility. Every Java claim below was verified by `grep`/`find`/`Read`. **Every
claim about an Angular template was verified with `grep`, because `ast-grep` cannot parse
Angular HTML** — that limitation also binds the verification pass in step 3.

Two process facts condition every intentionality verdict:

- **11 GitHub issues, zero pull requests.** `gh pr list --state all` returns nothing; the 11
  issues are exactly the roadmap items. Every commit went straight to `main`. There is no review
  record anywhere in which an alternative could have been weighed, so the only places a rejected
  alternative is ever recorded are a `plan-brief.md` "What We're NOT Doing" row or a `Q-0n` issue.
- **`Q-01` is the one worked example of a recorded rejection** (issue #7, closed 2026-05-31, with
  `llm-analysis-wiring/plan-brief.md:25` recording "Provider abstraction layer | None beyond
  `AiAnalysisService` | YAGNI"). It establishes what a deliberated decision looks like here.
  **Only C14 and C16 have an artifact of that shape.** For C1, C6, C13 and the codegen half of
  C4 the record is not thin but total: no doc, no issue, no commit body, no comment. Given that
  60% of all commits touch `context/changes/`, a topic's complete absence from that corpus is
  itself evidence it was never discussed.

## Detailed findings

### C1 — `analysis` has four jobs and no service layer

**Current shape.** `analysis/` (top level, excluding `llm/`) holds **32** of the backend's
**59** main-tree `.java` files. Sorting them by the job they serve gives **five** groups, not
the four `context/map/repo-map.md:105` names: HTTP surface (6), LLM domain + provider port (12),
listing acquisition + manual entry (7), `cepik`'s domain records + the score fold-in (6),
`market`'s domain record (1). The map collapses the third into the second.

`AnalysisController.java` is 164 lines and is the only orchestrator: `analyze` :44-71 does
request triage, `buildResponse` :73-118 sequences override → two enrichments → score adjustment
→ seller questions, `degradeOnThrow` :143-155 holds the entire resilience policy, `withExtracted`
:157-163 is a private record-rebuilder. There is **no `@Service` between the controller and its
four collaborators** — `AiAnalysisService`, `ListingFetchService`, `CepikEnrichmentService`,
`MarketPriceEnrichmentService` are injected into the controller directly.

The outbound seam already exists: three single-method ports. What is missing is the *inbound*
application-service boundary. `UserOverrides.apply` and `ManualListingComposer` are already
static helpers, i.e. already extracted.

**Intentionality: accidental.** `context/changes/s-01/plan.md:138` specifies the orchestration
into the controller by name — "**Intent**: Dispatch to URL fetch path or text path based on
which field is present" — and writes out the whole if/else contract at `:140`. No service layer
is mentioned, proposed, or rejected: not in "What We're NOT Doing", not in `Q-01`…`Q-03`, not in
the plan review. Every later slice then added a step to that same method rather than a layer:
`d259bdc` inserted `UserOverrides.apply`, `5b7a3b3` inserted `CepikRiskAdjuster`, S-04 and S-05
inserted the two `degradeOnThrow` blocks. The absence is first named in Era 2 and only as a
*cost*, never as a choice (`analysis-flow-analysis/research.md:379`). **No rationale recorded**;
whether a service layer was ever considered is unknown across 96 commits, 11 issues and 8 change
chains.

**Feasibility.** High cost. `AnalysisController.java` has 9 touches and `AnalysisControllerTest`
is the most-edited file in the repo at 11. Crucially, `AnalysisControllerTest:31`'s
`@ActiveProfiles("mock")` is **inert** — the class uses `MockMvcBuilders.standaloneSetup` with
Mockito mocks and never boots a context, so it will not catch a wiring regression. Combined with
C9's finding that the adjuster is unreachable under `mock`, an extraction that dropped the
adjuster call would leave all 286 tests green.

### C2 — both backend package cycles

**Current shape.** Measured by import line, the two cycles are **not the same shape**:

| Edge | Import lines | Files |
|---|---|---|
| `analysis → cepik` | 1 | `AnalysisController.java:3` |
| `cepik → analysis` | 16 | 5 |
| `analysis → market` | 4 | 2 (incl. `MarketPriceContext.java:3,4`) |
| `market → analysis` | 6 | 3 |

In the **cepik** case, `analysis/CepikResult.java` imports nothing from `cepik` — `CepikStatus`
is also filed in `analysis`. The cycle exists **only** through the controller's one wiring
import. In the **market** case the back-edge is at *record level*: `analysis/MarketPriceContext.java:3,4`
reaches into `market` for the two enums that type its own fields, because `MarketPriceStatus`
and `MarketPriceSampleQuality` stayed in `market` while the record did not. One is a wiring
artifact; the other is a misfiled type. `cepik` owns 8 files, `market` 7, `common` 3 — and
**nothing imports `common`** (fan-in 0), though `GlobalExceptionHandler` sits on every response
path.

**Intentionality: accidental, downstream of a deliberate decision about something else.** The
deliberate decision was *scheduling*: `2a068d0` created a shared base commit for S-04 and S-05
explicitly to avoid merge conflicts (`context/archive/2026-06-02-cepik-vin-lookup/plan.md:29,58`).
The package choice appears in the record only as a **bare file path** — `plan.md:68` reads
`analysis/CepikResult.java` with no "why `analysis`". Era 2 then *reproduced* the split:
`MarketPriceSampleQuality` was added to `market/` on 2026-09-03 while `MarketPriceContext`
stayed in `analysis/`, in the very file that imports it. `artifact-2-structure.md:33` names it:
"**The two backend package cycles are one mistake, made twice.**"

**Feasibility: LOW–MEDIUM, and the best-behaved candidate in the list.** There is **no
file-level cycle** to untangle in the cepik case, the moves are compiler-driven (every missed
import is a build error, not a silent defect), and the whole thing reverts as one commit. The
market enums have no imports at all, so they move freely.

### C3 — `buildResponse`'s ordering is load-bearing and unenforced

**Current shape.** `AnalysisController.java:73-118`, 46 lines, three ordering facts:

1. `UserOverrides.apply` at :77, with the reason at :74-76 — the registry lookup and the market
   query must both use the values the user vouched for, not the model's reading of the advert.
2. `ExtractedData extracted = result.extracted();` at :79 exists **only** to satisfy the lambda
   capture rule ("Effectively final for the lambdas below; `result` is reassigned twice"). So the
   first half of the constraint is compiler-enforced *by accident*.
3. `cepikRiskAdjuster.apply` at :95, with the reason at :93-94 — the LLM scored the listing
   before the registry was queried, so the findings must be folded in before anything reads
   scores or verdict. **Nothing enforces this.** Moving :95 above :82 compiles and produces a
   silently wrong score.

What communicates the constraint is three comments plus one accidental compiler check. No sealed
pipeline type, no assertion, no ordering test.

**Intentionality: deliberate constraint; the lack of enforcement is the accidental part.** This
is the best-documented shape in the candidate list, and the documentation exists because
production broke twice. `5b7a3b3`'s commit body gives the incident verbatim: "Production returned
`risk: 88, verdict: WORTH_CHECKING` for a Corolla with a registered szkoda istotna… the data was
on screen but absent from the judgement." Both orderings are Era 2 (2026-08-26), both born from
live defects, both restated in `backend/CLAUDE.md:55` and `:96`. The comments are contemporaneous
with the fixes, not retrofitted. That nothing *enforces* the order is first written down in Era 2
as §5.2's seven order-of-change traps.

**Feasibility.** The stages are already separable — `UserOverrides.apply` and
`CepikRiskAdjuster.apply` are pure `(input) -> output` functions and `withExtracted` is already
the rebuild step. But the same C9 gap applies: with the adjuster unreachable under `mock`, a
pipeline refactor that reordered the stages would not be caught end-to-end. **C9 gates this.**

### C4 — the REST contract is a hand-written mirror with no generator

**Current shape.** `frontend/src/app/shared/models/analysis.models.ts` is 177 lines with **20
exported declarations** = 16 `interface` + 4 `type` alias. 14 of the interfaces mirror
response-side Java records, 2 are request-side — which reconciles the "14 shapes" figure at
`repo-map.md:132`, since that counts response shapes only. The file is simultaneously the API
spec, the compile-time contract for `strictTemplates`, and the only human-readable schema. It
carries **0 (raport: 8)** functions: the 7 exported functions including VIN validation live in
`shared/models/vehicle-data.ts`. `repo-map.md:108` attributes them to the `shared/models/`
*directory*, which is correct; the first draft of this report mis-read it as the file.

**No codegen exists**, confirmed four ways: no `openapi`/`swagger`/`springdoc`/`jsonschema2pojo`/
`typescript-generator` in `backend/pom.xml`; no `*openapi*`, `*swagger*` or `*.schema.json` file
anywhere outside `node_modules`; no `generate`/`codegen` script in `angular.json` or
`package.json`.

**Intentionality: the mirror is deliberate; codegen is unknown — never considered, never
rejected.** `s-01/plan.md:260` specifies the mirror as an intent: "Single source of truth for all
types shared between… **Mirrors the Java record shapes exactly**." But a pickaxe over the whole
history for `openapi`/`springdoc`/`swagger` in `pom.xml` returns nothing, and `context/`'s only
hits are Era-2 map artifacts *stating the absence*. No `Q-0n` issue, no "What We're NOT Doing"
row. Given that `Q-01` shows exactly what a considered rejection looks like in this repo, the
alternative was almost certainly never on the table.

The mirror was maintained deliberately and drifted anyway: 6 of the 9 commits touching
`analysis.models.ts` also touched `backend/src/main/java`, and `88d2658` is a TypeScript-only
edit whose own message calls it a fix. Era 2 mitigated rather than removed the risk, and said so
— `market-price-contract.spec.ts` plus `frontend/CLAUDE.md` § "E2E: one spec, on purpose".

**Feasibility.** The generator option is the expensive one and adds a build-time dependency to a
repo whose deploy path is already unusual (Cloudflare Pages builds from a subdirectory). The
cheap slice is a **drift check**, not a generator: the Java records are already the canonical
schema and one executable witness already exists. The asymmetry is what makes this urgent
rather than merely untidy — per `repo-map.md:132-136`, **Java-first drift is silent and
TypeScript-first drift is loud**, so the dangerous direction is the one no gate watches.

### C5 — four hand-maintained copies of the LLM shape

**Current shape.** Taking the verdict as representative, four copies: the prompt text
(`AnalysisPrompt.java:56-57`, repeated in the worked examples at :89 and :113), the parser DTO
(`AnalysisResponseParser.java:248` `VerdictDto`), the domain records (`Verdict.java` +
`VerdictCode.java:4`), and TypeScript (`analysis.models.ts:1` union + `:122` interface).
`@JsonIgnoreProperties(ignoreUnknown = true)` is on **all six** private DTOs, per-DTO rather than
global — so a seventh DTO added without it would fail loudly on an unexpected field, the opposite
of its siblings' behaviour.

**Intentionality: the DTO layer is deliberate; `ignoreUnknown = true` is a deliberate decision
resting on a premise Era 2 proved false.** The annotation was flipped `false → true` in
`f9e3762` with a stated reason — a provider adding a field should not fail the parse, because
`validateRequired` would catch anything actually missing. That premise was false:
`test-plan.md` §6.7 records that `validateRequired` "null-checked six *containers* while all
sixteen leaf fields could be null." From Era 1 to Era 2, `ignoreUnknown = true` was discarding
silently and the safety net named in its own justification did not exist.

**Feasibility — stopped here deliberately.** The prompt copy is **instructions to a model, not a
type**; unifying the four copies means first deciding what the LLM schema *is*, which is a
business-concept redesign, not a code-structure change. Persistence (FR-010) will add a fifth
copy. The mechanical half — generating the schema block from the enum set — is downstream of
that decision, so this candidate is out of scope for a refactor ranking.

### C6 — `fetchStatus` as a raw `String`

**Current shape.** `AnalysisResponse.java:3` declares `String fetchStatus`; `analysis.models.ts:145`
declares the 4-member union `'ok' | 'url_failed' | 'text' | 'manual'`. Bare literal producer
sites on the wire: **6**, not the 5 the source analysis counts — `AnalysisController.java:55,66,70`
and `AnalysisResponse.java:12,16,20`. `:20` matters because `urlFailed` is the only one of the
three factories with a live caller.

There is also a **third untyped status carrier the priors never name**: `analysis/FetchResult.java`
— `record FetchResult(String status, …)` with `"ok"` at :6, `"url_failed"` at :10 and
`"ok".equals(status)` at :14. It shares the vocabulary but never reaches the wire. Total literal
sites across both carriers: **9**.

Frontend consumption is one branch: `analyzer.component.ts:111` tests `'url_failed'` and nothing
else. `'manual'` is produced by the backend and read by no one.

**Intentionality: accidental, and demonstrably not for want of the idea.** `s-01/plan.md:92`
specifies `String fetchStatus` in the record signature; `:42` calls the wrapper "the contract
between backend and frontend". Meanwhile the same author, in the same era, in the same package,
was doing the opposite conversion with a written intent: `llm-analysis-wiring/plan.md:154` —
"**Intent**: Tighten `severity` from `String` to a `RiskSeverity` enum so callers can reason
about levels", with the compile-time fallout planned for at `plan-review.md:32`. `VerdictCode`,
`EquipmentStatus`, `MarketPriceStatus` and `CepikStatus` are all enums. **That asymmetry is the
finding**: this was an oversight in a wrapper written as plumbing, not a judgement about `String`.

**Feasibility, with a trap.** `fetchStatus` is the **only lowercase enum-shaped field in the
whole contract**. A naive Java enum serialises as `URL_FAILED` and silently kills
`analyzer.component.ts:111` — the frontend stops branching and no test fails, because the
frontend suite asserts against hand-written doubles and the backend suite asserts its own JSON.
This is precisely the failure mode C4 exists to catch, which is why **C4's drift check should
land before C6.** And the deeper problem is conceptual: the field carries three orthogonal
notions — transport outcome (`url_failed`), input mode (`manual`, `text`) and success (`ok`).
An enum makes the four names type-safe and preserves the conflation. **Whether the response
should report how the input arrived is a product question**; the enum is only the mechanical half.

### C7 — cross-boundary duplicated constants

**Current shape — three separate duplications with three different stories.**

1. **`"szkoda-istotna"`, 3 production sites.** `HistoriaPojazduParser.java:41` (the constant),
   the same literal re-typed inside `KNOWN_EVENT_TYPES` at `:61`, and
   `cepik-result.component.html:193`. The constant is consumed at exactly one site (`:185`) —
   so the file that owns it uses it once and re-spells it once.
2. **The three Polish verdict labels, 3 production sites, all backend.** `AnalysisPrompt.java:57`,
   `MockAiAnalysisService.java:59-61`, `CepikRiskAdjuster.java:249-251`. The frontend has **zero**
   production occurrences — labels travel as data.
3. **The risk-flag display limit.** The number `4` lives only in
   `analysis-result.component.ts:47` and `:50`. The backend encodes it as **two comments that
   order their output around it** — `CepikRiskAdjuster.java:126` and `AnalysisResponseParser.java:161`.
   That is worse than a duplicated literal: **nothing can drift-detect a comment.**

**Intentionality: split, and two of the three are deliberate.** The `4` originates in an Era-1 UI
spec (`s-01/plan.md:453`) and became a backend constraint in Era 2 with comments at both
producing sites naming the frontend behaviour. The `KNOWN_EVENT_TYPES` re-spelling has an
explicit and unusual rationale in the parser's javadoc — the set is "a record of what was
observed, not a new mapping… evidence that the vocabulary is the one this parser was written
against", i.e. a **deliberate refusal to deduplicate**, consistent with the verbatim-capture rule
`8870d35` established. The verdict labels are triplicated with the enforcement mechanism stated
as a comment (`CepikRiskAdjuster.java:246`, "Kept in step with `MockAiAnalysisService` and
`AnalysisPrompt`'s examples"); `verification.md:63` grades it "Enforcement is the comment."

The two copies with **nothing written behind them** are `cepik-result.component.html:193` and the
frontend side generally — the side of the boundary no compiler watches. The live consequence is
recorded: the registry renames the event type → the backend correctly degrades to `null` → the
frontend's red highlight silently stops firing.

**Feasibility.** (3) is the actionable one and it is small: declare the limit on the response
(backend-declared, frontend-read) instead of as prose. (1) already has a canary test in the
parser that fires when the government vocabulary changes, so the mechanism for detecting drift
exists for one of the three cases.

### C8 — the mileage-mismatch rule lives only in the frontend

**Current shape.** `cepik-result.component.ts:151-157`, one computed, `max(2000 km, 5%)`
tolerance, registry-higher direction only. Exactly one implementation site: `2000`, `0.05` and
`tolerance` return nothing across `backend/src/main` and only this computed across
`frontend/src`. `CepikRiskAdjuster.java` (254 lines, read in full) contains **no** mileage
comparison — its registry rules are damage caps, damage-vs-claim contradiction handling and
`odometerRolledBack`. The only thing linking the two is prose in `frontend/CLAUDE.md:32`.

**Intentionality: unknown, leaning accidental.** Introduced by `8870d35` as one bullet in a
UI-honesty batch; `48b32dc` tuned the tolerance the same day. The closest thing to a rationale is
`8870d35`'s closing line — "**Verdict and scores still ignore these findings — recorded in
CLAUDE.md, not fixed here**" — which records an unfinished state, not a decision. The moment it
could have moved and did not is `5b7a3b3`, which folded registry findings into the score and
picked five caps: theft, odometer rollback, damage vs accident-free claim, szkoda istotna, no OC.
`odometerRolledBack` — the registry's *own* boolean — got a cap; the registry-vs-listing
comparison is absent, and the commit body does not mention it. Afterwards it is stated four times
as a location plus a maintenance rule and never as a justification. **`roadmap.md:170` files it
under "Carried forward"** — the project's own bookkeeping treats it as an open item. The one
document that reads like a decision is `backend/CLAUDE.md:105` ("One check that looks like it
belongs here does not"); two others contradict that reading.

**Feasibility.** The inputs are already co-located server-side: `CepikRiskAdjuster` receives both
`extracted().mileageKm` and `cepikResult.mileageStamps` at `AnalysisController.java:95`. But
moving the rule into scoring lands it in code **no gate executes** — see C9. So the fix is cheap
and its *verification* is not, unless C9 lands first.

### C9 — the mock beans are a parallel program with nothing binding them

**Current shape.** `MockCepikService.java` is **20 lines** with no conditional of any kind: the
whole body is `return CepikResult.withoutData(CepikStatus.LOOKUP_FAILED, …)`. Since
`CepikRiskAdjuster.apply` returns early on any status other than `FOUND` (:73-75), **the
adjuster's 254 lines are unreachable under `mock` — and `mock` is the only profile any gate or
E2E run activates** (`playwright.config.ts:61`).

`MockAiAnalysisService.java:147` inverts the business rule: the `NO_ACCIDENT_DECLARATION` flag is
gated on `if (!lower.contains("wypadek") && !lower.contains("bezwypadkowy") && !lower.contains("historia"))`,
so a listing containing "bezwypadkowy" — an *unverified seller claim* of no accidents, i.e.
exactly the case the rule targets — **suppresses** the flag. Two further divergences in the same
file: `accidentClaim` is set on only three keywords (:108-113), and the flag is emitted at
**`RiskSeverity.HIGH`** at :150 while `AnalysisPrompt.java:16` and `AnalysisResponseParser.java:180`
both use **`MEDIUM`**. That severity mismatch is not recorded anywhere.

**No shared contract test binds any mock bean to its real counterpart**, established three ways:
(a) `grep` for `abstract class`, `@Nested`, `extends ` across all of `backend/src/test` → **zero**
hits, so there is no shared base class; (b) `grep` for the three mock bean names in
`backend/src/test` → **zero** hits, so no test names a mock bean; (c) the three
`@SpringBootTest` + `@ActiveProfiles("mock")` classes autowire only `ObjectMapper` and never
touch a service bean.

**Intentionality: split three ways.** Mock *fidelity* was debated exactly once, in Era 1:
`context/archive/2026-06-02-market-price-context/reviews/impl-review.md:57-70`, finding F3 —
"Mock returns FETCH_FAILED unconditionally… **the codebase convention is for mocks to return
realistic success data**", resolved "FIXED via Fix A — mock returns OK(45k/55k/70k,
sampleSize=12)." **That rule was applied to the mock under review and never propagated.**
`MockCepikService` still returns `LOOKUP_FAILED` unconditionally. Fidelity was never a
*test-oracle* goal — `test-plan.md` §7 excludes mock output, with the re-evaluation trigger "if
a mock ever encodes business logic rather than a fixture", which the accident-rule inversion
arguably already meets. The production pinning was pure accident: `d97bf30` — "Render pinned
`SPRING_PROFILES_ACTIVE=mock`… F-01, S-04 and S-05 were merged and dark", and `8870d35` adds
"MockCepikService returned empty lists on LOOKUP_FAILED — the same lie."

**Feasibility: LOW, and it is the prerequisite for C1, C3, C8 and C14's verification.** Every
input already exists: the three ports are single-method interfaces (the exact shape a
parameterised contract test consumes), `MockRestServiceServer` is already in use, the
`standaloneSetup`-with-the-real-controller pattern is established, three verbatim registry
fixtures are captured under `backend/src/test/resources/cepik/`, and
`CepikDamageReachesTheResponseTest` already drives captured bytes to an HTTP body. Nothing new
has to be introduced and nothing in `src/main` has to move.

### C10 — `ListingFetchService` has no interface and no `@Profile`

**Current shape.** 143 lines, `@Service` at :22, implements nothing, carries no `@Profile`. It
performs a real DNS lookup for SSRF protection (`DNS_TIMEOUT_SECONDS = 5` at :37, applied at
:71-79) under **every** profile including `mock`. The shape the other three integrations share —
a single-method interface in the owning package, a `@Profile("mock")` bean, a profile-gated real
bean, and a profile-gated `RestClient` companion — is implemented three times in this same tree
and named as the convention in the root `CLAUDE.md`. `ListingFetchConfig` is ungated too.

**Intentionality: accidental, but a *different* seam was deliberately chosen.** The dating is
sharp: the interface+mock pattern shipped `fdd4553` (2026-05-31) and `ListingFetchService` was
created the **next day**, `7fd2591`. The s-01 plan discusses this service's shape ten times and
only ever in socket-stubbing terms — `:19` "must be structured the same way as
`OpenRouterAnalysisService`: accept `RestClient.Builder`", `:103` "Keeping configuration outside
`ListingFetchService` allows tests to inject a plain builder bound to `MockRestServiceServer`",
`:105` "`ListingFetchConfig` — `@Configuration` class (**no profile restriction**)". Zero
mentions of an interface, a mock bean, or a profile. **The seam it did get is real and used**:
`ListingFetchServiceTest` covers 6 failure modes through `MockRestServiceServer`, no interface
needed. What the plan did not notice is that the other three interfaces also serve the *demo*
profile, so the fourth integration silently opted out of `mock` — and under the profile every
gate runs, the URL path is unstubbed. No document anywhere proposes a `MockListingFetchService`.

**Feasibility: 6 files, not 2.** `ListingFetchServiceTest:38` and `RequestTimeoutBudgetTest:83,160`
read static constants off the class, so an interface extraction touches the tests that reach into
it. And **C10 and C11 are mutually blocking**: adding the missing port requires choosing a profile
polarity, and C11's fix changes what that polarity means. Done separately, one will be redone.

### C11 — profile polarity: deny-list for enrichment, allow-list for the LLM

**Current shape.** Twelve `@Profile` annotations in main: **7 allow-list** (`bedrock` ×2,
`openrouter` ×2, `mock` ×3) and **5 deny-list** (`!mock` on `HistoriaPojazduConfig:12`,
`HistoriaPojazduParser:35`, `HistoriaPojazduService:15`, `RealCepikEnrichmentService:29`,
`MarketPriceFetchService:23`). The `AiAnalysisService` port has three implementations covering
exactly three profile names; enrichment has one deny-list gate each. So under a profile named
anything else — `staging`, or `bedrock` misspelled — the five `!mock` beans instantiate and no
`AiAnalysisService` bean exists, so startup fails on an unsatisfied dependency. Default is
`spring.profiles.active=${SPRING_PROFILES_ACTIVE:mock}`.

**Intentionality: unknown as to reason, and the one document that appears to explain it is
factually wrong.** `git grep -n "@Profile" 51db3fb^ -- backend/src/main/java` proves that before
the S-04/S-05 merges **no `!mock` existed anywhere** in the codebase. Yet
`context/archive/2026-06-02-market-price-context/plan.md:288` cites its source as
"`MockAiAnalysisService.java` — profile guard pattern (`@Profile("mock")` / `@Profile("!mock")`)".
**The second half of that pattern did not exist.** The plan invented the convention it claimed to
be inheriting — the highest-confidence conflict in this exploration, because a mechanical check
refutes a document's explicit citation. A coherent reason does exist by inference (`!mock` is the
only single-annotation way to say "the real bean, whichever provider", for an integration with no
provider variants), but nobody wrote it down.

The live consequence is recorded at `research.md:347`: a profile that is neither `mock` nor a
named provider gets enrichment beans and no `AiAnalysisService` → startup failure, and **local
never reproduces it**. Reinforcing that: `AutoskanerAiApplicationTests` boots `@ActiveProfiles("mock")`
only, so **no test in the repo can see a wiring break under `openrouter` or `bedrock`.**

**Feasibility.** Mutually blocking with C10 (above). The target shape — a `mock`/`real` axis plus
an `llm.provider` property with `@ConditionalOnProperty` — is a config redesign whose blast radius
includes the two live deploy targets, and `main` auto-deploys to both. That is the one candidate
family where a mistake reaches production without a gate in between.

### C12 — dead structural surface

**Current shape — three findings.**

1. **`RiskAnalysis*`: 3 main files + 1 test.** The endpoint **is** live — `@RestController`,
   `@RequestMapping("/api/analysis")`, `@PostMapping("/risk")`, a real mapped deployed route —
   and additionally `@Deprecated`. It **is** uncalled: the three type names and the route string
   appear only inside these four files, and the only exercise is its own test. 146 lines, 5 tests
   — deleting it takes the backend suite 235 → 230.
2. **`AnalysisResponse.ok` (:11-13) and `.text` (:15-17) have zero callers** anywhere in main or
   test. Only `.urlFailed` is called. The controller constructs the other two states through
   `new AnalysisResponse(fetchStatus, …)` at :117 with the literal passed in — so the factories
   and the literals are two parallel encodings of the same states, one of them dead. **2 methods,
   0 callers: the cheapest slice in the entire candidate list.**
3. **`ManualListing.priceCurrency` is populated in Java but unreachable from the UI.** Read by
   `ManualListingComposer.java:33` and `UserOverrides.java:48-49`, declared in TS at
   `analysis.models.ts:158` — but `VehicleDataDraft` has no such field and `draftToRequest`
   builds the `manual` object with exactly 8 keys. No user can ever set it. It is also excluded
   from `ManualListing.hasAnyValue()`, which tests every other field — the file already treats it
   as not-a-value. Note a name collision: the live `ExtractedData.priceCurrency` is a different
   field that `UserOverridesTest:95` actually asserts on.

**Intentionality: only the facade has a rationale, and it expired.** `llm-analysis-wiring/plan-brief.md:30`
— "old endpoint stays unbroken for in-flight experiments" — with the removal condition set at
`plan.md:212-230` and `:618`, and `backend/CLAUDE.md` still saying "**deprecated** facade… to be
removed after S-01 ships". **S-01 shipped as `2175a70` on 2026-06-01.** The trigger has been
tracked, unfired, ever since; `roadmap.md` carries the row and `artifact-1-territory.md:169`
confirms it dead. `ok`/`text` are pure residue: `market-price-context/plan.md:171` instructed
replacing `AnalysisResponse.ok(result)` with the full constructor and said nothing about deleting
the factory. `priceCurrency` is half-wired residue from S-02 — **the one feature that shipped
with no plan document at all**, so there is no way to check whether a currency selector was
intended.

**Feasibility: the cheapest candidate, and it shrinks three others.** Deletion is compiler-verified
in both languages. It removes 6 of `analysis`'s 32 files (making C1 smaller before anyone measures
it), 2 of C6's 6 wire-level literal sites, and 1 of C4's 20 exported shapes. The only care needed
is the `priceCurrency` name collision.

### C13 — non-exhaustive `switch` on `string`, next to an exhaustive one

**Current shape.** `analysis-result.component.ts`, 116 lines, five switches. **One exhaustive with
no `default`**: the `VerdictCode` switch at :34-43, whose subject is typed, so a fourth verdict is
a compile error. **Four non-exhaustive**, each taking `(x: string)` with a `default` arm:
`severityLabel` :62-73, `severitySeverity` :75-84, `equipmentLabel` :86-95, `equipmentSeverity`
:97-106. The unions those four should accept **already exist but only inline, unnamed**:
`analysis.models.ts:104` `'CONFIRMED' | 'MISSING' | 'UNCLEAR'` and `:110` `'LOW' | 'MEDIUM' | 'HIGH'`.
`VerdictCode` and `CepikStatus` are named aliases; these two are not — which is exactly why the
parameters could be widened to `string` without anyone noticing.

**Intentionality: accidental. One author, one commit, two styles, no rationale.** Both entered in
`e4e740f` (2026-06-01). **A blame artifact was ruled out**: the case-arm lines attribute to
`4a2907a` (2026-09-04) purely because of the repo-wide prettier sweep, which
`artifact-1-territory.md:99` names as one of eight commits whose spread is an artifact. The design
is Era 1. Two styles written minutes apart makes a design intent implausible and a
copy-paste-with-widened-parameter likely — but "likely" is as far as the evidence goes.

**Feasibility, with the key caveat.** Naming the two unions and using them as the parameter types
is a small, purely frontend change. But **it buys nothing unless the `default` arms are deleted**:
`noFallthroughCasesInSwitch` does not give exhaustiveness, and a `default` arm satisfies the
return-type checker no matter how many union members are unhandled. `verdictClass:34-43` is the
in-repo proof the mechanism works when the `default` is absent.

### C14 — no deadline architecture

**Current shape.** Eight configured timeouts across four config classes, one properties file and
one hardcoded constant: DNS 5 s, listing connect 5 s / read 30 s, OpenRouter connect 10 s / read
30 s, historiapojazdu connect 5 s / read 10 s, and `llm.openrouter.deadline-seconds=70`. `market`
has no timeout config of its own — it shares `listingFetchBuilder`.

**Nothing enforces a request deadline**: `@Async`, `@EnableAsync`, `Executors.`, `TaskExecutor`,
`CircuitBreaker`, `Resilience` return **zero** hits across all of `backend/src/main/java`. The
only concurrency is `CompletableFuture.supplyAsync(...).get(5s)` for the DNS probe, on the common
ForkJoinPool with no configured executor.

**Intentionality: confirmed deliberate — the strongest such verdict in the list.** The NFR is in
`prd.md:98`. Async was deferred through a named review finding (`impl-review F10`) cited in
`roadmap.md`, `test-plan.md` §3 and `research.md` §5.1. The gap is asserted by a test rather than
papered over: `RequestTimeoutBudgetTest` (`12f7fd2`), with `test-plan.md` §6.4 stating the intent
exactly — "**Assert the configured budget against the documented NFR**… It does not claim the NFR
is enforced — it is not." It is an open roadmap question with a named owner ("Is a ~27 s
synchronous analysis acceptable for the MVP…? Owner: user. Block: no"). `research.md` §6 item 8
grades it "Deliberate? **yes**". Era 1 origin, and **Era 2 explicitly revisited it, measured it,
tested the gap, and chose to keep it.**

**Feasibility, and a trap worth stating plainly.** `RequestTimeoutBudgetTest` **pins the gap, not
the goal**: it asserts the total *equals* `Duration.ofSeconds(295)` and is more than 9× the NFR
ceiling, and its own description says the configured budget exceeds `prd.md:98` by roughly 10×
with no deadline enforcing it. So it does **not** become the guard for this fix — it is the first
thing the fix must break, deliberately, and its comment already says what to do when that
happens. All four HTTP clients being `RestClient` beans built in `@Configuration` classes gives
one shared interception point, and `degradeOnThrow` already gives a timeout somewhere to land for
the two enrichment legs.

### C15 — two nullability type-design defects

**Current shape.** **(a) `MileageStamp.date`.** Java `MileageStamp.java:3` makes both components
nullable; TS `analysis.models.ts:6` declares `date: string`, non-optional. The TS type is true
today **only** because of a guard at the single construction site,
`HistoriaPojazduParser.java:215-217` — `if (event.date() != null && km != null)`. So an invariant
asserted by the frontend's type is enforced by an `if` in a parser in another package, with no
comment connecting them.

**(b) `fetchedAt`.** TS `:76` declares it non-null. `MarketPriceFetchService` has **one** degraded
path passing `null` — `missing():154-157`, called from :45 and :51. `CepikResult.withoutData:66`
stamps `Instant.now()` on **every** degraded path. So two records with the same field, on the same
response, disagree about whether a degraded result has a timestamp.

**Intentionality: accidental, and the neighbouring fields prove it was inattention rather than
policy.** `MileageStamp` was declared on both sides in one Era-1 plan, contradicting itself 55
lines apart: `cepik-vin-lookup/plan.md:76` versus `:131`. The adjacent field got an explicit
decision *and* a comment — `DamageRecord.date: string | null`, "Nullable: the backend reads this
from the registry event's date field, which may be absent." `MileageStamp.date` has none.
Likewise `MarketPriceContext`'s own javadoc reasons carefully about `sampleQuality`'s nullability
two fields away and says nothing about `fetchedAt`. `research.md` §3.4 item 5 takes the right
side: "**`cepik` is right; `market` is the defect** — do not 'fix' both."

**Feasibility.** Small and mechanical either way, but note `research.md:376`'s wider finding —
`AnalysisMeta.generatedAt`, `CepikResult.fetchedAt` and `MarketPriceContext.fetchedAt` "are three
separate clocks", so a one-line fix to `missing()` is a patch and a coherent answer is a small
design question. Both halves are changes to both sides of the hand-written mirror, so **C4 gates
this** the same way it gates C6.

### C16 — `panels-do-not-import-each-other`, left failing

**Current shape.** The rule is at `frontend/.dependency-cruiser.cjs:66-77`, `severity: 'error'`,
1 of 7 rules. **Run during this exploration**: 35 modules, 71 dependencies, exactly **2** errors
— `analysis-result.component.ts → market-price-panel.component.ts` and
`→ cepik-result.component.ts` — plus 1 `no-orphans` warning for `environment.prod.ts`. So the rule
fires because `analysis-result` is a **composition root** and the rule cannot express "root may
import leaves".

**Nothing enforces it.** `dependency-cruiser: ^18.2.0` is a frontend devDependency invoked by
**nothing**: no `depcruise` script in `package.json`, not in `.githooks/pre-commit`, not in
`.githooks/pre-push`, and the only GitHub workflow is `workflow_dispatch:` only. The rule can fail
only when someone types the command.

**Intentionality: deliberate constraint, fully explained in writing.** `artifact-2-structure.md:60`
— "**My own layering rule was wrong, and the violation is the finding.** … `analysis-result` is a
**composition root**, not a sibling. … The rule is left in place unchanged and failing, because a
violation that teaches the reader the hierarchy is worth more than a green run." §6 adds: "the
honest fix is to encode the real two-level hierarchy… That is a five-line rule change, and it is
deliberately not made here… **a rule quietly rewritten to match the code teaches nobody
anything.** Fix it in M4-L4 or the first time a third panel is added." The same document applies
identical reasoning to the `no-orphans` warning, so it is a policy and not a one-off.

**Feasibility.** Amending the rule to allow root→leaf and adding an npm script plus a gate step is
far cheaper than restructuring the composition. The one thing not recorded is what happens if
nobody reads the note: the trigger is a message to a future reader, not an enforced condition.

Also measured here, and relevant to C1/C16 both: `cepik-result.component.html` is 286 lines with
roughly **24** lines dereferencing `cepikResult()!.<field>` directly, including the
business-critical `mileageStamps === null` at :128 and four raw status literals. The component
*does* expose view models (`damageState()`, `alerts`, `identityMismatches`), so it is a **partial**
view-model with the template reaching past it for ~24 fields.

## Architecture insights

**Four candidate clusters, not sixteen problems.**

- **"A domain vocabulary exists as text, not as a type"** — C5, C6, C7(2), C13, and C16's four raw
  status literals in the template. `fetchStatus` and the four `switch(x: string)` parameters are
  the same defect on opposite sides of the wire. All are fixed by the same move: name the union
  once, on both sides — which is why C4 sits underneath all of them.
- **"The frontend owns a rule the backend needs"** — C7(3) (the display limit of 4, present in the
  backend only as two comments that order output around it) and C8 (the mileage rule). Both invert
  the intended direction: presentation constrains the domain.
- **"`analysis` owns things it did not build"** — C1, C2, C12. C2's cycles are C1's misfiled-records
  job seen through the import graph, and C12's dead surface is what accumulates in a package with
  no seam to fall outside of.
- **"The fake program and the real program were allowed to diverge"** — C9, C10, C11, and the mock
  half of C15's asymmetry. Three symptoms of one absence: nothing tests that a `mock` bean
  satisfies the same contract as its real counterpart.

**Dependency order among the candidates** (a constraint on any plan, not a plan):

- **C4 gates C5, C6, C13, C15.** Each is a change to both sides of the hand-written mirror; without
  a drift check each fix reintroduces the risk it removes, and the risk is asymmetric — Java-first
  drift is silent.
- **C12 shrinks C1, C4 and C6** by deleting surface before anyone measures it.
- **C2 gates C1.** The records cannot move to a service boundary until they are in the right
  packages, and moving them is also what breaks both cycles.
- **C9 gates C1, C3, C8 and C14 as *verification*.** It does not make them correct; it makes them
  checkable. With the adjuster unreachable under `mock`, every one of those four lands in code no
  gate executes.
- **C10 and C11 are mutually blocking.**
- **C16's rule fix is independent**, but its enforcement gap is shared with the Playwright gap:
  the same missing CI step covers both.

**What "deliberate" looks like in this repo, and why most candidates are not.** The signature is a
`Q-0n` issue or a "What We're NOT Doing" row, a named review finding (F3, F10), a test that pins
the gap, and an owner. **Only C14 has all four**; C16 has the prose equivalent; C9 has a review
finding for one bean out of three. Everything else has a file path. The dominant reason for a shape
is "the plan named a file, not a decision" — C2 (a bare path), C6 (a `String` in a record
signature), C15 (two declarations 55 lines apart), C10 (ten mentions, none about a profile). A plan
format organised by *file* and *contract* records what will exist and is structurally silent about
what was rejected.

**Rationale reliability is era-dependent, and Era 1's failure mode is misdescription.** Era 2
rationales are contemporaneous with a live defect and land in three places at once (code comment +
commit body + `CLAUDE.md`): C3, C7's `4`, C14, C16. Era 1 rationales are plans written before
anything ran, and three of them assert something checkably false: C11's non-existent `!mock`
convention, C5's premise about `validateRequired`, C15's self-contradicting document. Per
`artifact-3-contributors.md:20` the model boundary is the era boundary with no overlapping day, so
"who wrote this" and "was this written before the code ran against a real provider" are the same
question.

**Six conflicts where a stated rationale disagrees with the code or the history**, ranked by
confidence:

1. **C11** — a plan cites a `@Profile("!mock")` convention that `git grep` at that commit proves
   did not exist. A mechanical check refutes an explicit citation.
2. **C5** — `f9e3762`'s justification for `ignoreUnknown = true` names a safety net that
   `test-plan.md` §6.7 shows did not cover leaf fields.
3. **C12** — `backend/CLAUDE.md` still describes the facade as "to be removed after S-01 ships";
   S-01 shipped 99 days ago. The doc reads to a newcomer as a current plan.
4. **C8** — `backend/CLAUDE.md:105` states it as settled architecture; `roadmap.md:170` and
   `8870d35`'s body state it as an open item. History supports the latter.
5. **C15** — `MarketPriceFetchService` contradicts itself inside one record.
6. **C13** — the repo defined `'LOW' | 'MEDIUM' | 'HIGH'` and an enum on the Java side, and the
   component widened it back to `string`.

**One near-conflict that is not one:** C16's rule fails on purpose and says so — but the rationale
lives in `artifact-2-structure.md`, not next to the rule in `.dependency-cruiser.cjs`. Anyone
reading only the tool output would mistake a deliberate constraint for a defect.

## The safety net a refactor would actually run against

Measured, because every cost estimate above depends on it:

| Layer | Covers | Cost |
|---|---|---|
| per-edit `PostToolUse` | `frontend/src/**.{ts,html,scss}` only; **exits 0 silently for backend edits** | 7.78 s wall |
| pre-commit | prettier `--check` on staged frontend sources, frontend suite, backend suite **only when Java or `pom.xml` is staged**; exits early for docs-only commits | `./mvnw -o test` 23.9 s wall |
| pre-push | both suites whole-tree, plus `npm run build` **only for `refs/heads/main`**; **never Playwright** | — |
| CI | one workflow, `workflow_dispatch:` only — nothing gates a push | — |

Suite sizes: backend **235** tests in 25 classes (29 test files, 4 `live-llm`-excluded), frontend
**51** in 5 spec files, Playwright 2 specs run by no gate. Three consequences for planning:

1. **A backend-only refactor gets no per-edit feedback at all** — the first signal is pre-commit.
2. **`main` auto-deploys to both hosts and pre-push is the last gate**, so C11's blast radius
   reaches production with nothing in between.
3. **No test can see a wiring break under `openrouter` or `bedrock`** —
   `AutoskanerAiApplicationTests` boots `mock` only.

Tools that have already failed in this repo, and therefore cannot be a first prerequisite step:
Lefthook (needs a root `package.json`, which does not exist and would touch the Cloudflare Pages
build path), Stryker and `npx vitest related` (plain Vitest has no Angular transform), ast-grep on
Angular templates, and dependency-cruiser's current rule (wired into nothing).

## Refactor opportunities (ranked)

A proposal for the planning session, not a decision. Ranked by **debt cost ÷ change cost**, with
prerequisites respected.

---

### #1 — Bind the mock beans to their real counterparts with contract tests (C9)

**Current shape.** Three `@Profile("mock")` beans that no test references, one of which
(`MockCepikService`, 20 lines, unconditional `LOOKUP_FAILED`) makes 254 lines of registry scoring
unreachable in the only profile any gate runs, and one of which
(`MockAiAnalysisService.buildRiskFlags`) inverts the app's central business rule and emits the
resulting flag at `HIGH` where both real paths use `MEDIUM`.

**Target shape.** One parameterised contract test per port, run against both the mock and the real
bean, asserting the properties that must hold of *any* implementation — chiefly that absence of
accident data never renders as absence of accidents. Plus `MockCepikService` returning a realistic
`FOUND` result, per the rule Era 1 already established for the market mock and never propagated.

**Why it earns first place.** The highest debt cost in the list against the lowest change cost.
Debt cost: the one rule the root `CLAUDE.md` declares non-negotiable is *inverted* in a bean that
served production for months while F-01, S-04 and S-05 were dark; and 254 lines plus 25 tests are
untouched by any end-to-end path, which is what makes C1, C3, C8 and C14 dangerous rather than
merely expensive. Change cost: **nothing in `src/main` has to move** and no new tooling is needed
— the ports are already single-method interfaces, `MockRestServiceServer` and the
`standaloneSetup`-with-real-controller pattern are established, three verbatim registry fixtures
are already captured, and `CepikDamageReachesTheResponseTest` already drives captured bytes to an
HTTP body.

**Blast radius.** Additive in `src/test`. Fixing `MockCepikService` touches one 20-line file, and
the two E2E specs both submit listing text with no VIN, so they reach `MISSING_INPUTS` and are
unaffected by a `FOUND` mock. `test-plan.md` §7's "don't test mock output" exclusion needs
amending, since its own re-evaluation trigger — "if a mock ever encodes business logic rather than
a fixture" — is met.

**Incremental path.** (a) Write the contract test for the `cepik` port against the real bean only,
proving the harness. (b) Add the mock bean as the second parameter and watch it fail. (c) Fix
`MockCepikService`. (d) Repeat for the LLM port, where the accident-rule inversion and the severity
mismatch are the two expected failures. (e) Amend `test-plan.md` §7 and the mock-profile risk-zone
note in `repo-map.md`.

**First prerequisite step.** None — this is the one candidate with no prerequisite, which is most
of why it ranks here. The first *action* is to decide what the contract asserts, and the answer is
already written: the business rule in the root `CLAUDE.md` plus the null-vs-`[]` distinction the
five backend serialisation classes and `cepik-result.component.spec.ts` already defend at both ends.

---

### #2 — Delete the dead surface, then move the two misfiled records (C12, then C2)

**Current shape.** `RiskAnalysis*` — a live, mapped, `@Deprecated`, uncalled endpoint whose stated
removal trigger fired on 2026-06-01 (4 files, 146 lines, 5 tests). `AnalysisResponse.ok`/`.text` —
2 methods, 0 callers, a second encoding of states the controller builds with literals.
`ManualListing.priceCurrency` — reachable from no UI, excluded from `hasAnyValue()`. And two
records filed in `analysis` that belong with their ports, one of which
(`MarketPriceContext.java:3,4`) reaches back into `market` for the enums that type its own fields.

**Target shape.** Those three deletions made; each integration's result record and its status enum
in the same package as its port, with `analysis` importing both directions cleanly.

**Why it earns second place.** It is the only pair in the list that is **fully compiler-driven in
both languages** — every missed reference is a build error, never a silent defect — and each half
reverts as a single commit. That matters more than it sounds: the repo's own history shows that its
characteristic failure is a *silent* one, and this is the candidate where silence is impossible.
It also shrinks three other candidates before they are measured: 6 of `analysis`'s 32 files, 2 of
C6's 6 wire-level literal sites, 1 of C4's 20 exported shapes, and the backend suite 235 → 230.
And C2 is the stated prerequisite for C1.

**Blast radius.** C12: 4 files deleted plus 2 methods plus 1 field, with one care point — the
`priceCurrency` name collision with the live `ExtractedData.priceCurrency` that `UserOverridesTest:95`
asserts on. C2: 2 files move, ~22 import lines update across 8 files, 235 tests recompile. Both are
backend-only, so **neither gets per-edit feedback** — the first signal is pre-commit's 23.9 s
`./mvnw -o test`.

**Incremental path.** (a) `AnalysisResponse.ok`/`.text` — two methods, zero callers, one commit.
(b) `RiskAnalysis*` — 4 files, and update the suite count in root `CLAUDE.md` and both hook labels
in the same commit, since those labels went stale once already. (c) `ManualListing.priceCurrency`,
checking the collision first. (d) `MarketPriceContext` + its two enums into `market`. (e)
`CepikResult`, `CepikStatus`, `DamageRecord`, `MileageStamp`, `VehicleEvent` into `cepik`, which
leaves `analysis → cepik` as the only edge.

**First prerequisite step.** Confirm the `RiskAnalysis*` endpoint has no caller outside this repo.
It is a deployed public route and this exploration has no access logs, so "referenced by nothing in
the repo" is not the same claim. Everything else in this item is unblocked.

---

### #3 — Make the frontend/backend contract drift-detectable (C4)

**Current shape.** 20 exported TypeScript declarations hand-mirroring the Java records, no
generator anywhere, and an asymmetric failure mode: TypeScript-first drift is a compile error,
**Java-first drift is silent** — the backend suite asserts its own JSON and the frontend suite
asserts against hand-written doubles, so a field rename ships green with the panel rendering
nothing. One executable witness exists (`market-price-contract.spec.ts`) and it is wired into no
gate.

**Target shape.** Not a code generator. A **drift check**: the Java records' serialised shape
compared against `analysis.models.ts` by something a gate can run, so the silent direction becomes
loud. Codegen remains the eventual answer and is deliberately not proposed here — it adds a
build-time dependency to a repo whose frontend deploy builds from a subdirectory on every push to
`main`, which is the same objection that ruled out Lefthook.

**Why it earns third place.** It is the highest-leverage item that is not itself a structural fix:
`repo-map.md` §3 ranks this the most expensive coupling in the repo, it has **already nearly
shipped a defect** (`sampleQuality`), and it gates four other candidates — C6, C13, C15 and the
mechanical half of C5 — each of which is a two-sided edit that reintroduces this exact risk while
fixing something smaller. C6 makes the trap concrete: `fetchStatus` is the only lowercase
enum-shaped field in the whole contract, so the obvious Java enum serialises as `URL_FAILED` and
silently kills the one frontend branch that reads it, with no test failing anywhere.

**Blast radius.** Additive: no production code changes. The risk is a false-positive gate that
someone learns to skip, so the check has to be exact about what it compares.

**Incremental path.** (a) Pin the current shape — serialise a fully-populated `AnalysisResponse`
and record it, which the existing serialisation tests already know how to build. (b) Assert the
recorded shape's field names against `analysis.models.ts`, accepting that the comparison is textual
and that **ast-grep cannot help on the TypeScript-in-Angular side**. (c) Wire it into pre-commit,
which already runs the backend suite when Java is staged. (d) Only then take C6, C13 and C15, in
that order, each behind the new check.

**First prerequisite step.** Decide what the check compares — field names only, or names plus
nullability. Names-only is cheap and catches the `sampleQuality` class of defect; nullability is
what C15 actually needs, and it is the harder half. This is the one open design question in the
top three, and it is small.

---

## Considered and not ranked

- **C1 (`analysis` has four jobs and no service layer)** — the largest problem in the list and
  deliberately ranked below all three above. Its debt cost today is moderate (a 164-line controller
  that is readable), its change cost is the highest (the most-edited test file in the repo), and its
  two prerequisites are unmet: C2 must move the records first, and C9 must make the adjuster
  reachable, because otherwise an extraction that drops the adjuster call leaves every gate green —
  and `AnalysisControllerTest`'s `@ActiveProfiles("mock")` is inert, so it will not catch it either.
  **After #1 and #2 land, this becomes the natural next candidate.**
- **C3 (unenforced ordering)** — deliberate, well-documented at all three sites, and currently
  correct. The enforcement gap is real but it is C1's problem in a different shape; doing it
  separately means writing a pipeline type that C1 would then rewrite.
- **C5 (four copies of the LLM shape)** — **stopped on purpose.** The prompt copy is instructions
  to a model, not a type, so the real fix requires deciding what the LLM schema *is* — a
  business-concept redesign. Persistence will add a fifth copy, which is an argument for deciding
  it before FR-010, not for restructuring now.
- **C6 (`fetchStatus`)** — blocked by #3, and its full fix is a product question: the field
  conflates transport outcome, input mode and success, `'manual'` has no consumer at all, and the
  enum makes the four names safe while preserving the conflation.
- **C7 (duplicated constants)** — two of the three duplications are deliberate and commented, one
  of them a documented *refusal* to deduplicate that the verbatim-capture rule justifies. Only the
  display limit of 4 is worth acting on, and it is small enough to ride along with a contract
  change rather than lead one.
- **C8 (mileage rule)** — cheap to move and impossible to verify until #1 lands. Also the one
  candidate where the record contradicts itself about whether it is a decision, so the planning
  session should resolve `backend/CLAUDE.md:105` against `roadmap.md:170` before touching code.
- **C10 + C11 (the missing port; profile polarity)** — mutually blocking, and C11's blast radius
  includes both live deploy targets with pre-push as the only gate in front of them. The right time
  is alongside a deployment change, not on its own.
- **C13 (`string` switches)** — small and blocked by #3. Worth noting for the planner that it buys
  **nothing** unless the `default` arms are deleted; `noFallthroughCasesInSwitch` does not give
  exhaustiveness.
- **C14 (no deadline architecture)** — the one confirmed-deliberate candidate, with a review
  finding, a roadmap question, a named owner and a test pinning the discrepancy. It is a product
  decision about a ~27 s synchronous analysis, not a refactor, and `RequestTimeoutBudgetTest` is
  the first thing its fix must break.
- **C15 (nullability)** — two small fixes behind #3, and `research.md:376`'s "three separate
  clocks" makes the `fetchedAt` half a small design question rather than a one-line patch.
- **C16 (`panels-do-not-import-each-other`)** — deliberately failing as documentation, and the
  cheap fix (allow root→leaf, add an npm script, wire it into a gate) is not a refactor of anything.
  Its real content is that dependency-cruiser is installed and invoked by nothing, which belongs
  with the Playwright gap in a CI item.

## Corrections to the prior analysis

Measured at `eea799d`; to be reconciled in place by the ast-grep verification pass.

| Prior claim | Measured | Where |
|---|---|---|
| `MarketPriceFetchService.missing():154-157` **and** `failed():159` both pass `null` for `fetchedAt` | `failed()` passes `Instant.now()`. **One** null site, not two | `research.md` §3.4 item 5, `verification.md` §2 item 5 |
| `fetchStatus` has 5 bare literal sites | **6** on the wire (`AnalysisResponse.java:20` omitted), plus a third untyped carrier in `FetchResult.java:6,10,14` the priors never name — **9** total | `research.md` §3 |
| `CepikRiskAdjusterTest` has 22 tests | **25** | prior counts |
| `analysis` has four jobs | **five** groups; the map collapses listing-acquisition into the LLM domain | `repo-map.md:105` |
| `AnalysisResponseParser` carries one frontend-constant comment | **two** (:161 and :177) | prior counts |
| The two package cycles are one shape | Two different shapes — cepik's exists only via one wiring import; market's back-edge is record-level | `artifact-2-structure.md:33` |

Three findings the priors do not record at all:

1. **`MockAiAnalysisService.java:150` emits `NO_ACCIDENT_DECLARATION` at `HIGH`** while
   `AnalysisPrompt.java:16` and `AnalysisResponseParser.java:180` use `MEDIUM` — a fourth mock/real
   divergence, on the accident rule.
2. **`AnalysisControllerTest.java:31`'s `@ActiveProfiles("mock")` is inert** — `standaloneSetup`,
   no Spring context.
3. **`dependency-cruiser` 18.2.0 is installed and invoked by nothing**, and **`common` has fan-in
   zero** despite `GlobalExceptionHandler` sitting on every response path.

## Weryfikacja twierdzeń (ast-grep)

Verification pass run at `eea799d` on 2026-09-08, after the report above was written. Scope: the
structural claims the three ranked opportunities rest on, plus every number this report corrects
in the prior analysis. `ast-grep` v0.45.3, invoked as
`npx --yes --package @ast-grep/cli ast-grep run -p '<pattern>' -l <lang>`; counts taken with
`--json=compact` piped through `node` (`JSON.parse(s).length`), because the default multi-line
output makes `wc -l` overcount. **Every zero was confirmed with an independent plain `grep`.**

| # | Claim | Verdict | Evidence | Method |
|---|---|---|---|---|
| 1 | `AnalysisResponse.ok`/`.text` have zero callers; `.urlFailed` has one | **CONFIRMED** | 0, 0, 1 | ast-grep `AnalysisResponse.ok($$$)` etc., `-l java`, over `backend/src`; zeros re-confirmed by grep |
| 2 | No shared contract-test scaffolding anywhere in `backend/src/test` | **CONFIRMED** | `abstract class` 0, `class … extends` 0, `@Nested` 0 | ast-grep `abstract class $N { $$$ }`, `class $N extends $P { $$$ }`; grep confirmation |
| 3 | No test names any mock bean | **CONFIRMED** | 0 hits for the three bean names | grep over `backend/src/test` |
| 4 | The three ports are single-method interfaces | **CONFIRMED** | 1 method each | ast-grep `$RET $M($$$ARGS);` per interface file |
| 5 | `@ParameterizedTest` is already in use in this repo | **REFINED — strengthens #1** | **4 sites, all in `cepik/RealCepikEnrichmentServiceTest.java:58,84,100,113`** | ast-grep `@ParameterizedTest`, `-l java` |
| 6 | `MockCepikService` contains no conditional | **CONFIRMED** | `if` 0, `switch` 0, `?` 0; 20 lines | ast-grep `if ($C) { $$$ }`, `switch ($C) { $$$ }` |
| 7 | `CepikRiskAdjuster.apply` early-returns on any status ≠ `FOUND` | **CONFIRMED** | `CepikRiskAdjuster.java:73` | grep — the guard is one line, no pattern needed |
| 8 | `CepikRiskAdjusterTest` has 25 tests | **CONFIRMED (raport: 22)** | 25 | ast-grep `@Test` over that file |
| 9 | `RiskAnalysis*` is referenced by nothing but its own 4 files | **CONFIRMED** | only the 3 main files + its own test | grep for 3 type names + `api/analysis/risk` over `backend/src`, `frontend/src`, `frontend/e2e` |
| 10 | `RiskAnalysis*` is 146 lines and 5 tests | **CONFIRMED** | 29+11+6+100 = 146; 5 `@Test` | `wc -l`, grep |
| 11 | `ManualListing.priceCurrency` is unreachable from the UI | **CONFIRMED** | `draftToRequest` builds exactly 8 `manual` keys, none of them `priceCurrency`; no occurrence in `vehicle-data.ts` | `Read` + grep. Collision confirmed: `ExtractedData.priceCurrency` is live and rendered at `analysis-result.component.html:66` |
| 12 | Package import edges: `analysis→cepik` 1, `cepik→analysis` 16, `analysis→market` 4, `market→analysis` 6 | **CONFIRMED** | 1/1 file, 16/5 files, 4/2 files, 6/3 files | grep per edge — ast-grep patterns on `import` declarations are not more precise here, since an import is one line |
| 13 | Nothing imports `common`; `common→analysis` is 2 lines | **CONFIRMED** | fan-in **0**; 2 lines in 1 file | grep over `backend/src/main/java` |
| 14 | `analysis/` holds 32 of the main tree's 59 files; `cepik` 8, `market` 7, `common` 3 | **CONFIRMED** | 32, 59, 8, 7, 3 | `find -maxdepth 1 -name '*.java'` |
| 15 | `analysis` serves five jobs, not four | **CONFIRMED (raport: 4)** | 6+12+7+6+1 = 32 | file-by-file assignment; `repo-map.md:105` collapses listing-acquisition into the LLM domain |
| 16 | `analysis.models.ts` has 20 exported declarations = 16 interface + 4 type | **CONFIRMED** | 16, 4, and `grep -c '^export '` = 20 | ast-grep `export interface $N { $$$ }` and `export type $N = $T`, `-l ts` |
| 17 | `analysis.models.ts` carries 8 functions | **REFUTED** | **0**; the 7 exported functions are in `vehicle-data.ts:22,44,52,60,79,83,100` | ast-grep `export function $N($$$) { $$$ }` = 0, grep confirmation. Corrected in place in §C4 |
| 18 | No codegen anywhere | **CONFIRMED** | 0 hits for `openapi\|swagger\|springdoc\|jsonschema2pojo\|typescript-generator` in `pom.xml`; 0 `generate\|codegen` in `package.json`/`angular.json` | grep — ast-grep cannot parse XML or JSON |
| 19 | `fetchStatus` has 6 wire-level literal sites + 3 in `FetchResult` = 9 | **CONFIRMED (raport: 5)** | `AnalysisController:55,66,70`; `AnalysisResponse:12,16,20`; `FetchResult:6,10,14` | grep. Note `AnalysisController:64` also contains `"manual"` but it is **a comment, not a site** — the count excludes it |
| 20 | `market`'s null `fetchedAt` is one site, not two | **CONFIRMED (raport: 2)** | `missing():155-156` passes `null` as the 7th argument; `failed():160-161` and the `INSUFFICIENT_DATA` path at `:75-76` both pass `Instant.now()` | grep + argument-position read of all four `new MarketPriceContext(...)` calls |
| 21 | 12 `@Profile` annotations in main: 7 allow-list, 5 deny-list | **CONFIRMED** | full inventory reproduced; the 5 `!mock` are `HistoriaPojazduConfig:12`, `HistoriaPojazduParser:35`, `HistoriaPojazduService:15`, `RealCepikEnrichmentService:29`, `MarketPriceFetchService:23` | ast-grep `@Profile($V)` = 12, cross-checked by grep listing |
| 22 | `analysis-result.component.ts` has 5 switches, 4 with a `default` arm | **CONFIRMED** | 5 switches (`:35,63,76,87,98`), 4 `default:` (`:70,81,92,103`); the exhaustive one is the typed `VerdictCode` switch at `:35` | ast-grep `switch ($S) { $$$ }`, `-l ts`; grep for `default:` |
| 23 | The two unions the four `string` switches should accept exist only inline, unnamed | **CONFIRMED** | 4 named aliases at `:1,3,59,67`; 2 inline at `:104` (`'CONFIRMED'\|'MISSING'\|'UNCLEAR'`) and `:110` (`'LOW'\|'MEDIUM'\|'HIGH'`) — exactly the two the switches widen to `string` | ast-grep `export type $N = $T` + grep for inline member unions |
| 24 | `dependency-cruiser` is installed and invoked by nothing | **CONFIRMED** | present only as `frontend/package.json:31` devDependency; 0 hits in `package.json` scripts, `.githooks/pre-commit`, `.githooks/pre-push`, `.claude/hooks/post-edit-check.mjs` | grep |
| 25 | The only GitHub workflow is `workflow_dispatch:` only | **CONFIRMED** | `live-market-price.yml:13-14` is the sole workflow | `ls` + grep |
| 26 | Backend suite is 235 tests, green | **CONFIRMED** | `Tests run: 235, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` | **actually ran** `./mvnw -o test` |
| 27 | Frontend suite is 51 tests in 5 spec files | **CONFIRMED** | 5 spec files; 51 once `submit(` is excluded | see the method note below |
| 28 | Headline file sizes: `AnalysisController` 164, `CepikRiskAdjuster` 254, `analysis.models.ts` 177, `MockCepikService` 20 | **CONFIRMED** | 164, 254, 177, 20 | `wc -l` |

**Three method notes, because they change what a future verification pass should do.**

1. **Counting tests with `grep` is invalid in this repo, in both stacks.** A naive
   `grep -c 'it('` over `frontend/src` returns **64** against a real 51 — the 13-count difference
   is entirely `submit(`, which contains `it(` as a substring. On the backend, `@Test` counts
   **212** against a real 235, because `@ParameterizedTest` (4 sites) expands to multiple
   invocations. Both numbers look plausible and both are wrong. The suite sizes in this report and
   in the root `CLAUDE.md` come from running the suites, which is the only method that works.
2. **Reproducing the backend suite reproduced the documented toolchain failure**, which
   independently confirms that `.githooks/common.sh`'s pinning is load-bearing rather than
   defensive: a bare `./mvnw -o test` in this shell dies with `Invalid maximum heap size: -Xmx12g`
   — a message about memory for a problem about Java — because the inherited `JAVA_HOME` is a
   32-bit Java 8 JRE. Sourcing `.githooks/common.sh` first makes it pass. Any plan that runs the
   backend suite outside the hooks has to do the same.
3. **`ast-grep` was the wrong tool for four of these claims and the right one for the rest.** It
   cannot parse Angular HTML templates (so every template claim in this report rests on `grep`),
   nor XML or JSON (so `pom.xml` and `package.json` rest on `grep`), and for single-line facts like
   an `import` declaration or a `@Profile` value it is no more precise than `grep`. Where it earned
   its place is structural counting — interface arity, switch statements, exported declarations,
   method-call sites — and specifically in proving **absences**: `AnalysisResponse.ok($$$)`
   returning 0 is a statement about call sites, whereas the equivalent grep is a statement about
   text.

**Nothing in "Refactor opportunities (ranked)" was changed by this pass, and no intentionality
verdict was revised.** One finding tends to strengthen the ranking rather than disturb it and is
recorded here for the planning session rather than folded into the proposal: claim 5 shows
`@ParameterizedTest` is already used four times, and all four are in
`RealCepikEnrichmentServiceTest` — the test class for the *real* bean of the very port that
opportunity #1 proposes to bind. So #1's "no new tooling is needed" is stronger than stated: the
parameterised harness exists in exactly the file the contract test would grow out of. **Do decyzji
na etapie planowania.**

## Open questions

- **Does `POST /api/analysis/risk` have callers outside this repo?** It is a deployed public route
  and there are no access logs here. This is #2's only prerequisite.
- **Would a third profile name actually fail startup?** C11's consequence is inferred from the bean
  set, not executed. Nothing in the test suite can reproduce it.
- **How often does the `MISSING_INPUTS` market path fire in production?** It is the one path that
  produces the null `fetchedAt`, and its runtime frequency sets C15's real urgency.
- **Should `fetchStatus` report how the input arrived at all?** `'manual'` has no consumer. A
  product answer would shrink C6 from four states to two or three.
- **Was a service layer ever considered?** Not a blocker, but the total silence across 96 commits,
  11 issues and 8 change chains is itself the answer for C1's intentionality verdict, and worth
  confirming with the one person who would know.
