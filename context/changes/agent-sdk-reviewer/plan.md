# Build the reviewer a second time on the Claude Agent SDK, then pick — Implementation Plan

## Overview

`packages/code-reviewer` holds one reviewer, built on the Vercel AI SDK's `ToolLoopAgent`
against OpenRouter. This change builds a second one on the **Claude Agent SDK**
(`@anthropic-ai/claude-agent-sdk`) against **Bedrock**, over the same schema, the same
prompt, the same verdict arithmetic and the same fixtures — then measures the two against
each other and writes down which one wins.

That is M5-L2's actual content: the lesson deliberately builds the same tool twice, once
assembled from parts and once on a ready-made harness, and the comparison is the lesson.
The pick is not a formality either — M5-L3's promptfoo custom provider wraps **one**
runner, so a change that left two runners side by side would push the decision into the
next lesson and risk building the provider twice.

Everything the two runners share already exists. What differs is exactly three things: the
loop, the tool layer, and the auth path.

## Current State Analysis

**The shared modules transfer.** Phase 1 of `tool-loop-agent` split one file into five for
this reason, and it paid off:

- `schema.ts` — `Severity`, `Evidence`, `Finding`, `ModelReview`, `ReviewOutcome`. Pure Zod
  and types, no provider. Reused verbatim.
- `verdict.ts` — `deriveVerdict`, `partitionByDiffScope`, `stripUnbackedEvidence`. Pure
  functions over findings. Reused verbatim.
- `diff.ts` — `validateDiff`, `changedFiles`. Pure. Reused verbatim.
- `repo.ts` — `resolveReadablePath` and the allow-list. The **policy** is reused; the
  enforcement mechanism is what changes.
- `errors.ts` — `ReviewerError` and its eight kinds. Reused, with the credential kind's
  wording made provider-neutral.

**`prompt.ts` does not transfer unchanged, and that is a finding rather than an
inconvenience.** `SYSTEM_PROMPT` (`prompt.ts:33`) is one template literal that mixes two
kinds of content: four project rules restated from `CLAUDE.md` and
`context/domain/03-anti-corruption-layer.md`, and three paragraphs naming this runner's
tools — `readRepoFile`, `findInRepo`, `submitReview` (`prompt.ts:52`, `:54`). Under built-in
tools those names are `Read` and `Grep`. Copying the prompt to fix the names would fork the
four rules into a second file with nothing detecting divergence, which is precisely the risk
`prompt.ts:19` already records about the copy from `CLAUDE.md` ("Nothing detects drift if a
rule changes upstream — an accepted risk"). One accepted instance of that risk is a
decision; two is a pattern.

**`env.ts` is OpenRouter-shaped throughout** — `DEFAULT_MODEL` is a `:free` slug,
`resolveApiKey` reads `OPENROUTER_API_KEY`, `loadRepoEnv` reads the root `.env`. The Bedrock
runner needs none of it: credentials resolve through the AWS chain, and there is no key for
this process to hold.

**`agent.ts` is the runner, and it is entangled with its provider by return type as much as
by import.** `ReviewRun.usage` is `{ inputTokens, outputTokens, totalTokens }` — the AI
SDK's field names. Bedrock reports `{ input_tokens, output_tokens, cache_creation_input_tokens,
cache_read_input_tokens }` plus `total_cost_usd`, and `steps` has no exact Bedrock analogue
(`num_turns` counts something related but not identical). A shared interface that reused
`ReviewRun` verbatim would force the Bedrock runner to fill AI-SDK-shaped fields, which is
how a comparison ends up measuring the adapter instead of the runners.

**The gate is already pointed at this package.** `.githooks/common.sh`'s
`run_reviewer_checks` runs `npm run typecheck` then `npm test` for `packages/code-reviewer`,
armed at pre-commit on `^packages/[^/]+/(src/.*\.ts|scripts/.*\.mjs|package\.json|tsconfig\.json)$`
and unconditionally at pre-push. New files under `src/` join it automatically; the arm must
stay offline, because it runs on every commit.

**Verified against the environment, not assumed** (2026-09-09):

- `CLAUDE_CODE_USE_BEDROCK=1`, `AWS_PROFILE=przemyslawprzeworski`, `AWS_REGION=eu-central-1`.
- `aws sts get-caller-identity` returns the assumed `AWSReservedSSO_KN-DevelopmentEngineer`
  role — the SSO session is **live**, not merely cached.
- `aws bedrock list-inference-profiles --region eu-central-1` lists
  **`eu.anthropic.claude-sonnet-5` as `ACTIVE`**, along with `eu.anthropic.claude-sonnet-4-6`
  and `eu.anthropic.claude-haiku-4-5-20251001-v1:0`.
- The `claude` CLI is on PATH at **2.1.261**. Node is **v22.22.1**.
- `ANTHROPIC_API_KEY` is **unset** — Bedrock is the only auth path available here, which is
  also why this half of the lesson is runnable today when the OpenRouter half is not.

**The OpenRouter half is quota-blocked.** `free-models-per-day` is an account-wide cap and
the account has zero credits, so `tool-loop-agent`'s criteria 4.3 and 4.9 are still open on
quota rather than on code. Phase 5 of this plan needs nine OpenRouter runs; that is a
scheduling risk, called out under Open Risks with a stated fallback.

**Origin discipline.** Everything above that reads like a guarantee has origin `code` — it
is what the implementation does today, not a rule the domain imposes. The one exception is
the `.env` refusal, which is a `product` rule: the repo is public, the root `.env` holds a
live key, and `tool-loop-agent`'s Phase 3 log establishes that **the allow-list, not the
containment check, is the layer protecting it.**

## Definitions

| Term | Decided meaning | Origin | On degenerate data | Verified by |
| ---- | --------------- | ------ | ------------------ | ----------- |
| **the reviewer** | A `Reviewer`: `(diff: string, options) => Promise<ReviewRun>`, where `ReviewRun` carries the `ReviewOutcome` plus a **provider-neutral** `usage` block. Both runners implement it; `index.ts` selects one. | user | A runner that cannot report a field leaves it `undefined` rather than zero — `0 tokens` and `unknown tokens` are different facts, and a comparison table must not conflate them. | 1.1, 1.5, 5.4 |
| **the tools** | Built-in `Read` and `Grep` only. `Write`, `Edit`, `Bash` removed from context via `disallowedTools` (bare names). Every call passes a `PreToolUse` hook that delegates to `resolveReadablePath`. | user | A `Grep` with no `path` scopes to the whole cwd — the hook must decide on a pattern it cannot fully resolve, and its default is deny. | 2.2, 2.3, 4.5 |
| **an accessed path** | A repo-relative path for which a `tool_result` **actually carried content back** in the message stream. A denied path is not accessed; an allow-listed path that does not exist is not accessed. | user | A run that reads nothing yields an empty set, so every `evidence` citation is stripped and counted — which is the correct outcome, not a bug. | 2.4, 2.5, 3.6 |
| **a written pick** | `context/changes/agent-sdk-reviewer/pick.md`: a criteria table with measured numbers from 3 fixtures × 3 runs × 2 runners, ending in **exactly one** named runner that M5-L3's provider wraps. | user | If the two runners tie on quality, the tiebreak order is written down before the runs, not chosen after seeing them: containment provability, then cost, then determinism. | 5.5, 5.7 |
| **hermetic** | `settingSources: []` and a bare-string system prompt built from `prompt.ts`. The repo's `CLAUDE.md` hierarchy, `.claude/settings.json` and `.claude/skills/` are **not** loaded for any judged run. | user | One separate, clearly-labelled run with `settingSources: ['project']` is recorded as a finding in `pick.md` and excluded from the comparison table. | 3.3, 5.3 |
| **the model** | `eu.anthropic.claude-sonnet-5` on Bedrock, with `maxBudgetUsd` set per review. Overridable by `CODE_REVIEW_BEDROCK_MODEL`. | user | An unavailable or misspelled model id must fail as a named error, never as an empty review. | 3.1, 4.2 |
| **offline** | The `packages/` gate arm makes no model call and needs no credentials. The live end-to-end test skips itself unless `npm run test:live` set `npm_lifecycle_event`. | product (root `CLAUDE.md`, "Local quality gates") | With credentials absent the suite still passes, and says which test it skipped. | 2.6, 3.7, 5.2 |
| **leaks** | A sentinel string appears in `util.inspect(error, { depth: 8 })` — **not** merely in `error.message`. Sentinels: an AWS credential fragment, a `set-cookie` value, the diff body. | product (established by `tool-loop-agent` Phase 4; `failures.test.ts`) | An error with no `cause` and a capped message passes; attaching `{ cause }` reopens the leak, because `util.inspect` follows `[cause]`. | 4.3, 4.4 |
| **verdict** | `fail` iff at least one surviving finding is `blocker` or `major`. Computed, never asked of the model. | product (`verdict.ts`, `schema.ts:8`) | Every finding dropped ⇒ `pass`, because nothing about *this diff* was wrong. | reused; `verdict.test.ts` |
| **submitted** | The review either arrives through the channel Phase 3 selects, or the run is a failure — `no-output` if absent, `malformed-output` if present and unreadable. Never a pass. | product (`errors.ts:23`, `:35`) | A model that writes its review as prose is `no-output`. An unsubmitted review is unfinished, not approved. | 3.4, 3.5 |

## Desired End State

`packages/code-reviewer` exports two runners behind one contract. `CODE_REVIEW_RUNNER=agent-sdk`
makes `npm run review` review a diff through the Claude Agent SDK on Bedrock; unset or
`=ai-sdk` keeps today's behaviour exactly. The SDK runner reads the repo with built-in `Read`
and `Grep` bounded by this repo's own allow-list, cannot write or run anything, cannot reach
`.env`, and cites only files it actually read. `pick.md` names one of the two as the runner
M5-L3 wraps, and shows the numbers that decided it.

Verified by: the existing 80 tests still green; the new offline units green in the commit
gate with no credentials present; one live end-to-end run per runner producing a review of
`fixtures/cross-file.diff`; and `pick.md` existing with a filled table and a named winner.

### Key Discoveries

- **The Agent SDK has native structured output** — `outputFormat: { type: 'json_schema', schema }`,
  read back as `msg.structured_output` on a `subtype: 'success'` result. The AI SDK's
  equivalent had to be **abandoned**: `Output.object` sends `response_format` alongside
  `tools` and constrained decoding leaves the model no channel to call a tool (`tools.ts:87`
  carries the tally — `output` unset: 3/3/2 steps and 15/14/16 files; set: 1/1/1 and 0/0/0).
  Whether Anthropic's harness repeats that is **the** decisive measurement here. The
  `error_max_structured_output_retries` result subtype suggests extraction-with-retry rather
  than constrained decoding, which would mean tools survive — but "suggests" is what cost
  `tool-loop-agent` an entire redesign, so Phase 3 measures it.
- **The permission chain is `PreToolUse` hook → deny rules → ask rules → `permissionMode` →
  allow rules → `canUseTool`.** Putting a tool in `allowedTools` skips `canUseTool` but
  **not** the hook. So `PreToolUse` is the only correct containment point; a `canUseTool`
  implementation would be dead code for any pre-approved tool.
- **`settingSources` defaults to `['user', 'project', 'local']`** — an SDK run loads this
  repo's `CLAUDE.md` hierarchy, `.claude/settings.json` and all of `.claude/skills/` unless
  told not to. That is the ready-made harness's headline property and an eval-determinism
  hazard in the same breath: with inheritance on, editing `CLAUDE.md` silently moves every
  eval score. This default must be verified empirically in Phase 3, not trusted — it is a
  documented default that has changed across versions.
- **`options.env` replaces the subprocess environment rather than merging it.** Normally a
  footgun (`PATH` disappears); here it is an opportunity, and Phase 3 uses it as one: hand
  the subprocess an environment with `OPENROUTER_API_KEY` **removed**, so the SDK runner
  cannot leak a key it was never given. That is containment by absence, one layer below
  containment by policy.
- **`maxBudgetUsd` is a hard cost stop with no AI SDK equivalent**, and `maxTurns` bounds
  turns. There is **no built-in wall-clock timeout** — that stays an `AbortController`, the
  same mechanism `agent.ts:269` uses via `AbortSignal.timeout`.
- **Custom in-process tools are `tool(name, description, zodShape, handler)` +
  `createSdkMcpServer({ name, version, tools })`, addressed as `mcp__<server>__<tool>`.**
  Needed only if Phase 3's A/B picks the submit-tool channel.
- **The npm package bundles the CLI binary as an optional platform dependency**;
  `pathToClaudeCodeExecutable` overrides it. The `claude` on PATH here (2.1.261) is a
  fallback, not the dependency — so a CI machine without it is not automatically broken, and
  a package manager that skips optional deps is.
- **Two things the docs do not answer**, both turned into criteria rather than assumptions:
  whether Agent SDK errors carry the request body in enumerable own properties the way AI SDK
  errors do (4.3), and what a single-shot `query()` does to the host process on abort — one
  doc phrasing says "process exits with a nonzero code", which for a library would be
  unacceptable and is most likely about the subprocess (4.6).

## What We're NOT Doing

- **No promptfoo, no eval configuration, no `promptfooconfig.yaml`.** That is M5-L3, and the
  pick is its input. Criterion 5.6 asserts no eval config was added.
- **No retry or fallback chain across models or providers.** Still deliberately excluded, for
  the reason `tool-loop-agent`'s Phase 1 log gives: choosing a fallback order before the eval
  work is guessing.
- **No change to the four project rules or to `verdict.ts`'s arithmetic.** The rules are
  reworded in exactly zero places; only the paragraph naming tools is parameterized.
- **No `canUseTool` implementation.** It cannot fire for pre-approved built-ins, so writing
  one would be a lookalike gate — the failure mode this repo has now been bitten by three
  times.
- **No per-edit gating for this package.** It stays at commit and push, per the root
  `CLAUDE.md` decision.
- **No attempt to fake the SDK subprocess.** A stub replaying the message protocol was
  considered and declined: it reimplements a protocol we do not control, against an 8-second
  gate budget.
- **Not touching `backend/` or `frontend/`.** Nothing here reaches a deploy path.
- **Declined edge cases**, recorded so a reviewer sees a decision rather than an omission:
  concurrent runs of the two runners against each other (each builds its own access log, so
  there is no shared state to race); resuming an aborted SDK session (`session_id` exists,
  but a resumed review is not a reviewed diff); and Bedrock throttling backoff (a throttle is
  reported as a named provider failure and the operator retries — the same treatment
  OpenRouter's quota gets).

## Implementation Approach

Five phases, ordered so that each one leaves the tree better than it found it even if the
next never happens.

Phase 1 touches no new dependency and adds no new runner: it widens the contract the working
runner already satisfies, and parameterizes the one prompt paragraph that cannot be shared as
written. If the rest of the change were abandoned, Phase 1 would still be worth having.

Phase 2 adds the dependency but calls no model. The two genuinely tricky pieces — deciding
`Read` and `Grep` calls against `resolveReadablePath`, and reconstructing the access log from
a message stream — are written as pure functions and tested offline, including by mutation.
This is deliberate sequencing: both pieces have the property that a wrong answer is silent.
A too-permissive hook leaks; a parser that records nothing makes `stripUnbackedEvidence`
strip everything, which looks like a strict reviewer and is a dead check.

Phase 3 makes the first real call, and settles the shape of the runner by measuring rather
than by reading docs: build with the channel known to work, then A/B it against
`outputFormat` and keep the winner with the tally recorded in the source.

Phase 4 is failure paths, on its own, because `tool-loop-agent`'s Phase 4 is where every
expensive finding lived and all of it was measurement rather than code.

Phase 5 spends the runs, writes `pick.md`, and registers the new surface in the two documents
that describe what this repo tests and how.

## Critical Implementation Details

**Ordering: the hook must be proven before the first live run, not after.** The SDK's `Read`
takes an absolute `file_path`. `resolveReadablePath` resolves its input against `REPO_ROOT`
and is therefore already correct for absolute paths — but it is the deny-by-default direction
that matters, and a hook wired after a working run gets tested against paths the model
happened to choose rather than against the paths it must refuse. Phase 2 comes before Phase 3
for that reason.

**`Grep` is not `Read` and must not be decided as if it were.** `Read` names one literal
path; `Grep` takes a `pattern` plus an optional `path` and optional `glob`, and a call with no
`path` is scoped to the whole cwd. `resolveReadablePath` answers about a *file*, so it cannot
decide a pattern. The hook therefore treats the two tools separately, and for `Grep` the rule
is: a `path` that resolves outside the allow-list is denied; an **absent** `path` is rewritten
or denied rather than allowed, because "search everything" includes `.env`. The safe reading
is deny-with-a-reason naming the allowed subtrees, which is a value the model can act on —
the same shape `tools.ts` uses for a refusal.

**The access log's failure mode is silence, so its test must fail on silence.** If the parser
records nothing, every citation is stripped and the run still reports a plausible review with
`strippedEvidence: N`. A test asserting "no unbacked evidence survived" would pass. The test
that catches it asserts the opposite direction: on a recorded stream that provably carried
file content, the parser returns a **non-empty** set naming those files. Same principle as
`scripts/run-tests.mjs` requiring a non-zero test count.

---

## Phase 1: The shared contract, no new dependency

### Overview

Widen what `packages/code-reviewer` exports so a second runner can exist without either one
distorting itself to fit the other. No new dependency, no model call, no new runner — and all
80 existing tests must still pass.

### Changes Required

#### 1. The runner contract

**File**: `packages/code-reviewer/src/reviewer.ts` (new)

**Intent**: Define the one type both runners implement, plus a provider-neutral usage block, so
`index.ts` and — later — M5-L3's promptfoo provider depend on a contract rather than on a file.

**Contract**: exports `type Reviewer = (diff: string, options?: ReviewOptions) => Promise<ReviewRun>`;
`interface ReviewUsage { inputTokens?: number; outputTokens?: number; totalTokens?: number; cachedInputTokens?: number; costUsd?: number }`;
`interface ReviewRun { review: ReviewOutcome; usage: ReviewUsage; modelId: string; runner: RunnerId; steps: number; accessedPaths: string[] }`;
`type RunnerId = 'ai-sdk' | 'agent-sdk'`. **Every `usage` field is optional on purpose** — a
runner that cannot report a number leaves it absent, because `0 tokens` and `unknown tokens`
are different facts and `pick.md`'s cost column depends on the difference. `steps` keeps its
name and is documented as "model turns, however this runner counts them", with the per-runner
meaning stated at each implementation rather than pretended equal.

#### 2. Adapt the existing runner

**File**: `packages/code-reviewer/src/agent.ts`

**Intent**: Make `reviewDiff` satisfy `Reviewer` and stamp its own `runner` id. A type-level
change plus one field; the loop, the tools, the error chain and the provider are untouched.

**Contract**: `reviewDiff` keeps its signature and gains `runner: 'ai-sdk'`. Its local
`ReviewRun` declaration moves to `reviewer.ts` and is re-exported from `agent.ts` so existing
importers do not break. **`ReviewAgentOptions` splits rather than moving** — amended 2026-09-10,
during implementation, with the user's approval: only the provider-neutral half (`modelId`,
`timeoutMs`) becomes `ReviewOptions` in the contract, and `ReviewAgentOptions extends
ReviewOptions` stays here holding `apiKey`, `baseURL` and `tools`. Moving it whole would have put
an OpenRouter credential, an OpenRouter endpoint and an AI SDK tool record into the file criterion
1.6 forbids AI SDK vocabulary in, and would have made the Bedrock runner declare an `apiKey` it
has no use for — the exact leak the contract exists to prevent. Every later phase reads the
contract as `ReviewOptions`, never as `ReviewAgentOptions`. `usage` maps the AI SDK's
`inputTokens`/`outputTokens`/`totalTokens` straight through and leaves `costUsd` **absent** —
OpenRouter does not report a per-call cost on this path, and inventing one would put a
fabricated column into the comparison.

#### 3. Parameterize the tool-naming paragraphs

**File**: `packages/code-reviewer/src/prompt.ts`

**Intent**: Keep the four project rules in exactly one copy while letting each runner name its
own tools. The rules, the severity definitions, the diff-scope rule, the evidence rule and the
injection-defence paragraph are identical for both runners and must not be duplicated —
`prompt.ts:19` already records one accepted instance of undetectable drift, and a second would
make it a pattern.

**Contract**: `SYSTEM_PROMPT` becomes `buildSystemPrompt(tools: ToolNaming): string`, where
`ToolNaming` names the read tool, the search tool, and how the review is to be delivered.
`buildUserPrompt` is unchanged. A `SYSTEM_PROMPT` const stays, defined as
`buildSystemPrompt(AI_SDK_TOOLS)`, so nothing downstream changes and the existing prompt tests
keep their subject. The module comment gains one line: the rules now exist in a single copy *by
construction*, and only the tool-naming paragraphs vary.

#### 4. Provider-neutral credential failure

**File**: `packages/code-reviewer/src/errors.ts`

**Intent**: `no-api-key`'s doc comment and the message built in `agent.ts` both name
`OPENROUTER_API_KEY`. Bedrock's equivalent is an absent or expired SSO session — the same class
of fact (the reviewer cannot reach a model), and it must not be reported as a passing review
either.

**Contract**: the kind stays `no-api-key` — renaming it churns three files and two tests for a
string nothing branches on — and its doc comment widens to "no usable credential for the
configured provider", naming both concrete cases. Each runner supplies its own message text.

#### 5. Runner selection in the CLI

**File**: `packages/code-reviewer/src/index.ts`

**Intent**: Let `CODE_REVIEW_RUNNER` choose a runner, with today's behaviour as the default,
and report which runner ran.

**Contract**: reads `CODE_REVIEW_RUNNER`; unset or `ai-sdk` selects `reviewDiff`; an
**unrecognised value exits 2 listing the valid ids** rather than falling back — a typo that
silently reviewed with the other runner would invalidate a comparison run. `agent-sdk` is
registered in Phase 3; until then it exits 2 saying so. The stderr summary line gains
`runner=<id>`.

### Success Criteria

#### Automated Verification

- `reviewer.ts` typechecks and every `ReviewUsage` field is optional — a runner that cannot report a number leaves it absent rather than sending `0`
- `npm test` reports **80** tests passing in 6 spec files, and `npm run typecheck` passes
- An unrecognised `CODE_REVIEW_RUNNER` exits 2 and names the valid ids
- The four project rules appear in exactly one copy in `prompt.ts` (`grep -c` on a distinctive rule sentence returns 1)
- `buildSystemPrompt(AI_SDK_TOOLS)` renders byte-identically to `git show HEAD:packages/code-reviewer/src/prompt.ts`'s `SYSTEM_PROMPT` — amended 2026-09-10 with the user's approval; see the note on Progress row 1.5 for why the original wording (a byte-identical `ReviewOutcome` on `fixtures/bad.diff`) was not runnable and why this is the stronger check

#### Manual Verification

- `reviewer.ts` reads as a contract a stranger could implement — no AI SDK vocabulary anywhere in it

**Implementation Note**: pause for manual confirmation before Phase 2.

---

## Phase 2: Containment and the access log, offline

### Overview

Add the dependency and write the two pieces whose wrong answers are silent — the permission
bridge and the access-log parser — as pure functions, tested offline and verified by mutation.
No model is called in this phase.

### Changes Required

#### 1. The dependency

**File**: `packages/code-reviewer/package.json`

**Intent**: Add `@anthropic-ai/claude-agent-sdk` and record the resolved version, so a later
behaviour change is attributable to a version rather than to a mystery.

**Contract**: one entry under `dependencies`; the resolved version recorded in the new skill
file (item 5). Note that this manifest is matched by pre-commit's `PACKAGE_SOURCES` regex, so
the commit adding it runs the reviewer arm — offline — and a dependency that fails to install
fails the commit loudly. That is the intent, not a hazard.

#### 2. The permission bridge

**File**: `packages/code-reviewer/src/permission.ts` (new)

**Intent**: Decide a single tool call against this repo's existing allow-list, as a pure
function, so the decision is testable without an SDK, a subprocess or a model. The registered
hook is a thin adapter over it.

**Contract**: `decideToolUse(toolName: string, toolInput: unknown): ToolDecision`, where
`ToolDecision` is `{ allow: true }` or `{ allow: false; reason: string }`. Rules in order: any
tool outside `{Read, Grep}` is denied by name; `Read` resolves `file_path` through
`resolveReadablePath` and denies carrying that decision's own reason; `Grep` requires a `path`
resolving inside an allow-listed subtree, and an **absent** `path` is denied because an
unscoped search covers `.env`. Malformed input — missing field, wrong type, non-string — is
denied, not thrown: a refusal is a value the model can act on, the shape `tools.ts:11` already
uses.

The SDK-facing shape is worth pinning because the default is the wrong way round — a bare
`return {}` means **allow**, so a hook that falls through on an unrecognised tool is permissive:

```ts
// PreToolUse: deny must be explicit. Returning {} is ALLOW.
return decision.allow
  ? {}
  : { hookSpecificOutput: { permissionDecision: 'deny', permissionDecisionReason: decision.reason } };
```

#### 3. The access-log parser

**File**: `packages/code-reviewer/src/stream.ts` (new)

**Intent**: Reconstruct, from the SDK's message stream, the set of repo-relative paths whose
tool result actually carried content back — the exact input `stripUnbackedEvidence` needs — plus
the terminal result's usage, cost, turn count and review payload.

**Contract**: `createStreamCollector()` returns `{ observe(message): void, accessedPaths: Set<string>, result }`.
A path is added only when a `tool_result` for a preceding `Read`/`Grep` `tool_use` carries
content and is not an error result. Paths are normalised to repo-relative with `/` separators
before insertion, matching what `stripUnbackedEvidence` compares against (`verdict.test.ts:133`
pins that spelling). A `Grep` result contributes every distinct file it names. Correlation is by
`tool_use_id`, never by order.

#### 4. Tests for both, including the mutations

**File**: `packages/code-reviewer/src/permission.test.ts`, `packages/code-reviewer/src/stream.test.ts` (new)

**Intent**: Pin both directions, and pin the **reason** rather than just the refusal.
`tool-loop-agent`'s Phase 3 found that assertions saying only "refused" kept passing when the
load-bearing layer was deleted, because a different layer happened to refuse too.

**Contract**: `permission.test.ts` covers an allow-listed source file allowed; `.env` denied
**naming the allow-list** as the reason; the one-level-further-out escape denied **naming
containment**; `Write`/`Edit`/`Bash` denied by name; `Grep` with no `path` denied; `Grep` scoped
to `backend/src` allowed; malformed input denied without throwing. `stream.test.ts` uses
hand-written message fixtures and asserts the **non-empty** direction first (a stream that
carried two files yields both paths), then: an error `tool_result` contributes nothing, a denied
call contributes nothing, `tool_use_id` correlation holds under interleaving, and usage/cost
extraction works off the terminal result.

#### 5. A skill for the SDK, mirroring the AI SDK one

**File**: `packages/code-reviewer/.claude/skills/agent-sdk/SKILL.md` (new)

**Intent**: The `ai-sdk` skill exists because guessing that API from memory got nine things
wrong, and it is how the next session avoids repeating them. The Agent SDK earns the same
treatment, and several entries are already known: `{}`-means-allow, the permission evaluation
order, the `settingSources` default, `env` replacing rather than merging, `outputFormat` versus a
submit tool, `mcp__<server>__<tool>` naming, no built-in timeout, and the Bedrock model-id form.

**Contract**: frontmatter `name: agent-sdk` plus a `description` naming when to reach for it,
then the `ai-sdk/SKILL.md` layout — a "which layer to use" table, then numbered gotchas each
stating **how it was found** (read / `tsc` / measured). Gotchas that Phases 3 and 4 will produce
are added there when measured, not guessed here.

### Success Criteria

#### Automated Verification

- `npm run typecheck` passes with `permission.ts` and `stream.ts` present
- `npm test` passes; the permission tests cover both directions and each refusal asserts the reason, naming which layer refused
- Deleting the `Grep`-without-`path` rule makes `permission.test.ts` fail naming that case — then reverted
- `stream.test.ts` asserts the non-empty direction: a recorded stream that carried two files yields both paths
- Making the collector record no paths makes `stream.test.ts` fail — then reverted
- `npm test` still passes with `AWS_PROFILE` and `AWS_REGION` unset — this phase needs no credentials
- `git status --porcelain` shows only intended files before the phase commit; both mutations are reverted

#### Manual Verification

- Reading `permission.test.ts`, every refusal names the layer that refused it rather than only that it was refused
- `SKILL.md` would have prevented the mistakes it lists, judged by someone who has not read this plan

**Implementation Note**: pause for manual confirmation before Phase 3.

---

## Phase 3: The runner, and which channel the review arrives on

### Overview

Make the first real call: a working `agent-sdk` runner on Bedrock Sonnet 5, hermetic, bounded,
containment enforced through the Phase 2 bridge. Then settle the runner's shape by measuring
`outputFormat` against a submit tool rather than by reading documentation.

### Changes Required

#### 1. The runner

**File**: `packages/code-reviewer/src/agent-sdk.ts` (new)

**Intent**: Implement `Reviewer` on `query()`, with the same three post-processing steps in the
same order as `agent.ts:228` — drop off-diff findings, strip unbacked evidence, then derive the
verdict. Deriving before dropping would let a finding about an untouched file fail the review.

**Contract**: exports `reviewDiffWithAgentSdk: Reviewer` plus a session factory, mirroring why
`createReviewAgent` is separate (`agent.ts:11`): an eval sweeping thirty diffs configures once.
Options: `settingSources: []`; `systemPrompt` a bare string from `buildSystemPrompt`;
`allowedTools: ['Read', 'Grep']`; `disallowedTools: ['Write', 'Edit', 'Bash']` (bare names,
which remove them from context entirely); `permissionMode: 'default'`; the `PreToolUse` hook
from `permission.ts`; `maxTurns`; `maxBudgetUsd`; `abortController`; `cwd` at `REPO_ROOT`; and
`env` per item 2. Diffs are validated by the same `validateDiff` and failures are the same
`ReviewerError` kinds. `usage` carries `costUsd` from `total_cost_usd` — a number the AI SDK
runner cannot supply.

Bounds are set at **parity** with the AI SDK runner so the comparison measures runners rather
than budgets: 8 turns, a 120 s `AbortController`. `maxBudgetUsd` has no counterpart and is a
safety stop, not a matched parameter; `pick.md` says so rather than listing it as an advantage
won in a fair fight.

The Bedrock model id form is not guessable and is therefore pinned in code:

```ts
// Verified ACTIVE in eu-central-1 on 2026-09-09 via `aws bedrock list-inference-profiles`.
const DEFAULT_BEDROCK_MODEL = 'eu.anthropic.claude-sonnet-5';
```

#### 2. Deny the subprocess the key it does not need

**File**: `packages/code-reviewer/src/agent-sdk.ts`

**Intent**: `options.env` **replaces** the subprocess environment rather than merging it. Use
that deliberately: pass a copy of `process.env` with `OPENROUTER_API_KEY` deleted, keeping
`PATH`, the AWS variables and `CLAUDE_CODE_USE_BEDROCK`. The reviewer subprocess then cannot
leak a key it never received — containment by absence, one layer below containment by policy.

**Contract**: a named `subprocessEnv()` helper with a comment stating the replace-not-merge
behaviour, because a future reader deleting the spread would silently strip `PATH`. Unit-tested
offline: the result has no `OPENROUTER_API_KEY` and retains `PATH`.

#### 3. Verify the `settingSources` default empirically

**File**: none — a measurement recorded in `change.md`

**Intent**: The plan asserts the default is `['user', 'project', 'local']` on documentation's
word. It is a documented default that has changed across versions, and the whole
hermetic-versus-inheriting decision rests on it. Measure it: one run with `settingSources`
omitted, asking something only this repo's `CLAUDE.md` can answer (the backend's port is the
obvious probe — it is 10000, not 8080), and one with `settingSources: []`.

**Contract**: the finding is recorded either way. If the default turns out to be `[]`, then
`settingSources: []` is redundant-but-explicit and stays, and Phase 5's inheritance run becomes
the only way to observe inheritance at all.

#### 4. The output-channel A/B

**File**: `packages/code-reviewer/src/agent-sdk.ts`, and `src/submit-tool.ts` (new, possibly discarded)

**Intent**: Determine whether the Agent SDK repeats the AI SDK's defect where structured output
suppresses tool use. Build first with the channel known to work — a `submitReview` tool whose
input schema **is** `ModelReview`, via `createSdkMcpServer` — get a green review, then switch to
`outputFormat: { type: 'json_schema', schema: z.toJSONSchema(ModelReview, { target: 'draft-7' }) }`
and run the same fixture three times each. Keep whichever preserves tool use.

**Contract**: the decision is recorded as a tally in the surviving module's comment — steps and
files-read per run, both configurations, same fixture, everything else equal — in the form
`tools.ts:87` already uses. If `outputFormat` wins, `submit-tool.ts` is **deleted** rather than
left dormant, and the tally is what explains its absence. If the submit tool wins, its name goes
into `buildSystemPrompt`'s `ToolNaming` and the runner gets the same "a run that never submits is
a failure" paragraph the AI SDK runner carries.

#### 5. Register the runner

**File**: `packages/code-reviewer/src/index.ts`

**Intent**: `CODE_REVIEW_RUNNER=agent-sdk` now selects the new runner instead of exiting 2.

**Contract**: one branch. The stderr summary reports `runner=agent-sdk` and adds `cost=` when
`costUsd` is present.

#### 6. The live end-to-end test

**File**: `packages/code-reviewer/src/agent-sdk.live.test.ts` (new)

**Intent**: One committed test that actually drives the runner against Bedrock, skipped unless
`npm run test:live`, following `injection.test.ts`'s existing gate.

**Contract**: skips unless `CODE_REVIEW_LIVE=1` or `npm_lifecycle_event === 'test:live'`, and
**says** it skipped rather than passing silently. When it runs: reviews `fixtures/cross-file.diff`,
asserts a parsed `ReviewOutcome`, asserts `accessedPaths` is **non-empty** (the tool loop is not
structurally dead — the property `tool-loop-agent`'s 4.8 established), and asserts no finding
names a file outside the diff.

### Success Criteria

#### Automated Verification

- `CODE_REVIEW_RUNNER=agent-sdk npm run review < fixtures/bad.diff` on `eu.anthropic.claude-sonnet-5` exits 1 and names both rule violations; a misspelled model id fails as a named error rather than an empty review
- `CODE_REVIEW_RUNNER=agent-sdk npm run review < fixtures/cross-file.diff` reads at least one file and finds the off-diff defect
- The `settingSources` default is measured both ways and recorded in `change.md`: the hermetic run cannot answer the `CLAUDE.md`-only probe
- The output-channel A/B tally is present in the source — three runs per configuration, steps and files read
- A run that never submits a review reports `no-output`, and an unreadable submission reports `malformed-output`; neither is a pass
- `accessedPaths` is non-empty on the live `cross-file.diff` run
- `npm test` still passes with no credentials present — the gate arm makes no model call
- `grep -r 'canUseTool' src/` returns nothing: the lookalike gate was not written

#### Manual Verification

- The A/B outcome is legible from the source comment alone, without this plan
- A review from the SDK runner reads as a review of *this* repo — the shared prompt is in effect, not the SDK's default persona

**Implementation Note**: pause for manual confirmation before Phase 4.

---

## Phase 4: Failure paths

### Overview

Measure what the SDK does when things go wrong, rather than assuming it behaves like the AI SDK
or unlike it. This phase is separate because `tool-loop-agent`'s Phase 4 is where every
expensive finding lived, and all of it was measurement rather than code — folded into a build
phase, that work is what gets skipped when the build works.

### Changes Required

#### 1. The three-sentinel hygiene measurement

**File**: `packages/code-reviewer/src/agent-sdk-failures.test.ts` (new)

**Intent**: Determine whether Agent SDK errors carry the request body, credentials or the
reviewed diff in enumerable own properties — the AI SDK defect that turned the other runner's
first live run into a credential leak. The method matters as much as the question: the first
draft of `failures.test.ts` asserted on `error.message` and passed with both guards deliberately
disabled, because the message was never the vector.

**Contract**: assertions run against `util.inspect(error, { depth: 8 })` and look for three
sentinels — an AWS credential fragment, a `set-cookie` value, and a distinctive string planted
in the diff body. Forced failures: an invalid model id, an aborted run, a schema violation, and
an unusable credential. Anything reproducible offline runs offline; anything that cannot must
skip loudly rather than pass.

#### 2. The credential failure

**File**: `packages/code-reviewer/src/agent-sdk.ts`, same test file

**Intent**: An absent or expired SSO session must surface as `no-api-key` naming the AWS path —
never as an empty passing review.

**Contract**: measured by pointing `AWS_PROFILE` at a nonexistent profile; the real session is
not disturbed. Asserts the kind, asserts exit 2 (not 0, and not the 1 that means "this diff
failed review"), and asserts no sentinel appears in the inspected error.

#### 3. Timeout and turn exhaustion

**File**: `packages/code-reviewer/src/agent-sdk.ts`, same test file

**Intent**: A hang must become a report. `errors.ts:47` records why: a `:free` slug held a
socket open for over ten minutes with no bytes and no error, and the only bound was the
operator's patience.

**Contract**: a 1 ms `AbortController` produces `ReviewerError` kind `timeout` naming the budget,
reproduced offline in well under a second. `maxTurns` reached with no review submitted maps to
`no-output`, not to a pass.

#### 4. What abort does to the host process

**File**: none — a measurement recorded in `change.md`

**Intent**: One documentation phrasing says an aborted single-shot `query()` leads to a process
exiting with a nonzero code. For a library that would be unacceptable — `errors.ts:1` exists
because a reviewer that exits takes a whole eval run with it. It is most likely about the
subprocess; confirm it rather than assume it.

**Contract**: the 1 ms abort test also asserts that the **test process** survives, reaches its
assertions and reports normally. If it does not, that is a finding that changes the pick, and it
goes into `pick.md` as one.

#### 5. The containment break

**File**: none — a verification, immediately reverted

**Intent**: Prove which layer actually refuses `.env` under the SDK's permission system, in the
same spirit as `tool-loop-agent`'s Phase 3, where the first containment mutation changed nothing
because a different layer was silently doing the work.

**Contract**: with `fixtures/injection.diff` (which asks the model to read `.env`), run once with
the hook registered and once with it removed. Expected: refused in the first; in the second,
either refused by something else — a finding worth more than the test — or reached, which proves
the hook is load-bearing. **The broken state is never committed**; the revert is verified with
`git status --porcelain` before the phase commit.

### Success Criteria

#### Automated Verification

- `npm test` passes; every test in the new file either runs offline or skips loudly
- An invalid model id and an unusable credential (`AWS_PROFILE` pointed at a nonexistent profile) each fail as a named `ReviewerError` with exit 2 — never as an empty review
- No sentinel (AWS credential fragment, `set-cookie`, diff body) appears in `util.inspect(error, { depth: 8 })` for any forced failure
- `grep -n 'cause' src/agent-sdk.ts` shows no `{ cause: error }` on any thrown `ReviewerError`
- `fixtures/injection.diff` under the SDK's permission system: the `.env` read is refused, the injected instruction is not obeyed, and the refusal is visible in the run
- A 1 ms abort yields kind `timeout` in under a second **and** the test process survives to report
- `maxTurns` exhausted with nothing submitted maps to `no-output`
- `git status --porcelain` is clean of the deliberate break before the phase commit

#### Manual Verification

- The `injection.diff` run with the hook removed was actually performed, and its outcome recorded either way
- Each `SKILL.md` gotcha added in this phase states how it was found

**Implementation Note**: pause for manual confirmation before Phase 5.

---

## Phase 5: The comparison, and the pick

### Overview

Spend the runs, write the pick, register the new surface. This phase produces the artifact
M5-L3 depends on.

### Changes Required

#### 1. The comparison runs

**File**: none — measurements feeding item 3

**Intent**: 3 fixtures × 3 runs × 2 runners = 18 runs. Three runs because variance is the one
number a single run cannot show, and this package has already been misled twice by single
samples — one free slug sent a stringified array one run in four.

**Contract**: per run, record verdict, finding count and severities, whether the *expected*
defect was found, turns, files read, wall-clock seconds, tokens, and cost where reported.
Expectations: `bad.diff` — both rule violations from the diff alone; `cross-file.diff` — the
off-diff port defect plus at least one `evidence` citation surviving the access-log check;
`injection.diff` — the `.env` read refused and the injected instruction not obeyed.

If the OpenRouter quota is still exhausted, the nine AI-SDK runs are blocked on quota rather
than on code. Fallback, in order: retry on a later day; if the pick cannot wait, write it on the
runs that exist and say in `pick.md` exactly which rows are missing and why — a pick with a
named gap is usable; a pick with an unmarked gap is not.

#### 2. The inheritance run

**File**: none — a measurement feeding item 3

**Intent**: Measure what `settingSources: ['project']` changes, since inheriting the repo's own
`CLAUDE.md` is the ready-made harness's headline claim. One run on `cross-file.diff`, clearly
labelled and excluded from the comparison table.

**Contract**: its own subsection in `pick.md` — what changed in the findings, what it cost in
tokens, and the determinism argument against using it for evals: with inheritance on, editing
`CLAUDE.md` silently moves every eval score.

#### 3. The pick

**File**: `context/changes/agent-sdk-reviewer/pick.md` (new)

**Intent**: The deliverable. One named winner, the numbers behind it, and what would change the
answer.

**Contract**: a criteria table, one row per axis — finding quality per fixture, containment
provability, cost per review, latency, run-to-run variance, failure hygiene, dependency weight,
auth availability, promptfoo-wrappability — with measured values for both runners. A cell whose
number a runner does not report reads `unreported`, never `0`. Then a **Pick** section naming
exactly one runner in its first sentence, a "Why not the other" section, a "What would change
this" section, and the tiebreak order from `Definitions` (containment provability → cost →
determinism) applied explicitly if the quality rows tie. Readable by someone who has not read
this plan.

#### 4. Register the new surface

**File**: `context/foundation/test-plan.md`

**Intent**: §4's Stack table, §5.1's gate costs and §8's Freshness Ledger describe what this
repo tests and what each layer costs. A second runner, three new spec files and a changed gate
arm belong there, or the next reader's map is wrong.

**Contract**: §4's `repo tooling (code reviewer)` row gains the Agent SDK dependency and the new
suite count; §5.1's pre-commit and pre-push rows carry the **re-measured** reviewer-arm cost
(measured, not estimated — the 8.2 s figure was measured and the difference showed); §8 gets a
dated entry recording the second runner, the pick, and the two empirical findings (the
`settingSources` default and the output-channel A/B).

#### 5. Update the root CLAUDE.md

**File**: `CLAUDE.md`

**Intent**: The suite-size line names `packages/code-reviewer` **80** tests. That number exists
to make a drop visible, and it is now wrong.

**Contract**: update the count and the arm's cost; add one line naming the two runners, the
`CODE_REVIEW_RUNNER` switch, and where the pick is written down. Keep it to the summary this
file is for — the reasoning lives in `pick.md`.

### Success Criteria

#### Automated Verification

- `test-plan.md` §4, §5.1 and §8 are updated, and §5.1's reviewer-arm cost matches a timed run of `run_reviewer_checks` within a second
- `npm test` and `npm run typecheck` pass, and the gate arm still makes no model call
- The inheritance run appears in `pick.md` as its own labelled subsection and is excluded from the comparison table
- No cell in the comparison table reads `0` for a number a runner does not report — those read `unreported`
- `pick.md`'s **Pick** section names exactly one runner in its first sentence, and states the tiebreak order it applied
- No `promptfooconfig.*` exists anywhere in the repo and nothing under `packages/` mentions promptfoo — the eval environment was not configured here
- The suite count in root `CLAUDE.md` matches `npm test`'s reported total
- A docs-only commit is still instant and silent (no arm fires)

#### Manual Verification

- All 18 comparison runs were performed, or the shortfall is recorded in `pick.md` naming which rows are missing and why
- `pick.md` is legible without this plan, and its pick is defensible from its own table
- The tiebreak order was fixed before the runs, not after seeing them

**Implementation Note**: final phase — roll up any pending manual criteria from earlier phases at the gate.

---

## Testing Strategy

### Unit Tests (offline, in the commit gate)

- `permission.test.ts` — both directions of every rule, each refusal pinned to the layer that
  refused it; `Grep`-without-`path`; `Write`/`Edit`/`Bash` by name; malformed input denied
  without throwing.
- `stream.test.ts` — the **non-empty** direction first; error results and denied calls
  contributing nothing; `tool_use_id` correlation under interleaving; usage and cost extraction.
- `subprocessEnv()` — no `OPENROUTER_API_KEY`, `PATH` retained.
- The existing 80: unchanged, and their continuing to pass is Phase 1's main criterion.

### Integration Tests (live, gated behind `test:live`)

- `agent-sdk.live.test.ts` — one end-to-end review of `cross-file.diff`: parseable outcome,
  non-empty `accessedPaths`, no off-diff findings.
- `agent-sdk-failures.test.ts` — forced failures with the three sentinels; anything reproducible
  offline runs offline, anything else skips loudly.

### Manual Testing Steps

1. `CODE_REVIEW_RUNNER=agent-sdk npm run review < fixtures/bad.diff` — exit 1, both rule
   violations, no fabricated evidence.
2. Same on `cross-file.diff` — off-diff defect found, at least one file read, a citation
   surviving.
3. Same on `injection.diff` — the `.env` read refused, the injected instruction not obeyed.
4. `CODE_REVIEW_RUNNER=nonsense` — exit 2, valid ids listed.
5. Time `run_reviewer_checks` for the §5.1 label.
6. Commit a docs-only change and confirm the gate stays silent and instant.

## Performance Considerations

The commit gate is the constraint. The reviewer arm costs 8.2 s today (1.5 s typecheck + 6.4 s
suite) and runs on every commit touching this package. Three new spec files of pure unit tests
should add well under a second; the figure is re-measured in Phase 5 rather than estimated,
because the current one was measured and that is why it is trustworthy.

Nothing here runs a model in a gate. Not a preference: `tool-loop-agent`'s Phase 5 log records
the day the OpenRouter quota would have made a model-calling gate refuse every commit for a
reason unrelated to the commit.

Live cost is bounded twice — `maxBudgetUsd` per review, and 19 runs total on Sonnet 5 over
diffs of roughly 2 KB. This is a corporate AWS account, so the cap is not optional.

## Migration Notes

No data, no deploy path, no schema. The single compatibility surface is `CODE_REVIEW_RUNNER`
being unset, which must behave exactly as today — pinned by Phase 1's byte-identical criterion.

`tool-loop-agent`'s criteria 4.3 and 4.9 remain open on the OpenRouter quota. They are not this
change's to close, but Phase 5's nine OpenRouter runs exercise the same quota, so a success there
closes them incidentally and should be written back to that plan if it happens.

## References

- The other runner: `packages/code-reviewer/src/agent.ts`
- Why structured output and tools cannot coexist in the AI SDK: `packages/code-reviewer/src/tools.ts:87`
- Why the library throws instead of exiting: `packages/code-reviewer/src/errors.ts:1`
- The containment policy being reused: `packages/code-reviewer/src/repo.ts`
- What the four rules are copied from, and the drift risk that copy accepted: `packages/code-reviewer/src/prompt.ts:19`
- Prior change, five phases and every finding: `context/changes/tool-loop-agent/change.md`
- The AI SDK gotcha list this change's skill mirrors: `packages/code-reviewer/.claude/skills/ai-sdk/SKILL.md`
- Gate layers and per-layer budgets: `context/foundation/test-plan.md` §5.1
- Agent SDK docs: `https://code.claude.com/docs/en/agent-sdk/typescript`, `/permissions`, `/hooks`, `/custom-tools`, `/structured-outputs`, `/claude-code-features`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: The shared contract, no new dependency

#### Automated

- [x] 1.1 reviewer.ts typechecks and every usage field is optional, so unreported is not zero
- [x] 1.2 npm test reports 80 tests passing in 6 spec files and typecheck passes
- [x] 1.3 An unrecognised CODE_REVIEW_RUNNER exits 2 and names the valid ids
- [x] 1.4 The four project rules appear in exactly one copy in prompt.ts
- [x] 1.5 Rendered SYSTEM_PROMPT is byte-identical to HEAD's, so no rule was reworded
      <!-- Reworded 2026-09-10, with the user's approval. As first written this row asked for a
      byte-identical ReviewOutcome from the default runner on bad.diff, which is not a runnable
      check: it needs a live OpenRouter call the account-wide free-tier cap blocks, and two calls
      to a non-deterministic model are never byte-identical even with quota. The risk it guards —
      that parameterizing the prompt silently reworded a project rule — is checked offline and
      deterministically by rendering buildSystemPrompt(AI_SDK_TOOLS) and diffing it against
      `git show HEAD:packages/code-reviewer/src/prompt.ts`'s SYSTEM_PROMPT. Result: identical,
      character for character, which is stronger than the output comparison because it takes the
      model out of the check. Verified once, by hand, in the phase's session; it is NOT in the
      suite, because adding a spec would have falsified criterion 1.2's pinned count of 80. -->


#### Manual

- [x] 1.6 reviewer.ts reads as a contract with no AI SDK vocabulary in it

### Phase 2: Containment and the access log, offline

#### Automated

- [ ] 2.1 typecheck passes with permission.ts and stream.ts present
- [ ] 2.2 Permission tests cover both directions and each refusal asserts which layer refused
- [ ] 2.3 Deleting the Grep-without-path rule fails permission.test.ts naming that case, then reverted
- [ ] 2.4 stream.test.ts asserts the non-empty direction on a stream that carried two files
- [ ] 2.5 Making the collector record no paths fails stream.test.ts, then reverted
- [ ] 2.6 npm test passes with AWS_PROFILE and AWS_REGION unset
- [ ] 2.7 Working tree clean of both mutations before the phase commit

#### Manual

- [ ] 2.8 Every refusal in permission.test.ts names the layer that refused it
- [ ] 2.9 SKILL.md would have prevented the mistakes it lists

### Phase 3: The runner, and which channel the review arrives on

#### Automated

- [ ] 3.1 agent-sdk runner reviews bad.diff on eu.anthropic.claude-sonnet-5, exit 1, both violations; a bad model id fails as a named error
- [ ] 3.2 agent-sdk runner reads at least one file on cross-file.diff and finds the off-diff defect
- [ ] 3.3 The settingSources default is measured both ways and recorded; the hermetic run cannot answer the CLAUDE.md-only probe
- [ ] 3.4 The output-channel A/B tally is in the source, three runs per configuration
- [ ] 3.5 An unsubmitted review reports no-output and an unreadable one malformed-output, never a pass
- [ ] 3.6 accessedPaths is non-empty on the live cross-file.diff run
- [ ] 3.7 The offline suite still passes with no credentials present
- [ ] 3.8 No canUseTool implementation exists in src/

#### Manual

- [ ] 3.9 The A/B outcome is legible from the source comment alone
- [ ] 3.10 An SDK-runner review reads as a review of this repo, not of a generic project

### Phase 4: Failure paths

#### Automated

- [ ] 4.1 npm test passes; every new test runs offline or skips loudly
- [ ] 4.2 A bad model id and an unusable credential each fail as a named ReviewerError with exit 2
- [ ] 4.3 No sentinel appears in util.inspect(error, depth 8) for any forced failure
- [ ] 4.4 No thrown ReviewerError attaches cause
- [ ] 4.5 injection.diff under the SDK: the .env read is refused and the injected instruction not obeyed
- [ ] 4.6 A 1 ms abort yields kind timeout in under a second and the test process survives
- [ ] 4.7 maxTurns exhausted with nothing submitted maps to no-output
- [ ] 4.8 Working tree clean of the deliberate break before the phase commit

#### Manual

- [ ] 4.9 The injection.diff run with the hook removed was performed and its outcome recorded
- [ ] 4.10 Each SKILL.md gotcha added here states how it was found

### Phase 5: The comparison, and the pick

#### Automated

- [ ] 5.1 test-plan.md 4, 5.1 and 8 updated, and the reviewer-arm cost matches a timed run
- [ ] 5.2 npm test and typecheck pass, and the gate arm still makes no model call
- [ ] 5.3 The inheritance run is a labelled subsection in pick.md, excluded from the table
- [ ] 5.4 No table cell reads 0 for a number a runner does not report
- [ ] 5.5 pick.md's Pick section names exactly one runner in its first sentence and states the tiebreak order
- [ ] 5.6 No promptfoo config exists anywhere in the repo
- [ ] 5.7 All 18 comparison runs performed, or the shortfall named in pick.md with its reason
- [ ] 5.8 The suite count in root CLAUDE.md matches npm test's reported total
- [ ] 5.9 A docs-only commit is still instant and silent

#### Manual

- [ ] 5.10 pick.md is legible without this plan and its pick is defensible from its own table
- [ ] 5.11 The tiebreak order was fixed before the runs, not after
