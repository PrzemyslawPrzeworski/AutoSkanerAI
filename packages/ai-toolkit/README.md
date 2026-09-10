# @przemyslawprzeworski/ai-toolkit

A private npm package that installs shared Claude Code assets into a repository: the
`code-review` Agent Skill, plus a short always-on rules block spliced into the
consumer's `CLAUDE.md`.

Built for 10xDevs Module 5 Lesson 4. Published to **GitHub Packages**.

## What it installs

| Ships | Lands at | Owned by |
|---|---|---|
| `skills/code-review/` | `.claude/skills/code-review/` | this package — overwritten on every install |
| `rules/CLAUDE.md` | a sentinel-delimited block inside the consumer's `CLAUDE.md` | this package, block only |
| — | `.claude/.ai-toolkit-manifest.json` | this package — the uninstall list |

Nothing outside `.claude/` is created, and the only file outside it that is ever
edited is `CLAUDE.md`, only between these two markers:

```
<!-- BEGIN @przemyslawprzeworski/ai-toolkit -->
...
<!-- END @przemyslawprzeworski/ai-toolkit -->
```

**Anything you write inside those markers is overwritten on the next install.**
Anything outside them is never touched — that is enforced by the splicer, and
`test/managed-block.test.js` asserts a full install/uninstall cycle restores the
file byte for byte.

## Consumer setup

GitHub Packages needs two things: a registry mapping for the scope, and a token.
They go in different files on purpose.

**1. `.npmrc` in the consumer repo — committed, registry only:**

```
@przemyslawprzeworski:registry=https://npm.pkg.github.com
```

**2. The token — never committed.** Put it in your user-level `~/.npmrc`, or
export it for the shell:

```
//npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}
```

A classic PAT with `read:packages` is enough to install. In GitHub Actions use
`NODE_AUTH_TOKEN: ${{ secrets.GITHUB_TOKEN }}` and no PAT at all.

**3. Install:**

```bash
npm install --save-dev @przemyslawprzeworski/ai-toolkit
```

`postinstall` does the rest and prints what it did.

## Commands

```bash
npx ai-toolkit install    [--target <dir>]   # copy skills, splice the rules block
npx ai-toolkit uninstall  [--target <dir>]   # remove everything the manifest names
npx ai-toolkit status     [--target <dir>]   # what is installed, and whether it succeeded
npx ai-toolkit validate                      # check the package before publishing
```

`--target` defaults to the project root above `$INIT_CWD`, then above the current
directory — the nearest `.git` wins over the nearest `package.json`, because in a
monorepo the nearest `package.json` is a sub-package and `CLAUDE.md` lives at the
top.

## Two exit-code contracts, on purpose

`postinstall` **always exits 0.** A consumer running `npm install` for some other
dependency should not have their install broken because a skill file could not be
copied. But an exit code given up is a signal given up, so the failure is kept
everywhere else it can be:

- a framed block on **stderr**, naming the error;
- `status: "failed"` plus the error text in the manifest — the only durable trace
  once the process is gone;
- `npx ai-toolkit status`, which reads that manifest and **exits 1**.

`npx ai-toolkit install` — run by a human, on purpose — **exits non-zero** on
failure. Same library underneath (`lib/`), so the two cannot drift; only the
reporting differs.

This split exists because of a documented failure in this repo: a quality gate sat
dead from May to September because its error path was `catch → process.exit(0)`
and nothing said so out loud.

## Uninstalling

```bash
npx ai-toolkit uninstall
npm uninstall @przemyslawprzeworski/ai-toolkit
```

Removal is **not** wired to npm's `preuninstall` lifecycle. Those hooks do not
run for every removal path, and a remover that only sometimes runs leaves a repo
in a state nobody chose. So it is an explicit command, printed at the end of
every install.

Uninstall deletes only what the manifest names, and re-checks every path against
`<root>/.claude` before deleting it — a manifest is a file on your disk that
something else could have edited, and this tool refuses to become a file remover
with a text-file trigger. Refused paths are reported and make the CLI exit 1.

## Development

```bash
npm run validate    # the same checks CI runs
npm test            # the suite; fails if it collected zero specs
npm pack --dry-run  # what would actually be published
```

`npm test` goes through `scripts/run-tests.mjs` rather than calling `node --test`
directly, because `node --test` on a pattern matching nothing prints `# fail 0`
and exits 0 — a test script that cannot tell "nothing broken" from "nothing ran"
is not a gate.

`postinstall` skips itself when the package is running from a source checkout
rather than from `node_modules`, so `npm install` in this directory during
development does not install the toolkit into the repo that produces it.
