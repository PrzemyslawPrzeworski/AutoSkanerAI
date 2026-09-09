# Opportunity Map

## Context

- **Project / context**: AutoSkanerAI — 3-week solo MVP, Spring Boot 4.0.6 + Angular 21.2.
  No team, so the frictions below are not team-coordination frictions; they are
  **solo-developer-plus-AI-agents** frictions: facts maintained by hand in several
  places at once, live instruction files pointing at documents that moved, and a
  `main` branch that deploys to two hosts with no independent check. The skill's
  "coordination cost between two roles" criterion is degenerate here and is read as
  "between two sources of truth" instead.
- **Data constraint**: local repo files, read-only, non-sensitive — git history, the
  markdown tree, and the two test suites' own output. No production credentials, no
  customer data. This is why every first useful version below is allowed to be a
  throwaway script.
- **Date**: 2026-09-09 (`git_commit: 23535bf`)

## Map

| Signal | Existing / default response | Thin complement | First useful version | Data risk | Direction if valuable |
|---|---|---|---|---|---|
| A document instructs an agent to read a file that moved — **22 dangling `context/changes/…` refs across 13 files**, one in the live `backend/CLAUDE.md:88` | None in-repo. Off the shelf: `lychee`, `markdown-link-check` | Resolve repo-relative links only, from the existing pre-commit hook | A read-only list of `file -> missing ref` (already written as a shell one-liner) | local, read-only, non-sensitive | **Adopt an existing tool** — generic utility, do not build |
| Suite counts asserted by hand in **10 places across 6 live files**, where the cheap check is provably wrong (`@Test` → 212 vs real 235; `it(` → 64 vs real 51) | **None.** No SaaS asserts "the numbers in these files equal the numbers the suites print" | Parse the two suite runs' stdout; compare against the six files | Read-only report: *asserted 235 / actual N*, per `file:line` | local; runs the two suites (~22 s), so not strictly read-only | **Internal tool → review / CI gate** |
| `main` auto-deploys to Render + Cloudflare with the only gate on one machine, bypassable by one flag, absent in a fresh clone until `git config core.hooksPath .githooks` | **Already installed.** `.github/workflows/live-market-price.yml` runs `./mvnw` on a Java 21 runner today | A second workflow, ~15 lines, calling the two commands the hooks already call | The same workflow, report-only before it gates | none — the existing workflow documents that no secrets are needed | **Use what's installed** → review / CI gate, which is **M5-L3's subject** |
| Java wire records and `analysis.models.ts` mirrored by hand; `repo-map.md` §3 records it as `[no tool]` | springdoc-openapi + openapi-generator, neither in `pom.xml` | A test asserting the field-name sets match, instead of generating types | Extract Java record components and TS interface fields; print the symmetric difference | local, read-only | **Wait** — the real answer is codegen, and the pain is currently avoided by design |

### Evidence behind the cells

- **Dangling refs**, measured 2026-09-09: 22 references across 13 files, every one
  caused by the same event — a change folder moving from `context/changes/<id>/` to
  `context/archive/<date>-<id>/` without its inbound links following. 21 sit inside
  archived documents; the 22nd is `backend/CLAUDE.md:88`, a file every agent session
  loads, pointing at `context/changes/market-price-context/research.md`, which has
  been at `context/archive/2026-06-02-market-price-context/research.md` since June.
- **Count sites**: root `CLAUDE.md:121`, `frontend/CLAUDE.md:16`,
  `test-plan.md:325`, `:564`, `:565`, `repo-map.md:266`, `.githooks/pre-commit:33`
  and `:38`, `.githooks/pre-push:28` and `:31`. `test-plan.md:564-565` records the
  drift as it happened — backend 229 → 235, frontend 39 → 41 → 51 — and states why
  the hook labels are the worst place for a stale number: *"a stale count there is
  the one place a reader would trust it without checking."*
- **The grep shortcut is wrong, and it is recorded as wrong**:
  `context/changes/refactor-opportunities/plan.md:194-195` — `@Test` reads 212
  against a real 235 because `@ParameterizedTest` expands; `it(` reads 64 against a
  real 51 because `submit(` contains `it(`.
- **CI is not absent**: `.github/workflows/live-market-price.yml` exists and works.
  It is `workflow_dispatch`-only for a stated reason (`:9-10`): the assertions depend
  on live third-party markup, so a red run means "Otomoto changed something", which
  "must not block unrelated merges". Root `CLAUDE.md`'s *"There is no CI yet"* is
  therefore imprecise — the platform is wired; nothing gates on it.

## Recommended First Candidate

```
Candidate:  suite-count drift check

Reads:      the two suite runs' own stdout (surefire's "Tests run: N", the
            vitest summary), plus the 10 asserted numbers in the 6 files above

Returns:    a read-only report — per file:line, "asserted 235 / actual N";
            exit non-zero only when they disagree

Does not do: not a linter, not a link checker, not a wire-contract check; does
            not edit any document; does not run in CI yet; and does not grep
            for @Test or it( — the counts come from a run or not at all

Data risk:  local, non-sensitive. Runs the same two suites the git hooks
            already run; reads only tracked files

Direction:  internal tool -> review / CI gate. If it earns regular use, the
            general shape is a "documented-fact verifier" that subsumes the
            dangling-link and wire-mirror signals — but that generalization is
            the second version, not the first
```

## Why This Candidate

Three of the four frictions have a correct answer that is not a build, and naming
that is the point of the exercise rather than a way out of it. The CI signal's tool
is already installed and running in this repository. The link-checking signal's tool
is a one-line install of a mature project, and the skill's own guardrail says to
default generic utility work to existing tools. The wire-contract signal's real
answer is code generation, and its pain is currently not even felt, because both
committed refactor plans deliberately freeze the wire shape to avoid it.

The count-drift signal is what is left over, and it is the only one that is
genuinely local to this project: the invariant "the numbers written in these six
files equal the numbers the suites print" is not something anyone sells. It joins
two sources — a test run and a documentation tree — which is the skill's
cross-source criterion in its solo form. And it has one property the others lack:
**the obvious cheap check is provably wrong, and has already been wrong here.**
That is why a helper earns its place over a habit — the pain is not that counting is
tedious, it is that the fast way to count lies.

It is also honest about being small. This is a drift report, not a platform. It
replaces no existing system's responsibility, it writes nothing, and it is easy to
throw away — which is exactly the bar the first useful version is supposed to clear.

## Next Direction If Valuable

**Review / CI gate.** The first version is a local script producing a report. The
natural second step is not more features but a different host: run it on the
GitHub Actions runner that already exists, alongside the two suites, so the check
lives where the numbers are produced rather than on one developer's machine. That
is the same lesson path the CI signal routes to — **M5-L3 (Code Review w erze AI:
standardy, DoD i Agent w pipeline)** — so the two strongest signals here converge on
one destination, and the drift checker is a natural passenger once that pipeline
exists.

The generalization to a "documented-fact verifier" covering dangling links and wire
fields is recorded as a direction, not a plan. It only earns a second version if the
first one is still being run after a month.

## Deliberately Not Acted On

Three verified factual errors in live files were found while building this map and
are **left open on purpose**, so this artifact stays the lesson's deliverable rather
than a cleanup commit:

1. `backend/CLAUDE.md:88` points at `context/changes/market-price-context/research.md`;
   the file is at `context/archive/2026-06-02-market-price-context/research.md`.
2. Root `CLAUDE.md` says *"There is no CI yet"*; `.github/workflows/live-market-price.yml`
   exists and runs.
3. `context/domain/03-anti-corruption-layer.md`'s "which files know the dependency"
   table and its grep criterion are scoped to `backend/src/main` and so omit
   `.github/workflows/live-market-price.yml:40-43` (the `r.jina.ai` prefix and the
   three Jina headers, duplicated a third time) and `:51` (`PRICE_PATTERN`
   re-implemented in Python). The document's claim is accurate as written; its table
   is incomplete, and the leak reaches into CI.
