# Evals for the code-review prompt — Implementation Plan

## Overview

Build the first eval configuration over `packages/code-reviewer`: one prompt, three models,
planted flaws, an LLM judge for the part only prose can answer, and code for everything
else. The harness is promptfoo, called as a library through a file provider; the reviewer is
the `ai-sdk` runner.

Two things make this more than wiring. First, the runner reversal it rests on is authorised
by one of `pick.md`'s five conditions but **not yet backed by a measurement of what that
condition promises to fix** — so phase 1 measures before anything is built on top. Second,
this repo's recurring failure mode is a gate that reports success while measuring nothing,
and this change contains four fresh instances of that shape at once (a reachable answer key,
a non-recursive spec discovery, a near-free `verdict === 'fail'` assertion, and an empty
`accessedPaths` that looks like strictness). Every phase below is designed against that
shape rather than merely aware of it.

## Current State Analysis

`packages/code-reviewer` was built to be evaluated, and its own design notes say so:
`agent.ts:4-9` states that `reviewDiff` is a plain async function *because* "a promptfoo
custom provider is a module whose `callApi(prompt, …)` is handed a string and must return
`{ output }`". `schema.ts:13` names "the promptfoo evals that compare models against one
shape" as its third consumer. Both runners satisfy one exported `Reviewer` type
(`reviewer.ts:115`); the verdict is arithmetic taken away from the model (`verdict.ts:22-27`);
`index.ts` is the only module permitted to touch stdio or exit.

What exists, and what its origin is:

- **Model, key, base URL and timeout are all injectable** (`agent.ts:247`, `:262`, `:271`,
  `:165-168`). Origin `code`, and it is the seam the eval uses.
- **The prompt has no seam at all.** `agent.ts:171` hard-codes `instructions: SYSTEM_PROMPT`;
  the only parameterisation is `buildSystemPrompt(tools: ToolNaming)`, varying tool *names*
  (`prompt.ts:91`). This is a deliberate product rule, not an omission — `prompt.ts:30-31`:
  "A runner supplies names and picks a channel; it does not supply sentences." **Consequence
  for this change: "one prompt, three models" is buildable exactly as the lesson asks; "eval
  a prompt change" is not, and this plan does not add that seam.**
- **`options.tools` is broken.** `agent.ts:263` builds `{tools, accessedPaths}`; `:267`
  substitutes `options.tools`; `:358` still validates citations against the path set from the
  **unused** line-263 tools. A caller using the seam gets `accessedPaths: []`, every citation
  stripped, and **no error**. Origin `code`; verified by reading both ends.
- **`deniedTools` is declared and never filled on this runner.** `reviewer.ts:106` declares
  it, and `:94-99` documents the measured reason it exists: with the containment hook removed
  the model quoted `.env`'s first line while `accessedPaths` stayed `[]` in **both** arms — so
  the access log provably cannot see a containment break, and the refusal record is the only
  externally visible difference. `tools.ts:165` and `:173` return `{denied, reason}` into a
  message history `agent.ts` discards; `agent.ts:369` returns no `deniedTools` at all. Origin
  `code`.
- **No sampling control.** `createReviewAgent` passes `model`, `instructions`, `tools`,
  `toolChoice`, `stopWhen` — no `temperature`, no `seed`. Origin `code`; this plan does not
  add one, because a `temperature` on the reviewer would change the artefact under test.
- **There must be no `output:` on the agent, ever.** `agent.ts:152-157` records that
  structured output sends `response_format` in the same request as `tools`, and a model under
  constrained decoding cannot emit a tool call — "the agent answered in one step, having read
  nothing, every single time." `tool-loop-agent/change.md:110-112` has the tally (1 step / 0
  files vs 3 steps / 15 files). Origin `product`, measured.
- **The gate makes no model call and needs no credential**, measured at
  `agent-sdk-reviewer/change.md:251-255`. `pick.md:240-243` records the comparison harness
  being *deleted* with its phase commit "because a committed comparison script would join the
  commit gate and the gate must make no model call." Origin `product`. This is the hardest
  constraint on where eval files may live.
- **Two structural blind spots in the gate itself.** `scripts/run-tests.mjs:34-42` enumerates
  specs with a **non-recursive** `readdirSync`, so `src/evals/*.test.ts` would silently never
  run; `tsconfig.json:23` is `"include": ["src/**/*.ts"]`, so anything outside `src/` is
  invisible to `tsc --noEmit`; and `.githooks/pre-commit:23` matches only
  `packages/*/(src/*.ts|scripts/*.mjs|package.json|tsconfig.json)`. `.githooks/pre-push:34-38`
  runs the arm unconditionally, which is the backstop. Origin `code`, all three verified.
- **`test` and `test:live` are the identical command** (`package.json:12-13`); they differ only
  by the `npm_lifecycle_event` that `liveRunRequested()` reads. Origin `code`. An `eval`
  script is a third lifecycle name and needs no new machinery.

## Definitions

Every row's origin is `user` — each was decided on 2026-09-10, in the two rounds recorded in
`change.md` plus the ten answers taken during this planning session. Nothing here rests on
"what the code happens to do".

| Term | Decided meaning | Origin | On degenerate data | Verified by |
| --- | --- | --- | --- | --- |
| **impactful flaw** | Impactful is authorial: a planted flaw is one I designed to matter. Scoring splits into **two independent axes** — the judge asks only "was it named?", and a code-side counter records whether it was filed at `blocker` or `major`. | user | Named as `minor` → recall hit, escalation miss, and the static verdict assertion fails. The three signals disagree, and that disagreement is the finding. | 5.4 escalation counter; 5.6 fixed-transcript test built from the r5 shape |
| **correctly identify** | Named **anywhere** in the serialized outcome: `summary`, plus every `findings[].summary` and `findings[].rationale`. Two stricter code-side counters run alongside — *filed as a finding* and *correct `file`*. | user | The real `pick.md:162-167` run named the defect and the file in `summary` and filed zero findings: recall 1, filed 0. A findings-only predicate scores it a total miss. | 5.3 judge input assembly; 5.6 fixed-transcript test built from the r4 shape |
| **a green case** | One assertion per planted flaw; the case is green only at 3/3. No repo gate consumes promptfoo's exit code in this change. | user | 2/3 is red. Red is information, not breakage. | 4.5 config shape; 6.2 the sweep |
| **a run with no review** | A third outcome, `errored`, never folded into either other. Recall is reported over runs that produced a review; the error rate is reported beside it as a first-class number. | user | 3 errors in 9 runs → recall over 6, error rate 33%. Never 0/3, never silently dropped. | 5.5 the metrics layer; 5.7 zero-judged-cases guard |
| **the review actually fails** | `verdict === 'fail'` **and** `dropped === 0`, asserted deterministically — plus a **clean-control fixture** that must return `pass`. | user (conjunction and control added in planning, see "What We're NOT Doing") | On a flawed fixture: `pass` is red. On the clean control: `fail` is red. Without the control the assertion is near-free in both directions. | 3.4 the control fixture; 5.2 the static assertion |
| **the same prompt** | `SYSTEM_PROMPT` exactly as shipped. The eval passes **no** `options.tools` and sets **no** `output:`. | user + product | Passing either would silently change the artefact under test rather than the model. | 4.6 provider option guard |
| **three models** | `z-ai/glm-5.1`, `deepseek/deepseek-v4-flash`, `qwen/qwen3-coder-30b-a3b-instruct`. | user | A 12× price spread between the first two is the informative part. | 1.2, 6.2 |
| **repeats** | n=3 per model per fixture, fixed. | user | n=1 inverted a verdict in this repo (`agent-sdk-reviewer/change.md:227-229`). | 6.2 |
| **the judge** | `google/gemini-3.8-flash`, `temperature: 0`, one call per planted flaw, `rationale` declared before `label` in the zod schema, family-clean against all three models under test. | user | If the 27 hand labels disagree with it, escalate to `anthropic/claude-sonnet-5` — do not explain the disagreement away. | 5.3, 6.3 |
| **precision** | Findings not on the answer key are counted and reported, never gated. | user | 15 findings containing 3 → recall 3/3, precision 3/15, both printed. | 5.5 |
| **in-stack flaws** | Project rules 1 + 2 + 3 (`prompt.ts:98-100`). Rule 3's first copy is off-diff, so it is settleable only by searching. | user | If the model never searches, rule 3 is unfindable — which is itself the measurement. | 3.3 |
| **the answer key** | Reachable, and **measured rather than hidden**. Natural placement kept; the allow-list is **not** narrowed for eval runs. A deterministic assertion requires `accessedPaths` to contain no path under `packages/code-reviewer/evals/` or `context/changes/code-review-evals/`. | user | A model that reads the fixture file turns the case red instead of scoring better. | 5.2 leak assertion; 2.2 `deniedTools` |

## Desired End State

`cd packages/code-reviewer && npm run eval` runs 27 reviews (3 models × 3 fixtures × 3
repeats) plus 54 judge calls, prints a matrix of recall / escalation / filed / correct-file /
precision / error-rate per model per fixture, exits non-zero if any case is red **or** if zero
flaws were judged, and writes a machine-readable JSON to a gitignored path. `npm test` and
`npm run typecheck` are unchanged in behaviour and still make no model call and need no
credential. `pick.md` carries a dated follow-up note; every repo sentence asserting the evals
wrap `agent-sdk` is corrected; `test-plan.md` carries the argued §2 risk-6 exception.

### Key Discoveries

- **promptfoo ships `tsx` as a runtime dependency** and registers the loader hook
  process-globally on any `.ts` module path, so `provider.ts → agent.ts → prompt.ts →
  schema.ts` all transpile with no build step and no loader flag. `noEmit` and
  `allowImportingTsExtensions` are typechecker flags and irrelevant to it.
- **Both reliability knobs are the second argument of `openrouter.chat()`** —
  `OpenRouterChatSettings` at `.d.ts:58`, `plugins` at `:98`, `provider.require_parameters` at
  `:221`, and `.chat(modelId, settings?)` at `:745`. That is exactly the call at
  `agent.ts:170`. `require_parameters` **defaults to `false`** (`.d.ts:219`).
- **`reviewer.ts:106` already declares `deniedTools?: string[]`** with the measured
  justification at `:94-99`. Only the `ai-sdk` path fails to fill it, and `createTools()`'s
  existing `accessedPaths` set is the exact pattern to mirror.
- **The judge does not need promptfoo's grader.** Implementing it as a `.ts` `javascript`
  assertion calling `generateObject` through the already-installed `@openrouter/ai-sdk-provider`
  means promptfoo's credential-discovery path is **never entered** — which converts its worst
  trap (silently routing to OpenAI, or to direct Anthropic where it still *works* on the wrong
  bill) from a footgun into an invariant this plan asserts.
- **`cross-file.diff` states its own answer, and that is a defect in the fixture**
  (`agent-sdk-reviewer/change.md:51-60`). The first run against it produced a correct review
  having read nothing, citing the fixture's own preamble; other runs did read a file, so "the
  assertion would have been flaky rather than wrong." `vendor-detail.diff` — the only fixture
  with no preamble — is the model to copy.

## What We're NOT Doing

- **Not adding a prompt seam.** `prompt.ts:30-31` forbids it by design. "One prompt, three
  models" needs none; "eval a prompt change" is a later change.
- **Not narrowing the reviewer's allow-list for eval runs.** Decided: the eval measures the
  reviewer that actually ships.
- **Not putting promptfoo in the pre-commit or pre-push gate.** No gate arm gains a model call
  or a credential. `npm run eval` is invoked by hand.
- **Not adding `temperature`/`seed` to the reviewer.** Controlling sampling would change the
  artefact under test. Nondeterminism is measured (n=3, error rate) rather than suppressed.
- **Not gating on precision.** An unplanted finding may be a real defect; a threshold would
  punish the reviewer for being right.
- **Not using `llm-rubric` or any promptfoo model-graded assertion type.** The judge is ours.
- **Not using the `options.tools` seam** — fixed in phase 2, still unused by the eval.
- **Not reporting per-case cost.** `agent.ts:284-287` deliberately omits `costUsd` because "a
  `0` there would read as 'this review was free'". The provider **omits** `cost` and
  `tokenUsage` keys rather than sending `0`. Cost comes from the OpenRouter dashboard.
- **Not claiming a κ for the judge.** 27 hand labels is a smoke test that the judge is not
  broken. Sizing for a real agreement figure is ~100 examples per failure mode.
- **Not rewriting `pick.md`.** A reversed decision is worth more with its history intact; it
  gets a dated follow-up note.
- **Two additions beyond the ten decisions, both named here rather than smuggled in:** a
  **clean-control fixture** (phase 3.4) and the `dropped === 0` half of the static assertion.
  Without them "the review actually fails" is a near-free assertion in both directions — a
  model that fails everything scores perfectly. Cost: 9 extra reviews, ≈$0.02. Cut 3.4 and the
  static assertion becomes decorative; that is the trade, stated so it can be taken.

## Implementation Approach

Seven phases, ordered so that the two phases that spend money sit at either end and the five
between them are offline. Phase 1 is a gate on the whole change: if paid slugs still return
malformed output with both reliability knobs on, the runner reversal has failed its own test
and phases 3-7 are being built on a runner that cannot answer — that is worth $0.07 to learn
before writing a fixture.

Placement follows the gate contract rather than convenience. Everything promptfoo touches
lives in `packages/code-reviewer/evals/`, which matches **no** pre-commit pattern and sits
outside `tsconfig.json`'s `include`. The only new file under `src/` is a dependency-free
offline guard spec, placed **directly** in `src/` because `readdirSync` is non-recursive. That
leaves the eval provider's types unchecked by default, which is an explicit decision taken in
phase 4.3 rather than a silent consequence.

Total spend: phase 1 ≈ $0.07, phase 6 ≈ $0.20 of reviews + ≈$0.41 of judge calls. **Under
$0.70 for the whole change.**

## Critical Implementation Details

**The two reliability knobs go in the second argument of `openrouter.chat()`, not in
`createOpenRouter()`.** They are `OpenRouterChatSettings` fields, and `createReviewAgent`
currently calls `.chat(options.modelId)` with no settings object at all (`agent.ts:170`).
`require_parameters` defaults to `false`, so structured-output support — which is **per
endpoint, not per model** — is currently whatever OpenRouter's router happened to pick:

```ts
model: openrouter.chat(options.modelId, {
  plugins: [{ id: 'response-healing' }],   // non-streaming only; our path is non-streaming
  provider: { require_parameters: true },  // default is false (.d.ts:219)
}),
```

**promptfoo's `file://` assertion values split on the first `:`** to separate an optional
export name, so a Windows absolute path truncates to `D`. Assertion `file://` values must be
**relative**. Provider paths take a different, `path.isAbsolute`-aware branch and are safe —
so the two look alike and behave differently.

**Nunjucks renders variable *values*, not just templates.** A diff passed as a var is silently
mutated or throws, and this repo's frontend is full of Angular `{{ }}`. The provider therefore
receives a fixture **path** and reads the file itself.

**promptfoo exits `100` on test failure**, `1` on any other error. Never test for `-eq 1`.

## Phase 1: Reliability first — measure before building

### Overview

Wire the two OpenRouter reliability options into the `ai-sdk` model construction, then measure
the `malformed-output` rate across the three paid slugs. Converts the runner reversal from
*authorised by one condition* to *evidenced* — or refutes it while refuting it is still cheap.

### Changes Required:

#### 1. The model construction

**File**: `packages/code-reviewer/src/agent.ts`

**Intent**: Turn on the two never-tried mitigations for the exact failure mode that was
`pick.md`'s first reason to reject this runner — `findings` sent as a string, `summary`
omitted. Both are typed and already installed; neither was on during any of the 32 comparison
runs.

**Contract**: `createReviewAgent` passes a second argument to `openrouter.chat()` —
`plugins: [{ id: 'response-healing' }]` and `provider: { require_parameters: true }`. No
signature change, no new option, no new dependency. A comment must record that
`require_parameters` defaults to `false` and that structured-output support is per endpoint
rather than per model, because that is a plausible mechanism for a failure rate previously
attributed to the model.

#### 2. The measurement

**File**: `packages/code-reviewer/probe-reliability.ts` — **throwaway, deleted in this phase's
commit**

**Intent**: Run each of the three slugs three times against the existing bare
`fixtures/vendor-detail.diff` and record how many produced a review.

**Contract**: Follows the `pick.md:240-243` precedent exactly — a throwaway at the package
root, deleted with the phase commit, because a committed comparison script would join the
commit gate and the gate must make no model call. `git status --porcelain` must be clean of it
before committing. Results go into `change.md` § Measurements as a table (slug, runs,
reviewed, errored, error kinds, wall clock), plus a one-line verdict on whether the reversal
holds.

`vendor-detail.diff` and not a new fixture: it is the only existing fixture with no prose
preamble, and reusing it keeps this measurement comparable to the 32 recorded runs.

### Success Criteria:

#### Automated Verification:

- `npm run typecheck` passes in `packages/code-reviewer`
- `npm test` passes, still with no credential set and no model call
- `git status --porcelain` shows no trace of `probe-reliability.ts`

#### Manual Verification:

- The measured malformed-output rate for all three paid slugs is recorded in `change.md`, with
  the reversal verdict stated either way
- If the rate is non-zero on any slug, the decision to continue (or to reconsider the runner)
  is written down before phase 2 starts

**Implementation Note**: After completing this phase and all automated verification passes,
pause for manual confirmation before proceeding.

---

## Phase 2: The two contract repairs

### Overview

Fix the `options.tools` dead check and fill `deniedTools` on the `ai-sdk` path. Offline, no
model call. The second repair is what makes the phase-5 answer-key assertion able to see a
refused read at all.

### Changes Required:

#### 1. The `options.tools` / `accessedPaths` mismatch

**File**: `packages/code-reviewer/src/agent.ts`

**Intent**: A caller who substitutes tools currently gets citations validated against the path
set of the tools that were **not** used — `accessedPaths: []`, every citation stripped, no
error. `reviewer.ts:78-81` already names this class in the abstract: "an empty one strips every
citation — which looks like a strict reviewer and is a dead check."

**Contract**: When `options.tools` is supplied, the path set used at `agent.ts:358` must be the
one belonging to the tools actually passed to the agent. Either the option carries its own
accessed-path set or the substitution point and the validation point read one value. A caller
passing tools with no path set must get a **loud** failure, never a silent empty set.

#### 2. `deniedTools` on the `ai-sdk` path

**File**: `packages/code-reviewer/src/tools.ts`, `packages/code-reviewer/src/agent.ts`

**Intent**: `reviewer.ts:94-99` records the measurement: with the containment hook removed the
model quoted `.env`'s first line while `accessedPaths` stayed `[]` in **both** arms. The access
log cannot see a containment break; the refusal record is the only externally visible
difference. On this runner the refusals go into a message history `agent.ts` discards.

**Contract**: `createTools()` returns a third member alongside `tools` and `accessedPaths`,
accumulating one entry per refusal (the tool's name, appended per occurrence — matching the
`agent-sdk` shape `['Grep','Grep']`). Both denial sites record: `tools.ts:165` (not in the
allow-list) and `:173` (no such file). `reviewDiff` returns it **always**, so the field goes
from `undefined` to `[]` on a clean run — and `reviewer.ts:106`'s documented distinction holds:
`undefined` means no record, `[]` means a record with nothing refused.

#### 3. Tests for both

**File**: `packages/code-reviewer/src/tools.test.ts` (extend), `src/verdict.test.ts` or the
existing agent spec (extend)

**Intent**: Both repairs are about a signal that was absent, so both need a test that fails if
the signal goes absent again.

**Contract**: Offline only — no credential, no model. One test drives a denial through
`createTools()` and asserts the refusal is recorded with the tool's name; one asserts a clean
run yields `[]` and not `undefined`; one covers the `options.tools` path set. All must skip
with a printed reason if they ever need a credential, per `injection.test.ts:10-13`.

### Success Criteria:

#### Automated Verification:

- `npm run typecheck` passes
- `npm test` passes with `OPENROUTER_API_KEY`, `AWS_PROFILE`, `AWS_REGION`,
  `AWS_DEFAULT_REGION` and `CLAUDE_CODE_USE_BEDROCK` all unset
- The reported test count has grown; `scripts/run-tests.mjs` still refuses a zero tally
- `.githooks/pre-commit` passes on the staged set (both files match `src/.*\.ts`, so the arm
  runs)

#### Manual Verification:

- A run with the containment hook conceptually removed would now leave a trace — confirmed by
  reading the new test's assertions, not by breaking containment
- The gate arm cost is re-measured (not re-estimated) and noted for phase 7's `test-plan.md`
  §5.1 update

---

## Phase 3: The fixtures and the offline guard

### Overview

Author three new fixtures — the React 16→19+ migration the lesson names, an in-stack
Angular/Spring control, and a clean control — plus an answer key and a dependency-free spec
that fails if any fixture drifts from what it tests. No model call anywhere in this phase.

### Changes Required:

#### 1. The React 16 → 19+ migration fixture

**File**: `packages/code-reviewer/evals/fixtures/react-19-migration.diff`

**Intent**: The lesson's named subject, with three impactful flaws, authored so that a model
cannot get the answer from the file.

**Contract**: **No prose preamble of any kind** — this is the single most important precedent
in the change (`agent-sdk-reviewer/change.md:51-60`). "Rather complex" is operationalised as:
at least two files, at least ~120 diff lines, and a genuine migration shape (class component →
function component, `ReactDOM.render` → `createRoot`, lifecycle methods → hooks). The three
planted flaws:

| # | Flaw | Severity intended | Decidable from |
| --- | --- | --- | --- |
| R1 | The `componentDidCatch` error boundary is dropped in the conversion and replaced by a `try/catch` around the render body that renders a fallback and swallows the error | blocker — **project rule 2**, the only reachable one | the diff alone |
| R2 | A `useEffect` subscription returns no cleanup, and its dependency array is `[]` while the effect closes over a changing prop | blocker | the diff alone |
| R3 | `createRoot` is called per mount without the corresponding unmount teardown, so a second root attaches on every navigation | major | the diff alone |

Three of the reviewer's four project rules are idle on this fixture, and the tool, permission
and evidence layers go idle with them — that is the known, accepted cost of lesson fidelity,
and fixture 3.3 is the control that measures it.

#### 2. The answer key

**File**: `packages/code-reviewer/evals/fixtures/answer-key.ts`

**Intent**: One typed record per planted flaw, consumed by both the judge and the code-side
counters, so the two axes can never drift apart.

**Contract**: Per flaw — a stable `id`, the file the flaw lives in, the intended severity
(`blocker` | `major`), a one-paragraph description written for a judge rather than for a
model, and the rubric sentence the judge is asked to decide. The clean-control fixture has an
**empty** flaw list, and the loader must treat empty as legitimate for that fixture and as an
error for any other.

#### 3. The in-stack control fixture

**File**: `packages/code-reviewer/evals/fixtures/in-stack-rules.diff`

**Intent**: Exercise all three enforcement layers and three of the four project rules, so the
pair measures how much the off-domain React subject costs. This is the fixture that keeps the
eval a measurement of *this prompt* rather than of general React knowledge.

**Contract**: No prose preamble. Real Angular/Spring shapes drawn from this repo. The three
planted flaws:

| # | Flaw | Rule | Decidable from |
| --- | --- | --- | --- |
| G1 | Absent accident data rendered as a clean history rather than as unknown | rule 1, blocker — the project's hardest rule, with real user harm behind it | the diff alone |
| G2 | Enrichment failure caught and converted into an empty-but-successful result | rule 2, blocker | the diff alone |
| G3 | A vendor-specific detail duplicated into a second class, whose **first copy is off-diff** | rule 3, major | **only by searching the repo** |

G3 is the load-bearing one: `schema.ts:22-27` explains that rule 3 "is structurally
unanswerable from a diff alone, because the *first* copy of the duplicated detail is never in
it", and names `MarketPriceFetchService:28` as where such a first copy lives. Confirm the exact
current line when authoring and duplicate *that* detail. If no suitable first copy still
exists, say so and pick another rule-3 subject rather than planting an unfindable flaw.

#### 4. The clean control fixture

**File**: `packages/code-reviewer/evals/fixtures/clean-control.diff`

**Intent**: Make "the review actually fails" mean something. Asserting `verdict === 'fail'` on
flawed fixtures alone is near-free in both directions — a model that fails everything scores
perfectly.

**Contract**: A real-looking in-stack diff with **no** rule violation, including at least one
shape that *resembles* a violation and is correct (an error that is caught and rethrown with
context, say). Expected verdict `pass`. Empty flaw list in the answer key. `minor`/`nit`
findings are permitted — they do not move the verdict — and are counted as precision noise.

#### 5. The offline fixture guard

**File**: `packages/code-reviewer/src/eval-fixtures.test.ts` — **directly in `src/`**

**Intent**: `injection.test.ts:92-99` already carries this pattern: "A fixture edited down to
just the blocker would leave the test above passing while testing nothing."

**Contract**: Dependency-free (`node:test`, `node:fs`), no credential, no model, no promptfoo
import. Per fixture it asserts: the file exists and is non-empty; it parses as a diff and
names the files the answer key names; each planted flaw's marker text is still present; and it
contains **no prose preamble** — no non-diff prose before the first `diff --git`. It also
asserts the clean control has an empty flaw list and every other fixture has exactly three
entries. Placed directly in `src/` because `run-tests.mjs`'s `readdirSync` is non-recursive and
a spec one directory down would silently never run.

### Success Criteria:

#### Automated Verification:

- `npm test` passes and the new spec appears in the reported tally
- Removing one planted flaw from a fixture makes the guard spec fail (checked by trying it and
  reverting)
- Adding a prose line above the first `diff --git` makes the guard spec fail (same)
- `npm run typecheck` passes — note that `evals/` is outside `tsconfig.json`'s `include`, so
  `answer-key.ts` is **not** covered here; that is phase 4.3's decision

#### Manual Verification:

- Each fixture read end to end confirms no sentence states or hints at its own answer
- G3's first copy is confirmed to exist at the cited location, or a substitute is documented
- The React fixture reads like a real migration a developer would open a PR for, not like a
  quiz

---

## Phase 4: The promptfoo harness

### Overview

Add promptfoo as a package-local devDependency, write the file provider and the config, and
wire an `npm run eval` that fails loudly rather than reporting a clean run.

### Changes Required:

#### 1. The dependency

**File**: `packages/code-reviewer/package.json`

**Intent**: promptfoo inside the package only. A root-level install is the exact risk root
`CLAUDE.md` rejected for Lefthook — Cloudflare Pages builds this repo from a subdirectory on
every push to `main`.

**Contract**: `promptfoo` pinned **exact** (`0.123.0`, no caret) in `devDependencies`, because
the `.ts`-assertion behaviour this plan relies on contradicts promptfoo's published docs. A new
`eval` script. `engines.node` already says `>=22`; promptfoo requires `>=22.22.0` against a
local `22.22.1`, so the floor is checked at runtime in 4.4 rather than trusted to a manifest
field.

Note the gate consequence: `package.json` **does** match the pre-commit pattern, so this commit
runs the reviewer arm — and `.githooks/` never runs `npm install`, so a declared-but-unfetched
promptfoo stays invisible unless something under `src/` imports it. Nothing under `src/` may
import promptfoo.

#### 2. The custom provider

**File**: `packages/code-reviewer/evals/provider.ts`

**Intent**: Adapt `reviewDiff` to promptfoo's provider contract without letting either side's
conventions corrupt the other's.

**Contract**: Default-exported class with `id()` and
`callApi(prompt, context?, options?)`. Constructed once per `providers:` entry with
`{...providerOptions, id: providerId}`.

- `id()` derives from `config.modelId`, because `providerId` defaults to the provider *path*
  and all three entries share one path — without this, three models are indistinguishable in
  the results.
- `callApi` treats its input as a **fixture path**, reads the file itself, and calls
  `reviewDiff(diff, { modelId: config.modelId })`. No `tools`, no `output`, no `temperature`.
- Returns `{ output: <the ReviewRun, structured> }` on success.
- On a thrown `ReviewerError` returns `{ error: '<kind>: <message>' }` — the seven kinds stay
  distinguishable, because "errored" is a first-class outcome and collapsing them would lose
  the error-kind column phase 1 measured.
- **Omits** `cost` and `tokenUsage` keys entirely when unreported. promptfoo has no "unknown",
  and a `0` reads as "free" (`agent.ts:284-287`).

#### 3. The typecheck decision

**File**: `packages/code-reviewer/tsconfig.json` or a sibling `evals/tsconfig.json`

**Intent**: `tsconfig.json:23` is `"include": ["src/**/*.ts"]`, so every file added in phases
3 and 4 outside `src/` is unchecked. `common.sh:99-101` says the typecheck "is the only thing
that ever reads them, and skipping it would mean the types are decoration." Leaving this
implicit is how the change acquires unchecked code by accident.

**Contract**: Extend the checked set to cover `evals/**/*.ts` while keeping `noEmit`. If that
turns out to pull promptfoo's own types into the gate's typecheck and slow or break it, the
fallback is a separate `evals/tsconfig.json` invoked by the `eval` script rather than by the
gate — and **the choice made, with its measured cost, is written into `change.md`**. Either way
the answer is explicit.

#### 4. The eval runner script

**File**: `packages/code-reviewer/scripts/run-eval.mjs`

**Intent**: `npm run eval` must not be able to report success while measuring nothing. This is
`run-tests.mjs`'s design applied to a second runner, for the same stated reason.

**Contract**: Checks `process.version` against promptfoo's `>=22.22.0` floor and **fails loudly
by name** if below — the repo rule that every layer fails loudly when its own toolchain is
missing. Sets `PROMPTFOO_DISABLE_UPDATE`, `PROMPTFOO_DISABLE_TELEMETRY` and
`PROMPTFOO_DISABLE_REMOTE_GENERATION`. Spawns promptfoo with `-j 1` and
`-o evals/results/<timestamp>.json`. Captures the exit code **without** short-circuiting, then
always runs the report step from phase 5, then exits non-zero if either failed. Treats any
non-zero promptfoo exit as failure — `100` is its test-failure code, `1` is everything else.

#### 5. The config

**File**: `packages/code-reviewer/evals/promptfooconfig.yaml`

**Intent**: Three providers, three fixtures, per-flaw assertions.

**Contract**: Three `providers:` entries pointing at `file://evals/provider.ts`, each with its
own `config.modelId` **and** a distinct `label`. `tests:` carries one entry per fixture with a
`fixture:` var holding a **path**, never the diff body — Nunjucks renders var values and this
repo's frontend is full of `{{ }}`. `repeat: 3`. Assertions are `file://` **relative** paths
into `evals/assertions/`, never absolute — the value splits on the first `:`. No
`llm-rubric` or any other model-graded assertion type anywhere in the file.

#### 6. Ignore the outputs and guard the invariants

**File**: `.gitignore`, `packages/code-reviewer/src/eval-config.test.ts`

**Intent**: Results must not land in the tree, and the two config invariants that fail silently
must be asserted rather than remembered.

**Contract**: `.gitignore` gains `packages/code-reviewer/evals/results/`. Nothing else
promptfoo writes touches the repo — its cache and results DB live under `os.homedir()`. The
new spec is offline and dependency-free (it reads the YAML as text) and asserts: no
model-graded assertion type appears anywhere in the config, so promptfoo's credential
discovery is never entered; every assertion `file://` value is relative; every provider entry
has a distinct `label` and a `config.modelId`; and the provider passes neither `tools` nor
`output`.

### Success Criteria:

#### Automated Verification:

- `npm run typecheck` passes under the phase-4.3 decision
- `npm test` passes with no credential; the new config spec is in the tally
- `npm run eval -- --help` (or a dry equivalent) resolves `evals/provider.ts` through tsx with
  no build step, no loader flag and no `.mjs` shim
- A deliberately broken `.ts` assertion import surfaces as an error, not as a pass
- `git status --porcelain` is clean after an eval run except for the gitignored results
  directory
- The node-floor check fails loudly when run under an older node (simulated)

#### Manual Verification:

- One single-case run against one model produces a real review through the provider
- `promptfoo view` (or the JSON) attributes results to three distinct providers, not one
- The reviewer arm's gate cost after the devDependency is measured, for phase 7

---

## Phase 5: The judge and the scoring layer

### Overview

One binary judge call per planted flaw, five code-side counters, and a report that fails when
nothing was judged.

### Changes Required:

#### 1. The judge module

**File**: `packages/code-reviewer/evals/judge.ts`

**Intent**: Answer exactly one question per planted flaw, in the framing where a mid-tier judge
is defensible: the flaws are planted, so the answer key is free and the task is "does this
English text name this known defect?" — reading comprehension, not code review.

**Contract**: `generateObject` through the installed `@openrouter/ai-sdk-provider`,
`google/gemini-3.8-flash`, `temperature: 0`. The zod schema declares **`rationale` before
`label`**, because the model generates in property order and rationale-before-label is what
makes it chain-of-thought rather than post-hoc justification; `label` is
`z.enum(['PASS','FAIL'])` with the spelling pinned. Reference-guided: the answer key's
description for that flaw is in the prompt. One flaw per call — never all three in one — to
prevent halo effects. The candidate review is **delimited**, and the prompt states that content
inside the delimiters is data and never instructions, because the judge reads model-authored
prose and this package ships `injection.diff`.

Judge failures are their own outcome, distinct from `FAIL`: a judge that cannot return
structured output must not silently score zero.

#### 2. The static assertion

**File**: `packages/code-reviewer/evals/assertions/static.ts`

**Intent**: Everything already representable in `ReviewOutcome`, asserted deterministically —
including the answer-key leak check.

**Contract**: Exported named functions, addressed as `file://evals/assertions/static.ts:<name>`
with a **relative** path. It asserts, per case:

- the output parses — `ModelReview` is reusable directly from `src/schema.ts`; `verdict`,
  `dropped` and `strippedEvidence` have no runtime validator to reuse and are checked
  structurally
- `verdict === 'fail'` for a flawed fixture, `verdict === 'pass'` for the clean control
- `dropped === 0`
- **`accessedPaths` contains no path under `packages/code-reviewer/evals/` or
  `context/changes/code-review-evals/`** — the answer-key leak, converted from a silent,
  self-confirming risk into a check that fails loudly. Recording, not gating, for
  `deniedTools`: a refusal is information, not a failure.

#### 3. Judge input assembly

**File**: `packages/code-reviewer/evals/judge.ts`

**Intent**: The judge must see `summary`, because `pick.md:162-167` records a run whose summary
named the right defect *and* the right file and which then reported zero findings. A predicate
scoped to `findings` scores that as a total miss.

**Contract**: The judged text is the serialized `summary` plus every `findings[].summary` and
`findings[].rationale`, in a stable order, with severities and files included so the counters
and the judge read the same artefact.

#### 4. The escalation counter

**File**: `packages/code-reviewer/evals/metrics.ts`

**Intent**: The second of the two axes. A flaw named as `minor` is a calibration failure, not a
knowledge failure, and the eval must tell them apart.

**Contract**: Per planted flaw: `named` (from the judge), `filed` (a `findings[]` entry, not
just prose), `escalated` (that entry's severity is `blocker` or `major`), `correctFile` (its
`file` matches the answer key). Four booleans, never collapsed into one score.

#### 5. The metrics layer

**File**: `packages/code-reviewer/evals/metrics.ts`

**Intent**: Turn per-case results into the matrix a human reads.

**Contract**: Per model per fixture — recall over **runs that produced a review**, the
`errored` count and rate with error kinds broken out, escalation/filed/correct-file rates,
precision (findings not on the answer key, over total findings), and `strippedEvidence` /
`deniedTools` totals as reported columns. Recall and precision are always printed together, so
neither can be quoted alone. The `errored` count is never folded into recall and never dropped.

#### 6. Fixed-transcript tests

**File**: `packages/code-reviewer/src/eval-metrics.test.ts` — **directly in `src/`**

**Intent**: The scoring layer is where a silent wrong answer would be most expensive and least
visible. Every degenerate row from the planning walk becomes a test against a hand-written
`ReviewOutcome` — no model, no credential, no judge call.

**Contract**: Offline. Cases: the r4 shape (named in `summary`, zero findings → `named` true,
`filed` false); the r5 shape (named as `minor` → `named` true, `escalated` false, verdict
`pass` → static assertion red); the r6 shape (right defect, wrong file → `correctFile` false);
the r2 shape (15 findings containing 3 → recall 3/3, precision 3/15); the r3 shape (an
`errored` run → excluded from the recall denominator, counted in the error rate); a run whose
`accessedPaths` contains an eval-directory path (→ leak assertion red). The judge is stubbed
with a fixed label so these test the metrics, not the model.

#### 7. The zero-judged-cases guard

**File**: `packages/code-reviewer/scripts/run-eval.mjs`, `evals/metrics.ts`

**Intent**: The guard that transfers from this repo's own history — `node --test` on a pattern
matching nothing exits 0, which is why `run-tests.mjs` exists at all.

**Contract**: The report step exits non-zero when the number of judged flaws is zero, when any
expected (model × fixture × repeat) cell is missing, or when the judge itself failed on every
call. A run that produced no measurement must fail, not pass — and must say which of the three
it was.

### Success Criteria:

#### Automated Verification:

- `npm test` passes offline; both new specs are in the tally
- Every degenerate row from the Definitions table has a passing test
- Deleting all cases from the results JSON makes the report step exit non-zero
- Pointing the judge at an unreachable model surfaces a judge failure, not a score of zero
- `npm run typecheck` passes

#### Manual Verification:

- The judge prompt is read end to end for leakage: it must not reveal how many flaws exist,
  nor name the other flaws, nor name the model under test
- The delimiting is verified against `injection.diff`-style content
- The printed matrix is legible without the plan open next to it

---

## Phase 6: The sweep and judge calibration

### Overview

Run it: 3 models × 3 fixtures × 3 repeats, 54 judge calls, then hand-label all 54 to check the
judge is not broken. ≈$0.61.

### Changes Required:

#### 1. The sweep

**File**: `context/changes/code-review-evals/change.md` § Measurements

**Intent**: Produce the numbers, and record them where a later reader can find them without
re-running anything.

**Contract**: One table per fixture: model, runs, reviewed, errored (with kinds), recall,
escalated, filed, correctFile, precision, `strippedEvidence`, `deniedTools`, median wall clock.
Plus the React-vs-in-stack comparison, which is the whole reason both fixtures exist — and an
explicit statement of what the React fixture could not measure.

#### 2. Judge calibration

**File**: `context/changes/code-review-evals/change.md` § Measurements

**Intent**: Check the judge, without pretending 54 labels is a κ.

**Contract**: Hand-label every judged flaw from the sweep, compare to the judge's labels, and
report raw agreement and every disagreement individually with its rationale. State plainly that
this is a smoke test that the judge is not broken and **not** a measurement of judge quality —
sizing for that is ~100 examples per failure mode, with confidence intervals too wide below 60.
If agreement is poor, escalate the judge to `anthropic/claude-sonnet-5`, re-run, and record
both — do not explain the disagreement away.

#### 3. The leak, measured

**File**: `context/changes/code-review-evals/change.md` § Measurements

**Intent**: The user's decision was to measure the answer-key leak rather than hide it. This is
where the measurement gets written down.

**Contract**: Report how many of the 27 runs read any eval-directory path, and what
`deniedTools` recorded across the sweep. A zero is a real result and worth stating; a non-zero
means the fixture placement has to be revisited, and that conclusion belongs in `change.md`
rather than in a later surprise.

### Success Criteria:

#### Automated Verification:

- `npm run eval` completes and writes a results JSON
- The report prints the full matrix and exits non-zero if any expected cell is missing
- `npm test` and `npm run typecheck` still pass afterwards
- `git status --porcelain` shows nothing but the intended `change.md` edit

#### Manual Verification:

- Every table in `change.md` was produced by running the thing, not estimated
- The recall figures are stated as directional at n=3, not as measurements
- Actual OpenRouter spend is read from the dashboard and recorded, since the runner cannot
  report per-case cost by design

---

## Phase 7: Corrections to the record

### Overview

Six documentation sites are false or stale the moment this change lands, and one carve-out
needs an argued exception rather than silence.

### Changes Required:

#### 1. The reversal's stale claims

**File**: `CLAUDE.md`, `context/foundation/test-plan.md`,
`context/changes/agent-sdk-reviewer/{change.md, plan.md, plan-brief.md}`

**Intent**: Five sentences assert that the evals wrap `agent-sdk`. After this change they are
false, and a false sentence in `CLAUDE.md` is loaded into every future session's context.

**Contract**: Correct `CLAUDE.md:112` ("the evals wrap `agent-sdk`"), `test-plan.md:589`
("it is the runner M5-L3's promptfoo provider wraps"), `test-plan.md:338`'s pointer,
`agent-sdk-reviewer/change.md:12`, and `plan.md:13` / `plan-brief.md:18` ("wraps **one**").
Each correction names the reversal and points at this change; none rewrites history.

#### 2. `pick.md`'s dated follow-up note

**File**: `context/changes/agent-sdk-reviewer/pick.md`

**Intent**: A reversed decision is worth more with its history intact. `pick.md` is 32 measured
runs and a tiebreak order fixed before any of them; deleting or rewriting it would destroy the
only record of why the other choice was defensible.

**Contract**: An appended, dated note — not an edit to the body, not a changed title. It states
which of the five reversal conditions was satisfied and that it was satisfied **in form before
phase 1 and in evidence after it**, cites phase 1's measured malformed-output rate, records
that phase 2 filled `deniedTools` and thereby closed the first tiebreak, and links here.

#### 3. The `test-plan.md` updates

**File**: `context/foundation/test-plan.md`

**Intent**: §5.1's own standard is "re-measured on 2026-09-10, **not re-estimated**". This
change moves three of its numbers.

**Contract**: §4 Stack (line 338) — the promptfoo devDependency and the new suite counts. §5.1
(lines 362-375) — the re-measured gate arm cost from phases 2 and 4, and the credential-free
proof restated as still holding. §8 Freshness Ledger — a **new** dated entry **and** a
correction to the existing 2026-09-10 entry, not merely an append.

#### 4. The §2 risk-6 exception, argued

**File**: `context/foundation/test-plan.md` §2

**Intent**: §2 risk #6 names "an eval asserting a specific model wording" as a thing this repo
does not do, and carves out the repo-tooling reviewer specifically because "its output is a
schema … rather than prose, and whose assertions are therefore about a decision, not a
wording." **A judge reading `summary` and `rationale` reads prose and sits on the far side of
that carve-out.** Leaving this unargued would be the change quietly breaking a rule it is
otherwise built to respect.

**Contract**: An explicit exception, argued rather than asserted. The argument available: the
assertion is about whether a *known planted defect* was identified — a decision — not about how
it was worded; the label is binary rather than a wording score; the answer key is authored, so
the judge does reference-guided reading comprehension rather than review; and the deterministic
half of the suite still carries every assertion that a schema can express. The exception must
also state its own limit: this does **not** authorise a judge scoring review quality in
general, only recall against an authored key.

#### 5. Package documentation

**File**: `packages/code-reviewer/README.md` if present, else `CLAUDE.md`'s packages paragraph

**Intent**: Someone arriving at the package needs to know `npm run eval` exists, that it costs
money, and that it is deliberately outside every gate.

**Contract**: One short section: what `npm run eval` does, what it spends, why no gate runs it,
and where the results go.

### Success Criteria:

#### Automated Verification:

- `git grep -n "evals wrap"` and equivalents return no stale assertion
- `npm test`, `npm run typecheck`, and the full pre-push suite pass
- Prettier passes on every edited markdown file the frontend hook covers

#### Manual Verification:

- `CLAUDE.md` reads correctly to someone who has never seen `pick.md`
- The §2 risk-6 exception states its own limit, not just its permission
- `pick.md` is still readable as the decision it was, with the reversal appended rather than
  woven in
- The `change.md` scope-decision sections and the final Measurements agree with each other

---

## Testing Strategy

### Unit Tests (offline, no credential, no model — every one of them):

- Fixture guard: each fixture still contains its planted flaws and still has no prose preamble;
  clean control has an empty flaw list
- Config guard: no model-graded assertion type; all assertion `file://` values relative;
  distinct labels; no `tools` or `output` passed
- Metrics, from fixed transcripts: the r2 / r3 / r4 / r5 / r6 shapes and the leak case
- The two phase-2 repairs: a recorded refusal, `[]` versus `undefined`, and the
  `options.tools` path set

### Integration Tests:

- One promptfoo case, one model, end to end — proves the tsx loader chain and the provider
  contract
- A deliberately broken `.ts` assertion import surfaces as an error rather than a pass
- An empty results JSON makes the report exit non-zero

### Manual Testing Steps:

1. `cd packages/code-reviewer && npm run eval` with no `OPENROUTER_API_KEY` — must fail with
   `no-api-key`, never a pass
2. Run under an older node — must fail loudly by name on the version floor
3. Full sweep; read the matrix; confirm recall and precision are printed together
4. Hand-label all 54 judged flaws and compare
5. Read the OpenRouter dashboard and record actual spend
6. `git status --porcelain` after everything — clean but for the gitignored results directory

## Performance Considerations

`-j 1` for every run in this change: promptfoo's default concurrency is 4, which on an agentic
multi-step reviewer is four concurrent paid runs. The reviewer's own wall clock varies 8×
(9.1–73.6 s measured), so a 27-review sweep at `-j 1` is roughly 10–25 minutes. That is
acceptable for a hand-invoked script and unacceptable for a gate — which is one more reason no
gate runs it. `DEFAULT_TIMEOUT_MS` stays at 120 s; a slow model is a failed review, not a
passing one.

## Migration Notes

No data, no schema, no deploy path. Two backward-compatibility notes: `deniedTools` moves from
`undefined` to `[]` on the `ai-sdk` path, which is a **meaningful** change under
`reviewer.ts:94-99`'s documented distinction and must not be treated as cosmetic; and pinning
`promptfoo` exactly means a future upgrade has to re-verify the `.ts`-assertion behaviour that
contradicts the published docs.

## References

- Research: `context/changes/code-review-evals/research.md`
- Scope decisions: `context/changes/code-review-evals/change.md`
- The reversed decision: `context/changes/agent-sdk-reviewer/pick.md`
- The fixture precedent: `context/changes/agent-sdk-reviewer/change.md:51-60`
- The n=3 precedent: `context/changes/agent-sdk-reviewer/change.md:227-229`
- The gate's no-model-call rule: `context/changes/agent-sdk-reviewer/change.md:251-255`,
  `pick.md:240-243`
- Structured output kills the tool loop: `context/changes/tool-loop-agent/change.md:110-112`
- The carve-out that authorises this: `context/foundation/test-plan.md` §2 risk #6
- Skip-with-a-reason and the fixture guard: `packages/code-reviewer/src/injection.test.ts:10-13`,
  `:92-99`
- Why the suite is enumerated from disk: `packages/code-reviewer/scripts/run-tests.mjs:4-8`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Reliability first — measure before building

#### Automated

- [ ] 1.1 `npm run typecheck` passes in `packages/code-reviewer`
- [ ] 1.2 `npm test` passes, still with no credential set and no model call
- [ ] 1.3 `git status --porcelain` shows no trace of `probe-reliability.ts`

#### Manual

- [ ] 1.4 Measured malformed-output rate for all three paid slugs recorded in `change.md`, with the reversal verdict stated either way
- [ ] 1.5 If the rate is non-zero on any slug, the decision to continue is written down before phase 2

### Phase 2: The two contract repairs

#### Automated

- [ ] 2.1 `npm run typecheck` passes
- [ ] 2.2 `npm test` passes with every credential env var unset
- [ ] 2.3 Reported test count has grown; `run-tests.mjs` still refuses a zero tally
- [ ] 2.4 `.githooks/pre-commit` passes on the staged set

#### Manual

- [ ] 2.5 A containment break would now leave a trace — confirmed by reading the new assertions, not by breaking containment
- [ ] 2.6 Gate arm cost re-measured (not re-estimated) and noted for phase 7

### Phase 3: The fixtures and the offline guard

#### Automated

- [ ] 3.1 `npm test` passes and the new fixture-guard spec is in the tally
- [ ] 3.2 Removing a planted flaw makes the guard fail (tried and reverted)
- [ ] 3.3 Adding a prose line above the first `diff --git` makes the guard fail (tried and reverted)
- [ ] 3.4 `npm run typecheck` passes

#### Manual

- [ ] 3.5 Each fixture read end to end confirms no sentence states or hints at its own answer
- [ ] 3.6 G3's off-diff first copy confirmed to exist, or a documented substitute
- [ ] 3.7 The React fixture reads like a real migration PR, not a quiz

### Phase 4: The promptfoo harness

#### Automated

- [ ] 4.1 `npm run typecheck` passes under the phase-4.3 decision
- [ ] 4.2 `npm test` passes with no credential; the config guard spec is in the tally
- [ ] 4.3 `evals/provider.ts` resolves through tsx with no build step, loader flag or `.mjs` shim
- [ ] 4.4 A deliberately broken `.ts` assertion import surfaces as an error, not a pass
- [ ] 4.5 `git status --porcelain` clean after an eval run but for the gitignored results directory
- [ ] 4.6 The node-floor check fails loudly when run under an older node

#### Manual

- [ ] 4.7 One single-case run against one model produces a real review through the provider
- [ ] 4.8 Results attribute to three distinct providers, not one
- [ ] 4.9 Reviewer arm gate cost after the devDependency measured, for phase 7

### Phase 5: The judge and the scoring layer

#### Automated

- [ ] 5.1 `npm test` passes offline; both new specs in the tally
- [ ] 5.2 Every degenerate row from the Definitions table has a passing test
- [ ] 5.3 Deleting all cases from the results JSON makes the report exit non-zero
- [ ] 5.4 An unreachable judge model surfaces a judge failure, not a score of zero
- [ ] 5.5 `npm run typecheck` passes

#### Manual

- [ ] 5.6 Judge prompt read for leakage — flaw count, other flaws, and model identity all absent
- [ ] 5.7 Delimiting verified against `injection.diff`-style content
- [ ] 5.8 The printed matrix is legible without the plan open next to it

### Phase 6: The sweep and judge calibration

#### Automated

- [ ] 6.1 `npm run eval` completes and writes a results JSON
- [ ] 6.2 The report prints the full matrix and exits non-zero on any missing expected cell
- [ ] 6.3 `npm test` and `npm run typecheck` still pass afterwards
- [ ] 6.4 `git status --porcelain` shows nothing but the intended `change.md` edit

#### Manual

- [ ] 6.5 Every table in `change.md` produced by running the thing, not estimated
- [ ] 6.6 Recall figures stated as directional at n=3, not as measurements
- [ ] 6.7 Actual OpenRouter spend read from the dashboard and recorded

### Phase 7: Corrections to the record

#### Automated

- [ ] 7.1 No stale "evals wrap `agent-sdk`" assertion survives a grep
- [ ] 7.2 `npm test`, `npm run typecheck` and the full pre-push suite pass
- [ ] 7.3 Prettier passes on every edited markdown file the frontend hook covers

#### Manual

- [ ] 7.4 `CLAUDE.md` reads correctly to someone who has never seen `pick.md`
- [ ] 7.5 The §2 risk-6 exception states its own limit, not just its permission
- [ ] 7.6 `pick.md` still readable as the decision it was, reversal appended rather than woven in
- [ ] 7.7 `change.md`'s scope decisions and final Measurements agree with each other
