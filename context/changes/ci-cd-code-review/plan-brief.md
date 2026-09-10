# Plan Brief: AI code review in CI/CD — a GitHub Actions workflow on every PR

**Change ID**: `ci-cd-code-review`
**Full plan**: `context/changes/ci-cd-code-review/plan.md`
**Base**: `a7573ae` on `main` — depends on `agent-sdk-reviewer` (implemented) and **fires its
reversal condition #2**; runs alongside `code-review-evals` (planned, unimplemented)

## What We're Doing

Running `packages/code-reviewer` on pull requests from GitHub Actions, via a composite action,
and turning its exit code into two side-effects on the PR: one sticky comment carrying the
review, and exactly one of `ai-cr:passed` / `ai-cr:failed` — or **neither**, when no review
happened. Adding `ai-cr:review` re-runs it. Six phases: the untrusted-input path, the six
criteria as an additive field, the comment renderer, the two YAML files plus a guard over them,
three watched PRs, and corrections to eight documents.

## Why

This is M5-L3's workstream B, and it fills a slot the test plan left open by name:
`test-plan.md:514-516` § 6.6 is literally "Adding a CI gate — TBD". The reviewer's CLI was built
for a caller like this one — `index.ts:2-3` states the stdin/stdout/exit-code contract, and
`errors.ts:9-11` guarantees no failure path returns 0, which is exactly what makes a label
derivable from an exit status.

It is also the moment the record predicted. `agent-sdk-reviewer/pick.md:215-230` listed five
conditions that reverse its choice of `agent-sdk`, and #2 reads: "the moment the reviewer must
run somewhere other than this laptop, Bedrock's short-lived SSO session disqualifies `agent-sdk`
on auth availability alone." CI therefore runs `ai-sdk` against OpenRouter, and no AWS credential
goes near a hosted service — `backend/CLAUDE.md:47`.

## Key Decisions

| Decision | Choice | Source |
|---|---|---|
| Trigger | `pull_request_target`, types `[opened, synchronize, reopened, labeled]` | User |
| Head checkout | **Never** — not now, not behind `allow-unsafe-pr-checkout` | User |
| Runner in CI | `ai-sdk` + `OPENROUTER_API_KEY` as the repo's first Actions secret | Record (`pick.md` #2) |
| The six criteria | An **additive** `assessments` field on `ModelReview` — one prose line per criterion | User |
| 1–10 scores | Not adopted. Severities and `deriveVerdict` are untouched | User |
| Verdict source | `deriveVerdict`'s blocker/major rule, unchanged. Criteria decide nothing | User |
| Missing `assessments` | `.default([])` — a degraded review beats exit 2; the renderer says so out loud | Plan |
| Third outcome | Exit 2 → **neither** label, a comment naming the reason, the job red | User |
| Comment content | Summary + six criterion notes + **every** finding grouped by severity + counters when non-zero | User |
| Retrigger | `ai-cr:review` is **consumed** by the run; the same comment is edited via a hidden marker | User |
| Loop safety | Labels written with `GITHUB_TOKEN` — `labeled` is not an exception to the no-recursion rule, so no run is created | Research |
| Over-cap diffs | Refused with the size and the cap named. Never truncated, never chunked | User |
| Truncation asymmetry | The **description** truncates; the **diff** never does (`errors.ts:33`) | Plan |
| Gating | Report-only. No branch protection; gating is a follow-up with a stated precondition | User |
| Injection defence | Sanitise (HTML comments, zero-width/bidi), isolate in a JSON-encoded region after the diff, plus offline specs | User |
| New option names | `changeTitle` / `changeDescription` — **not** `prTitle`/`prBody`; `reviewer.ts:11-14` bans host vocabulary in the shared contract | Plan |
| Narrative transport | Env vars, not argv — a body is multi-line and argv is visible to `ps` | Plan |
| Empty description | The section is **omitted entirely**. Never the literal `null`, never an empty region | User |
| Renderer placement | A pure function in `src/`; the write lives in `index.ts`, the only I/O module | Plan |
| Failure comments | `fail()` writes one too, so the workflow always has a file to post | Plan |
| Permissions | `contents: read`, `pull-requests: write`. No `issues: write`, no `id-token` | Research |
| YAML guard | Text assertions, **no** YAML parser dependency — a drift alarm, not a proof, and it says so | Plan |
| Spec placement | Directly in `src/` — `run-tests.mjs`'s `readdirSync` is non-recursive | Research |
| Path filters | **None.** "No check" is indistinguishable from "workflow broken", and `context/` prose carries project rule 1 | Plan |
| Verification | Push, then three throwaway PRs — flawed, clean, over-cap — each watched | User |
| Doc corrections | All of them, in a final phase | User |
| `pick.md` | A dated follow-up note appended, never a rewrite | Precedent |

## Scope

**In**: `packages/code-reviewer/src/` — new `untrusted.ts`, `comment.ts`, three new specs, plus
edits to `reviewer.ts`, `prompt.ts`, `schema.ts`, `env.ts`, `index.ts`, `agent.ts`,
`agent-sdk.ts`; new `.github/workflows/ai-code-review.yml` and
`.github/actions/ai-code-review/action.yml`; three GitHub labels and one Actions secret;
corrections to root `CLAUDE.md`, `test-plan.md` (§3, §5.1, §6.6, §8, the stale frontend counts),
`repo-map.md`, `.githooks/pre-push`, `opportunity-map.md`, `pick.md`, `.env.example`, and
`code-review-evals/plan.md` phase 7.

**Out**: 1–10 scores in the schema; merge gating and branch protection; raising or chunking past
the 60 000-char cap; the backend/frontend suites on PRs; path filters; OIDC, Bedrock, a PAT or an
App token in Actions; `id-token: write`; a YAML parser; a graded injection eval fixture; fixing
the per-branch Render/Cloudflare preview hazard.

## The six phases

1. **The untrusted-input path** — `sanitizeNarrative`, `ReviewOptions.changeTitle/changeDescription`,
   a JSON-encoded `PR NARRATIVE` region after the diff, `env.ts` plumbing, offline specs. No model
   call.
2. **The six criteria as an additive field** — `assessments` on `ModelReview` and `ReviewOutcome`,
   a criteria paragraph in `buildSystemPrompt`, and a spec proving `deriveVerdict` never sees it.
3. **The comment renderer** — `renderReviewComment` / `renderFailureComment` as pure functions,
   the write in `index.ts` behind `CODE_REVIEW_COMMENT_PATH`, ten degenerate shapes pinned.
4. **The workflow and its composite action** — the two YAML files, plus eleven text assertions
   over them in the reviewer suite that the push gate runs.
5. **Setup and live verification** — three labels, one secret, the push, then PR A (flawed → red +
   `ai-cr:failed`), PR B (clean → green + `ai-cr:passed`), PR C (over-cap → neither label), and
   the retrigger twice.
6. **Corrections to the record** — the four live "no CI" claims, the credential misattribution in
   `pick.md`, `.env.example`, `test-plan.md` §6.6 and its ledger, and the three stale
   "evals wrap `agent-sdk`" lines.

**Phases 1–4 are entirely offline and credential-free**, so the commit gate runs them and still
makes no model call. Phase 5 is the only one that spends money or touches production.

## Five things worth knowing before starting

- **This repo is PUBLIC and has never had a pull request.** Measured with `gh` at `a7573ae`: no
  Actions secrets, `default_workflow_permissions: read`, no branch protection on `main`, 17 labels
  and no `ai-cr:*`. On a public repo a fork PR under `on: pull_request` gets **no secrets and a
  read-only token**, and the clamp is applied *after* the `permissions:` block — the "send write
  tokens to workflows from pull requests" escape is private-repos-only. That is what forces
  `pull_request_target`, whose own documented use is "label or comment on pull requests from
  forks", safe only while PR contents stay passive data. Ours do: the diff arrives on stdin and
  the reviewer reads base-branch files through its own allow-list.
- **Three CLI invocation traps, all measured.** `npm run review` writes npm's `> tsx src/index.ts`
  banner to **stdout**, ahead of the JSON — use `npx tsx src/index.ts`. `npm ci --omit=dev` breaks
  the run, because `tsx` is a devDependency and `review` *is* `tsx`. And `index.ts:44-45` prints
  its usage error only when `process.stdin.isTTY`, which is never true in Actions — a forgotten
  pipe reports `empty-diff` with no hint.
- **A full checkout is a correctness requirement, not a convenience.** `tools.ts:276-280` silently
  skips an allow-listed subtree that does not exist, so under a sparse or shallow checkout the
  reviewer reviews blind and says nothing about it. Likewise `fetch-depth` defaults to 1, so
  `git diff base...head` exits 128 — the diff comes from `gh pr diff` (REST, three-dot), falling
  back to `GET /pulls/{n}/files` on a `406`.
- **Composite actions fail at parse time, not runtime, for things that look like runtime
  problems.** They can read neither `secrets` nor `vars` — `vars` raises
  `Unrecognized named-value: 'vars'`. `shell:` is required on every `run:`; workflow-level
  `defaults.run` does not apply; `timeout-minutes` is unsupported; and `required: true` on an
  input is **not enforced**, so the action validates its own inputs.
- **Prompt injection is a documented attack class against this exact design.** OWASP MASTG #3783,
  PromptPwnd, and CamoLeak (CVE-2025-59145, CVSS 9.6) — the last of which hid its payload in a
  **PR description**, which is one of the two channels this change adds. `prompt.ts:141-144`
  already states the governing principle: "the prompt discourages, the policy prevents."

## Risks

- **`pull_request_target` runs privileged, and that is the whole risk surface.** Mitigated
  structurally rather than by care: no head checkout, no `npm ci` against PR code, the narrative
  JSON-encoded inside a delimited region, the comment body posted **from a file** so nothing
  interpolates `${{ }}` into a `script:` or a `run:`, and the label names as literals in the
  workflow. The model's output reaches exactly two places: a comment body and nothing else.
- **The comment is the model's text on a world-readable page.** A finding's `rationale` can quote
  a diff line containing the marker or `</details>`, so the renderer escapes the model's output
  too — one layer further out than the PR narrative.
- **The workflow files sit in a gate blind spot.** `.githooks/pre-commit` matches no path under
  `.github/`, so a workflow edit alone triggers nothing locally. The phase-4 guard lives in the
  reviewer suite instead, which pre-push runs unconditionally. Its limit is stated: text
  assertions cannot see a semantically equivalent restructuring.
- **The 60 000-char cap binds before GitHub's does, and it binds often.** Measured against this
  repo's own history, a typical recent commit is 2–6× over it (`b9b5ce1` alone is 133 KB / 2999
  lines). Many real PRs here will get "refused" rather than a review. That is the honest outcome
  of `errors.ts:33` and it is accepted, not worked around — but it means the first weeks may
  produce more refusals than reviews, and the fix is smaller PRs.
- **Phase 5 cannot be rehearsed.** `pull_request_target` reads the workflow from the default
  branch, so the workflow must be pushed to `main` — carrying **15 local commits** into two live
  auto-deploys — before anything can be verified. A red deploy during PR verification would be
  indistinguishable from a workflow fault, so the deploys are confirmed green first.
- **The 120 s reviewer timeout is not configurable from the CLI.** Measured `ai-sdk` latency is
  ~30 s mean with a 74 s worst case, so it fits — but not comfortably, and a slower model would
  need a code change rather than an env var.
- **Cost is `unreported` on this path.** OpenRouter returns no per-call price to the `ai-sdk`
  runner, so `index.ts:89` prints `n/a` — absent is not zero. Phase 5 reads actual spend from the
  dashboard.

## Verification

Phases 1–4 are provable offline with no credential, and that is what the commit and push gates
run: the sanitiser against closed and unterminated HTML comments, zero-width and bidi characters,
and a forged region marker; the schema against a missing and an unknown criterion; every
pre-existing verdict case unchanged with `assessments` present, absent and empty; the renderer
against ten degenerate outcomes including a 400-finding truncation and all seven error kinds; and
eleven text assertions over the two YAML files. One integration check exercises the real
`index.ts` write path end to end — a fixture diff, no credential, exit 2, a `no-api-key` failure
comment on disk, stdout empty.

Two properties are asserted as absences, because this repo has shipped a dead gate before: the
suite must still report zero failures with all five credential variables unset, and no previously
passing test may start skipping.

Phase 5 is verified by watching, following `test-plan.md:405-412` — "Each path was verified by
**watching it block**, not by reading the code." Three PRs for the three outcomes, the retrigger
run twice to prove the label is consumed and re-addable, and `gh run list` checked for runs nobody
triggered. Wall-clock, spend, and whether Render and Cloudflare actually spin per-branch previews
all land in `change.md` — the last of these tests a `deployment-plan-backend.md:452-453` claim that
has never been exercised.
