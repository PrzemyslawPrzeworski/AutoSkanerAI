---
name: agent-sdk
description: Claude Agent SDK 0.3.267 API reference for this package — query(), tool restriction, PreToolUse permission hooks, the message stream, usage/cost, structured output, and the Bedrock auth path. Use when writing or changing the agent-sdk runner under packages/code-reviewer/src.
---

# Claude Agent SDK — the parts this package uses

Verified against `@anthropic-ai/claude-agent-sdk@0.3.267` (the resolved version
behind `^0.3.267`) on 2026-09-10.

**Provenance, because it changes how much to trust each line.** Every gotcha
below is marked **[read]**, **[tsc]** or **[measured]**:

- **[read]** — from the shipped `.d.ts`, with the line cited. `sdk.d.ts` is
  ~6 000 lines of declarations with unusually dense doc comments, and it is the
  only authority here.
- **[tsc]** — the compiler caught it while writing this package.
- **[measured]** — a run proved it.

**As of Phase 2 there are no [measured] entries.** Phase 2 is offline by
construction: `permission.ts` and `stream.ts` are pure functions tested against
hand-written fixtures, and nothing has spawned a subprocess yet. That is a real
limit on this file — the fixtures in `stream.test.ts` could disagree with what
the SDK actually emits and no test would say so. Phase 3 runs the loop for the
first time and is where the [measured] lines get added. **Until then, treat every
claim about runtime behaviour as a claim about the declarations.**

The other half of this comparison is `../ai-sdk/SKILL.md`. Read both before
deciding either runner is "simpler" — the two SDKs put the difficulty in
different places, which is the whole question `pick.md` answers.

## Which layer to reach for

| You want | Use |
|---|---|
| one prompt, stream the whole session back | `query({ prompt, options })` and `for await` |
| restrict which tools **exist** | `options.tools: ['Read', 'Grep']` — gotcha 3 |
| decide each call against a policy | `options.hooks.PreToolUse` — gotchas 1 and 2 |
| skip the approval prompt for a tool | `options.allowedTools` — **not** a policy; gotcha 4 |
| a schema-shaped answer | `options.outputFormat` — gotcha 8, and it changes where the answer lands |
| a hard ceiling on a runaway loop | `maxTurns` + `maxBudgetUsd` — gotcha 9; there is no timeout option |
| to know what it read | reconstruct it from the stream — gotcha 6 |

There is no `Agent` class and nothing to construct. `query()` (sdk.d.ts:2953) is
the entire entry point:

```ts
import { query } from '@anthropic-ai/claude-agent-sdk';

for await (const message of query({ prompt, options })) {
  collector.observe(message);
}
```

The unit of reuse is therefore an `Options` object, not an object with a
`.generate()`. Worth noting for the comparison: the AI SDK's `ToolLoopAgent` is
constructed once and called many times; here the config travels as a plain
value.

## Permission: the hook is the containment boundary

```ts
options.hooks = {
  PreToolUse: [{ hooks: [async (input) => {
    if (input.hook_event_name !== 'PreToolUse') return {};
    return toPreToolUseOutput(decideToolUse(input.tool_name, input.tool_input));
  }] }],
};
```

`HookCallbackMatcher` (sdk.d.ts:866) is `{ matcher?, hooks: HookCallback[], timeout? }`
— the array of functions is under `hooks`, nested inside an array of matchers, so
the shape is `hooks: { PreToolUse: [{ hooks: [fn] }] }`. `matcher` is a tool-name
pattern; omit it to see every call. `HookCallback` (sdk.d.ts:859) is
`(input, toolUseID, { signal }) => Promise<HookJSONOutput>` — always async, and
`input` is a 33-way union (`HookInput`, sdk.d.ts:875) that must be narrowed on
`hook_event_name` before `tool_name` exists.

**Gotcha 1 — returning `{}` ALLOWS the call. [read]** Deny is the explicit case
(`HookPermissionDecision`, sdk.d.ts:879, is `'allow' | 'deny' | 'ask' | 'defer'`).
A hook written as "recognise the dangerous tools, deny those, fall through
otherwise" is therefore permissive by construction, and every tool a later SDK
version adds walks straight through it. `decideToolUse` denies on its **default**
branch and allows exactly two names for this reason, and
`permission.test.ts` pins it with a tool name that does not exist.

**Gotcha 2 — `hookEventName` is required inside `hookSpecificOutput`, and
omitting it is a deny that does not deny. [tsc]**
`PreToolUseHookSpecificOutput` (sdk.d.ts:2577) is
`{ hookEventName: 'PreToolUse'; permissionDecision?; permissionDecisionReason?; … }`.
`hookSpecificOutput` is a union across all 33 hook events and `hookEventName` is
its discriminant, so without it the object is not a `PreToolUse` output at all.
This package's plan contained a sketch that omitted it. `tsc` rejected it — the
good outcome; the bad one is the same mistake in a JS project, where the refusal
would be silently ignored and the read would proceed.

`SyncHookJSONOutput` also carries a top-level legacy `decision?: 'approve' | 'block'`.
Two ways to say the same thing; use `hookSpecificOutput` and do not mix them.

**Gotcha 3 — restricting `tools` and restricting permission are different
mechanisms, and this package uses both. [read]** `options.tools` (sdk.d.ts:1505)
sets the base set that *exists* — `[]` disables all built-ins. `allowedTools`
(sdk.d.ts:1449) marks tools *auto-approved*. Neither is a policy: `tools` cannot
express "`Read` but only under `backend/src`". Pass `tools: ['Read', 'Grep']`
**and** deny in the hook, because a tool that does not exist cannot be argued
into existence and a hook can be misconfigured.

**Gotcha 3b — a runner that does not name `Grep` may get search as `Bash`
instead. [read]** Verbatim from the `tools` doc comment: *"native builds may
provide search via Bash `find`/`grep` instead of the dedicated Grep/Glob tools.
List Grep/Glob here or in `allowedTools` to get them."* `Bash` is denied here, so
the failure mode of forgetting `Grep` is not an error — it is a reviewer that
quietly never searches and cites less.

**Gotcha 4 — do not reason about hook-versus-permission ordering; the
declarations only pin part of it. [read]** `PermissionDeniedHookInput`
(sdk.d.ts:4879) says denials *"that resolve before canUseTool runs — PreToolUse
hook denies, and deny-rule overrides of hook allow/ask decisions — are not
covered"* by that event. So a hook deny resolves **before** `canUseTool`. What is
**not** documented anywhere in `sdk.d.ts` is whether listing a tool in
`allowedTools` short-circuits the hook. If it does, `allowedTools` would be a
containment hole. **Open question for Phase 3 to measure.** Until it is measured,
this package lists nothing in `allowedTools` that the hook is relied on to bound.

`permissionMode` (sdk.d.ts:1834, values at 2317) is
`'default' | 'acceptEdits' | 'bypassPermissions' | 'plan' | 'dontAsk' | 'auto'`.
For a headless reviewer, `'dontAsk'` is documented as *"Don't prompt for
permissions, deny if not pre-approved"* — deny-by-default with no interactive
prompt, which is the only mode whose failure is a refusal rather than a hang.
`'bypassPermissions'` additionally requires `allowDangerouslySkipPermissions: true`;
never set either here.

## The result, and its authoritative records

**Gotcha 5 — `usage` is the wrong field for accounting; `modelUsage` is the right
one, and they are spelled differently. [read]** `SDKResultSuccess`
(sdk.d.ts:5032) carries both, and the doc comment on `usage` (sdk.d.ts:5060) says
verbatim: *"MAIN AGENT LOOP ONLY — excludes Task subagent, sidechain, and
auxiliary model calls, and is per-turn in streaming-input sessions. Prefer
modelUsage for token/cost accounting."*

The spellings are not interchangeable, and this is the single easiest way to
silently zero the comparison table:

| | field names |
|---|---|
| `modelUsage: Record<string, ModelUsage>` (sdk.d.ts:1307) | **camelCase** — `inputTokens`, `outputTokens`, `cacheReadInputTokens`, `cacheCreationInputTokens`, `costUSD` |
| `usage: NonNullableUsage` (a `BetaUsage`) | **snake_case** — `input_tokens`, `output_tokens`, `cache_read_input_tokens` |

`modelUsage` is keyed by model id, so it must be summed across entries. Note
`costUSD` — capital USD on `ModelUsage`, against `total_cost_usd` on the result.

**Gotcha 6 — cost and usage are cumulative across `result` messages, so the LAST
one is the answer. [read]** Adding them up double-counts: the second result's
`total_cost_usd` already contains the first's. `stream.ts` keeps only the latest
and `stream.test.ts` pins it.

`total_cost_usd` (sdk.d.ts:5058) is an SDK-computed **estimate**, not an invoice —
settings can even reprice it against contracted rates (sdk.d.ts:6026). It is
still the one number the AI SDK runner cannot produce at all, which is why
`ReviewUsage.costUsd` is optional rather than defaulted: `0` and "this runner
does not say" are different facts.

**Gotcha 7 — there is no access log; the set of files actually read has to be
reconstructed from two different message types. [read]** `SDKAssistantMessage.message`
is a Messages API message whose `content` carries the `tool_use` blocks (what was
asked); `SDKUserMessage.message` carries the `tool_result` blocks (whether it
worked). Correlate on `tool_use_id`, never on order. Structured tool output
arrives as `tool_use_result`, a **sibling** of `message` and **singular** — so
with two `tool_result` blocks in one message there is no way to know which it
describes. Useful shapes: `FileReadOutput.file.filePath` (sdk-tools.d.ts:208) for
`Read`, and `GrepOutput.filenames` (sdk-tools.d.ts:3443) — the only structured
record of which files a search touched. `GrepInput.path` is the subtree searched,
not a file found; recording it would vouch for every file underneath.

`result.permission_denials` (sdk.d.ts:5068) is worth more than it looks: the doc
at sdk.d.ts:4879 calls it *"the authoritative record"* of denials. A containment
claim can be checked against the subprocess's own account rather than against a
log line this code chose to write.

`FileReadInput.file_path` (sdk-tools.d.ts:805) is documented **absolute**, and
`GrepInput.path` is optional and *"Defaults to current working directory"* — which
is the repo root, which holds the gitignored `.env`. An absent `path` is the
broadest possible search, not an incomplete request; `permission.ts` denies it.

## Structured output moves the answer

**Gotcha 8 — `outputFormat` is implemented as an end-turn tool, so the review
does not arrive in `result.result`. [read]** `outputFormat` (sdk.d.ts:1821) is
`{ type: 'json_schema'; schema: Record<string, unknown> }` — `OutputFormat` has
exactly one member (sdk.d.ts:2290). The doc at sdk.d.ts:1957 describes what that
does to a turn: *"End-turn tool sessions (`outputFormat: {type: 'json_schema'}` …):
a completed turn there ends on a successful tool_result carrier — with no trailing
assistant message — followed by a `structured_output` attachment holding the
turn's actual output (the carrier's data is a placeholder)."*

Three consequences:

1. Read the answer from `result.structured_output` (sdk.d.ts:5073), not from
   `result.result`. `stream.ts` carries both and privileges neither, because
   which channel to use is the Phase 3 measurement.
2. `schema` is **raw JSON Schema**, not a Zod schema. The shared `ModelReview`
   Zod schema has to be converted (`z.toJSONSchema`) rather than passed.
3. Unlike the AI SDK — where `output: Output.object(...)` suppressed tool calls
   entirely and left the read tools unreachable (`../ai-sdk/SKILL.md` gotcha 8) —
   nothing in these declarations says `outputFormat` blocks tool use, and the
   end-turn-tool implementation suggests it should not. **Not measured. Do not
   assume it; Phase 3 checks whether files are still read with `outputFormat` set,
   because that specific regression is invisible: the run answers correctly
   shaped, having read nothing.**

## Bounds, and the one that does not exist

**Gotcha 9 — there is no request timeout on `Options`. [read]** The only
`timeout` fields are per-MCP-server tool-call timeouts (sdk.d.ts:540, 1070, …) and
the hook matcher's timeout in seconds (sdk.d.ts:869). A wall-clock bound on the
whole review is the caller's job, via `abortController` (sdk.d.ts:1401) plus a
timer. That matters here because `ReviewOptions.timeoutMs` is part of the shared
contract, so this runner has to implement by hand what the AI SDK gave for free.

The budget bounds that do exist:

- `maxTurns` (sdk.d.ts:1773) — a turn is a user message plus an assistant response.
  Not an AI SDK step; `ReviewRun.steps` deliberately means "however this runner
  counts", and this runner reports `num_turns`.
- `maxBudgetUsd` (sdk.d.ts:1778) — *"The query will stop if this budget is
  exceeded, returning an `error_max_budget_usd` result."* Note the failure mode is
  a **result subtype**, not a thrown error, so a runner that only inspects
  `result.result` reads a budget kill as an empty review. `stream.ts` carries
  `subtype` verbatim so Phase 4 can report it by its own name.

## Settings, environment, and the subprocess

**Gotcha 10 — omitting `settingSources` loads every filesystem setting, including
this repo's own hooks and CLAUDE.md. [read]** sdk.d.ts:2086, verbatim: *"When
omitted, all sources are loaded (matches CLI defaults). Pass `[]` to disable
filesystem settings (SDK isolation mode). Must include `'project'` to load
CLAUDE.md files."*

Both directions are hazards, and this repo makes both concrete:

- **Omitted** — the subprocess inherits `.claude/settings.json`, which here wires
  a `PostToolUse` hook that runs prettier and the whole frontend suite on every
  edit. A reviewer cannot edit, so it should not fire; relying on that is relying
  on the tool restriction being right.
- **`[]`** — no CLAUDE.md, so the reviewer cannot read the rules it is enforcing.
  That is a genuine loss for this reviewer specifically: `repo.ts`'s allow-list
  includes the three `CLAUDE.md` files precisely so the rules are readable.

Decide deliberately and write down which was chosen. This is the option most
likely to make the two runners incomparable without anyone noticing, since one of
them cannot load a CLAUDE.md at all.

**Gotcha 11 — `options.env` REPLACES the subprocess environment; it does not
merge. [read]** sdk.d.ts:1512, verbatim: *"When set, this value REPLACES the
subprocess environment entirely — it is not merged with `process.env`. Spread
`process.env` yourself if the subprocess still needs inherited variables like
`PATH`, `HOME`, or `ANTHROPIC_API_KEY`."* Setting one variable therefore unsets
`PATH` and every AWS credential variable at once. Spread, or omit the option:

```ts
env: { ...process.env, CLAUDE_CODE_USE_BEDROCK: '1' }
```

## The auth path: Bedrock, not an API key

This runner authenticates completely differently from the AI SDK one, and it is
the difference `pick.md` cares about most, because it is the one a CI job feels.

| | ai-sdk runner | agent-sdk runner |
|---|---|---|
| credential | `OPENROUTER_API_KEY` from the gitignored root `.env` | AWS SSO profile, short-lived |
| fails when | the variable is unset | the SSO session expired |
| renewable in CI | yes, it is a secret | no, it is an interactive login |

The environment this repo uses: `CLAUDE_CODE_USE_BEDROCK=1`, `AWS_REGION=eu-central-1`,
`AWS_PROFILE=przemyslawprzeworski`, and `ANTHROPIC_API_KEY` unset. The Bedrock
model id takes a region prefix — `eu.anthropic.claude-sonnet-5` — which is not the
form `Options.model`'s own examples use (`'claude-sonnet-5'`, sdk.d.ts:1808), so
the id is a property of the provider, not of the SDK.

**The dangerous failure is the expired session, not the missing one.** An unset
`OPENROUTER_API_KEY` is absent and says so. An expired SSO session leaves
`AWS_PROFILE` set, `~/.aws/config` intact, and every check that tests for a
non-empty variable passing — configuration that looks complete and is not. Same
shape as this repo's `require_java` bug, where `JAVA_HOME` pointed at a JRE with
no `javac` and the gates died complaining about heap size: **check for the
capability, not for the variable.** `errors.ts`'s `no-api-key` covers both cases
and its doc comment says which is which.

**Never copy AWS credentials into Render.** The only credential source here is a
short-lived corporate SSO profile.

## Running it here

Node 22, ESM, no build step: `npx tsx src/index.ts`, with
`CODE_REVIEW_RUNNER=agent-sdk` selecting this runner. The SDK spawns a `claude`
subprocess (`pathToClaudeCodeExecutable`, sdk.d.ts:1825, overrides which one), so
this runner cannot be part of an offline gate — `.githooks/common.sh` runs
`npm test`, and every test that touches this SDK must stay a pure-function test
over fixtures. The live path belongs behind `npm run test:live`.
