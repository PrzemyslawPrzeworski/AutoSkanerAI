---
change_id: ci-cd-code-review
title: AI code review in CI/CD — a GitHub Actions workflow on every PR
status: planned
created: 2026-09-10
updated: 2026-09-10
archived_at: null
---

## Notes

M5-L3, workstream B. Run the code reviewer from `packages/code-reviewer` inside GitHub
Actions on every pull request to master, via a composite action so the top-level
workflow stays readable. Inputs are the PR title, the PR description and the git diff.
Six criteria scored 1–10; business alignment and architectural fit are parked because
both need broader context than a diff carries. Side-effects are a PR comment with the
summary plus one of the labels `ai-cr:failed` / `ai-cr:passed`, and adding the label
`ai-cr:review` retriggers the review on demand.

Full requirements, written by the user before any research: `requirements.md`.

Two facts from the surrounding work that this change has to reckon with, recorded here
so research does not have to rediscover them. **Both were stated wrongly when this file
was written on 2026-09-10 and are corrected here after research** — the original
wording is kept struck through, because getting them wrong is part of why the research
was worth running:

- ~~**There is no CI at all today.** Root `CLAUDE.md` says so explicitly — so this
  change introduces the *first* CI.~~ **Wrong.** `.github/workflows/live-market-price.yml`
  exists and works; it is `workflow_dispatch`-only for a stated reason. As
  `context/team/opportunity-map.md:45-49` already put it: **the platform is wired;
  nothing gates on it** — and it files root `CLAUDE.md`'s *"There is no CI yet"* as a
  known imprecision. Eight documents repeat that claim, this file having been the eighth
  (`research.md` §1.1 lists them all). What is true: `main` auto-deploys to Render and
  Cloudflare Pages, and the three local git-hook layers are "the last gate before
  production, not a pre-filter in front of CI." So this change adds the **first gating**
  workflow to a working Actions setup — a Java 21 runner and the house style for a
  workflow file are already there.
- **The reviewer exists twice behind one contract**, selected by `CODE_REVIEW_RUNNER`:
  `ai-sdk` (OpenRouter) and `agent-sdk` (a `claude` subprocess against Bedrock). ~~Which
  one runs in Actions is an open question for research~~ — **not an open question: the
  record decided it in advance.** `agent-sdk-reviewer/pick.md:215-230` lists five
  conditions that reverse its pick, and #2 is verbatim this change: "the moment the
  reviewer must run somewhere other than this laptop, Bedrock's short-lived SSO session
  disqualifies `agent-sdk` on auth availability alone." So CI runs `ai-sdk` with
  `OPENROUTER_API_KEY` as an Actions secret. The standing rule that AWS credentials are
  never copied into a hosting platform lives in **`backend/CLAUDE.md:47`**, not in root
  `CLAUDE.md` as first written here — a misattribution this file shares with
  `pick.md:65`.

The title above said "to master" until planning corrected it. The default branch is **`main`**;
`master` does not exist in this repository. That was the fourth of the requirements' assumptions
not to survive research, and it is fixed at the source rather than footnoted.

Research complete: `research.md` (2026-09-10, base `a7573ae`). Four of the
requirements' assumptions do not survive it — the trigger cannot be a plain
`on: pull_request` on a PUBLIC repo, two diff ceilings sit below this repo's real PR
sizes, the six 1–10 criteria collide with a rule this repo wrote for itself, and the
default branch is `main`, not `master`. Nine open questions carry recommendations into
`/10x-plan`.

## Plan (2026-09-10)

`plan.md` + `plan-brief.md`, six phases, base `a7573ae`. Eleven decisions taken by the user;
the rest recorded in the brief's decision table with their source.

The shape: the reviewer's decision surface does not move. `deriveVerdict`'s blocker/major rule
still decides the exit code, the four severities stay, and the requirements' six criteria arrive
as an **additive** prose field the comment renders and nothing computes from. The two parked
criteria lose their heading, not their coverage — project rules 1 and 3 keep producing findings.

Three consequences worth carrying forward:

- **The trigger is `pull_request_target` with no head checkout.** Forced by the repo being PUBLIC:
  a fork PR under `on: pull_request` gets no secrets and a read-only token, and the clamp lands
  after the `permissions:` block. Safe here only because PR contents genuinely stay passive data —
  the diff arrives on stdin, `npm ci` never runs against fork code.
- **Labels are written with `GITHUB_TOKEN` on purpose.** `labeled` is not among the two documented
  exceptions to the no-recursion rule, so the workflow's own label writes create no run. A PAT or
  App token here would loop.
- **Phases 1–4 are offline and credential-free**, so the commit gate covers them and still makes no
  model call. Phase 5 — three watched PRs — is the only phase that spends anything or touches
  production, and it cannot be rehearsed: `pull_request_target` reads the workflow from the default
  branch, so the 15 local commits must be pushed to `main` first.
