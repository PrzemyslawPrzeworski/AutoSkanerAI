---
change_id: async-remote-agents
title: Delegating a planned change — the sandbox setup layer, and what /goal needs from this repo
status: implemented
created: 2026-09-10
updated: 2026-09-10
archived_at: null
---

## Notes

M5-L5, "Innovate: Async & Remote Agents", the optional lesson closing Module 5. Worked because the
standing rule is lessons in order, finished completely — optional is not the same as skipped.

Unlike every other Module 5 lesson this one **ships no prompts and no spec handouts**. `10x-cli get
m5l5` writes exactly two files, `.claude/skills/10x-goal-implement/{SKILL.md,references/implementation-discipline.md}`,
and both were already on disk — every write reported `unchanged`. So there is no `/10x-research` →
`/10x-plan` chain to run and no Zadanie document to satisfy; this file is the record instead.

The manifest at `.claude/.10x-cli-manifest.json` now names `m5l5`. Worth knowing: it lists
`m4l2, m5l1, m5l2, m5l5` and **not** `m5l3` or `m5l4`, whose prompts are nonetheless on disk. Those
were fetched by a narrower path in earlier sessions, so the manifest is not a reliable completion
ledger — which is the same reason lesson position is tracked in memory rather than read off it.

### The four ideas, and which one this repo failed

1. **The control moment.** Autonomy is not a permission level, it is a choice of *when you look*:
   real-time remote control, kick-off-and-monitor, or a scheduled routine nobody starts.
2. **Three remote-execution archetypes**, picked by task rather than taste — SSH/tmux into your own
   machine, a hosted relay (Happy), or a managed cloud sandbox.
3. **The transferable sandbox configuration model** — setup, network, MCP, cache, secrets. The test
   of each layer is whether you can *hand it over*.
4. **Isolation-as-autonomy** — you buy autonomy by shrinking the blast radius, not by loosening
   permissions.

Layer 1 of item 3 is where this repo was broken, and it took running the check to see it.

### The finding: the setup layer was in a file no agent sources

Every `#### Automated` success criterion in all four phases of `refactor-opportunities` is
`./mvnw -o test`. `/10x-goal-implement` runs that command directly. In a bare shell:

```
JAVA_HOME=[C:\Program Files (x86)\Zulu\zulu-8-jre\]
MAVEN_OPTS=[-Xmx12g -XX:ReservedCodeCacheSize=256m -Xss24m]
$ cd backend && ./mvnw -o -v
Invalid maximum heap size: -Xmx12g
Error: Could not create the Java Virtual Machine.
```

The backend suite only ever ran here because `.githooks/common.sh` pins `JAVA_HOME` and `MAVEN_OPTS`
itself — and that file executes only when a git hook fires. So the skill's preflight step 3 would
have printed `PREFLIGHT: ./mvnw -o test not runnable`, then stopped at Phase 1 having changed
nothing.

**What makes this the lesson's point rather than a config typo:** every gate in the repo was green.
Root `CLAUDE.md` already documents this exact trap — the 32-bit JRE, the `-Xmx12g` that cannot be
represented, the "message about memory for a problem about Java" — and documents it as *solved*,
because from the only angle anyone was looking from (a git hook) it was. Nobody had asked what a
caller outside the hooks sees. Headless `claude -p` and a cloud sandbox see the same thing.

### The fix, and why it is duplicated on purpose

`.claude/settings.json` gains an `env` block carrying the same two values. Two callers, two entry
points, one pair of values:

- `common.sh` covers a `git commit` typed in a terminal with Claude not running.
- `settings.json` covers any agent session, interactive or headless.

Neither can cover the other's case, so the duplication is structural rather than sloppy. It is
**not gated**, and that is a decision: reading `settings.json` needs node, and making a Java-only
commit depend on node to verify a file that only affects agent sessions inverts the cost. The
pointer is a comment in both directions instead — `common.sh` names `settings.json`, root
`CLAUDE.md` names both.

One useful surprise: Claude Code re-reads `env` **per Bash call**, not once at session start. So the
fix was provable in the session that made it, which is why the verification below is a real run and
not a claim about the next session.

## Implemented (2026-09-10)

### Verified, with the commands

- **`env` reaches a live session.** After the edit, `echo $JAVA_HOME` in the same session printed
  `D:/Software/Java/jdk-26.0.1` and `$MAVEN_OPTS` printed `-Xmx1g`, replacing the inherited
  zulu-8 / `-Xmx12g` pair. No restart.
- **The plan's actual gate command passes with no env prefix.** `./mvnw -o test` →
  `Tests run: 235, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`. This is the criterion the
  skill would run, run the way the skill would run it — not `common.sh`'s version of it.
- **`settings.json` still parses and the `PostToolUse` hook survived the edit** — checked by reading
  the file back through `node` and asserting the hook command string is intact, because a malformed
  settings file degrades to "no hooks" rather than to an error.
- **The plan is structurally compatible.** `refactor-opportunities/plan.md` already carries the
  `#### Automated` / `#### Manual` split the skill requires (four phases, **12** Automated rows and
  **5** Manual, counted off the file), so no plan surgery is needed to delegate it.

### Not verified, and why

- **No autonomous run has happened.** `/goal` is a session-level command a human types; it cannot be
  started from inside a session. The handoff below is the deliverable, not a completed run.
- **`references/progress-format.md` does not exist.** Both `10x-goal-implement/SKILL.md` and
  `10x-implement/SKILL.md` cite it; `10x-implement` has no `references/` directory at all and
  `10x-goal-implement` ships only `implementation-discipline.md`. Harmless here because the plan
  already has the format, but a plan written *from* that citation would have nothing to read.
- **Node's PATH is untouched.** `common.sh` prepends `/c/nvm4w/nodejs` because nvm4w keeps node off
  the default PATH; the `env` block does not, because setting `PATH` there replaces rather than
  extends it. Node happens to be resolvable in sessions launched from a shell that has it. A remote
  sandbox is the case that would break, and it is unproven.

### The handoff — delegating `refactor-opportunities`

Type these two, in order. The turn bound is the runaway stop.

```
/goal Use the 10x-goal-implement skill to implement all phases of
context/changes/refactor-opportunities/plan.md. Done when: every row under
#### Automated in the plan's ## Progress section is checked, each phase has its
own Conventional-Commits commit, and the final output lists any pending
#### Manual rows. Constraints: do not modify or weaken existing tests unless the
plan says so; do not touch files outside the plan's scope. Stop after 20 turns
if not complete.
```

```
/10x-goal-implement refactor-opportunities
```

Three things to know before starting it:

- **The plan already prescribes the deliberate-break gate**, by hand, before the gate existed:
  invert `AnalysisResponseParser:170`, confirm the contract test's *real* parameter fails, revert.
  Phase 3 does the same at `CepikRiskAdjuster:73`. The skill's gate (b) and the plan's criteria will
  agree rather than compete.
- **Phase 2 inverts the accident rule on purpose.** The guard at `AnalysisResponseParser:170` is
  what enforces "absence of accident data means unknown, not clean". The inversion is a test of that
  rule and must be reverted before anything is staged — the skill restores with
  `git checkout -- <file>` against the already-staged version, which is why staging happens *before*
  the break-check.
- **Phase 4 rewrites the suite counts** in root `CLAUDE.md`, `.githooks/pre-commit:38` and
  `.githooks/pre-push:28`. Those three numbers are currently 235 and will move.

### Consequences carried forward

- **A second toolchain value now needs two edits.** If a future gate needs, say, a `NODE_OPTIONS` or
  a `SPRING_PROFILES_ACTIVE` pin, it belongs in both `common.sh` and `settings.json` for the same
  reason these two do — and the reason is written down in both files rather than inferred.
- **"The gates are green" stops meaning "the toolchain works."** It means the toolchain works *for
  the hooks*. Any new unattended or remote path should be preflighted from a bare shell before it is
  trusted, which is precisely what `/10x-goal-implement`'s preflight step is for.
