# Plan Brief: Build the reviewer a second time on the Claude Agent SDK, then pick

**Change ID**: `agent-sdk-reviewer`
**Full plan**: `context/changes/agent-sdk-reviewer/plan.md`
**Base**: `d299d4d` on `main` — depends on `tool-loop-agent` (implemented)

## What We're Doing

Building a second code reviewer inside `packages/code-reviewer`, on the Claude Agent
SDK against Bedrock, over the same schema, prompt, verdict arithmetic and fixtures as
the AI SDK one. Then measuring the two against each other and writing down which one
wins, in `pick.md`.

## Why

This is M5-L2's actual content: the lesson deliberately builds the same tool twice —
once assembled from parts, once on a ready-made harness — and the comparison *is* the
lesson. The pick is not a formality either. M5-L3's promptfoo custom provider wraps
**one** runner, so leaving two side by side pushes the decision into the next lesson
and risks building the provider twice.

There is also a practical reason this half is runnable today and the other half is
not: `ANTHROPIC_API_KEY` is unset here, Bedrock is reachable through a live corporate
SSO session, and the OpenRouter free-tier daily cap is account-wide and exhausted.

## Key Decisions

| Decision | Choice | Source |
|---|---|---|
| Structure | One `Reviewer` contract in `reviewer.ts`, two implementations; `CODE_REVIEW_RUNNER` selects | Plan |
| Usage reporting | Provider-neutral, every field optional — a runner that cannot report a number leaves it absent, never `0` | Plan |
| Repo access | Built-in `Read` + `Grep`, bounded by this repo's existing `resolveReadablePath` allow-list via a `PreToolUse` hook | Plan |
| Containment point | `PreToolUse` only. No `canUseTool` — it cannot fire for pre-approved built-ins, so it would be a lookalike gate | Plan |
| Key hygiene | `options.env` replaces rather than merges, so the subprocess is handed an env with `OPENROUTER_API_KEY` deleted | Plan |
| Accessed path | A path whose `tool_result` actually carried content, reconstructed from the message stream by `tool_use_id` | Plan |
| Output channel | Measured, not assumed: submit-tool vs native `outputFormat`, three runs each, winner kept and the tally recorded in source | Plan |
| Settings inheritance | Hermetic (`settingSources: []`) for every judged run, plus one labelled `['project']` run reported separately | Plan |
| Model | `eu.anthropic.claude-sonnet-5` on Bedrock, `maxBudgetUsd` capped | Plan |
| Prompt sharing | `buildSystemPrompt(toolNaming)` — the four project rules stay in one copy, only the tool-naming paragraphs vary | Plan |
| Testing | Pure units offline in the commit gate; one live end-to-end test gated behind `test:live` | Plan |
| Error hygiene | Measured with three sentinels against `util.inspect(error, { depth: 8 })`, not against `error.message` | Plan |
| Evidence for the pick | 3 fixtures × 3 runs × 2 runners = 18 runs | Plan |
| Eval environment | Explicitly out of scope — that is M5-L3, and the pick is its input | Plan |

## Scope

**In**: `reviewer.ts`, `permission.ts`, `stream.ts`, `agent-sdk.ts` (and possibly
`submit-tool.ts`) under `packages/code-reviewer/src/`; three new spec files; a
`.claude/skills/agent-sdk/SKILL.md`; small edits to `agent.ts`, `prompt.ts`,
`errors.ts`, `index.ts`, `package.json`; `pick.md`; `test-plan.md` §4/§5.1/§8 and the
root `CLAUDE.md` suite-size line.

**Out**: promptfoo config or provider, retry/fallback chains, any rewording of the four
project rules, `canUseTool`, per-edit gating for this package, a faked SDK subprocess,
and anything under `backend/` or `frontend/`.

## The five phases

1. **The shared contract, no new dependency** — `Reviewer` + provider-neutral usage;
   `reviewDiff` adapted; the prompt's tool-naming paragraphs parameterized; runner
   switch with only the OpenRouter runner registered. All 80 tests still green.
2. **Containment and the access log, offline** — add the dependency; write the
   permission bridge and the message-stream parser as pure functions, tested and
   mutation-verified. No model call.
3. **The runner, and which channel the review arrives on** — the first real Bedrock
   call, hermetic and bounded; then the output-channel A/B, kept or discarded on
   measured numbers.
4. **Failure paths** — the three-sentinel hygiene measurement, the credential and
   timeout paths, what abort does to the host process, and the deliberate break that
   proves which layer refuses `.env`.
5. **The comparison, and the pick** — 18 runs plus the inheritance run; `pick.md` with
   one named winner; register the new surface in `test-plan.md` and root `CLAUDE.md`.

## Three things worth knowing before starting

- **A `PreToolUse` hook returning `{}` means allow.** Deny is the explicit case, so a
  hook that falls through on an unrecognised tool is permissive. This is the inverse of
  the safe default and it is why the bridge denies by name first.
- **`Grep` is not `Read`.** It takes a `pattern` plus an *optional* `path`, and an
  absent `path` scopes to the whole cwd — which includes `.env`. `resolveReadablePath`
  answers about files, so it cannot decide a pattern; the two tools are decided
  separately and an unscoped search is denied.
- **The access log's failure mode is silence.** If the parser records nothing, every
  citation gets stripped and the run still reports a plausible review. So its test
  asserts the *non-empty* direction — the same reason `scripts/run-tests.mjs` demands a
  non-zero test count.

## Risks

- **The nine OpenRouter runs in Phase 5 are quota-blocked today.** `free-models-per-day`
  is account-wide and the account has zero credits — the same wall that still holds
  `tool-loop-agent`'s 4.3 and 4.9 open. Fallback is stated in the phase: retry on a
  later day, or write the pick on the runs that exist and name the missing rows in
  `pick.md`. A pick with a named gap is usable; one with an unmarked gap is not.
- **Two SDK behaviours are undocumented and become criteria rather than assumptions** —
  whether errors carry the request body in enumerable properties (4.3), and what abort
  does to the host process (4.6). One doc phrasing suggests a nonzero process exit,
  which for a library would be unacceptable.
- **The `settingSources` default is taken from documentation** and that default has
  changed across versions. Phase 3 measures it rather than trusting it, because the
  whole hermetic-vs-inheriting decision rests on it.
- **Bedrock is a corporate SSO session with a short life.** An expired session must
  surface as a named error with exit 2, never as an empty passing review — which is
  what criterion 4.2 checks.

## Verification

Four things are provable offline with no credentials: the permission decision in both
directions, the access-log reconstruction, the subprocess env stripping, and the whole
existing 80-test suite. Those are what the commit gate runs.

The live checks mirror the ones the AI SDK runner already passed: `bad.diff` must exit
1 with both rule violations, `cross-file.diff` must find a defect that is invisible
inside the diff and cite a path that survives the access-log check, and
`injection.diff` must have its `.env` read refused. Both directions matter at every
phase boundary — a reviewer that always fails is as useless as one that always passes.

Every containment claim is verified by watching it block, and each deliberate break is
reverted before its phase commit.
