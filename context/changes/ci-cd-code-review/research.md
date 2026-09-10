---
date: 2026-09-10T16:57:33+02:00
researcher: Claude (Opus 5)
git_commit: a7573aedd08bbef5e540d914d14c1117d9bbba47
branch: main
repository: PrzemyslawPrzeworski/AutoSkanerAI
topic: "AI code review in CI/CD — a GitHub Actions workflow on every PR"
tags: [research, ci-cd, github-actions, code-reviewer, security, prompt-injection]
status: complete
last_updated: 2026-09-10
last_updated_by: Claude (Opus 5)
---

# Research: AI code review in CI/CD — a GitHub Actions workflow on every PR

**Date**: 2026-09-10T16:57:33+02:00
**Researcher**: Claude (Opus 5)
**Git Commit**: `a7573ae` (`a7573aedd08bbef5e540d914d14c1117d9bbba47`)
**Branch**: `main`
**Repository**: `PrzemyslawPrzeworski/AutoSkanerAI`

> **No GitHub permalinks in this document.** `main` is **15 commits ahead of `origin/main`**
> (last pushed: `ab30865`). Nothing in `packages/code-reviewer` exists on GitHub yet, so a
> permalink at `a7573ae` would 404. All references are repo-relative paths with line numbers.
> This is not incidental — see §2.1, it is the first constraint on verifying this change.

## Research Question

How should the code reviewer in `packages/code-reviewer` be wired into GitHub Actions to run
on every pull request, per `requirements.md`: a composite action, inputs of PR title +
description + git diff, six criteria scored 1–10, a PR comment plus `ai-cr:passed` /
`ai-cr:failed` labels, and an `ai-cr:review` label that retriggers on demand?

Scope agreed before research (three `AskUserQuestion` answers, 2026-09-10):

1. **Criteria gap — both paths, blast radius mapped.** Research what changing the reviewer's
   output to six 1–10 scores costs, *and* what keeping the current schema costs, with the
   files and tests each touches.
2. **Runner — own reviewer, alternatives noted.** Research wiring `packages/code-reviewer`;
   note `claude-code-action` and similar only as comparison.
3. **CI scope — AI review only, gap flagged.** Research the AI review as asked; record the
   absent test gate as a named adjacent risk, not a design.

## Summary

**The workflow is buildable, and four things in the requirements do not survive contact with
this repo.** In descending order of consequence:

1. **The trigger cannot be a plain `on: pull_request`.** This repo is **PUBLIC**. On a fork
   PR, GitHub withholds every secret *and* clamps `GITHUB_TOKEN` to read-only — and the clamp
   is applied **after** your `permissions:` block, so it cannot be overridden. A plain
   `pull_request` workflow reviewing a fork PR has neither the LLM key nor the write access to
   post the comment or apply the label. It fails twice, on exactly the contributor the review
   is for. `pull_request_target` is the documented fix and is also the documented RCE trap.
2. **Two independent diff ceilings sit below this repo's typical PR.** The reviewer refuses
   above **60 000 chars** (`diff.ts:10`) — it never truncates, by design. `gh pr diff` hard-
   fails with `406` above **20 000 lines**. Measured against real history here: `b9b5ce1` is
   133 KB of patch; the largest is 371 KB. A typical PR is **2–6× over** the reviewer's cap and
   would produce no review at all, exiting 2.
3. **The six 1–10 criteria are a different artefact from what ships**, and adopting them lands
   on the prohibited side of a rule this repo wrote for itself (`test-plan.md:77-81`). They
   also **park the two criteria where this project's two most important rules live**.
4. **`master` does not exist.** The default branch is `main`. The requirements' trigger would
   never fire.

Two things the requirements got right that research strengthens: the **composite action** is
sound (and its one hard limit — no `secrets` context — has a clean workaround), and the
**`ai-cr:review` retrigger is loop-safe by construction**, because a label applied with
`GITHUB_TOKEN` creates no workflow run at all.

And one correction to the record before anything else: **`change.md`'s claim that "there is no
CI at all today" is wrong**, and was already known to be wrong.

---

## 1. The state of CI in this repo — the record corrects itself

### 1.1 Eight documents say there is no CI. One says they are wrong, and is right.

| Claim | Location |
|---|---|
| "**There is no CI yet** and `main` auto-deploys to both hosts, so **pre-push is the last gate before production**" | `CLAUDE.md:55-57` |
| "Normally pre-push sits in front of CI. Here **there is no CI yet**" | `context/foundation/test-plan.md:383-385` |
| "the hooks are versioned but git will not pick them up on its own, and **there is no CI**" | `context/map/repo-map.md:238-239` |
| "**There is no CI yet** … here it stands where CI would" | `.githooks/pre-push:5-8` |
| "no frontend, no database, no auth, **no CI**" | `context/domain/01-domain-distillation.md:23`, `02-invariant-aggregate-refactor.md:19` |
| "Still no CI (`test-plan.md:340`)" | `context/changes/code-review-evals/research.md:510` |
| "**There is no CI at all today.** Root `CLAUDE.md` says so explicitly" | **`context/changes/ci-cd-code-review/change.md:25-28` — this change's own note** |

The correction already exists, at `context/team/opportunity-map.md:45-49`:

> "**CI is not absent**: `.github/workflows/live-market-price.yml` exists and works. It is
> `workflow_dispatch`-only for a stated reason (`:9-10`): the assertions depend on live
> third-party markup, so a red run means 'Otomoto changed something', which 'must not block
> unrelated merges'. Root `CLAUDE.md`'s *'There is no CI yet'* is therefore imprecise — **the
> platform is wired; nothing gates on it**."

It is filed as a known-unfixed factual error at `opportunity-map.md:121-122`.

**Why the distinction is load-bearing and not pedantry.** "The platform is wired; nothing gates
on it" makes this change *smaller* than "introduce the first CI":

- A **Java 21 runner** already works here (`live-market-price.yml:23-27`, `actions/setup-java@v4`,
  temurin 21, `cache: maven`).
- The existing workflow **documents that no secrets are needed** — so `OPENROUTER_API_KEY` in
  Actions is the repo's first Actions secret, and that is the new ground, not Actions itself.
- The house style for a workflow file is established (§6.1).

`opportunity-map.md:24` already anticipated this change as the destination:

> "`.github/workflows/live-market-price.yml` runs `./mvnw` on a **Java 21 runner** today | A
> second workflow, ~15 lines … | The same workflow, **report-only before it gates** | none —
> **the existing workflow documents that no secrets are needed** | **Use what's installed** →
> review / CI gate, which is **M5-L3's subject**"

### 1.2 The slot for this change is already reserved, and empty

`test-plan.md:514-516`:

```
### 6.6 Adding a CI gate
- TBD — see §3 Phase 4.
```

And §3 Phase 4 (`test-plan.md:94`): "Run both suites on PR and push before auto-deploy, keeping
live-tagged tests out of the gate" — status **`not started`**. With the reason it is last
(`:107-108`):

> "Phase 4 comes last because a gate over a suite that does not yet cover the top risks locks
> in a false floor — **but it must land, because `main` auto-deploys to production on merge and
> nothing runs the suites today**."

**This is the adjacent gap the third scope answer asked to flag, and it is not an oversight —
it is a scheduled, named, still-empty section.** See §9.1.

### 1.3 Grounded facts about the GitHub side of this repo

Measured with `gh` at `a7573ae`, none of them recorded in any document:

| Fact | Value | Consequence |
|---|---|---|
| Default branch | **`main`**, not `master` | The requirements' trigger never fires as written |
| Visibility | **PUBLIC** | The entire fork-secret problem (§3) |
| PRs ever opened | **zero** (120 commits; 2 local merge commits `d5e0fed`, `51db3fb`) | Nothing to run on; verification needs a deliberate throwaway PR (§9.2) |
| Actions secrets configured | **none** | `OPENROUTER_API_KEY` in Actions is new ground |
| `default_workflow_permissions` | **`read`**; `can_approve_pull_request_reviews: false` | An explicit `permissions:` block is mandatory; the token cannot approve PRs |
| `main` branch protection | **none** | `ai-cr:failed` is advisory only — and `main` auto-deploys |
| Labels | **17**, `prefix:value` convention (`stream:A`, `status:ready`); no `ai-cr:*` | Three labels to create; the convention fits |
| Workflows | one, `live-market-price.yml`, `workflow_dispatch` only | §1.1 |
| `main` vs `origin/main` | **15 commits ahead**, last pushed `ab30865` | §2.1 |

---

## 2. The reviewer's runtime surface — what CI must satisfy

### 2.1 The reviewer does not exist on GitHub yet

`code-review-evals/research.md:44-48` states it: "`git log origin/main..HEAD` is **15 commits**,
last pushed is `ab30865`, and `main` auto-deploys to Render and Cloudflare Pages — so **nothing
in `packages/code-reviewer` exists on GitHub yet**."

**The push that carries the reviewer to GitHub is simultaneously a production deploy of
fifteen commits.** A workflow file cannot be tested before that push, and `workflow_run`-style
triggers additionally require the workflow to exist *on the default branch* (§3.3). This
ordering is a plan concern, not a research one, but nothing else about this change can be
verified until it is resolved.

### 2.2 Environment variables

`env.ts:4-7` sets the tone: "Everything here returns a value or null. Nothing here exits,
prints, or throws."

| Variable | Read at | Default | Absence fatal? |
|---|---|---|---|
| `OPENROUTER_API_KEY` | `env.ts:60`, `:68` | none | **Yes for `ai-sdk`** — `agent.ts:247-250` throws `ReviewerError('no-api-key')` → exit 2 |
| `CODE_REVIEW_MODEL` | `env.ts:72` | `dots-studio/dots-3-note-preview:free` (`env.ts:49`) | No |
| `CODE_REVIEW_RUNNER` | `index.ts:37` | `'ai-sdk'` | No — but an **unrecognised value exits 2 and never falls back** (`index.ts:40`) |
| `CODE_REVIEW_BEDROCK_MODEL` | `agent-sdk.ts:550-551` | `eu.anthropic.claude-sonnet-5` | No |
| `AWS_PROFILE` / `AWS_REGION` / `CLAUDE_CODE_USE_BEDROCK` | never read by name; passed to the subprocess by `subprocessEnv()` (`agent-sdk.ts:146-153`) | none | Only for `agent-sdk` |
| `CODE_REVIEW_LIVE`, `npm_lifecycle_event` | tests only | — | No — gates live tests off |

`subprocessEnv()` **deletes `OPENROUTER_API_KEY`** (`agent-sdk.ts:149`) and is explicitly *not*
an allow-list (`:140-141`).

**Budgets are constants, not env-configurable.** `MAX_DIFF_CHARS = 60_000` (`diff.ts:10`);
`STEP_BUDGET = 8` / `DEFAULT_TIMEOUT_MS = 120_000` (`agent.ts:45`, `:60`); `TURN_BUDGET = 8`,
`MAX_BUDGET_USD = 0.5` (`agent-sdk.ts:85`, `:99`). Only `ReviewOptions.timeoutMs`
(`reviewer.ts:56-57`) can move the clock and the CLI does not surface it — **a CI step cannot
raise the 120 s budget without editing code.**

One Node-version trap: `loadRepoEnv()` calls `process.loadEnvFile` (`env.ts:63`), which needs
Node ≥ 20.12. `engines` asks ≥ 22.

### 2.3 🚩 The diff cap — the single biggest operational obstacle

There is **no truncation anywhere**. `diff.ts:22-27`:

```
if (diff.length > MAX_DIFF_CHARS) {
  return `diff is ${diff.length} chars, over the ${MAX_DIFF_CHARS} limit. ` +
    'Review it in smaller commits rather than raising the cap blindly.'
```

`errors.ts:33` gives the reasoning: *"Past MAX_DIFF_CHARS — reviewing a truncated diff would be
reviewing a fiction."* The runner throws `diff-too-large` before spawning anything
(`agent-sdk.ts:314-317`) → **exit 2**.

**Measured against this repo's own history** (`git show --stat`, `git format-patch`):

| Commit / shape | Size |
|---|---|
| `b9b5ce1` (agent-sdk-reviewer p2) | 10 files, 2999 lines, **133 KB** |
| `d5e0fed` (merge, PR-shaped) | 21 files, 819 lines |
| `51db3fb` (merge, PR-shaped) | 41 files, 1499 lines |
| `0e5c398` (largest in history) | **371 KB** |
| Small doc commits | 1–4 KB |

60 000 chars is roughly 1 000–1 500 diff lines. **A realistic PR here is 2–6× the cap.** The
requirements assume the reviewer reads "the git diff"; on this repo's actual commit shapes it
would refuse most of them, loudly, with no review posted and a red job reporting a *setup*
failure rather than a code finding.

Cheap corollary: the PR **description** is rounding error beside a 20k–35k-token diff. The
requirements' `(?? cost tradeoff)` note on the description almost certainly inverts — the
description is nearly free; the diff is the entire cost.

Other diff facts:

- **No line cap and no file-count cap** — chars only.
- **Empty or whitespace-only** → `'the diff on stdin is empty — nothing to review.'`
  (`diff.ts:19-21`) → `empty-diff` → exit 2.
- **Changed-file parsing** (`diff.ts:50-87`): a `--- `/`+++ ` pair (`:75-82`), with `/dev/null`
  on the added side meaning a deletion (`:79`); the `diff --git a/x b/y` header is *the only*
  source when no pair follows (`:68-72`, `:85`) — i.e. mode-only and binary changes. Renames:
  the `b/` side wins, "that is the file a reviewer can open" (`:42-44`).
- Documented parser limit: a diff-of-a-diff can have `-- `/`++ ` misread as a header pair
  (`diff.ts:45-48`).

### 2.4 Repo root, the allow-list, and why a shallow checkout is silently wrong

**Repo root is derived from the module's own location, not `cwd` and not git** (`repo.ts:20-23`):

```ts
const HERE = dirname(fileURLToPath(import.meta.url));
/** `src` -> `code-reviewer` -> `packages` -> the repo root. */
export const REPO_ROOT = resolve(HERE, '../../..');
```

Immune to the step's working directory; would silently point at the wrong place if the package
were ever installed into a `node_modules` tree. `REPO_ROOT` is also the subprocess `cwd`
(`agent-sdk.ts:246`).

Allow-list (`repo.ts:36-59`, `:67`): subtrees `backend/src`, `frontend/src`, `frontend/e2e`,
`context`, `packages`, `.githooks`, `.github`; root files `CLAUDE.md`, `backend/CLAUDE.md`,
`frontend/CLAUDE.md`, `render.yaml`; denied segments `node_modules`, `target`, `dist`, `build`
and **every dot-segment except `.githooks` and `.github`**. The repo root itself is refused for
reads (`:105`) and for search (`:162-168`, "too broad to search — it contains .env").
`permission.ts:110-150` binds this to the Agent SDK's tools: only `Read`, `Grep` and the
injected answer tool; `Grep` with no `path` is denied because an omitted path searches the root
(`:128-133`); `Bash` hits deny-by-default (`:146-149`).

**🚩 An allow-listed path that does not exist passes the policy, and a missing subtree is
swallowed.** `resolveReadablePath` returns `ok: true` for a missing-but-allowed path
(`repo.ts:284-290`); the walker has `catch { return; } // an allow-listed subtree that does not
exist in this checkout` (`tools.ts:276-280`).

**Under a sparse or partial checkout the reviewer does not error — it reads nothing and reviews
the diff blind.** A full `actions/checkout` is a correctness requirement, not a convenience.
Verified on disk: all seven subtrees and all four root files exist at `a7573ae`.

**`.env`: no dependency in either direction** (`env.ts:59-64`), with the comment at `:51-53`:
*"Prefer a real environment variable when one is set, so CI never depends on a file that is not
committed."* `.env` is gitignored (`.gitignore:37`), so in CI it will not exist and the key must
come from a secret. The only failure mode is a *present but malformed* `.env`, where
`process.loadEnvFile` throws a non-`ReviewerError`.

### 2.5 Shallow clone: breaks the caller, not the package

**Nothing in `src/` invokes git.** `tools.ts:14` states it: *"Neither tool spawns a process.
`findInRepo` walks with `fs`…"*. The only subprocess in the tree is the test runner
(`scripts/run-tests.mjs:26`, spawning `node --import tsx --test`). The `agent-sdk` runner spawns
`claude`, but inside the SDK's `query()` (`agent-sdk.ts:334`), and it is denied `Bash` outright
so the model cannot shell out to git either.

**`fetch-depth: 1` breaks nothing in the package** — it only breaks whatever step produces the
diff (§4).

### 2.6 Install and invoke — three hazards, all measured

`packages/code-reviewer/package.json`: scripts `review` → `tsx src/index.ts`, `test` →
`node scripts/run-tests.mjs`, `test:live` → the same command (the difference is
`npm_lifecycle_event`), `typecheck` → `tsc --noEmit`. **No `bin` field**; `"private": true`,
`"type": "module"`; `engines.node >= 22`.

**Lockfile verified in sync.** `lockfileVersion 3`, 173 entries; all seven manifest ranges
resolve to concrete entries (`@anthropic-ai/claude-agent-sdk` 0.3.267,
`@openrouter/ai-sdk-provider` 3.0.0, `ai` 7.0.94, `zod` 4.5.4, `@types/node` 26.5.0, `tsx`
4.23.13, `typescript` 7.0.2). It carries `@anthropic-ai/claude-agent-sdk-linux-x64` and
`@esbuild/linux-x64`, so **a Windows-authored lock installs clean on `ubuntu-latest`**.

`tsconfig.json:14-19` — `noEmit: true`, `allowImportingTsExtensions: true`, because "tsx runs
the .ts sources directly." **No build step; nothing to cache but `node_modules`.**

1. **🚩 `npm run review` corrupts its own output.** npm's banner (`> tsx src/index.ts`) goes to
   **stdout**, ahead of the JSON — verified by running it. That breaks the stdout-is-JSON
   contract `index.ts` declares. Use `npx tsx src/index.ts` or `npm run --silent review`.
2. **🚩 `npm ci --omit=dev` breaks the run** — `tsx` is a devDependency and `review` *is*
   `tsx src/index.ts`. Full `npm ci`.
3. **The TTY guard does not protect CI.** `index.ts:44-45` prints the usage error only when
   `process.stdin.isTTY`. In Actions stdin is never a TTY, so a step that forgets the pipe reads
   zero bytes and reports `empty-diff` → exit 2, with no usage message.

### 2.7 Failure modes and the exit-code invariant

Every kind is declared in `errors.ts:14-74`; the class carries `kind` so callers never match on
message text (`:76-88`). **The mapping is uniform** — `index.ts:61-65`: *"Every kind maps to exit
2 … What matters is that no failure exits 0 … And nothing may escape to exit 1 either, which is
the code for 'the diff failed'."*

| Kind | Trigger | Exit | CI-caused? |
|---|---|---|---|
| `no-api-key` | No `OPENROUTER_API_KEY` (`agent.ts:247-250`); or no usable AWS credential — never loaded (`agent-sdk.ts:395-413`) or **loaded-and-refused**, detected at the second auth retry (`:353-356`, `:372-384`) | 2 | **Yes — the most likely CI failure.** `errors.ts:20-24` calls the refused case the dangerous one: "the profile is there, the region is set, and only the session is gone" |
| `empty-diff` | Nothing on stdin (`diff.ts:19`) | 2 | **Yes** — shallow clone or wrong base ref; indistinguishable from a no-op PR |
| `diff-too-large` | > 60 000 chars (`diff.ts:22`) | 2 | **Yes, environmentally** — any real PR here (§2.3) |
| `provider` | Provider refused/errored; catch-all after the credential branches (`agent-sdk.ts:415`); SDK result subtypes (`:512-519`) incl. `error_max_budget_usd` | 2 | **Yes** — blocked egress, rate limit, missing platform binary, spawn failure (`:368-371`: these "all arrive as prose") |
| `no-output` | Agent never called `submitReview` (`agent.ts:346-352`); no `structuredOutput` (`agent-sdk.ts:524-534`); `error_max_turns` (`:505-507`) | 2 | Mostly model-caused; indirectly CI-caused via a bad `CODE_REVIEW_MODEL` |
| `malformed-output` | Payload fails the `ModelReview` schema (`agent-sdk.ts:537-543`) | 2 | No |
| `timeout` | 120 s exceeded (`agent.ts:308-312`; `agent-sdk.ts:388-393`) | 2 | **Yes** — slow runner or throttled provider; **not tunable from the environment** |

**Exit 1 is only `review.verdict === 'fail'`** (`index.ts:116`); exit 0 is a passing review.
`errors.ts:9-11` states the invariant: `no-api-key` and `provider` "are setup failures, and
neither may ever be reported as a passing review."

**For a CI operator this is the single most valuable property: no failure path can produce exit
0.** A green job genuinely reviewed something. It also means **the job cannot distinguish "code
is fine" from "code is bad" by success alone** — 0 and 1 are both real reviews, 2 is not a
review, and the workflow must branch on all three (§6.3).

---

## 3. 🚩 The trigger problem — a PUBLIC repo cannot review fork PRs on `pull_request`

This is the finding that most changes the requirements.

### 3.1 The clamp, and that it is applied last

> "With the exception of `GITHUB_TOKEN`, secrets are not passed to the runner when a workflow
> is triggered from a forked repository." — [use-secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)

> "The `GITHUB_TOKEN` has read-only permissions in pull requests from forked repositories."
> — [events-that-trigger-workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)

**It cannot be overridden by `permissions:`.** From
[workflow-syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions) — the clamp runs **after** your YAML:

> "**Finally**, if the workflow was triggered by a pull request event other than
> `pull_request_target` from a forked repository, and the *Send write tokens to workflows from
> pull requests* setting is not selected, the permissions are adjusted to change any write
> permissions to read only."

The escape hatch is **private-repos-only** — the 2021 changelog: "Pull requests from public
forks are still considered a special case and will receive a read token regardless of these
settings." (One documentation disagreement: the `workflow-syntax` page states the exception
*without* the private-repo qualifier while the repo-settings page scopes it to private repos;
the changelog resolves it. **For a public repo treat the clamp as non-negotiable.**)

**Consequence:** a plain `on: pull_request` workflow reviewing a **fork** PR on this repo has
neither the LLM key nor write access to comment or label. Both side-effects in the requirements
fail, and they fail on external contributions — the case where an automated review is worth
most.

For **same-repo** PRs (branch → `main`, which is the only shape this repo has ever had — it has
had no PRs at all, and one contributor) `pull_request` works fully: secrets present, token
writable. So a `pull_request`-only design is *functional today* and *silently broken the first
time someone forks*.

### 3.2 `pull_request_target` — the fix and the trap, and why the trap is avoidable here

`pull_request_target` takes the workflow file and the default checkout from **the base repo's
default branch**, and gets secrets and a read/write token "even when it is triggered from a
public fork."

GitHub's dedicated page —
[Securely using pull_request_target](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target),
new in mid-2026 — states the mechanism exactly:

> "The checkout step alone does not execute untrusted code. The workflow file itself still
> comes from the default branch. **The vulnerability is completed by the next step that runs
> code checked out into the current working directory.**"

And the rule:

> "You must ensure the checked-out code is only ever inspected as data and never executed."

> "Execution is not limited to your own steps: build and test commands such as `npm install`
> and `npm run build`, as well as configuration files and dependencies … can all run
> attacker-controlled code."

**🚩 This lands directly on our design: the reviewer is a Node CLI.** `npm ci` against a fork's
`package.json` is arbitrary code execution via `preinstall`/`postinstall`, inside a job holding
`OPENROUTER_API_KEY`. **Never install dependencies from the PR head in a privileged job.**
([GitHub Security Lab](https://securitylab.github.com/resources/github-actions-github-actions-preventing-pwn-requests/)
has flagged this since 2021.)

The good news: **our reviewer needs no PR-head checkout at all.** It reads a diff on stdin and
reads *base-branch* files through its own allow-list. The default `pull_request_target`
checkout — the default branch — is exactly the tree it wants. The diff arrives via API as data
(§4). This is GitHub's own sanctioned use: *"This event allows your workflow to do things like
label or comment on pull requests from forks,"* safe when "the PR contents are treated as
passive data."

**New default that helps:** `actions/checkout` is **v7** (v7.0.1, 2026-07-20) and now **refuses**
to check out fork PR code under `pull_request_target` / `workflow_run` unless passed
`allow-unsafe-pr-checkout: true` (shipped 2026-06-18, backported to all supported majors
2026-07-20). Floating tags inherit the protection; **SHA pins do not**. Not blocked: `git fetch`
or `gh pr checkout` inside a `run:` block. `pull_request_target` also now has read-only cache
access, closing cache poisoning.

### 3.3 The `workflow_run` split — GitHub's stated preference, and its costs

From [secure-use](https://docs.github.com/en/actions/reference/security/secure-use): "For
privilege separation between workflows, `workflow_run` is a better trigger." An unprivileged
`pull_request` workflow produces the diff as an artifact; a privileged `workflow_run` workflow
consumes it and holds the secrets.

Costs, each of which is a real complication here:

- Runs from the **default branch**, and "will only trigger a workflow run if the workflow file
  exists on the default branch" — interacting with §2.1.
- **The PR number is not in the context.** Pass it via artifact, or look it up by head SHA.
- **Artifacts are untrusted input**: "Artifacts resulting from untrusted PR data are themselves
  untrusted." Extract outside the workspace; validate the PR number as an integer.
- `context.issue.number` is **`undefined` for `workflow_run`** (`@actions/github` resolves
  `payload.issue || payload.pull_request || payload`).
- **Does not appear in the PR checks UI** (checks attach to a SHA; a `workflow_run` run's SHA is
  the default-branch head). Surfacing it needs a Checks API POST against the PR head SHA. This
  is **UNVERIFIED** as an official statement — strongly indicated, not documented.
- Max three levels of chaining; set `persist-credentials: false`; gate on
  `workflow_run.conclusion == 'success'` **and** `workflow_run.event == 'pull_request'`.

### 3.4 Other controls, ranked

- **Environment approval gates.** "A workflow job cannot access environment secrets until
  approval is granted by a reviewer." Up to 6 reviewers, one approval needed, "Prevent
  self-review" available. Useless on self-hosted runners, which "should almost never be used for
  public repositories" — not relevant here (`ubuntu-latest`).
- **Label gating is the weakest, and GitHub does not endorse it.** Absent from both security
  references. Security Lab warns a `safe to test` label is "still prone to a race condition in
  which the attacker may push new changes after the workflow was approved" and "prone to human
  error." Note this bears directly on `ai-cr:review`: as a *retrigger* it is fine; as a *security
  control* it is not.
- **Fork-approval defaults**: "By default, all first-time contributors require approval to run
  workflows" — but approval is about **compute abuse**; approving does **not** hand the run
  secrets or a write token.
- **Workflow execution protections** (public preview, 2026-06-18) — ruleset-based allowlists for
  which events and actors may trigger workflows, with an "evaluate mode", explicitly
  recommending you "Restrict or prohibit `pull_request_target`". Documented at enterprise, org
  **and repository** level — but **whether a personal-account repo (not in an org) can use it is
  UNCERTAIN**; setup instructions describe only org/enterprise paths. This repo is a
  personal-account repo.

### 3.5 Relevant platform changes

| Date | Change |
|---|---|
| 2023-02-02 | Default `GITHUB_TOKEN` → read-only for **newly created** repos; existing untouched. (This repo reports `read` — consistent.) |
| **2026-06-18** | `checkout@v7` GA refuses fork-PR checkout in privileged contexts |
| **2026-06-18** | Workflow execution protections in public preview |
| 2026-07-20 | Checkout protection backported to all supported majors (v1 excluded) |
| 2026-09-03 | New `vulnerability-alerts` read-only permission scope |
| announced 2026-03-26 | [2026 Actions security roadmap](https://github.blog/news-insights/product-news/whats-coming-to-our-github-actions-2026-security-roadmap/): **scoped secrets**, an egress firewall, "write access to a repository will no longer grant secret management permissions" — all **planned, not GA** |

**Current action versions:** `checkout@v7` (7.0.1), `github-script@v9` (9.0.0, 2026-04-09),
`setup-node@v7` (7.0.0), `setup-java@v4` (what the existing workflow pins).
`ubuntu-latest` = Ubuntu 24.04 x64, with `gh` 2.98.0 and `jq` 1.7 preinstalled.

---

## 4. Getting the diff — a second ceiling, below the first

| Option | Verdict |
|---|---|
| **`gh pr diff <n>`** | Simplest. It is **REST, not git** — GETs `/pulls/{n}` with `Accept: application/vnd.github.v3.diff`. `--name-only` is **not** a separate request; it regex-parses the same diff client-side, so it inherits the ceiling even for few files. **Hard-fails `406 "Sorry, the diff exceeded the maximum number of lines (20000)"`.** Needs `GH_TOKEN` per step. |
| **`git diff` after checkout** | The only option with **no server-side ceiling**. Needs `fetch-depth: 0` or incremental `--deepen`. Under `pull_request_target` this means fetching the fork ref — data only, never executed. |
| **`GET /pulls/{n}/files`** | Degrades gracefully; the fallback when the diff media type 406s. Caps at **3000 files**, 30/page (max 100). |
| **`GET /compare/{basehead}`** | "includes up to **300 changed files**"; "The list of changed files is only shown on the first page." Commits capped at 250. |

**Documented limits** — the only page where GitHub states diff numbers is
[repository-limits](https://docs.github.com/en/repositories/creating-and-managing-repositories/repository-limits) § Diff limits:

> "In a pull request, no total diff may exceed **20,000 lines** … or **1 MB** of raw diff
> data." / "No single file's diff may exceed 20,000 lines … or 500 KB." / "The maximum number of
> files in a single diff is limited to **300**."

**Two ceilings stack, and both are below this repo's PRs.** GitHub refuses the diff media type
above 20 000 lines; the reviewer refuses above 60 000 chars (§2.3). `b9b5ce1` (2999 lines,
133 KB) clears GitHub's line limit but is **2.2× the reviewer's char cap**. So the binding
constraint is ours, and it binds first.

**Three-dot is what the requirements mean.** "Pull requests on GitHub show a three-dot diff";
the Files changed tab "shows what would change if the pull request merged." Two-dot "changes
when the base branch is updated, even if you haven't made any changes to the topic branch."

**`fetch-depth` defaults to `1`** — "Only a single commit is fetched by default." With depth 1
there is no local common ancestor, so `git merge-base` and `git diff base...head` fail with
`exit status 128`. The default and "single commit" are documented; the merge-base failure is
**verified by report, not by GitHub docs**.

What checkout gets: on `pull_request`, `GITHUB_REF` = `refs/pull/N/merge` and `GITHUB_SHA` = the
**merge commit** — "Because `actions/checkout` uses `GITHUB_REF` by default, it checks out the
merge branch," detached HEAD. On `pull_request_target`, both point at the **default branch**.

**Corrections to common assumptions:**

- **406 on the *compare* endpoint is not documented** — its statuses are 200/404/500/503. The
  406 **is** documented on `GET /pulls/{n}`. The 406 *message texts* come from observed
  responses ([cli#10712](https://github.com/cli/cli/issues/10712)), not docs.
- **There is NO documented patch-size truncation on `/pulls/{n}/files`** — no "20k lines," no
  per-file cap; `patch` is simply an optional string in the schema. Empirically it *is* omitted
  for some files ([docs#32223](https://github.com/github/docs/issues/32223)), but that is an
  open bug report, not a specification.
- Nearest documented caveat, on *Get a commit*: "Larger diffs may time out and return a 5xx."

**Practical shape:** diff media type → `/pulls/{n}/files` fallback → local `git diff` as floor.

---

## 5. Labels, retriggers and loop prevention — the requirements are right here

### 5.1 Activity types

21 types, identical for `pull_request` and `pull_request_target`, including **`labeled`** and
`unlabeled`.

> "By default, a workflow only runs when a `pull_request` event's activity type is `opened`,
> `synchronize`, or `reopened`."

**🚩 `types:` REPLACES the defaults.** `types: [labeled]` alone means no runs on push. The
requirements need both behaviours, so:
`types: [opened, synchronize, reopened, labeled]`.

### 5.2 The loop rule, and why `ai-cr:review` is safe by construction

GitHub's own [reusable snippet](https://raw.githubusercontent.com/github/docs/main/data/reusables/actions/actions-do-not-trigger-workflows.md):

> "When you use the repository's `GITHUB_TOKEN` to perform tasks, events triggered by the
> `GITHUB_TOKEN` will not create a new workflow run, with the following exceptions:"

Exceptions, both verified: (1) `workflow_dispatch` and `repository_dispatch` "always create
workflow runs"; (2) `pull_request` with types `opened`/`synchronize`/`reopened` — runs **are**
created but land in an **approval-required** state.

**`labeled` is not among the exceptions.** So the workflow applying `ai-cr:passed` /
`ai-cr:failed` with `GITHUB_TOKEN` produces **no run at all**, and cannot loop. GitHub documents
this with a paired example: the PAT version means "Any workflows that run when a label is added
will run once this step is performed"; the `GITHUB_TOKEN` version "will not trigger any
workflows that run when a label is added."

**🚩 A PAT or GitHub App token DOES trigger — that is the infinite loop.** The requirements'
design (apply a label from the same workflow that triggers on `labeled`) is safe **only** while
the write uses `GITHUB_TOKEN`. That is a constraint to state in the plan, not an accident to
rely on.

### 5.3 Guarding on the label

```yaml
if: github.event.label.name == 'ai-cr:review' ||
    contains(github.event.pull_request.labels.*.name, 'ai-cr:review')
```

Two different meanings, and both are wanted: `github.event.label.name` = "the label just
applied" (absent on a `synchronize` run); `contains(...labels.*.name, ...)` = "the PR currently
carries it" (true on any activity type).

`github.event.label` for `pull_request` is **schema-verified**
([octokit payload-schemas](https://raw.githubusercontent.com/octokit/webhooks/main/payload-schemas/api.github.com/pull_request/labeled.schema.json)
— `required: [action, number, pull_request, label, repository, sender]`) but only
documented-by-example for `issues`.

### 5.4 Concurrency

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: ${{ github.event.action == 'synchronize' }}
```

Semantics are fully documented; this exact string composes two documented patterns rather than
being a documented snippet. **The conditional `cancel-in-progress` is deliberate**: with a plain
`true`, a `synchronize` push mid-review kills a review a human explicitly requested via label.
Alternative: add `-${{ github.event.action }}` to the group key so pushes and label requests do
not contend.

New in 2026: a **`queue`** option. `single` (default) — "At most one job or workflow run can be
`pending`," a newer arrival cancels the pending one. `max` — up to 100 pending. **"The
combination of `queue: max` and `cancel-in-progress: true` is not allowed"** (validation error).
Group names are case-insensitive; FIFO but "ordering is not guaranteed."

### 5.5 Label inventory and colours

17 labels exist, all `prefix:value` (`stream:A`, `status:ready`). None are `ai-cr:*`. **Three
labels need creating** — `ai-cr:passed` (green), `ai-cr:failed` (red), `ai-cr:review` — and the
naming convention already fits. Colours are a create-time property; the requirements name red
and green, which matches GitHub's own `d73a4a` / `0e8a16` conventions.

---

## 6. Composite action, permissions, and the sticky comment

### 6.1 House style for a workflow file in this repo

`.github/workflows/live-market-price.yml` (66 lines) is the only precedent and it sets a clear
standard:

- A **10-line "why" header comment** before any YAML — explaining that `r.jina.ai` is
  unreachable behind the corporate proxy, that production runs `SPRING_PROFILES_ACTIVE=mock`,
  that **"No secrets are required"**, and "Not on push: the assertions depend on live
  third-party markup."
- `actions/checkout@v4`, `actions/setup-java@v4` (temurin 21, `cache: maven`).
- `defaults.run.working-directory: backend`.
- An inline `python3` heredoc readiness probe.
- `actions/upload-artifact@v4` with `if: always()` and `if-no-files-found: warn`.

**The header comment is the convention that matters.** A workflow here is expected to say why it
is shaped as it is, in the file, including why it is *not* wired to a trigger.

### 6.2 Composite actions — one hard limit, cleanly worked around

**A composite `action.yml` cannot reference `${{ secrets.* }}`.** From the
[contexts reference](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts):

> "The `secrets` context is not available for composite actions due to security reasons." … "If
> you want to pass a secret to a composite action, you need to do it explicitly as an input."

The runner's [`action_yaml.json`](https://raw.githubusercontent.com/actions/runner/main/src/Runner.Worker/action_yaml.json)
is authoritative: allowed contexts inside composite steps are exactly `github, inputs, strategy,
matrix, steps, job, runner, env, hashFiles`. **No `secrets`, and no `vars`.**

```yaml
- uses: ./.github/actions/ai-review
  with:
    api-key: ${{ secrets.OPENROUTER_API_KEY }}   # (a) as an input
  env:
    OPENROUTER_API_KEY: ${{ secrets.OPENROUTER_API_KEY }}  # (b) as env
```

**Prefer (b), `env`.** Masking is **value-based and runner-side**, so both are redacted — "a
secret will only be redacted if it was used within a job and is accessible by the runner"
([secure-use](https://docs.github.com/en/actions/reference/security/secure-use)) — but `env`
keeps the value out of the `with:` block the runner echoes in step headers. Whether `with:`
values leak under `ACTIONS_STEP_DEBUG` is **UNVERIFIED**; (b) is the cheap hedge. Two caveats
from the same page: redaction "is not guaranteed" if the composite transforms the value
(re-register with `::add-mask::`), and "Never use structured data as a secret." Prefer env or
stdin over argv — a secret on a command line "may be visible to other users (using the `ps`
command)". **Our reviewer reads the key from the environment, which is the right shape already.**

**Capability table, corrected against stale common knowledge:**

| Key on a composite step | Supported? |
|---|---|
| `if:` | **Yes** (runner ≥ 2.284.0, 2021-11) |
| `continue-on-error` | **Yes** — implemented [runner#1763](https://github.com/actions/runner/pull/1763) (2022-04), undocumented until 2025 |
| `timeout-minutes` | **No** — [runner#1979](https://github.com/actions/runner/issues/1979) open |
| `working-directory` | **Yes, on `run` steps only** |
| `shell:` | **Required on every `run` step**; workflow-level defaults do not apply |
| `defaults.run` | **No** — [runner#836](https://github.com/actions/runner/issues/836) open |
| `services`, `strategy`, `runs-on`, `permissions` | **No** — a composite inherits the calling job's token permissions |
| `post:` / `post-if:` | **No** (JS/Docker only). But post steps of actions the composite `uses:` **do** run |
| `uses:` in a step | **Yes** since 2021-08-25 (local `./`, other repos, `docker://`, other composites) |

Nesting depth: docs say 10, the runner source says `CompositeActionsMaxDepth = 9`. Irrelevant at
depth 1 — but **do not trust the docs number**.

Outputs need `id` + `$GITHUB_OUTPUT`, wired through `outputs.<name>.value`. Cap 1 MB/job,
50 MB/run.

**Five gotchas that would actually bite:**

- **`vars` fails at parse time** with `Unrecognized named-value: 'vars'`
  ([runner#2551](https://github.com/actions/runner/issues/2551)). A regression in v2.331.0 let
  it through for ~2 months in early 2026 — **silently evaluating to empty** — and v2.333.0
  restored the error. GitHub: "Apologies, `vars` was never intentionally supported."
- **Composite actions get no `INPUT_*` env vars** — use the `inputs` context. And "Actions using
  `required: true` will not automatically return an error if the input is not specified," so
  **validate inputs yourself**. (This repo's own rule — every layer fails loudly when its
  toolchain is missing — applies verbatim.)
- **`GITHUB_ENV` written inside a composite leaks out** to the caller's later steps. Sanctioned
  by docs; still an encapsulation leak. Prefer outputs.
- **`success()` / `failure()` inside a composite mean the *composite's* status, not the job's**
  ([ADR 1438](https://github.com/actions/runner/blob/main/docs/adrs/1438-conditional-composite.md)).
  Use `github.action_status`.
- **`./` inside a composite resolves against `$GITHUB_WORKSPACE`, not the action directory.** Use
  `$GITHUB_ACTION_PATH`. New 2026-07-30: `uses: $/...` self-repository syntax (runner ≥ 2.336.0,
  not on GHES).

`secrets: inherit` is **reusable-workflows-only** — a job-level key, meaningless for composites.

### 6.3 The permissions block

```yaml
permissions:
  contents: read        # actions/checkout; GET /compare/{basehead}
  pull-requests: write  # create/update/list comments, add/remove labels
```

**`issues: write` is NOT required** — settled by the per-endpoint permission blocks on the REST
reference pages, which say *"at least one of the following permission sets"*:

| Endpoint | Requirement (verbatim) |
|---|---|
| `POST /issues/{n}/comments` | "at least one of": Issues (write) / **Pull requests (write)** |
| `PATCH /issues/comments/{id}` | "at least one of": Issues (write) / **Pull requests (write)** |
| `GET /issues/{n}/comments` | "at least one of": Issues (read) / Pull requests (read) |
| `POST /issues/{n}/labels` | "at least one of": Issues (write) / **Pull requests (write)** |
| `DELETE /issues/{n}/labels/{name}` | "at least one of": Issues (write) / **Pull requests (write)** |
| `GET /pulls/{n}/files` | Pull requests (read) — **only** |
| `GET /compare/{basehead}` | **Contents (read) — only** |
| `POST /pulls/{n}/reviews` | Pull requests (write) — only |

The decoding rule, from
[troubleshooting-the-rest-api](https://docs.github.com/en/rest/using-the-rest-api/troubleshooting-the-rest-api):
in `X-Accepted-GitHub-Permissions`, **comma = AND, semicolon = OR**. The index page is
*ambiguous* — its ✓ column cannot distinguish AND from OR — so read the endpoint page or the
header.

`contents: read` is strictly unnecessary on a **public** repo (every read endpoint carries "can
be used without authentication … if only public resources are requested") — keep it anyway: it
is free and it is what saves this workflow if the repo ever goes private.

**Three research-process findings worth recording:**

- The commonly cited URL `docs.github.com/en/actions/reference/authentication/github-token` is
  **404**. The old scopes-and-defaults table has been removed from the Actions docs; it survives
  only on Enterprise Server pages, which are **UNVERIFIED for github.com in 2026**.
- **`metadata` and `repository-projects` are no longer settable `permissions:` scopes**;
  `artifact-metadata` and `code-quality` are new. Any lint rule asserting `metadata: read` needs
  revisiting.
- **Markdown converters silently drop the permission blocks from REST pages** — raw HTML was
  required. Do not trust a markdown-rendered GitHub REST page for permissions.
- Minor unresolved disagreement: whether `models` is a current scope.

### 6.4 The sticky comment

`actions/github-script` is **v9.0.0**. **v9 breaking change:** `require('@actions/github')` no
longer works — `@actions/github` v9 is ESM-only; use the injected `getOctokit`.

```yaml
- uses: actions/github-script@v9
  with:
    script: |
      const fs = require('fs');
      const MARKER = '<!-- ai-review -->';
      const body = MARKER + '\n' + fs.readFileSync('review.md', 'utf8');
      const { owner, repo } = context.repo;
      const issue_number = context.issue.number;
      const comments = await github.paginate(
        github.rest.issues.listComments, { owner, repo, issue_number, per_page: 100 });
      const existing = comments.find(c =>
        c.user.login === 'github-actions[bot]' && c.body.includes(MARKER));
      if (existing) {
        await github.rest.issues.updateComment({ owner, repo, comment_id: existing.id, body });
      } else {
        await github.rest.issues.createComment({ owner, repo, issue_number, body });
      }
```

- **`github.paginate` is required, not optional** — `listComments` defaults to `per_page: 30`,
  so on a busy PR the marker lands on page 2 and the bot posts duplicates.
- The `user.login` check matters: `updateComment` on someone else's comment 403s.
- **🚩 Read the body from a file; never interpolate `${{ }}` into `script:`.** That is script
  injection, and it also avoids `Argument list too long`.

The `gh` one-liner works too:

```yaml
- env: { GH_TOKEN: '${{ secrets.GITHUB_TOKEN }}' }
  run: gh pr comment ${{ github.event.pull_request.number }} --edit-last --create-if-none --body-file review.md
```

`--edit-last` = "Edit the last comment of the current user"; `--create-if-none` "Can be used only
with --edit-last". **Semantic difference: `--edit-last` matches the last comment by the current
user with no marker awareness** — if the workflow ever posts two kinds of comment as
`github-actions[bot]`, it clobbers the wrong one. Keep the HTML marker regardless, for
auditability.

- **`context.issue.number`** resolves via `(payload.issue || payload.pull_request ||
  payload).number`. Works for `issues`, `issue_comment`, `pull_request`, `pull_request_target`,
  `pull_request_review`. **`undefined` for `push`, `schedule`, `workflow_dispatch`, and
  `workflow_run`.**
- **Body limit is 65 536 characters — real but UNVERIFIED in official docs.** Zero hits for
  `65536` across the docs; it surfaces only as `422 "Body is too long (maximum is 65536
  characters)"`, reproduced across five independent issue threads, with one report claiming
  ~200 KB went through. **Truncate at ~60 000 and put the full text in `$GITHUB_STEP_SUMMARY`.**

---

## 7. 🚩 The six-criteria requirement — a different artefact, and a rule it breaks

This is the largest design question in the requirements, and the reason the third scope answer
asked for both paths mapped.

### 7.1 What ships today

| Current | Requirements ask for |
|---|---|
| `ModelReview` (zod) = `{summary, findings[]}`; `Finding` = `{file, severity, summary, rationale, evidence?}` | Six named criteria, each scored **1–10** |
| `Severity = z.enum(['blocker','major','minor','nit'])` | 1–10 integers |
| `deriveVerdict` (`verdict.ts:22-27`) = `fail` **iff** any surviving finding is `blocker` or `major` — **arithmetic, not stated by the model** | not specified; presumably a threshold |
| Four **project rules** at `prompt.ts:96-101` | six **generic** criteria |
| Severities defined in **one line**, `prompt.ts:103`, ending "Choose them honestly; the overall conclusion is computed from them, not stated by you." | 1/10 verbal anchors per criterion |

`partitionByDiffScope` then `stripUnbackedEvidence` run **before** `deriveVerdict`, so a
`blocker` on an off-diff file yields `pass`. `minor` and `nit` **cannot** affect the verdict.
`Verdict` / `ReviewOutcome` are plain TS interfaces with **no runtime validator**.

### 7.2 🚩 The two parked criteria are where this project's most important rules live

| Current project rule (`prompt.ts:96-101`) | Maps onto | Status in the requirements |
|---|---|---|
| 1. Absence of accident data means UNKNOWN, never "clean" — *blocker* | business alignment | **parked** |
| 2. Fail loudly when a dependency is missing — *blocker* | implementation correctness | ✅ the only clean map |
| 3. Vendor detail belongs in one adapter — *major* | architectural fit | **parked** |
| 4. E2E test hygiene | test / risk coverage | partial — covers "assert on behaviour", not `waitForTimeout` or locators |

`prompt.ts:8-9` describes rule 1 as "the project's first rule, and the one with a real user harm
behind it" — it is the same rule root `CLAUDE.md` opens with. Rule 3 is the only rule whose
enforcement exercises the tool loop, the allow-list and the evidence-stripping layer at all.

**Adopting the six criteria as written drops both.** And in the other direction, three of the
six — **complexity, documentation, security/safety** — have no mention anywhere in
`prompt.ts:92-115`; idiomaticity appears only weakly by implication (`:96` "outrank general
style preferences", `:103` "nit = taste").

### 7.3 Blast radius of changing the schema

- **35 tests are coupled to the `{summary, findings[], severity}` shape; 12 assert directly on a
  severity value or on `verdict`** — 8 offline in `verdict.test.ts`, 1 offline in
  `failures.test.ts`, 3 live-only.
- `tools.test.ts:23`/`:33` read `src/verdict.ts` as a **fixture** and grep for
  `export function deriveVerdict`, so renaming it breaks a repo-reading test.
- ~15 production files touch the shape.

### 7.4 🚩 It collides with `test-plan.md`'s own risk-6 carve-out

`test-plan.md:74` lists as Risk #6's anti-pattern: *"An eval asserting a specific model wording —
non-deterministic and expensive for the signal."* And `:77-81` carves out the reviewer
**precisely because its output is not prose**:

> "It does not prohibit evals over the repo-tooling reviewer in §4, **whose output is a schema
> (severity, file, verdict) rather than prose, and whose assertions are therefore about a
> decision, not a wording.**"

**Six 1–10 scores make the output a wording score**, landing it on the prohibited side of the
rule that currently authorises this repo's reviewer work at all.

### 7.5 It collides with the `code-review-evals` plan in nine places

That plan was written the same day (`status: planned`, unimplemented) and depends on severities:

| Collision | Where |
|---|---|
| The static assertion (`verdict === 'fail'`) | `plan.md:85`, `:626` |
| The escalation counter | `plan.md:650-654` |
| Answer-key intended severities | `plan.md:361-363`, `:378-379`, `:395-397`, `:415-416` |
| Fixed-transcript tests | `plan.md:677-678` |
| The "same prompt" rule | `plan.md:86`, `:130-131` |
| Rule-based fixtures | `plan.md:91`, `:399-403` |
| Judge input assembly | `plan.md:642-643` |
| Three-flaws-per-fixture guard | `plan.md:429-430` |
| `test-plan.md` §2 risk-6 exception | `plan.md` phase 7 |

Independently, `code-review-evals/research.md:324` already argues 1–10 scales are the wrong
instrument here: **κ 0.85 binary → κ 0.55 in 1–5 form**, and a binary metric reaches a given CI
width at roughly **¼ the N**.

### 7.6 What survives either decision

promptfoo mechanics; the no-preamble fixture precedent; the `options.tools`/`accessedPaths`
repair; `deniedTools`; the answer-key leak assertion; the recall/precision split; the `errored`
third outcome; the zero-judged-cases guard.

**This is the headline open question for `/10x-plan`** — see §9.

---

## 8. Prompt injection — a documented attack class, matching this design exactly

### 8.1 Two findings that describe this workflow specifically

**[OWASP MASTG issue #3783](https://github.com/OWASP/mastg/issues/3783) (2026-05-01) is this
design, verbatim** — a `pull_request_target` workflow with `pull-requests: write`, feeding PR
title/body/diff to a model, using the verdict to apply one of several labels and post a generated
comment. Filed as a security concern.

**["PromptPwnd"](https://www.aikido.dev/blog/promptpwnd-github-actions-ai-agents)** (Aikido, Dec
2025, upd. Mar 2026) names the class: untrusted issue/PR text → prompt → agent calls privileged
tools → secrets leak. The PoC injected a fake instruction block in an issue body telling the
agent to `gh issue edit --body` with `$GEMINI_API_KEY` and `$GITHUB_TOKEN` in it. "At least 5
Fortune 500 companies impacted." No CVE.

**CamoLeak (CVE-2025-59145, CVSS 9.6)** — a hidden HTML comment in a **PR description** injected
Copilot Chat, which exfiltrated private source one character per pre-signed
`camo.githubusercontent.com` pixel, defeating CSP via GitHub's *own* image proxy. Fixed by
disabling image rendering outright. **The lesson: domain allowlisting is not enough, because a
first-party proxy is on the allowlist.** Directly relevant: the requirements feed the **PR
description** to the model.

### 8.2 The controls that matter, in order

**The highest-leverage one: keep the model out of the write path.** Have it return JSON; validate
the label against the two literals in trusted code; exit non-zero otherwise; let *our* code apply
the label and post a fixed-template comment. This is MASTG #3783's own recommendation, matches
OWASP LLM01's "handle these functions in code rather than providing them to the model," and is the
[dual-LLM pattern](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)
("A privileged LLM holds the tools but never reads untrusted content directly").

**This repo is already most of the way there.** `index.ts` prints a validated `ReviewOutcome` as
JSON on stdout and exits 0/1/2; the verdict is *computed* by `deriveVerdict`, not stated by the
model (`prompt.ts:103`). The label follows from an exit code, not from model text. That is the
right architecture already — the plan needs to not break it.

Then, roughly in order:

- **No secrets in the job that ingests untrusted text** (Anthropic: "if credentials never enter
  the sandbox, they can't be exfiltrated"). This is the `workflow_run` split's real argument.
- **Strip HTML comments, zero-width Unicode, image alt text and hidden attributes** before the
  prompt (CamoLeak).
- **Model output never becomes a command** — no `run: ${{ steps.ai.outputs.* }}`, no `gh`
  invocation built from model text. Env-var indirection prevents *shell* injection but Aikido is
  explicit that it "does not protect against prompt injection."
- **Sanitise images and links out of anything posted back.**
- Allowlist tools with no shell and no arbitrary URL fetch — **already true**
  (`permission.ts:146-149` denies `Bash` by default; only `Read`, `Grep` and the answer tool are
  allowed).
- **Pin actions to full SHAs** — but note §3.2: SHA pins do **not** inherit checkout's new
  fork-checkout protection.
- **Never set `ACTIONS_STEP_DEBUG=true`** — logs are "publicly visible in GitHub Actions for
  public repositories."

**Anthropic's prompt-construction guidance**
([mitigate-jailbreaks](https://platform.claude.com/docs/en/test-and-evaluate/strengthen-guardrails/mitigate-jailbreaks))
is the most directly actionable: put untrusted content **only in `tool_result` blocks** — "never
in `system` prompts or plain user `text` blocks. Claude is trained to treat instructions that
appear inside tool results with appropriate skepticism"; **JSON-encode it** — "an attacker cannot
close a quote or tag to 'break out'"; put your instructions in a `user` turn **after** the tool
result; add an explicit `<untrusted_content_policy>` block.

**GitHub's own agentic principles** ([2025-11-25](https://github.blog/ai-and-ml/github-copilot/how-githubs-agentic-security-principles-make-our-ai-agents-as-secure-as-possible/))
— "maximize interpretability, minimize autonomy": strip invisible content before the model sees
it; default-deny egress; least privilege ("the shortcut to preventing leaks is to not give access
to it in the first place"); no irreversible state changes; and on public repos read issue comments
**only from write-access users**.

**Anthropic's `claude-code-action`
[security doc](https://raw.githubusercontent.com/anthropics/claude-code-action/main/docs/security.md)**
is worth reading if that route is ever considered: its primary control is a **write-access gate on
the trigger**, `allowed_non_write_users` is called "a significant security risk," and it mandates
`secrets.GITHUB_TOKEN` over a PAT because "a static token does not rotate between runs and could
be partially or fully recovered over time via prompt injection." It also has the pattern this
design would need if it ever did check out head: base at root, PR head into a subdirectory via
`--add-dir`.

**Two honest limits.** Capping input size is worth doing for cost and latency but **is not an
injection defence** — LLM01's "payload splitting" scenario argues truncation is not a security
control. And no source claims any of this makes injection impossible; OWASP LLM01: "it is unclear
if there are fool-proof methods of prevention." OWASP is still the **2025** edition — no 2026 list
exists. The terms "delimiting" and "spotlighting" appear in **no** GitHub or Anthropic source
fetched — vocabulary, not cited controls.

### 8.3 What this repo already knows about injection

`packages/code-reviewer/src/injection.test.ts` exists, and `injection.diff` is one of the three
fixtures the runner comparison used (`pick.md:79-81`). The reviewer has been measured against a
diff that tries to talk to it. That is a starting asset, not a solved problem — the fixture tests
the *diff* channel; the requirements add the *title* and *description* channels, which are new.

---

## 9. Which runner, and the credential question — already decided by the record

### 9.1 `pick.md`'s condition #2 *is* this change

`context/changes/agent-sdk-reviewer/pick.md:215-230` lists five conditions that would reverse its
choice of `agent-sdk`. Condition #2, verbatim:

> "**CI, or any second machine.** The moment the reviewer must run somewhere other than this
> laptop, **Bedrock's short-lived SSO session disqualifies `agent-sdk` on auth availability
> alone, regardless of every other row.**"

Condition #5 adds the cost: the `agent-sdk` platform binary is **210 MB** for `…-win32-x64`,
eight variants published — "free on a warm laptop and **is not free in a container image or a cold
CI cache**." `ai-sdk` is `ai` 8.1 MB + provider 1.4 MB, no binary.

The auth row itself (`pick.md:65`):

> "a **long-lived `OPENROUTER_API_KEY`; deployable to CI** … | **a corporate AWS SSO session that
> expires within hours, cannot go to CI or Render**, and **whose expiry looks exactly like working
> configuration**"

**So CI runs `ai-sdk` with `OPENROUTER_API_KEY` as an Actions secret.** This is not a decision the
plan must make — the record made it in advance, and `code-review-evals/change.md:26-31` has
already taken the same reversal for the evals.

### 9.2 The credential rule, and where it actually lives

The rule "never copy AWS credentials into a hosting platform" is real, but **`pick.md:65` and this
change's `change.md:32` both attribute it to root `CLAUDE.md`, where it is not present.** Its
actual home is `backend/CLAUDE.md:47`:

> "the sole AWS credential source here is a corporate SSO profile (`kn.awsapps.com`, role
> `KN-DevelopmentEngineer`) issuing short-lived credentials, so it cannot back a hosted service —
> **do not copy AWS credentials into Render to work around this.**"

Restated at `packages/code-reviewer/.claude/skills/agent-sdk/SKILL.md:412-413`,
`roadmap.md:75`, `repo-map.md:79-80`, `artifact-1-territory.md:170`.

**Counter-evidence to fix:** `.env.example:6-12` still says "`# Render prod: create IAM user with
bedrock:InvokeModel, paste access keys here`" — a stale instruction that **directly contradicts
the rule**. Worth correcting whatever this change decides.

### 9.3 OIDC to Bedrock — researched, and it does not solve the fork problem

Four pieces, ~30–45 min one-time: an IAM OIDC provider
(`https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`); an IAM role with a
trust policy on `sts:AssumeRoleWithWebIdentity` using **`StringEquals`, never `StringLike`** (a
stray `*` after the repo name means anyone who can push a branch can assume it);
`permissions: { id-token: write, contents: read }` at **job** level; and
`aws-actions/configure-aws-credentials@v6` (6.2.4). **Thumbprints are no longer required** — "The
thumbprint, if specified, will be ignored."

**2026 gotcha:** repos created after **2026-07-15**, or renamed/transferred, emit `sub` with
embedded numeric IDs (`repo:org@123456/repo@456789:ref:...`), and a legacy name-only trust policy
then fails. AutoSkanerAI predates this, so the name form applies — but dump the token and verify.

**Bedrock policy: `eu.anthropic.*` model IDs need three grants, not one.** Those are geographic
cross-region inference profiles, requiring the inference profile in the source region, the
foundation model in the source region, **and the foundation model in every destination region**
([AWS docs](https://docs.aws.amazon.com/bedrock/latest/userguide/geographic-cross-region-inference.html)),
ideally with a `bedrock:InferenceProfileArn` condition. Add `bedrock:Converse`/`ConverseStream` if
the SDK uses the Converse API. **A missing destination region fails intermittently.**

**🚩 The `sub` subtlety.** For a `pull_request` event with no environment, `sub` is exactly
`repo:OWNER/REPO:pull_request` — the **base** repo, and **the same string for every PR**. It cannot
distinguish a maintainer's PR from anyone else's, so the real trust set is **anyone with push
access**.

**And a fork PR cannot assume the role anyway, because the OIDC token is never issued** —
`id-token` has no `read` level and all write permissions are downgraded on a fork `pull_request`.
The *outcome* is well established; the *mechanism* claim (that `ACTIONS_ID_TOKEN_REQUEST_TOKEN` is
never injected) is **UNVERIFIED** — GitHub's OIDC reference says nothing about fork PRs.

**Do not grant `id-token: write` in a `pull_request_target` workflow.** Since Dec 2025 its
`GITHUB_REF` resolves to the default branch and environment rules evaluate against that same
branch, so pinning `sub` to `refs/heads/main` is not obviously a defence — as far as GitHub is
concerned the run genuinely *is* on the default branch. GitHub does not document what subject
`pull_request_target` emits. (Single blog-post source whose author had not tested it — which is
itself the argument for not relying on it.)

**Net: OIDC is a real option for a Bedrock-backed reviewer on same-repo PRs, and it is strictly
worse than an OpenRouter key here** — more setup, an intermittent-failure mode in the policy, a
`sub` that cannot distinguish PRs, and it still cannot serve a fork PR.

### 9.4 Measured cost and latency, for budgeting the workflow

| Metric | `ai-sdk` (OpenRouter) | `agent-sdk` (Bedrock) |
|---|---|---|
| Cost/review | **`unreported`** — OpenRouter reports no per-call price on this path | **$0.0213 mean**, $0.0116–$0.0358 |
| Latency | **29.8 s mean**, 9.1–73.6 s | **18.1 s mean**, 11.1–40.6 s |
| Subprocess floor | none (in-process) | **~7.1 s** (7.14/7.09/7.08) |
| Package weight | 9.5 MB, no binary | 4.9 MB JS + **210 MB** platform binary |

`pick.md:68-71`: "**`unreported` is not zero, and `0` in this table is not `unreported`.**"
Total provenance spend across 32 runs: **$0.2942** (`pick.md:234-237`).

Two latency facts that matter for a PR job: `ai-sdk`'s **73.6 s worst case** sits inside the
reviewer's 120 s timeout but not comfortably; and `change.md:113-122` records that **a refused
credential once took 127 s and reported `timeout`** — "the right refusal for a slow model, the
wrong diagnosis for a credential that will never be accepted." Fixed by aborting on the second
auth notice: **now 9.7 s.** Failure-path elapsed times: bad model id 3.8 s, credential absent
2.9 s, credential refused 9.7 s.

### 9.5 Findings a CI job inherits from the runner comparison

- **`settingSources` silently injects `CLAUDE.md`.** Default is `['user','project','local']`;
  inheritance cost **+31% per review**, 2–4 extra turns, and **inverted the verdict on 1 run in
  3** — the `pass` run's summary named the right port and file, then reported zero findings.
  "**With inheritance on, `CLAUDE.md` is part of the prompt without appearing in it.**" The
  shipped runner passes `[]`.
- **`outputFormat` errors arrive as *results*, not throws** — "the wrong answer is an empty
  **passing** review, which is why `requireResult` maps every subtype to a `ReviewerError`"
  (`test-plan.md:589`).
- **Containment has a measured capability cost.** All three hermetic `cross-file.diff` runs
  recorded `denied=[Grep]` (4 refusals), because the permission hook refuses an unscoped
  repo-root search — "an unscoped search covers `.env`."
- **The access log cannot detect a policy break.** With `hooks: undefined` the subprocess read
  `.env` and quoted "`# Database`" — **yet `accessedPaths` was `[]` in both arms**.
  "`denied=['Read']` versus `denied=[]` is the only externally visible difference between a policy
  that held and one that was removed."
- **Error hygiene is structural, not careful.** The unwrapped Agent SDK error is a plain `Error`,
  own properties exactly `['telemetryMessage','errorClass']`, **514 characters**, no
  headers/schema/prompt — because the model call happens in a subprocess. **The `ai-sdk` error,
  by contrast, "is a container: request body, schema and a `set-cookie` header in enumerable own
  properties, and `util.inspect` follows `[cause]`."** 🚩 **CI logs on a public repo are world-
  readable; the runner CI will use is the one whose errors carry the request body.** This is a
  new consequence of the reversal, not previously recorded.
- **Corporate proxies: the row was withdrawn.** Phase 4 recorded
  `Connect Timeout Error (attempted address: openrouter.ai:443)` and diagnosed `HTTPS_PROXY`
  honoured by `curl`, ignored by Node's global `fetch`. Re-measured: "Node's global `fetch`
  reached `openrouter.ai` in 0.4 s with no dispatcher configured … **the failure is not
  reproducible — the Zscaler tunnel's state changed, not the transport.**" Irrelevant on a GitHub
  runner, but the sentence worth keeping is `change.md:193-194`: "**a corporate proxy is exactly
  the environment a reviewer runs in.**"

---

## 10. The local gates, and the rules a CI job inherits

### 10.1 The three layers, and the one sentence this change makes obsolete

`.githooks/pre-push:1-11`:

> "Outermost local layer — and in this repo it is also the **LAST** layer, which is why it is the
> heavy one. **There is no CI yet** … **both hosts auto-deploy on push to main** … So a push to
> main reaches production with nothing between. Normally pre-push is a cheap pre-filter in front
> of CI; here it stands where CI would."

Timings (`test-plan.md:362-366`): per-edit 1.2 s (`.scss`) / 6.9 s (`.ts`,`.html`); pre-commit
0.4 s when nothing matches, 9.2 s frontend, ~22 s backend, **8.4 s reviewer**; pre-push **39 s to
`main`**.

### 10.2 🚩 The credential-free rule, measured

`test-plan.md:368-375`:

> "The reviewer arm was **re-measured on 2026-09-10** … 8413 ms and 8330 ms over two consecutive
> runs, 1.4 s typecheck plus 6.9 s suite … **The arm stays credential-free by construction:
> measured with `AWS_PROFILE`, `AWS_REGION`, `AWS_DEFAULT_REGION`, `CLAUDE_CODE_USE_BEDROCK` and
> `OPENROUTER_API_KEY` all unset, 168 tests, 158 pass, 10 skip with a printed reason, 0 fail.**"

**A CI job may run the reviewer's *test suite* with no credential at all.** Only the *review step*
needs the key. Keeping those in separate jobs (or separate workflows) is what makes the
`workflow_run` split cheap rather than contrived.

### 10.3 The four gate rules that transfer verbatim

- **"A gate that cannot report is worse than no gate, because it reads as coverage"**
  (`test-plan.md:388-394`). The prettier hook "was dead from May to September — `node` was not on
  PATH." **"Every layer here therefore fails loudly when its own toolchain is missing, rather than
  skipping."** → A workflow step must fail when `tsx`, node, or the key is absent.
- **"A test runner that reports success on zero tests is that same failure in a different tool"**
  (`:395-403`). `node --test <pattern-matching-nothing>` prints `# fail 0` and exits 0, so
  `scripts/run-tests.mjs` enumerates specs from disk and fails on a zero count. **"Worth checking
  in any runner added here — the question is not 'did it pass' but 'can it say it didn't'."** →
  A review step that posts no comment must be red, not green.
- **`require_java` checks for a compiler, not a variable** (`common.sh:53-63`): "A JAVA_HOME
  pointing at a JRE satisfies every non-empty test and still cannot build anything." → Check for
  the capability, not the variable. Applies directly to `OPENROUTER_API_KEY` (present-but-revoked
  is `no-api-key` at the second retry, §2.7).
- **Verification method** (`:405-412`): "Each path was verified by **watching it block, not by
  reading the code**." → The workflow is not verified until a PR has been seen to go red.

### 10.4 `.gitattributes` and the CI runner

`.gitattributes:1-9` pins `backend/src/test/resources/market/*.md` as `-text` because "**A clone
or CI runner with `core.autocrlf=true` would rewrite both files to one style and the pair would
silently stop testing anything.**" And `test-plan.md:488`: "**A config file that nothing checks is
not a guarantee**" — a test asserts the pair still differs only in line endings. `ubuntu-latest`
does not set `autocrlf`, so this holds; it is listed because it is the one existing
CI-runner-specific constraint in the repo.

### 10.5 Three structural blind spots in the reviewer's own gate

From `code-review-evals/plan.md:62-70`, all of which apply to any file this change adds under
`packages/code-reviewer/`:

- `scripts/run-tests.mjs:34-42` uses a **non-recursive** `readdirSync` — specs in a subdirectory
  silently never run.
- `tsconfig.json:23` is `"include": ["src/**/*.ts"]` — anything outside `src/` is invisible to
  `tsc --noEmit`.
- `.githooks/pre-commit:23` matches only four path shapes.

`.githooks/pre-push:34-38` is the backstop, and it is unconditional: "**this layer is the only one
that sees a commit made while a hook was bypassed**, and **the reviewer is a tool whose failure
mode is silence**."

### 10.6 🚩 The gate must make no model call — enforced by deleting code

`pick.md:239-243`: the 32-run comparison harness "was a throwaway at the package root — **deleted
with the phase commit** … **because a committed comparison script would join the commit gate and
the gate must make no model call.**" Restated in `code-review-evals/plan.md:57-61` as "the hardest
constraint on where eval files may live," and `:134-135`: "**Not putting promptfoo in the
pre-commit or pre-push gate.**"

**A `.github/workflows/` file is inside the reviewer's allow-list** (`repo.ts:36-59` admits
`.github`) but is **not** matched by any pre-commit pattern, so a workflow file adds no local gate
cost. A composite action under `.github/actions/` is likewise ungated locally — which means
**whatever validates the workflow YAML has to be the workflow itself**.

---

## 11. Deployment interaction — the first PR is a production event

### 11.1 Three documented hazards, none yet hit, because there has never been a PR

1. **🚩 Render `autoDeploy: true` fires on every branch push.**
   `deployment-plan-backend.md:452-453`: "**preview services spin up for every open branch**.
   Preview services on the free tier sleep after 15 minutes. To avoid unexpected spend on
   long-lived branches, either set `autoDeploy: false` and trigger manually, or add branch-filter
   rules in the Render dashboard (**dashboard-only setting; not expressible in `render.yaml`
   v1**)." Corroborated at `infrastructure.md:69`; `:59` adds "**Build minutes can accumulate.**
   Each push triggers a full Docker build."
2. **🚩 Cloudflare Pages builds a preview per branch push, and previews are CORS-blocked.**
   `deployment-plan-frontend.md:374-375` (preview URLs `https://<branch>.autoskaner-ai.pages.dev`;
   disable under Branch deploy controls) and `:337-338`: "**These are not covered by the single
   `frontendUrl` origin. For MVP this is acceptable — previews will have CORS blocked.**"
3. **A new root `package.json` is a named, forbidden risk.** `CLAUDE.md:68-71` /
   `test-plan.md:379-382`: Lefthook was rejected because "Cloudflare Pages builds this repo from a
   subdirectory on every push to `main`; **a new root manifest is an unverifiable risk to a live
   deploy path**." `deployment-plan-backend.md:445-448` is the mirror: "Render detects
   `render.yaml` **only when it is at the exact repo root**." → **This change must not add a root
   manifest.** A composite action needs none.

Also: `infrastructure.md:57` — "Dashboard changes don't sync back to `render.yaml`"; an acceptance
criterion is "Render dashboard shows 0 config drift from `render.yaml`" (`:559`). Any
branch-filter fix for hazard 1 is dashboard-only and therefore *is* drift — a tradeoff to state,
not to resolve silently.

### 11.2 What the deploy config is

`render.yaml`: `type: web`, `name: autoskaner-ai-backend`, `runtime: docker`, `rootDir: backend`,
`plan: starter`, `healthCheckPath: /actuator/health`, **`autoDeploy: true`**; env
`SPRING_PROFILES_ACTIVE=openrouter`, `OPENROUTER_API_KEY` `sync:false`, `FRONTEND_URL`
`sync:false`. `backend/Dockerfile:1-15` — multi-stage temurin 21, and **`./mvnw package
-DskipTests`**: the image build runs no tests.

Cloudflare Pages is **dashboard-only** — no `wrangler.toml`, no `_headers` in the tree; the only
committed artefact is `frontend/public/_redirects`. Settings recorded at
`deployment-plan-frontend.md:346-362`: preset Angular, root `frontend`, build `npm run build`,
output `dist/frontend/browser`, `NODE_VERSION=20` on Production **and Preview**.

---

## 12. Code References

**The reviewer's runtime surface**
- `packages/code-reviewer/src/index.ts:19,37-45,61-65,116` — `fail()`, runner selection, TTY
  guard, exit-code mapping and the "no failure exits 0" invariant
- `packages/code-reviewer/src/diff.ts:10,19-27,42-48,50-87` — the 60 000-char cap, empty-diff,
  rename/binary handling, the diff-of-a-diff limit
- `packages/code-reviewer/src/errors.ts:9-11,14-74,76-88` — the invariant, the seven kinds, `kind`
  on the class
- `packages/code-reviewer/src/env.ts:4-7,49-64,72` — nothing throws; `DEFAULT_MODEL`;
  `loadRepoEnv` and the "CI never depends on a file that is not committed" comment
- `packages/code-reviewer/src/repo.ts:20-23,36-59,90-128,105,162-168,284-290` — `REPO_ROOT` from
  `import.meta.url`, the allow-list, check order, root refused, missing-path-passes-policy
- `packages/code-reviewer/src/tools.ts:14,37-75,170-174,276-280,290` — no subprocess, the six
  caps, missing-file reason, **the swallowed missing subtree**
- `packages/code-reviewer/src/permission.ts:110-150,128-133,146-149` — `decideToolUse`, unscoped
  `Grep` denied, `Bash` deny-by-default
- `packages/code-reviewer/src/agent.ts:45,60,247-250,308-312,346-352` — step budget, timeout,
  `no-api-key`, abort, `no-output`
- `packages/code-reviewer/src/agent-sdk.ts:71,85,88,99,140-153,246,314-317,327,353-356,368-393,
  395-413,505-543` — Bedrock default, budgets, `subprocessEnv`, `cwd`, `diff-too-large`, the
  second-auth-notice abort, result subtypes
- `packages/code-reviewer/src/verdict.ts:22-27` — `deriveVerdict`, `fail` iff `blocker`/`major`
- `packages/code-reviewer/src/prompt.ts:8-9,30-31,92-115,96-101,103` — rule 1's user harm, the
  no-seam rule, the four project rules, severities in one line
- `packages/code-reviewer/src/tools.test.ts:23,33` — `verdict.ts` read as a **fixture**
- `packages/code-reviewer/package.json:11-13` — `review`, `test`, `test:live`
- `packages/code-reviewer/tsconfig.json:14-19,23` — `noEmit`, tsx runs sources, `include` is
  `src/**` only
- `packages/code-reviewer/scripts/run-tests.mjs:26,34-42,44-57` — the only subprocess, the
  **non-recursive** `readdirSync`, the zero-test guard

**CI, gates and deploy**
- `.github/workflows/live-market-price.yml:1-10,23-27,40-43,51` — the header-comment convention,
  Java 21 + maven cache, the duplicated Jina prefix, `PRICE_PATTERN` in Python
- `.githooks/common.sh:2-7,9-13,15-33,38-63,66-95,97-119` — fail-not-skip, PATH, JDK/heap pinning,
  `require_java` checking `javac`, the three run functions
- `.githooks/pre-commit:1-10,17-23,29-50` — staged-scope rationale, the four path patterns,
  `--check` not `--write`
- `.githooks/pre-push:1-11,28-48` — "the LAST layer", unconditional reviewer arm, main-only prod
  build
- `.gitattributes:1-9` — `-text` pin and the CI-runner reason
- `render.yaml:1-20`, `backend/Dockerfile:1-15` — `autoDeploy: true`, `-DskipTests`

**The record**
- `context/team/opportunity-map.md:24,45-49,100-107,121-128` — **the CI correction**, this change
  named as the destination, three open factual errors
- `context/foundation/test-plan.md:74,77-81,94,107-108,226,331-340,349,362-375,377-403,405-412,
  414-423,488,514-516,587-610` — risk 6 and its carve-out, Phase 4, per-layer budgets, the
  credential-free measurement, the four rules, the gates table, the empty §6.6, the §8 ledger
  format
- `context/changes/agent-sdk-reviewer/pick.md:59-71,79-81,125-137,203-243` — cost/latency, the
  withdrawn proxy row, the five reversal conditions, the deleted harness
- `context/changes/agent-sdk-reviewer/change.md:19-36,51-60,89-130,146-162,176-204,239-255` —
  `settingSources`, the self-answering fixture, failure-path timings, the invisible policy break,
  the proxy finding, the 8.4 s arm
- `context/changes/code-review-evals/research.md:44-48,324,502-520` — the 15 unpushed commits, the
  κ argument against 1–10 scales, the five conditions audited
- `context/changes/code-review-evals/plan.md:57-70,85-91,130-135,361-430,626-678` — the
  no-model-call constraint, the three blind spots, the nine collision points
- `backend/CLAUDE.md:47` — **the actual home of the "never copy AWS credentials" rule**
- `.env.example:6-12` — the stale instruction that contradicts it

---

## 13. Architecture Insights

1. **The reviewer was built for a caller that pipes and reads an exit code, and CI is that
   caller.** `index.ts` is "the ONLY module allowed to touch process.stdin, stdout, console, or
   process.exit"; `agent.ts:4-9` says `reviewDiff` is a plain async function *because* a promptfoo
   provider is handed a string and must return `{ output }`. The seam CI needs already exists.
2. **The verdict is arithmetic, not narration** (`prompt.ts:103`, `verdict.ts:22-27`). That is
   precisely the property that makes the label safe to derive from an exit code, and precisely the
   property the six-criteria requirement removes.
3. **Every layer in this repo fails loudly when its own toolchain is absent** — from
   `require_java` checking `javac` to `run-tests.mjs` counting specs to `errors.ts`'s "no failure
   exits 0." A workflow that posts nothing must be red. This is the single most consistent
   convention in the repo and the one a CI job is most likely to violate by accident.
4. **Containment is already at the right shape for untrusted input**: allow-listed reads, no
   `Bash`, no unscoped search, evidence stripped when unbacked. The gap the requirements open is
   not the diff — it is the **PR title and description**, two new untrusted channels that no
   existing fixture covers.
5. **"Report-only before it gates" is the repo's own stated pattern for a new gate**
   (`opportunity-map.md:24`), and the existing workflow embodies it — `workflow_dispatch` with a
   written reason for not being on push. A first version that comments without failing the check
   is in keeping, not a compromise.
6. **The public-repo fork model is the one constraint with no local analogue.** Every other
   constraint here has a precedent in the hooks or the deploy config. This one is new, it is
   binary, and it cannot be worked around by configuration.

## 14. Historical Context (from prior changes)

- `context/changes/agent-sdk-reviewer/pick.md` — the 32-run comparison, its five reversal
  conditions, and **condition #2 which this change satisfies**
- `context/changes/agent-sdk-reviewer/change.md` — the measured containment, error-hygiene and
  credential-failure findings a CI job inherits
- `context/changes/code-review-evals/{research,plan,change}.md` — same-day workstream A; the
  runner reversal already taken, the nine severity collisions, the no-model-call gate constraint
- `context/changes/deployment/deployment-plan-{backend,frontend}.md` — the per-branch preview
  hazards, dashboard-only settings, and the CORS boundary
- `context/team/opportunity-map.md` — **the CI correction**, and this change already named as the
  destination for both the CI gate and the suite-count-drift check
- `context/archive/2026-08-27-testing-enrichment-honesty/plan.md:123` — "**No CI wiring.** §3
  Phase 4 owns the gate."

## 15. Related Research

- `context/changes/code-review-evals/research.md` — the reviewer's internals, promptfoo mechanics,
  the κ argument against 1–10 scales, the five-condition audit
- `context/changes/refactor-opportunities/research.md:709-711,955-956` — "nothing gates a push"
  confirmed; `./mvnw -o test` at 23.9 s wall

---

## 16. Open Questions

Ordered by how much the answer changes the plan. **Q1 is the headline.**

**Q1 — 🚩 The six criteria: adopt, reject, or add alongside?**
Adopting them changes ~15 production files and 35 tests, drops the two project rules that map to
the parked criteria (rule 1 = the accident-data rule with real user harm; rule 3 = the only rule
exercising the tool loop), lands on the prohibited side of `test-plan.md:77-81`'s risk-6 carve-out,
and collides with the `code-review-evals` plan in nine places. Rejecting them keeps a shipped,
tested, arithmetic verdict but does not deliver what `requirements.md` asks for. A third path —
keep `{severity, verdict}` as the machine contract and render the six criteria as *prose sections
of the PR comment only* — satisfies the requirement's visible intent without moving the decision
surface. **Recommendation: the third path, argued explicitly rather than assumed.**

**Q2 — 🚩 Trigger: `pull_request` (fork-broken), `pull_request_target` (no head checkout), or the
`workflow_run` split?**
This repo has one contributor and zero PRs, so `pull_request` works today and breaks silently on
the first fork. `pull_request_target` is GitHub's sanctioned answer for "label or comment on PRs
from forks" and our reviewer genuinely needs no head checkout — but the trigger carries a
reputation and a preview-mode control that may recommend prohibiting it. `workflow_run` is
GitHub's stated preference and costs the PR-number lookup, the untrusted-artifact handling, and
absence from the checks UI. **Recommendation: `pull_request_target` with no head checkout and no
`npm ci` from head, with the reasons in the file header per §6.1's convention.**

**Q3 — 🚩 The 60 000-char cap versus this repo's real PRs.**
Most PRs here would exceed it and produce exit 2 — a red job with no review. Options: raise the
cap (against `errors.ts:33`'s stated reasoning); review per-file or per-commit and aggregate;
post an explicit "diff too large to review" comment and pass; or accept the refusal as the honest
answer. **Recommendation: an explicit `diff-too-large` branch that comments *why* and applies
neither label** — a review that did not happen must not be reported as either outcome, which is
the same principle as `errors.ts:9-11`.

**Q4 — Does the workflow gate, or only report, in its first version?**
`main` has no branch protection and auto-deploys, so `ai-cr:failed` is advisory whatever we do.
The repo's own pattern is "report-only before it gates" (`opportunity-map.md:24`). **Recommendation:
report-only first**, with the gating step written down as a follow-up condition.

**Q5 — Does this change also fill `test-plan.md` §6.6 / Phase 4 (the two suites on PR)?**
The third scope answer said "AI review only, gap flagged" — so the answer is *no*, but §6.6 exists
and is empty, and the same workflow file would host it for near-zero marginal cost. **Recommendation:
out of scope, and named in *What We're NOT Doing* with the §6.6 pointer** so the next change finds
it.

**Q6 — Which base for the diff, and how is `diff-too-large` distinguished from a genuine error?**
Three-dot from the merge base is what the PR UI shows. `gh pr diff` is simplest and 406s at
20 000 lines; local `git diff` needs `fetch-depth: 0`. All setup failures share exit 2, so the
workflow must parse stderr or the reviewer must grow a distinguishable signal. **This is a design
question with a possible small code change behind it.**

**Q7 — How are the PR title and description sanitised before they reach the prompt?**
Two new untrusted channels, no existing fixture, and CamoLeak was a PR-description injection.
`injection.diff` covers only the diff channel. **Recommendation: strip HTML comments and
zero-width characters, JSON-encode, place after the diff, and add a fixture** — the last of which
may belong to `code-review-evals` rather than here.

**Q8 — Corrections to the record: in this change or a follow-up?**
Five statements to fix — the four "no CI" claims (`CLAUDE.md:55-57`, `test-plan.md:383-385`,
`repo-map.md:238-239`, `.githooks/pre-push:5-8`), this change's own `change.md:25-28`,
`pick.md`/`change.md`'s misattribution of the credential rule to root `CLAUDE.md` (it is
`backend/CLAUDE.md:47`), and `.env.example:6-12`'s stale "paste access keys here". Plus
`test-plan.md`'s empty §6.6 and its §8 ledger entry. **Recommendation: in this change** — the
precedent is `code-review-evals` phase 7, and a workflow that makes four documents wrong on the
day it lands is worse than the documents were.

**Q9 — UNCERTAIN and worth verifying before relying on either:** whether repository-level
**workflow execution protections** are available on a personal-account repo (§3.4), and whether a
`workflow_run` job can be made to appear in the PR checks UI without a Checks API POST (§3.3).
