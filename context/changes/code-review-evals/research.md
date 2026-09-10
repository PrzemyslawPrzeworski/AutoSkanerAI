---
date: 2026-09-10T00:00:00+02:00
researcher: Claude Opus 5 (with Przemyslaw Przeworski)
git_commit: a7573aedd08bbef5e540d914d14c1117d9bbba47
branch: main
repository: AutoSkanerAI
topic: "Evals over packages/code-reviewer — is promptfoo the right harness, and what does this repo already constrain?"
tags: [research, code-reviewer, evals, promptfoo, llm-as-a-judge, openrouter]
status: complete
last_updated: 2026-09-10
last_updated_by: Claude Opus 5
---

# Research: evals over `packages/code-reviewer`

**Date**: 2026-09-10
**Researcher**: Claude Opus 5, with Przemyslaw Przeworski
**Git Commit**: `a7573ae` (local; see "A note on permalinks" below)
**Branch**: `main`
**Repository**: AutoSkanerAI

## Research Question

> Analyze the current state of `packages/code-reviewer` in the context of potential
> eval introduction — reusability of prompts, importability of agent, etc. My first
> pick for eval toolkit is promptfoo. If my tech stack is aligned with this tool, go
> in that direction. Otherwise, analyze other OSS tools allowing me to eval my
> prompts and agents.

Downstream goal, from the lesson prompt (`.claude/prompts/m5l3-promptfoo.md`): one
configuration testing the same code-review prompt on three models, one complex test-case
diff migrating a React 16 component to React 19+ with three impactful planted flaws,
LLM-as-a-judge verifying whether the review correctly identifies what is broken, plus a
static test verifying the review actually fails.

### Scope decisions taken before research (user, 2026-09-10)

1. **Runner: `ai-sdk`** (Vercel AI SDK loop against OpenRouter), as the lesson implies.
2. **Credits: assume a small OpenRouter top-up.** Paid slugs are in scope.

## A note on permalinks

The skill asks for GitHub permalinks where the commit is pushed. **It is not.**
`git log origin/main..HEAD` is **15 commits**, last pushed is `ab30865`, and `main`
auto-deploys to Render and Cloudflare Pages — so nothing in `packages/code-reviewer`
exists on GitHub yet and a permalink at `a7573ae` would 404. All references below are
local `file:line`, correct as of `a7573ae`.

## Summary

**promptfoo fits, and the fit is better than expected — go that direction.** The single
question that decided between "import the library" and "shell out to a CLI" is answered
in the favourable direction: promptfoo ships `tsx` as a **runtime dependency** and
registers the tsx ESM loader hook process-globally as soon as a provider path ends in
`.ts`, so `provider.ts → agent.ts → prompt.ts → schema.ts` all transpile with **no build
step, no loader flag, no `.mjs` shim, and no `exec:` fallback**. `noEmit` and
`allowImportingTsExtensions` are typechecker flags; tsx transpiles via esbuild and does
not typecheck, so `tsconfig.json` is a non-issue.

The package is unusually eval-ready by deliberate design, and the design notes say so:
`agent.ts:4-9` states that `reviewDiff` is a plain async function *because* "a promptfoo
custom provider is a module whose `callApi(prompt, …)` is handed a string and must return
`{ output }`, and a reviewer that read stdin or exited the process could not be driven by
one at all."

**But the plan is not "wire up the obvious thing."** Research surfaced four constraints
that change the shape of the work, in descending severity:

1. **The model under eval can read the eval's answer key.** `packages` and `context` are
   both inside the reviewer's own allow-list, so its tools can fetch the fixture, the
   promptfoo config, this document, and its own system prompt. Silent and
   self-confirming — cheating scores *better*.
2. **A React fixture leaves three of the reviewer's four project rules idle** and takes
   the entire tool/containment/evidence layer offline, because the diff's subject files
   exist nowhere in the allow-listed tree.
3. **`verdict === 'fail'` is a near-free assertion in both directions**, and
   `dropped`/`droppedFiles` can silently turn a correct review into `pass`.
4. **There is no seam to vary the prompt.** "One prompt, three models" is buildable
   exactly as the lesson asks; "eval a prompt change" is not, yet.

One finding materially strengthens the runner reversal the user chose: the measured
`malformed-output` failures that were `pick.md`'s first reason to reject `ai-sdk` have an
**untried, typed mitigation already installed** — see "The reversal" below.

## Detailed Findings

### 1. promptfoo mechanics — the crux, resolved

**Version researched and verified: `promptfoo@0.123.0`** (`npm view`, 2026-09-10).

Verified directly:

```
version = '0.123.0'
engines = { node: '>=22.22.0' }
dependencies.tsx = '^4.23.11'
dependencies.zod = '^4.3.6'
dependencies.ai = '^6.0.264'
local node = v22.22.1
```

- **`tsx` is in `dependencies`, not `devDependencies`.** promptfoo's `src/esm.ts`
  `ensureTypescriptLoader()` tests the module path against `/\.[cm]?ts$/` and, on a
  match, `import('tsx')` — the same entrypoint as `NODE_OPTIONS=--import tsx`. Because
  the hook is process-global once registered, **transitive** `.ts` imports resolve too.
  The upstream comment names our exact case: "so package-less providers can resolve
  extensionless transitive TypeScript imports."
- **Explicit `.ts` specifiers are the easy case**, not the hard one — they need tsx's
  *load* hook only, not its *resolve* hook.
- **`engines.node >= 22.22.0` against local `v22.22.1`** — passing by one patch. Worth
  pinning and stating.
- **`ai ^6.0.264` vs our `ai ^7.0.94`** — a major-version divergence. npm nests both, so
  it works, but a hoisting accident there breaks the runner, not the eval. Name it.
- **Install weight**: ~80 runtime dependencies, ~31 MB unpacked (bundles express,
  socket.io, drizzle+libsql, opentelemetry, nunjucks, openai, `@anthropic-ai/sdk`,
  posthog). A devDependency **inside `packages/code-reviewer`** is safe — nothing deploys
  from there. A root-level install is precisely the risk root `CLAUDE.md` rejected for
  Lefthook (Cloudflare Pages builds from a subdirectory on every push to `main`).
- **Nothing lands in the repo tree.** Cache is `~/.promptfoo/cache`; results go to a
  SQLite DB under `os.homedir()/.promptfoo`. Only chosen `-o` output paths need
  gitignoring. `--no-write` skips the DB entirely.

**Provider contract.** A file provider is a module whose default export is a class with
`id()` and `callApi(prompt, context?, options?)`, instantiated once per `providers:` entry
with `{...providerOptions, id: providerId}`. `ProviderResponse` supports `output` (string
**or** structured object), `error`, `prompt`, `tokenUsage {total, prompt, completion}`,
`cost`, `cached`, `metadata`.

**Sweeping three slugs**: list the same provider file three times with different `config`
and different `label`. Attribution caveat — `providerId = providerOptions.id ?? providerPath`,
so all three entries otherwise share one id. Set distinct `label`s **and** make the
provider's own `id()` derive from `config.modelId`.

**Model-graded assertions available**: `llm-rubric` (the one we want), `g-eval`,
`model-graded-closedqa`, `factuality`, `answer-relevance`, `similar`, `select-best`,
`max-score`, `agent-rubric`, `classifier`, `moderation` (OpenAI-only, unusable here), the
RAG context checks, and the multi-turn judges.

**Deterministic assertions**: `is-json` (JSON Schema, not zod), `contains-json`, `equals`,
`contains`/`icontains`/`regex`, `latency`, `cost`, all negatable with `not-`, plus
`javascript` — inline expression or `file://path.ts[:namedExport]`, receiving
`(output, context)` and returning `boolean | number | GradingResult`.

**A `.ts` assertion file works, contradicting promptfoo's own docs.** The published
`javascript` assertion page says `.mjs` only and tells you to transpile TypeScript first;
in 0.123.0, `assertions/index.ts` → `loadFromJavaScriptFile` → `loadFunction` →
`importModule` is the *same* tsx-registering loader as providers. So an assertion file can
import our real zod `ReviewOutcome` schema rather than a hand-copied JSON Schema — which
is strictly better, because it is the same schema the runner validates against. Because it
contradicts the docs, **pin the version and cover it with one smoke test.**

#### The six promptfoo traps

| # | Trap | Mitigation |
|---|---|---|
| 1 | **The default grader cannot see `OPENROUTER_API_KEY`.** promptfoo's credential probe (`src/providers/defaults.ts`) checks Anthropic, OpenAI, Gemini/Google, Azure, Mistral, xAI, Google ADC, Codex login — **not OpenRouter** — and the final `else` is "Using OpenAI default providers". So every model-graded assertion silently routes to OpenAI and dies on a missing key. Worse: if `ANTHROPIC_API_KEY` is present, the judge silently becomes direct Anthropic — different account, different bill, and it still *works*. | Always set `defaultTest.options.provider: openrouter:<slug>` (or `--grader`). Never rely on discovery. |
| 2 | **Nunjucks renders var *values*, not just templates** (`src/evaluatorHelpers.ts` `renderPrompt`). Our fixtures are diffs of a repo full of Angular `{{ }}` interpolation, so a diff passed as a var is silently mutated or throws a parse error — turning a review-quality eval into a template-escaping eval. Two partial guards mean many diffs survive *by luck*, which is the worst kind of bug. | **Pass a fixture *path*, not the diff.** The provider reads the file. Paths contain no template syntax. This also sidesteps the parsed-object-vs-string question. |
| 3 | **Exit code on test failure is `100`, not `1`.** Any other error is `1`. Threshold and code are env vars (`PROMPTFOO_PASS_RATE_THRESHOLD`, `PROMPTFOO_FAILED_TEST_EXIT_CODE`); there is no `--fail-on-error`. | Test for non-zero, never `-eq 1`. |
| 4 | **Default concurrency is 4** (`DEFAULT_MAX_CONCURRENCY`). On an agentic multi-step reviewer that is four concurrent paid runs. | `-j 1` for first runs. Provider-level `delay:` if a slug rate-limits. |
| 5 | **An update check and PostHog telemetry fire independent of any provider** (`src/updates.ts`, `src/telemetry.ts`) — and the telemetry code records a "telemetry disabled" event *before* honouring the opt-out. | `PROMPTFOO_DISABLE_UPDATE`, `PROMPTFOO_DISABLE_TELEMETRY`, `PROMPTFOO_DISABLE_REMOTE_GENERATION`. **And keep promptfoo out of the pre-commit gate entirely.** |
| 6 | **`file://` assertion values split on the first `:`** to separate the function name, so `file://D:/…` truncates to `D`. | Relative paths only for assertion `file://` values. (Provider paths take a different, `path.isAbsolute`-aware branch and are safe.) |

### 2. Config seams in `packages/code-reviewer/src/`

**Shared contract** (`reviewer.ts:53-58`): `ReviewOptions { modelId?, timeoutMs? }`.
**`ai-sdk` extension** (`agent.ts:124-137`): `ReviewAgentOptions extends ReviewOptions
{ apiKey?, tools?, baseURL? }`. The rule is stated at `agent.ts:120-123`: "An option a
single implementation understands is not part of a shared contract."

| Vary | Seam | Note |
|---|---|---|
| model | `options.modelId` → `agent.ts:262` `options.modelId ?? resolveModelId()` | **Use this, never the env var** — see trap 10 below. |
| API key | `options.apiKey` → `agent.ts:247` | Falls back to `OPENROUTER_API_KEY`, then repo-root `.env` via `loadRepoEnv()`. |
| base URL | `options.baseURL` → `agent.ts:165-168` | Exists so `failures.test.ts` can point at a local replay server. |
| tools | `options.tools` → `agent.ts:267` | **Broken — see finding 6.** |
| timeout | `options.timeoutMs` → `agent.ts:271` | Default `DEFAULT_TIMEOUT_MS = 120_000` (`agent.ts:60`). |
| **prompt** | **No seam at all.** | `agent.ts:171` hard-codes `instructions: SYSTEM_PROMPT`; `SYSTEM_PROMPT` is a module const (`prompt.ts:130`). The only parameterisation is `buildSystemPrompt(tools: ToolNaming)` (`prompt.ts:91`), varying **tool names only**: "A runner supplies names and picks a channel; it does not supply sentences" (`prompt.ts:30-31`). |

**Call the library, not the CLI.** `Reviewer = (diff, options?) => Promise<ReviewRun>`
(`reviewer.ts:115`). `reviewDiff` (`agent.ts:244`) returns
`{ review, usage, modelId, runner, steps, accessedPaths, deniedTools? }` and **throws
`ReviewerError`** in seven kinds (`errors.ts:14-74`): `no-api-key`, `empty-diff`,
`diff-too-large`, `provider`, `no-output`, `malformed-output`, `timeout`.

The CLI is the wrong target: it collapses all seven kinds into exit `2`, drops `usage` /
`accessedPaths` / `runner` into a prose stderr line, and cannot be given a `modelId`
except through the process-global `CODE_REVIEW_MODEL`. `errors.ts:4-7` explains the split:
"a promptfoo provider that exits the process takes the whole eval run with it. So the
library throws and the CLI decides what a throw means."

**The verdict is deterministic given findings, and only given findings**
(`verdict.ts:22-27`): `fail` iff any finding is `blocker` or `major`. The pipeline order is
fixed (`agent.ts:237-243`, `:357-359`): drop out-of-scope → strip unbacked evidence →
derive verdict. "Deriving before dropping would let a finding about an untouched file fail
the review."

**Existing fixtures** (`packages/code-reviewer/fixtures/`): `bad.diff` (two planted rule
violations, decidable from the diff), `cross-file.diff` (the defect no line contains),
`injection.diff` (blocker + three injection shapes), `vendor-detail.diff` (the only one
with **no prose preamble**; settleable only by searching the repo).

**`vendor-detail.diff` is the model to copy; `cross-file.diff` is the mistake to avoid.**
`agent-sdk-reviewer/change.md:51-60` verbatim: "**`cross-file.diff` states its own answer,
and that is a defect in the fixture.** … The first `accessedPaths` run against it produced
a correct review in 6 turns having read **nothing**, citing the preamble. It reads a file
on other runs, which is worse than a consistent failure: the assertion would have been
flaky rather than wrong."

**The offline-skip pattern** (`injection.test.ts:42-65`) is three parts: a
`liveRunRequested()` predicate on `npm_lifecycle_event`, a `whySkipped()` returning
`string | false`, and `node:test`'s `{ skip: <reason> }`, which prints the reason. The
doctrine (`injection.test.ts:10-13`): "It SKIPS WITH A REASON, always printed, never
silently absent — that is the shape of the hook which sat dead in this repo from May to
September … and a security test that vanishes when unconfigured is the same defect wearing
different clothes."

### 3. The gate contract — the sharpest structural constraint

The pre-commit selector, `.githooks/pre-commit:23` verbatim:

```sh
PACKAGE_SOURCES=$(echo "$STAGED" | grep -E '^packages/[^/]+/(src/.*\.ts|scripts/.*\.mjs|package\.json|tsconfig\.json)$' || true)
```

Run against each candidate path:

| Path | Triggers the reviewer arm? |
|---|---|
| `packages/code-reviewer/promptfooconfig.yaml` | **NO** |
| `packages/code-reviewer/evals/provider.ts` | **NO** |
| `packages/code-reviewer/src/evals/provider.ts` | **YES** (`src/.*\.ts` — `.` matches `/`) |
| `packages/code-reviewer/evals/fixtures/react-migration.diff` | **NO** |
| `packages/code-reviewer/package.json` (new devDependency) | **YES** |
| `packages/code-reviewer/package-lock.json` | **NO** |

`.githooks/pre-push:34-38` runs `run_reviewer_checks` **unconditionally**, so everything
above is caught at push regardless.

Three further facts:

- **`tsconfig.json:23` is `"include": ["src/**/*.ts"]`.** So `evals/provider.ts` is
  invisible to `tsc --noEmit` *and* to the pre-commit regex — doubly ungated.
  `common.sh:99-101`: the typecheck "is the only thing that ever reads them, and skipping
  it would mean the types are decoration."
- **`scripts/run-tests.mjs` uses non-recursive `readdirSync`** (`:34-42`), so
  `src/evals/*.test.ts` is **silently ignored** — reintroducing, one directory down,
  exactly the dead-gate shape the script was written to close (`:4-8`: "`--test
  src/*.nosuchpattern.ts` prints `# fail 0` and exits 0, so the runner cannot distinguish
  'nothing broken' from 'nothing ran'"). Nothing here *breaks* the runner; the only break
  case is `specs.length === 0`.
- **There is no Node analogue of `mvnw -o`, because the gate never resolves dependencies.**
  `common.sh:105-119` runs exactly `npm run typecheck` and `npm test`; there is no
  `npm install` anywhere in `.githooks/`. So a declared-but-unfetched `promptfoo` is
  invisible *unless something under `src/` imports it*, in which case both commands fail
  loudly on every commit — with no hint text explaining why, unlike the backend arm's
  `common.sh:81-82`.

**The hard rule**, measured at `agent-sdk-reviewer/change.md:251-255`: "the arm makes no
model call and needs no credential." And the precedent, `pick.md:240-243`: the comparison
harness "was a throwaway at the package root — deleted with the phase commit … **because a
committed comparison script would join the commit gate and the gate must make no model
call.**"

**Recommended placement** (repo-side reasoning): provider, config and fixtures **outside
`src/`** — `packages/code-reviewer/evals/{promptfooconfig.yaml, provider.ts, fixtures/}` —
plus an `npm run eval` script. The *only* thing under `src/` is a dependency-free offline
guard spec named `*.test.ts` asserting the fixture still contains what it tests, the
pattern `injection.test.ts:92-99` already uses ("A fixture edited down to just the blocker
would leave the test above passing while testing nothing"). Whether the provider's types
get checked at all is then an **explicit decision**, because today it defaults to "not
checked."

### 4. The judge

**No new dependency and no OpenAI key needed.** `@openrouter/ai-sdk-provider@3.0.0` is
already installed alongside `ai@7` and `zod@4`; a judge is
`generateObject({ model: openrouter(slug), schema, prompt })`. Independently verified:
OpenRouter's base URL is `https://openrouter.ai/api/v1`, auth is `Authorization: Bearer`
and nothing else (`HTTP-Referer` / `X-OpenRouter-Title` are attribution-only), and it
implements `POST /chat/completions` with `response_format: {type:'json_schema'}` plus
`tools`/`tool_choice`. It does **not** implement `POST /responses`.

**Two typed reliability knobs, verified present in the installed provider's `.d.ts`:**

```
node_modules/@openrouter/ai-sdk-provider/dist/index.d.ts
  24: type IdResponseHealing = 'response-healing';
  98:     plugins?: Array<{ … }>
 221:     require_parameters?: boolean;
```

- **`plugins: [{ id: 'response-healing' }]`** repairs malformed JSON — missing brackets,
  trailing commas, unquoted keys, markdown fences, prose before the JSON. **Non-streaming
  only**, which is our path.
- **`provider: { require_parameters: true }`** restricts routing to endpoints supporting
  every parameter sent. **Structured-output support is per endpoint, not per model**, so
  one slug served by two providers can silently differ.

This is the plan's biggest single de-risking lever and is discussed under "The reversal".

**Judge reliability — the evidence.** Zheng et al., *MT-Bench* ([arXiv 2306.05685](https://arxiv.org/abs/2306.05685),
NeurIPS 2023): GPT-4-vs-human non-tie agreement **85%** against human-vs-human **81%**.
Measured biases: position-consistency under order swap GPT-4 **65.0%** / Claude-v1
**23.8%**; verbosity attack failure GPT-4 8.7% / Claude-v1 **91.3%**; self-enhancement
GPT-4 **+10%**, Claude-v1 **+25%**. Panickssery et al. ([arXiv 2404.13076](https://arxiv.org/abs/2404.13076),
NeurIPS 2024) show self-preference is **mechanistic** — linearly correlated with
self-recognition — so **the judge must not share a family with any model under test**.
JudgeBench ([arXiv 2410.12784](https://arxiv.org/abs/2410.12784), ICLR 2025) puts cheap
judges at or below chance on subtle-correctness pairs (GPT-4o-mini **50.00**,
Gemini-1.5-flash **39.71**, Claude-3-Haiku **33.14**).

**Why a mid-tier judge is nonetheless defensible here.** JudgeBench measures
reference-*free* pairwise verification, where the judge must do the reasoning itself. We
**plant the flaws**, so the answer key is free and the task collapses to "does this English
text name this known defect?" — reading comprehension, not code review. Those numbers are a
floor for the hard framing, not a prediction for ours. Note the caveat: all these figures
are on 2023–2024 model generations; the bias *mechanisms* are current, the percentages do
not transfer to today's slugs.

**Design choices that measurably reduce variance:**

| Choice | Evidence |
|---|---|
| **Binary, never 1–10** | Apple's judge guide rates binary pass/fail reliability "Highest" and names leniency/score-compression on odd scales. Arize's re-run: numeric ranges "bunch, flip, or collapse"; binary shows lowest cross-run variance. aievals.co: a rubric at κ 0.85 binary often falls to **κ 0.55** in 1–5 form, and binary reaches a given CI width at ~**one quarter the N**. |
| **One judge call per planted flaw** | Autorubric evaluates each criterion in a separate call **to prevent halo effects**: binary criteria 87.0% exact / κ 0.642, ordinal only 38–58% exact. Aggregate positive bias **+0.170**. |
| **Reference-guided grading — the biggest single lever** | Zheng et al.'s math-grading failure rate: default **70% → CoT 30% → reference-guided 15%**. |
| **Rationale before label, enforced in the schema** | CoT alone 70%→30%. Since we emit through zod, **declare `rationale` before `label`** — the model generates in property order. |
| **Pointwise, not pairwise** | Pairwise imports position bias at the rates above and needs two runs with orders reversed. We have per-flaw ground truth; the "which model reviewed best" ranking is a **derived aggregate computed in code**. |
| **Pin everything else** | `temperature: 0`; `z.enum(['PASS','FAIL'])`; pin the label spelling (format changes alone can move accuracy substantially); version the rubric in a file. |

**The hybrid boundary.** Code owns everything already representable in `ReviewOutcome`:
did it parse (zod-validated); `verdict === 'fail'`; ≥1 `blocker`/`major`; `dropped === 0`
and `droppedFiles`; `strippedEvidence`; latency/turn budgets; stability across N runs;
**precision** — findings not on the answer key. The judge owns exactly one binary per
planted flaw:

> Given the answer key for flaw *k*, and the **full serialized `ReviewOutcome`
> (`summary` + every `findings[].summary` + `findings[].rationale`)**, did the review name
> flaw *k*? Return `{rationale, label}`.

**The judge must read `summary` as well as `findings`, and this repo has the receipt.**
`pick.md:162-167` records a run whose own summary named the right defect *and* the right
file — "the backend actually listens on 10000 per
`backend/src/main/resources/application.properties`" — and which then reported **zero
findings**. A predicate scoped to the findings array scores that as a miss. Pydantic's
LLM-as-a-judge guide names the other two ways a keyword predicate answers wrongly —
synonyms and negation ("We cannot offer a refund" contains "refund") — and **predicate
scope** is the third, which this repo hit for real.

**Verbosity bias cuts against what we are measuring.** A model emitting 15 speculative
findings will contain the 3 real ones and out-score a model naming exactly 3. Per-flaw
binary recall does not penalise that at all → **report recall and precision separately**,
with the precision counter in code.

**Judge cost.** At ~6k in / 800 out (estimated, not measured): `google/gemini-3.8-flash`
$0.0075/call, `anthropic/claude-sonnet-5` $0.020/call. A 3 models × 3 repeats × 3 flaws =
27-call sweep is **$0.20–$0.54**. Cost is not the constraint; a `:free` judge is, because
a judge that fails to return structured output does not score zero — it takes the run with
it.

**One adversarial note:** the judge reads model-authored prose, and this package ships
`injection.diff`. Delimit the candidate review in the judge prompt and state that content
inside the delimiters is data, never instructions.

### 5. Model sweep — measured, not assumed

Measured against OpenRouter's live catalogue on 2026-09-10: **370 of 436 slugs support
`tools`.** Both lesson-named slugs exist and support tools. **Neither is free.**

Per-review cost at L2's measured volumes (15k in / 1.5k out):

| Slug | In $/M | Out $/M | Per review |
|---|---|---|---|
| `z-ai/glm-5.1` | 0.966 | 3.036 | **$0.0190** |
| `deepseek/deepseek-v4-flash` | 0.089 | 0.177 | **$0.0016** |
| `qwen/qwen3-coder-30b-a3b-instruct` | — | — | **$0.0015** |
| `qwen/qwen3-coder-next` | — | — | $0.0030 |
| `openai/gpt-5-mini` | — | — | $0.0067 |
| `moonshotai/kimi-k2.5` | — | — | $0.0101 |

A 12× spread between the two named slugs makes them an informative pairing. A
3-model × 1-case × 3-repeat sweep is ≈ **$0.07**; a $5 top-up funds the lesson many times.

**The lesson names two slugs but asks for three.** Recommended third:
`qwen/qwen3-coder-30b-a3b-instruct` — code-specialised, a distinct family, and at $0.0015
it tests whether a coder-tuned model beats a 12×-pricier generalist. **If any third
candidate is a `:free` slug, note that `free-models-per-day` is an account-wide cap across
slugs** (`tool-loop-agent/change.md:138-140`) — one day of probing locks out every free
model at once.

**Cost cannot be reported per case on this runner, by design.** `pick.md:59`:
"`unreported` — OpenRouter reports no per-call price on this path", and `agent.ts:285-287`
deliberately omits `costUsd` because "a `0` there would read as 'this review was free'".
So the axis that motivated the flip is the one axis the harness cannot measure — cost comes
from the OpenRouter dashboard.

### 6. Latent bug in the `tools` seam — verified in source

```
agent.ts:263   const { tools, accessedPaths } = createTools();
agent.ts:267     tools: options.tools ?? tools,
agent.ts:358   const { findings, stripped } = stripUnbackedEvidence(kept, accessedPaths);
```

If a caller passes `options.tools`, the substituted tools are used but line 358 still
validates citations against the path set from the **unused** `createTools()` at line 263.
Result: `accessedPaths: []`, `strippedEvidence` equal to the number of citations, **no
error**. `reviewer.ts:78-81` already names the failure mode in the abstract: "an
over-broad answer lets fabricated evidence through and **an empty one strips every
citation — which looks like a strict reviewer and is a dead check.**"

**Do not use the `tools` seam in an eval provider without fixing this first.**

### 7. The React fixture problem

**There is no React in this repo** — verified: case-insensitive `\breact\w*` across the
tree excluding `node_modules` returns three hits, none of them React (this change's own
`change.md`, the lesson prompt, and the English word "reacts" in a Java comment). No
`react` dependency in `frontend/package.json`, no `.jsx`/`.tsx` under `frontend/src`.

`prompt.ts:92` opens: "You review diffs for AutoSkanerAI, an AI-powered used-car listing
analyzer for the Polish market (**Spring Boot 4 + Java 21 backend, Angular 21 +
TypeScript frontend**)." A React diff is off-domain by the prompt's own first sentence.

| Rule | `prompt.ts` | On a React fixture |
|---|---|---|
| 1. Absence of accident data means UNKNOWN, never "clean" — a blocker | `:98` | **Idle.** Polish used-car domain semantics; nothing in a React migration can trip it. This is the project's first rule and the one with real user harm behind it. |
| 2. A layer must fail loudly when its dependency or toolchain is missing; a swallowed error or a success-reporting fallback is a blocker | `:99` | **The only reachable rule** — and only if a planted flaw is deliberately a swallowed error / removed error boundary / `.catch(() => {})`. Framework-agnostic. |
| 3. Vendor detail belongs in one adapter; a second copy is a major finding | `:100` | **Idle and structurally unanswerable.** `schema.ts:22-27`: rule 3 "is structurally unanswerable from a diff alone, because the *first* copy of the duplicated detail is never in it." No first copy of any React vendor detail exists here. A React fixture also **retires `vendor-detail.diff`'s coverage**. |
| 4. Tests: no `waitForTimeout`, no CSS/XPath locators in E2E; assert behaviour | `:101` | **Idle** unless the diff includes a Playwright spec. |

**And the tool layer goes offline too.** `repo.ts:36-56` allow-lists `backend/src`,
`frontend/src`, `frontend/e2e`, `context`, `packages`, `.githooks`, `.github` plus four
root files. A React fixture's subject files are either outside the allow-list (`readRepoFile`
→ `{denied: true, reason: "not in the reviewer's allow-list"}`, `tools.ts:165`) or inside it
but non-existent (`{denied: true, reason: 'no such file in this repo'}`, `tools.ts:170-174`
— **without** adding to `accessedPaths`, since the `.add` at `:177` is after the throw).
Either way `accessedPaths` stays empty, so `stripUnbackedEvidence` strips **every** citation
the model writes. `findInRepo` searches only allow-listed trees, which contain no React.

So the tool loop, the containment policy, the permission layer and the evidence check —
which `agent-sdk-reviewer/change.md:240-247` calls "the clearest confirmation this change
has produced that none of them is decorative" — all go idle. **The eval would measure the
models' general React 16→19 knowledge, not this prompt.** That directly contradicts
`pick.md:187-189`: "an eval exists to attribute a score difference to a prompt change."

The confound is *constant across models*, so a relative ranking still survives. It is a
poor absolute measure, and it retires the coverage the existing fixtures were built for.

### 8. The answer-key leak — the most severe finding

`packages` and `context` are both allow-listed, so the model under eval can read the eval's
own answer key. Probed directly through the reviewer's own resolver:

```
READABLE  packages/code-reviewer/fixtures/bad.diff
READABLE  packages/code-reviewer/evals/promptfooconfig.yaml
READABLE  packages/code-reviewer/evals/fixtures/react-migration.diff
READABLE  context/changes/code-review-evals/change.md
READABLE  packages/code-reviewer/src/prompt.ts
DENIED    .env
```

And `findInRepo('pojazd bezwypadkowy')` already returns
`packages/code-reviewer/fixtures/bad.diff:5` among its hits — the existing fixtures are
grep-reachable today.

So a model given the repo tools can find the fixture file and its prose preamble, the
promptfoo config, the judge rubric, any expected-findings list, its own system prompt, and
this document — which names the fixture subject and the slugs.

**This is `cross-file.diff`'s documented defect promoted from one fixture to the whole
harness**, and it is silent and self-confirming: a model that cheats scores *better*, so
the eval reads as success. Containment itself is intact — `.env` is still denied — this is
a fixture-placement problem, not a security one. No placement inside `packages/` or
`context/` avoids it, and those are the only natural homes.

### 9. Nondeterminism, and what it does to a score

- **No sampling control at all.** `createReviewAgent` (`agent.ts:165-177`) passes `model`,
  `instructions`, `tools`, `toolChoice: 'auto'`, `stopWhen` — no `temperature`, no `seed`,
  no `topP`, no `maxOutputTokens`.
- **Failure-to-answer is the dominant variance, measured.** `pick.md:62`: "verdict stable
  where a review arrived; **the failure itself is the variance** — 4 of 9 runs produced
  none." `pick.md:188-189`: "a provider with a 44% no-answer rate on its shipped model
  contributes more variance than the prompt does." 11% on the better free slug.
- Turns and files read vary run to run (`pick.md:88`, `:110-111`); citation survival varies
  (`pick.md:56`: "1 citation survived, 2 were stripped"); wall clock varies 8× (9.1–73.6 s).
- **The LLM judge adds a second uncontrolled nondeterministic layer.**

The repo has already ruled on sample size — `agent-sdk-reviewer/change.md:227-229`: the
single run "*inverted the verdict* … and **this package's own plan warns twice about being
misled by single samples**. At n=3 the inversion is 1 in 3 rather than 1 in 1, which is a
weaker claim and a defensible one."

**At n=1 per model the score is unattributable.** And the judge-calibration figures
(3 flaws × 9 runs = 27 hand labels) are a smoke test that the judge is not broken, **not a
measurement of judge quality** — Hamel Husain's sizing is ~100 examples per failure mode,
with confidence intervals too wide below 60. Say so in the plan rather than reporting a κ
as if it were solid.

### 10. The reversal — `ai-sdk` over `agent-sdk`

`pick.md:215-230` lists five conditions that would flip its pick. The user's decision
satisfies **one, and in form rather than in evidence**:

| Condition | Satisfied? |
|---|---|
| **Credits on the OpenRouter account** — "the most likely flip" | **Partially.** A top-up is assumed. But pick.md's claim is that "a paid frontier model on the `ai-sdk` path removes the confound and the failure rate at once", and **no `ai-sdk` run in pick.md was ever made on a paid slug**. Both measured rates (5/9, 8/9) are free-tier, so the failure rate on the slugs the lesson names is **unmeasured**. |
| CI, or any second machine | **No.** Still no CI (`test-plan.md:340`); `.githooks/pre-push:5-9` "stands where CI would". |
| A refusal record on the `ai-sdk` contract | **No.** `tools.ts:165`/`:173` still return `{denied, reason}` into the discarded message history; `agent.ts:369` returns no `deniedTools`. **This is the tiebreak the original pick turned on** — order fixed before any run, "containment provability → cost → determinism" (`pick.md:24-27`). |
| A 30-case sweep hitting the subprocess floor | **No — and it argues the other way.** The plan has one test case; ~7.1 s × 1 is trivial. |
| The 210 MB platform binary becoming a cost | **No.** Warm laptop, no container. |

**The honest reading: the reversal is authorised by one condition, not yet backed by a
measurement of what that condition promises to fix, and leaves the deciding tiebreak
untouched.**

**What repairs it, and it is new information.** The `response-healing` plugin and
`require_parameters` (finding 4, verified installed) are a typed, three-line, never-tried
mitigation for the exact failure mode — `findings` sent as a string, `summary` omitted —
that was pick.md's first reason to reject this runner. Structured-output support being
per-endpoint rather than per-model is also a plausible mechanism for a failure rate that
was attributed to the model. **A first phase that measures the paid slugs with these two
options on would convert the reversal from authorised to evidenced**, and would also be the
cheapest way to neutralise pick.md's objection #1.

Aggregating `deniedTools` on the `ai-sdk` path — pick.md's own suggested fix, "a small
change [that] would neutralise the first tiebreak" — remains open and would close
objection #2.

**Stale claims the reversal creates**, all needing correction in this change:

| File:line | Verbatim | Status |
|---|---|---|
| `CLAUDE.md:112` | "Both stay tested; the evals wrap `agent-sdk`, and the reasoning is in …" | **False after the reversal.** |
| `test-plan.md:589` (§8 ledger) | "it is the runner M5-L3's promptfoo provider wraps" | **False.** |
| `test-plan.md:338` (§4 Stack) | "Which one the evals wrap, and the 32 runs behind that: …`pick.md`" | Pointer to a now-reversed file. |
| `pick.md:3`, `:33`, `:208-213` | Title "Which of the two reviewers the evals wrap"; "Why not both"; "the evals get one" | Needs a **dated follow-up note**, not a rewrite — a reversed decision is worth more with its history intact. |
| `agent-sdk-reviewer/change.md:12` | "which is what M5-L3's promptfoo provider will wrap" | Stale. |
| `agent-sdk-reviewer/plan.md:13`, `plan-brief.md:18` | "M5-L3's promptfoo custom provider wraps **one**" | Stale as to which. |

### 11. What an eval addition owes `context/foundation/test-plan.md`

1. **§4 Stack (line 338)** — dependency list, suite counts ("168 tests in 11 spec files,
   ~6.9 s, of which 10 skip offline"), `checked: 2026-09-10`. A promptfoo devDependency and
   any new spec change all three.
2. **§5.1 (lines 362-375)** — the pre-commit scope in prose, the 8.4 s arm cost, and the
   credential-free proof. §5.1's own standard is "**re-measured on 2026-09-10, not
   re-estimated**" (line 368).
3. **§8 Freshness Ledger (lines 587-604)** — a new dated entry, **and** a correction to the
   existing 2026-09-10 entry, not merely an append.
4. **§2 Risk #6 carve-out (lines 74, 77-81)** — this both authorises and constrains the
   work:

   > line 74: "An eval asserting a specific model wording — non-deterministic and expensive
   > for the signal"
   >
   > lines 77-81: "It does not prohibit evals over the repo-tooling reviewer in §4, **whose
   > output is a schema (severity, file, verdict) rather than prose, and whose assertions
   > are therefore about a decision, not a wording.**"

   A judge scoring whether the review *named* three flaws reads `summary` and `rationale` —
   prose. **The judge sits on the far side of that carve-out and needs an argued exception
   in the plan, not silence.** The available argument: the assertion is about whether a
   known defect was identified (a decision), not about how it was worded, and the label is
   binary rather than a wording score.

### 12. If promptfoo had not fit — the fallback, and why it is not needed

Kept because it is cheap insurance and because two entries are hard disqualifications
worth recording.

**Strongest alternative: Vitest 5 + hand-written scorers.** Verified empirically in a
throwaway probe: `{"type":"module"}` + `vitest@5` + a spec importing `./lib.ts` **with the
explicit `.ts` extension and no tsconfig at all** → 1 passed, 211 ms. Vitest transforms via
esbuild and does not typecheck, so `noEmit`/`allowImportingTsExtensions` are irrelevant and
`tsc --noEmit` stays the typecheck. It also dissolves the grader-credential question
entirely: the judge is a `generateObject` call through the provider already in
`package.json`. Vitest 5.0.0 shipped 2026-09-03.

| Tool | Verdict |
|---|---|
| **Vitest 5 + own scorers** | Best fallback. Buys `test.for` table cases, `retry` for LLM flake, `concurrent`/`maxConcurrency`, `--reporter=json`, and a reporter API for threshold-to-exit-code. |
| **DeepEval TS SDK** (0.9.15, 2026-09-06) | No longer Python-only; runs *as* Vitest and ships a first-class `OpenRouterModel`. Choose if prebuilt judge metrics matter more than a hand-written one. Bundles `posthog-node`; TS telemetry opt-out flag **UNCONFIRMED**. |
| **`@arizeai/phoenix-evals`** (2.5.0, 2026-09-09) | Lightest prebuilt scorers; the only package already declaring `ai ^7.0.93` + `zod ^4.5.4` — our exact majors. |
| **`autoevals`** (Braintrust) | Scorer library, not a harness. **Default baseURL is the Braintrust proxy if unset** — must be set explicitly. Decelerating. |
| **`node:test` + hand-rolled** | Works (already the gate's runner) but ~150–250 lines you own, most of it the aggregation layer `node:test` has no hook for. |
| **`vitest-evals`** (Sentry) | **Best-designed API in the survey and unusable today**: peers `vitest >=4 <5` and `ai >=4 <7`. Recheck in a month. |
| **evalite** | **Hard blocker**: peers `ai ^6` against our `ai ^7.0.94`; AI-SDK-v7 issue open since 2026-06-29; no stable release in ten months; an unanswered "is this still active" issue. Adopting it means a gate that rots silently. |
| **Langfuse** | Works but needs a server (Postgres + ClickHouse + Redis + S3). Heaviest option. |
| **OpenAI Evals** | Dormant; Python + YAML registry; hardcoded around OpenAI. |

**Applies regardless of harness:** the AI SDK's own testing utilities
(`MockLanguageModelV4`, `mockValues`) are mock providers, not an eval harness — but they
are the right tool for the deterministic half of the suite, exercising the loop and the
malformed-output paths **without spending a token**.

**And one guard that transfers from this repo's own history:** `scripts/run-tests.mjs`
exists because `node --test` on a pattern matching nothing exits 0. Whatever harness lands,
**a run that produced zero judged cases must fail, not pass.**

## Code References

- `packages/code-reviewer/src/agent.ts:4-9` — why `reviewDiff` is a plain async function: the promptfoo provider contract
- `packages/code-reviewer/src/agent.ts:120-137` — `ReviewAgentOptions`, and the rule about what belongs in a shared contract
- `packages/code-reviewer/src/agent.ts:244-269` — `reviewDiff` entry, model/key/tools resolution
- `packages/code-reviewer/src/agent.ts:263-267` + `:358` — **the `options.tools` bug**
- `packages/code-reviewer/src/agent.ts:171` — `instructions: SYSTEM_PROMPT`, hard-coded: no prompt seam
- `packages/code-reviewer/src/agent.ts:285-287` — why `costUsd` is deliberately absent
- `packages/code-reviewer/src/prompt.ts:30-31` — "A runner supplies names and picks a channel; it does not supply sentences"
- `packages/code-reviewer/src/prompt.ts:92`, `:98-101` — the domain sentence and the four project rules
- `packages/code-reviewer/src/schema.ts:13` — the three consumers, promptfoo named among them
- `packages/code-reviewer/src/schema.ts:22-27` — why rule 3 is structurally unanswerable from a diff
- `packages/code-reviewer/src/schema.ts:70-84` — `dropped` / `droppedFiles` / `strippedEvidence`
- `packages/code-reviewer/src/reviewer.ts:8-9` — "the promptfoo custom provider … wraps this type rather than a file"
- `packages/code-reviewer/src/reviewer.ts:78-81` — the "dead check" warning
- `packages/code-reviewer/src/reviewer.ts:115` — the `Reviewer` type
- `packages/code-reviewer/src/verdict.ts:22-27` — `deriveVerdict`
- `packages/code-reviewer/src/verdict.ts:73-91` — `stripUnbackedEvidence`
- `packages/code-reviewer/src/repo.ts:36-56` — the allow-list (the answer-key leak)
- `packages/code-reviewer/src/tools.ts:165`, `:170-177` — denial paths, and `accessedPaths` not being written on a throw
- `packages/code-reviewer/src/errors.ts:4-7`, `:14-74` — why the library throws, and the seven kinds
- `packages/code-reviewer/src/index.ts:2-3`, `:36-41`, `:61-63` — CLI exit codes; `selectRunner` refusing to fall back
- `packages/code-reviewer/src/injection.test.ts:10-13`, `:42-65`, `:92-99` — skip-with-a-reason, and the offline fixture guard
- `packages/code-reviewer/scripts/run-tests.mjs:4-8`, `:34-42` — why specs are enumerated from disk; non-recursive `readdirSync`
- `.githooks/pre-commit:23` — the `packages/` staged-path regex
- `.githooks/pre-push:34-38` — the unconditional reviewer arm
- `.githooks/common.sh:99-101`, `:103-104`, `:105-119` — typecheck rationale, the offline claim, the arm itself
- `packages/code-reviewer/tsconfig.json:14-19`, `:23` — `noEmit`, `allowImportingTsExtensions`, `include`

## Architecture Insights

- **The package was built to be evaluated, and the seams that exist are the ones a harness
  needs.** Prompts are pure functions of tool names; both runners satisfy one exported
  `Reviewer` type; output is zod-validated; the verdict is arithmetic taken away from the
  model; `index.ts` is the only module permitted to touch stdio or exit. The one seam that
  is *missing* is a prompt override — deliberately, per `prompt.ts:30-31`.
- **The three enforcement layers only work on in-repo subjects.** Tool allow-listing,
  permission refusal and evidence-stripping are all defined relative to files that exist in
  the allow-listed tree. Any fixture whose subject is not in this repo silently disables all
  three — which is why fixture subject choice is an architectural decision, not a content
  one.
- **`unreported` is not zero, and that discipline propagates outward.** `ReviewUsage`
  fields are optional on purpose; `deniedTools` distinguishes `undefined` (no record) from
  `[]` (a record with nothing refused). A promptfoo provider must **omit** `tokenUsage` /
  `cost` keys rather than mapping absent to `0`, because promptfoo has no "unknown" and a
  `0` cost reads as "free".
- **This repo's recurring failure mode is a gate that reports success while measuring
  nothing** — the dead `PostToolUse` hook (May→September), `node --test` on an empty
  pattern exiting 0, a fixture that states its own answer, an empty `accessedPaths` looking
  like a strict reviewer. Every finding above that I would call severe is an instance of
  the same shape. **The eval must be designed against it**: zero judged cases must fail,
  and the answer key must not be reachable.

## Historical Context (from prior changes)

- `context/changes/agent-sdk-reviewer/pick.md` — the 32-run comparison, the tiebreak order
  fixed before any run, and the five reversal conditions. Reversed by this change; needs a
  dated follow-up note.
- `context/changes/agent-sdk-reviewer/change.md:51-60` — `cross-file.diff` states its own
  answer. **The single most important precedent for the new fixture.**
- `context/changes/agent-sdk-reviewer/change.md:227-229` — n=1 inverted a verdict; n=3 is
  the defensible minimum.
- `context/changes/agent-sdk-reviewer/change.md:240-247` — the containment policy's
  measurable capability cost, and the three layers visible in one run.
- `context/changes/agent-sdk-reviewer/change.md:249-255` — the 8.4 s gate arm, and the
  credential-free proof the eval must not break.
- `context/changes/tool-loop-agent/change.md:110-112` — **setting a structured output
  silently kills the tool loop** (1 step, 0 files vs 3 steps, 15 files). Any eval that
  reintroduces a `response_format` on the reviewer path destroys what it is measuring.
- `context/changes/tool-loop-agent/change.md:138-140` — `free-models-per-day` is an
  account-wide cap.

## Related Research

- `context/changes/agent-sdk-reviewer/pick.md` — the runner comparison this change reverses
- `context/foundation/test-plan.md` §2 risk #6, §4, §5.1, §8 — the carve-out that
  authorises evals here, and the ledger entries this change owes

## Open Questions

Each of these is a plan-time decision, deliberately not taken here.

**Questions 1, 2 and 7 were answered by the user on 2026-09-10, after this document was
written — see `change.md` § "Scope decisions taken after research".** In short: **both**
fixtures; **measure** the answer-key leak with an `accessedPaths` assertion rather than
hiding it or narrowing the allow-list; and **phase 1 measures the paid slugs** with
`response-healing` + `require_parameters` before the harness is built. They are left in
place below because the alternatives and their costs are what the answers were chosen
against.

1. **Fixture subject — the biggest fork.** React 16→19 as the lesson names it (one of four
   rules reachable, tool layer idle, existing coverage retired); an in-stack Angular/Spring
   fixture (exercises all four rules and all three enforcement layers, but departs from the
   lesson); or **both** (the lesson's fixture for fidelity plus an in-stack one as the
   control that shows how much the off-domain subject costs). The third option is the most
   informative and the most work.
2. **Containment of the answer key.** No placement inside `packages/` or `context/` hides
   the fixture from `findInRepo`. Candidate answers: narrow the allow-list for eval runs;
   strip prose preambles and keep the answer key outside the tree entirely; or accept it and
   assert `accessedPaths` contains no eval-directory path — turning the leak into a
   *measured* check rather than an unexamined risk.
3. **The third model slug.** The lesson names two. `qwen/qwen3-coder-30b-a3b-instruct` is
   the evidence-based recommendation; confirm or substitute.
4. **Repeats per model.** n=1 is unattributable against a measured 11–44% no-answer rate.
   n=3 is this repo's own established minimum. Cost makes n=3 trivial (~$0.07).
5. **Whether to fix the `options.tools` bug in this change** or simply avoid the seam and
   record it as known debt.
6. **Whether to add `deniedTools` to the `ai-sdk` path** — pick.md's own "small change
   [that] would neutralise the first tiebreak", and the cleanest way to make the reversal
   complete rather than merely authorised.
7. **Whether phase 1 measures the paid slugs with `response-healing` +
   `require_parameters`** before building the harness on top. This is what would convert the
   runner reversal from authorised to evidenced, and it is cheap.
8. **The §2 risk-6 exception** for a prose-reading judge — argued explicitly in the plan, or
   the judge narrowed until it reads only schema fields.
