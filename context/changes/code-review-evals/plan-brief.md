# Plan Brief: Evals for the code-review prompt — one prompt, three models, planted flaws

**Change ID**: `code-review-evals`
**Full plan**: `context/changes/code-review-evals/plan.md`
**Base**: `a7573ae` on `main` — depends on `agent-sdk-reviewer` (implemented), and **reverses its pick**

## What We're Doing

Building the first eval configuration over `packages/code-reviewer`: promptfoo calling the
`ai-sdk` reviewer through a custom file provider, three models against one prompt, three
fixtures carrying planted flaws, an LLM judge for recall and deterministic code for
everything a schema can express. Ends with a measured sweep — 27 reviews, 54 judge calls,
under $0.70 — and corrections to every repo sentence the runner reversal makes false.

## Why

This is M5-L3's workstream A, and the package was built for it: `agent.ts:4-9` says
`reviewDiff` is a plain async function *because* a promptfoo provider is handed a string and
must return `{ output }`, and `schema.ts:13` names the evals as its third consumer. The seam
exists; nothing has ever driven it.

The reversal matters as much as the harness. `pick.md` chose `agent-sdk` on 32 measured runs
and named five conditions that would flip it; one — credits on the OpenRouter account — is now
satisfied. But it is satisfied **in form**, not in evidence: `pick.md`'s first reason to reject
`ai-sdk` was a 4-in-9 `malformed-output` rate, and two typed mitigations for exactly that
failure (`response-healing`, `require_parameters`) were installed and never switched on.
Phase 1 spends $0.07 settling that before anything is built on top.

## Key Decisions

| Decision | Choice | Source |
|---|---|---|
| Runner | `ai-sdk`, as the lesson implies — reverses `pick.md` | User |
| Reversal evidence | Phase 1 turns on `response-healing` + `require_parameters` and measures, before the harness exists | User |
| Harness | promptfoo `0.123.0`, pinned exact, package-local devDependency | Research |
| Provider | Default-export class, `id()` derived from `config.modelId` so three models don't collapse into one row | Plan |
| Models | `z-ai/glm-5.1`, `deepseek/deepseek-v4-flash`, `qwen/qwen3-coder-30b-a3b-instruct` | User |
| Repeats | n=3, fixed — n=1 inverted a verdict in this repo | User |
| Fixtures | Both: the React 16 → 19+ migration the lesson names, **and** an in-stack Angular/Spring one | User |
| Third fixture | A clean control that must return `pass` — without it "the review actually fails" is near-free | Plan |
| "Impactful" | Two axes scored separately: the judge asks "was it named?", code counts whether it was filed `blocker`/`major` | User |
| "Correctly identify" | Anywhere in the outcome — `summary` **and** every finding's `summary`/`rationale` — plus stricter code-side counters | User |
| Green case | One assertion per flaw, all 3 required; no repo gate consumes the exit code | User |
| No-output run | A third outcome, `errored`; recall is over reviewed runs, error rate reported beside it | User |
| Judge | `google/gemini-3.8-flash`, `temperature: 0`, one call per flaw, `rationale` before `label`, family-clean vs all three SUTs | User |
| Judge plumbing | Our own `generateObject` in a `.ts` assertion — **no** `llm-rubric`, so promptfoo's credential discovery is never entered | Plan |
| Precision | Counted and reported, never gated | User |
| Answer key | Reachable, and **measured** — an assertion fails if `accessedPaths` contains an eval-directory path. Allow-list not narrowed | User |
| Contract repairs | Both: the dead `options.tools`/`accessedPaths` check, and `deniedTools` on the `ai-sdk` path | User |
| Prompt seam | None added — `prompt.ts:30-31` forbids it by design | Research |
| Gate placement | `evals/` matches no pre-commit pattern; no gate ever runs a model call | Research |
| Spec placement | The offline guards go **directly** in `src/` — `run-tests.mjs`'s `readdirSync` is non-recursive | Research |
| `pick.md` | A dated follow-up note appended, never a rewrite | User |

## Scope

**In**: `packages/code-reviewer/evals/` (provider, config, judge, metrics, assertions, three
fixtures, answer key); `scripts/run-eval.mjs`; three new offline specs directly in `src/`;
edits to `src/agent.ts`, `src/tools.ts`, `package.json`, `tsconfig.json`, `.gitignore`;
`change.md` measurements; corrections to root `CLAUDE.md`, `test-plan.md` §2/§4/§5.1/§8,
`pick.md`, and three stale lines in `agent-sdk-reviewer/`.

**Out**: a prompt seam; narrowing the reviewer's allow-list; promptfoo in any git hook;
`temperature`/`seed` on the reviewer; gating on precision; `llm-rubric`; a κ for the judge;
per-case cost reporting; anything under `backend/` or `frontend/`.

## The seven phases

1. **Reliability first** — the two OpenRouter knobs into `openrouter.chat()`'s second
   argument, then a throwaway probe measuring the malformed-output rate across all three paid
   slugs. ≈$0.07. A gate on the whole change.
2. **The two contract repairs** — the `options.tools` path-set mismatch and `deniedTools` on
   this runner, with offline tests for both. No model call.
3. **The fixtures and the offline guard** — React 16→19+, in-stack rules 1+2+3, the clean
   control, the typed answer key, and a spec that fails if any fixture drifts or grows a
   prose preamble.
4. **The promptfoo harness** — devDependency, provider, config, `npm run eval`, the typecheck
   decision for files outside `src/`, and a config guard asserting no model-graded assertion
   exists.
5. **The judge and the scoring layer** — one binary call per flaw, four booleans per flaw, a
   metrics matrix, and fixed-transcript tests for every degenerate shape.
6. **The sweep and judge calibration** — 27 reviews, 54 judge calls, 54 hand labels, the leak
   measured. ≈$0.61.
7. **Corrections to the record** — five stale sentences, `pick.md`'s dated note, three
   `test-plan.md` sections, and the §2 risk-6 exception argued rather than assumed.

The two paid phases sit at either end; the five between them are offline.

## Four things worth knowing before starting

- **promptfoo ships `tsx` as a *runtime* dependency** and registers the loader hook
  process-globally on a `.ts` provider path, so `provider.ts → agent.ts → prompt.ts →
  schema.ts` all transpile with no build step and no loader flag. This contradicts the
  published docs, which is why the version is pinned exact.
- **Both reliability knobs are the second argument of `openrouter.chat()`** —
  `OpenRouterChatSettings`, `.d.ts:98` and `:221` — not `createOpenRouter()`'s. And
  `require_parameters` **defaults to `false`**, so structured-output support (which is per
  *endpoint*, not per model) has so far been whatever OpenRouter's router picked. That is a
  plausible mechanism for a failure rate previously charged to the model.
- **A `file://` assertion value splits on the first `:`** to peel off an export name, so a
  Windows absolute path truncates to `D`. Provider paths take a different, `isAbsolute`-aware
  branch — the two look alike and behave differently. Assertion paths must be relative.
- **`cross-file.diff` states its own answer, and that is a defect in the fixture**
  (`agent-sdk-reviewer/change.md:51-60`): a correct review, six turns, nothing read. Other
  runs *did* read a file, "so the assertion would have been flaky rather than wrong."
  `vendor-detail.diff` — no preamble — is the model every new fixture copies.

## Risks

- **The React fixture leaves most of the reviewer idle.** Three of four project rules, plus
  the tool, permission and evidence layers, have nothing to do on a React diff. That is the
  accepted cost of lesson fidelity, and the in-stack fixture exists to measure it — but the
  React numbers alone are not a measurement of this reviewer.
- **The answer key is readable by the model under eval.** `packages/` and `context/` are both
  inside the allow-list. The decision was to measure the leak rather than hide it, so a
  fixture-reading run turns the case red — and phase 6 reports how often it happened. A
  non-zero count means the placement has to be revisited.
- **`verdict === 'fail'` is near-free in both directions.** Mitigated by the clean control and
  the `dropped === 0` conjunction. Cut the control and the static assertion becomes decorative.
- **54 hand labels is a smoke test, not a judge measurement.** Stated as such; the escalation
  target (`anthropic/claude-sonnet-5`) and the trigger are written down in advance so a
  disagreement cannot be explained away after the fact.
- **A judge reading prose sits on the far side of `test-plan.md` §2 risk #6's carve-out.**
  Phase 7 argues the exception explicitly and states its own limit. Left silent, this change
  would quietly break a rule it is otherwise built to respect.
- **A wall-clock spread of 8× at `-j 1`** makes the sweep 10–25 minutes. Acceptable by hand,
  which is one more reason no gate runs it.

## Verification

Everything except two phases is provable offline with no credential: the fixture guard (each
planted flaw still present, no prose preamble), the config guard (no model-graded assertion,
every assertion path relative, distinct provider labels, no `tools`/`output` passed), the
metrics layer against six hand-written degenerate outcomes, and both contract repairs. Those
are what the commit gate runs, and it still makes no model call.

Two guards are inherited rather than invented, because this repo has been bitten by both
shapes: `npm run eval` fails when zero flaws were judged or any expected cell is missing — the
same reason `scripts/run-tests.mjs` enumerates specs from disk — and the node version floor is
checked by name, because every layer here fails loudly when its own toolchain is absent.

The two paid phases are verified by their own numbers landing in `change.md`, produced by
running the thing rather than estimated, with actual spend read from the OpenRouter dashboard
since the runner deliberately cannot report per-case cost.
