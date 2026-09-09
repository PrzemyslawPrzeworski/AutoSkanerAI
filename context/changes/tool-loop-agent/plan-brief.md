# Plan Brief: Turn the code-reviewer spike into a modular ToolLoopAgent

**Change ID**: `tool-loop-agent`
**Full plan**: `context/changes/tool-loop-agent/plan.md`
**Base**: `2d29dbf` on `main` — no prerequisite change

## What We're Doing

Splitting `packages/code-reviewer/src/index.ts` — a working 190-line spike doing six
jobs — into nine single-purpose modules on the AI SDK's `ToolLoopAgent`, with two
read-only tools, and exporting the reviewer as a plain function so it can be called
without a terminal.

## Why

Two of the reviewer's own rules currently exist only as English inside its prompt:
"fail iff at least one blocker or major", and "the file must be one the diff
changed". Nothing checks either. That is the exact shape root `CLAUDE.md` spends a
paragraph on — a check whose signal is asserted rather than computed. This change
turns both into code.

The reusability requirement has a concrete consumer already on disk:
`.claude/prompts/m5l3-promptfoo.md:9` will run the same prompt across three models
with an LLM-as-a-judge. A promptfoo provider gets a `prompt` string and must return
an object — so the reviewer cannot own stdin, stdout, or the exit code.

## Key Decisions

| Decision | Choice | Source |
|---|---|---|
| Export shape | `reviewDiff(diff, opts)` + `createReviewAgent({modelId})` — model injectable per call | Plan |
| Tools | Two, read-only: `readRepoFile`, `findInRepo` | Plan |
| Verdict | Derived in code from severities; removed from the model's schema entirely | Plan |
| Off-diff findings | `finding.file` must be a diff-touched path; off-diff findings are dropped and counted. Optional `evidence` field for tool-discovered paths, validated against what the tools actually returned | Plan |
| Path containment | Allow-list of subtrees plus a deny-list, resolved through `realpath`, case-insensitive on `win32` | Plan |
| Injection defence | Structural (diff in a user message, inside a delimiter, `allowSystemInMessages` off) plus one static test | Plan |
| Review rules | Literal text in `src/prompt.ts`, each with a comment naming its source document | Plan |
| Tests | `node:test` via `node --import tsx --test` — no new test dependency | Plan |
| Gate wiring | Done here, in Phase 5, not deferred | Plan |
| Eval environment | Explicitly out of scope | Plan |

## Scope

**In**: nine modules under `packages/code-reviewer/src/`, four test files, two
fixtures, `.githooks/common.sh` + `pre-commit` + `pre-push` arms, three
`test-plan.md` sections.

**Out**: promptfoo config or provider, the per-edit hook, prettier/eslint for this
package, streaming, retry/fallback chains, and any change to `backend/` or
`frontend/`.

## The five phases

1. **Extract, no behaviour change** — `schema`, `prompt`, `diff`, `env` out of
   `index.ts`; still one `generateObject` call. Checkpoint: identical results on the
   two fixtures the spike was verified against.
2. **Make the prose rules executable** — derive the verdict in code, drop it from the
   schema, parse the diff's changed-file set, drop off-diff findings. First unit tests.
3. **Containment policy, then the tools** — `repo.ts` before `tools.ts`, deliberately:
   this is the repo's first path-containment check, and writing the tool first is how
   the policy ends up an afterthought.
4. **Swap to `ToolLoopAgent`** — `agent.ts` with the factory and the exported
   `reviewDiff`; `index.ts` becomes a thin CLI; injection test lands.
5. **Wire and register the gate** — pre-commit and pre-push arms for `packages/`, plus
   `test-plan.md` §4, §5.1 and the freshness ledger.

## Three things worth knowing before starting

- **The AI SDK field is `instructions`, not `system`.** Passing `system` to the
  `ToolLoopAgent` constructor is silently ignored — the agent then runs with no
  system prompt and still returns plausible output. Two related gotchas: the result
  is `result.output`, not `result.object`, and generating the structured output
  consumes a step, so the step budget must be tool-rounds + 1. All three are in
  `packages/code-reviewer/.claude/skills/ai-sdk/SKILL.md:42-53`.
- **Deriving the verdict in code is what makes the injection test cheap.** With
  `verdict` gone from the schema, a successful injection can only appear as zero
  findings — detectable from the exit code, with no assertion on model wording. So
  `test-plan.md:74`'s objection to wording-based evals does not apply here.
- **`packages/` is currently invisible to all three quality gates**, by construction:
  `post-edit-check.mjs:96` gates on `frontend/src/`, and `pre-commit` greps for
  `^frontend/src/` or `^backend/`. Phase 5 closes the commit-time half. The per-edit
  hook is deliberately left alone — nothing in `packages/` is deployed, so a break
  cannot reach production between commit and push.

## Risks

- **Free OpenRouter slugs vanish without notice** — a dead one answers HTTP 404
  "unavailable for free", not 429. The default is a slug the backend already lists as
  a fallback; the recovery recipe is in the SKILL.md.
- **Node's test runner and `.ts` files** — whether `node --import tsx --test src`
  collects `*.test.ts` from a directory needs to be confirmed by watching a
  deliberately failing assertion fail. A runner reporting success on zero collected
  tests would be the dead-hook shape again.
- **Prompt-rule drift from `CLAUDE.md`** — accepted, undetected, and recorded. Same
  drift class `context/team/opportunity-map.md` already ranked.

## Verification

Four pure units are testable offline with no model call: verdict derivation, diff
file-list parsing, path containment, and evidence validation. The live checks are the
two fixtures the spike already passed — a rule-violating diff must exit 1 with both
blockers, and a real benign commit (`eea799d`) must exit 0 with an empty findings
array. A reviewer that always fails is as useless as one that always passes, so both
directions are checked at every phase boundary.

Each gate path in Phase 5 is verified by watching it block, per `test-plan.md:380`.
