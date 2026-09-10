---
change_id: ai-toolkit-distribution
title: The team AI toolkit — a code-review skill, packaged and published
status: implemented
created: 2026-09-10
updated: 2026-09-10
archived_at: null
---

## Notes

M5-L4, both tasks. **Zadanie 1**: a `code-review` Agent Skill built from
`.claude/prompts/m5l4-shared-conventions.md`. **Zadanie 2**: that skill packaged as a private
npm package with an installer, plus CI that validates and publishes it.

Unlike M5-L3, this lesson's prompts are build specs, not research prompts — they say "generate
this structure" and give starter code. So there is no `/10x-research` → `/10x-plan` chain here;
this file is the record instead.

### The mismatch in the lesson materials

Zadanie 2 has two delivery models, and the handouts and the skills disagree about which is
primary:

| | Handouts say | Skills shipped |
|---|---|---|
| Model 1 — GitHub Packages | "**Default path for Zadanie 2**" | none |
| Model 2 — AWS CodeArtifact + Terraform | "AWS appendix… start with GitHub Packages" | all three |

`.claude/skills/{pack-init,setup-cicd,tf-registry}/SKILL.md` all name Model 2 explicitly —
`pack-init`'s own description ends "…for the Model 2 CodeArtifact delivery path". So the
recommended path has specs and no tooling, and the tooled path is the one the handouts say to
skip.

`pack-init` also calls itself "step two of the Model 2 pipeline… after `/create-skill` produces
`skills/code-review/SKILL.md`". **`create-skill` was never synced** — it is not in
`.claude/skills/`. Zadanie 1 is therefore built directly from `m5l4-shared-spec-skill.md`, which
is the same input that skill would have read.

### Decisions (user, 2026-09-10)

- **Both models.** GitHub Packages built and published for real, because it is the only one that
  can work from this machine. CodeArtifact authored through the three lesson skills and
  statically checked, never applied — so the lesson's tooling is exercised and the limit is
  written down rather than skipped.
- **Conventions adapted to this stack.** The handout is TypeScript-only; this repo is Java 21 /
  Spring Boot 4 plus Angular 21. The handout instructs adaptation in its own second sentence:
  "Treat it as a starter, not as universal truth." Rules are rewritten per stack and the two
  accident-data business rules are added.
- **Validate always, publish behind `workflow_dispatch`.** The spec publishes on every push to
  `main`; `main` here auto-deploys to two live hosts, and a package version appearing on the
  user's GitHub account is outward-facing. Validation is unconditional, publishing is manual.

### Facts about this repo that settled parts of the spec

- **The package must live at `packages/ai-toolkit/`, never the repo root.** The spec allows
  either. Root `CLAUDE.md` forbids a root `package.json`: Cloudflare Pages builds this repo from
  a subdirectory on every push to `main`, and a new root manifest is "an unverifiable risk to a
  live deploy path". `pack-init` independently defaults to `packages/ai-toolkit/`.
- **The scope is forced, not chosen.** GitHub Packages requires the npm scope to equal the owning
  account. The repo is owned by the user `PrzemyslawPrzeworski`, so the scope is
  `@przemyslawprzeworski`. The lesson's `@twoj-zespol` / `@10xdevs` placeholders cannot be used —
  publishing under a scope you do not own is rejected by the registry.
- **Terraform is not on PATH.** `tf-registry` covers this case: "If Terraform is not installed…
  say so and still run static checks over the generated files."
- **The commit gate had a false arm, found while wiring this.** `.githooks/pre-commit` matched
  `^packages/[^/]+/(src/.*\.ts|scripts/.*\.mjs|package\.json|tsconfig\.json)$` and then called
  `run_reviewer_checks`, which only ever `cd`s into `packages/code-reviewer`. So staging
  `packages/ai-toolkit/package.json` fired the arm and checked a **different package**, passing
  green over an unverified one. And `install.js` matched no pattern at all — the file that writes
  into other people's repositories and edits their `CLAUDE.md` was the least gated thing in the
  tree. Both fixed here.

### One deliberate tension with the spec

The installer spec says to "avoid failing the whole `npm install` when postinstall cleanup or
linking fails". Root `CLAUDE.md` says the opposite about this repo's own layers: "Every layer
fails loudly when its own toolchain is missing", and documents a hook that was "dead from May to
September" because its signal was hard-wired to success via `catch → process.exit(0)`.

Resolved by splitting on who asked:

- **`postinstall`** exits 0 — a consumer's `npm install` must not break because a skill file
  could not be copied — but prints an unmissable failure block to stderr and records
  `status: "failed"` plus the error in the manifest. Never silent.
- **`ai-toolkit install`** (the bin, run by a human) exits **non-zero** on the same failure,
  because someone asked for a result and is entitled to know they did not get one.

## Implemented (2026-09-10)

### What shipped

| Path | Holds |
|---|---|
| `packages/ai-toolkit/skills/code-review/SKILL.md` | **Zadanie 1** — the skill, six categories adapted to both stacks, four project rules that outrank them |
| `packages/ai-toolkit/rules/CLAUDE.md` | the short always-on block the installer splices |
| `packages/ai-toolkit/lib/{managed-block,project-root,manifest,toolkit,validate}.js` | all logic; nothing here prints or exits |
| `packages/ai-toolkit/{install,uninstall}.js`, `bin/cli.js` | the three I/O shells, two exit-code contracts |
| `packages/ai-toolkit/{package.json,pack.yaml,.npmrc,README.md}` | the manifests; `.npmrc` carries the registry mapping and no token |
| `packages/ai-toolkit/test/*.test.js` + `scripts/run-tests.mjs` | 42 tests, and a runner that fails on zero |
| `.github/workflows/publish-ai-toolkit.yml` | **Model 1** — validate on push/PR, publish behind `workflow_dispatch` |
| `.github/workflows/publish-ai-toolkit-codeartifact.yml` | **Model 2** — OIDC, dispatch-only, never run |
| `terraform/*.tf` + `check-static.mjs` + `README.md` | **Model 2** infrastructure, authored and statically checked |
| `.githooks/{common.sh,pre-commit,pre-push}` | the false-arm fix, per-package dispatch, the terraform arm |

### Verified, with the commands

- **42/42** tests pass (`node scripts/run-tests.mjs`), 4 spec files, no skips — the suite needs no
  network and no credential.
- **The zero-spec guard fires.** `node --test 'test/nosuchpattern*.js'` exits **0**; the same runner
  pointed at an empty `test/` exits **1** with a message. The trap and the guard were both run.
- **24/24** structural checks pass (`node bin/cli.js validate`), and the arm fails on a planted
  version drift between `package.json` and `pack.yaml`.
- **A real `npm install` of the packed tarball into a scratch git repo**, not a library-level
  simulation. The skill landed at `.claude/skills/code-review/SKILL.md`, the manifest recorded
  `status: "ok"`, the rules block spliced into `CLAUDE.md` without touching the consumer's prose.
- **Idempotency through the real lifecycle.** `npm install` twice is a no-op because npm skips the
  lifecycle entirely, which proves nothing — so `npm rebuild --foreground-scripts` forced
  `postinstall` to re-run: `managed block unchanged`, one sentinel block, `CLAUDE.md` md5 identical.
- **Both exit-code contracts, on the same induced failure** (a stray `BEGIN` marker with no `END`):
  `npm rebuild` exited **0** with the framed stderr block and wrote `status: "failed"` carrying the
  error text; `npx ai-toolkit install` exited **1**; `npx ai-toolkit status` exited **1**.
- **Uninstall restores the file byte for byte.** `diff` against the original `CLAUDE.md` is empty
  and `.claude/` is pruned away entirely.
- **Both workflows parse** (`npx js-yaml`), and 14 structural assertions hold on the GitHub Packages
  one: publish gated on `workflow_dispatch`, `needs: validate`, workflow permissions `contents: read`
  only with `packages: write` added on the publish job alone, no path filter on push, no
  `AWS_ACCESS_KEY_ID`, no `ACTIONS_STEP_DEBUG`.
- **9/9 terraform static checks pass, and the checker is not vacuous** — mutation-tested against six
  planted defects (renamed variable in one file, hardcoded account id, `access_key` in the provider,
  dropped `use_lockfile`, reference to an undeclared resource, unbalanced brace). All six caught.
- **The gate fix behaves both ways**: `run_package_checks some-new-package` **fails** rather than
  skipping, and the widened pattern now matches 18 of the package's files where the old one matched
  **2** — and those 2 were the ones that used to run the *wrong* package's checks.

### Not verified, and why

- **Nothing has been published.** Both workflows are `workflow_dispatch`-gated and neither has run;
  no version exists on GitHub Packages. That is the user's decision working as intended — the trigger
  is a person, and pushing is a separate act.
- **Neither workflow has executed at all**, because nothing here has been pushed. What is verified is
  that they parse and that their gating is structurally what it claims; a green run is not evidence
  that exists yet.
- **No `terraform init` / `validate` / `plan` / `apply`.** Terraform is not on PATH, confirmed. So the
  provider schema has never checked these files: a misspelled attribute or a wrong argument type
  would survive every check that ran. `terraform/README.md` carries the full list.
- **The IAM policy has never authenticated anything.** `sts:GetServiceBearerToken` is included
  because it is the action `codeartifact login` needs and the one most often missed, but only an
  apply proves the action set is sufficient and minimal.

### Deviations from the specs, each deliberate

| Spec said | Built | Why |
|---|---|---|
| `.github/workflows/ci.yml` | `publish-ai-toolkit-codeartifact.yml` | a file called `ci.yml` in a repo whose real gates are git hooks implies it is *the* CI |
| publish on push to the default branch | `workflow_dispatch` only | the user's decision, and `main` here auto-deploys to two live hosts |
| a validation job in the CodeArtifact workflow | none; it calls `bin/cli.js validate` | two copies of the same checks means one that drifts, and it is always the one nobody runs |
| five validation checks as shell `grep -q` | `lib/validate.js`, 24 checks, called by both workflows | a `grep -q` reports the same green whether it matched or the file was absent |
| installer may symlink in npm mode | always copies | a symlink into `node_modules` breaks the moment the dependency is removed, and leaves the consumer a dangling skill |
| `preuninstall` lifecycle hook | `npx ai-toolkit uninstall` | npm's uninstall hooks do not run on every removal path; a remover that only sometimes runs leaves a state nobody chose |
| skill category 3 "TypeScript" | "Types and null-safety" | it has to carry Java too |
| `--namespace 10xdevs` | `--namespace przemyslawprzeworski` | the scope is forced by GitHub Packages; the domain stays `devs10x`, which is exactly the gotcha the spec flags |

### Consequences carried forward

- **`.claude/skills/` is gitignored in this repo.** So dogfooding the toolkit here would install a
  skill that git does not track — correct behaviour for a managed install, but it means the repo
  cannot demonstrate its own package by committing the result. The install/uninstall cycle was
  therefore verified against scratch directories, which is also the only way to assert
  "`CLAUDE.md` is restored byte for byte" without risking the real one.
- **A new package under `packages/` now needs a `run_package_checks` arm** or the commit gate fails.
  Intended, and written into root `CLAUDE.md` so it is discoverable before it bites.
- **`terraform/.terraform.lock.hcl` is deliberately not gitignored** even though it does not exist
  yet — it pins provider hashes, which is the difference between the version constraint meaning one
  provider build and meaning whichever one the runner downloaded.
- **The `code-review` skill and `packages/code-reviewer` will drift** unless someone keeps them in
  step. They review the same code for the same team with different consumers, and only the CLI has
  evals. Worth a change of its own if the skill starts being relied on.
