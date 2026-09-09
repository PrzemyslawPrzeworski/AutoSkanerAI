---
change_id: tool-loop-agent
title: Turn the code-reviewer spike into a modular ToolLoopAgent
status: implementing
created: 2026-09-09
updated: 2026-09-09
archived_at: null
---

## Notes

Convert `packages/code-reviewer/src/index.ts` into a well-organized, modular code
review agent based on the AI SDK's `ToolLoopAgent`. Extract the structured output
schemas into their own modules, and the prompts too. The agent module must be
reusable and must export the reviewer, so promptfoo evals can run against it
later. Do not configure the eval environment in this change.

M5-L2 step 2. Step 1 (the working spike) landed as `2d29dbf`.

## Log

**Phase 5, 2026-09-09.** The gate wiring found one thing, and it was in the runner
rather than in the hooks.

**`packages/` was invisible to all three quality gates**, and had been since the
directory was created. `post-edit-check.mjs:96` matches `frontend/src/`; both git hooks
matched `^frontend/src/…` and `^backend/(src/…|pom\.xml)`. So a package with 80 tests
had nothing running them — the same shape as the dead prettier hook, one level up: not a
gate reporting wrongly, a gate that was never pointed at the code. Now a pre-commit arm
on `^packages/[^/]+/(src/.*\.ts|scripts/.*\.mjs|package\.json|tsconfig\.json)$` and an
unconditional pre-push arm. Per-edit is deliberately left alone: this package is edited
in bursts, and 8 s per keystroke-level edit buys nothing the commit gate does not.

**The runner could not report an empty suite.** Phase 2 recorded that
`node --import tsx --test src/*.nosuchpattern.ts` prints `# fail 0` and exits 0, and
left it to this phase because a guard only matters once something depends on the signal.
It now does. `scripts/run-tests.mjs` enumerates the specs with `readdirSync` — which
also removes the bash-expands-it / cmd.exe-passes-it-through divergence the glob relied
on — and requires the run's own TAP tally to be non-zero before honouring the exit code.
Watched blocking both ways: an inverted assertion in `verdict.test.ts` (the hook failed
naming the assertion, and the break was reverted immediately), and every spec file moved
aside, where the bare runner would have said `ok`.

The typecheck runs first and is not optional here the way it is for the other two
stacks. Nothing compiles this package — tsx strips the types and runs — so `tsc
--noEmit` is the only thing that ever reads them; without it the types are decoration.
Measured: 1.5 s typecheck + 6.4 s suite = 8.2 s for the arm, 39 s for a full pre-push to
`main`.

The arm is offline by construction, and the day's quota proved why that matters rather
than being a preference: `npm run test:live` still answers
`Rate limit exceeded: free-models-per-day`, so a gate that made a model call would today
refuse every commit for a reason that has nothing to do with the commit. 4.3 and 4.9
stay blocked on the same quota as yesterday. One thing did come out of the attempt — the
live failure surfaced as `kind: 'provider'` with a one-line message and no request dump,
which is the Phase 4 hygiene work behaving correctly on a path that reached a real
provider.

Two adaptations beyond the plan's file list. `PACKAGE_SOURCES` also matches
`scripts/*.mjs`, because after this phase the runner itself is load-bearing and a break
there is invisible to a matcher that only watches `src`. And the root `CLAUDE.md` gate
table and suite-size line were updated alongside `test-plan.md`: the plan named
`test-plan.md` as the place layers are registered, but `CLAUDE.md` describes the same
three layers, and a table that omits an arm reads as "not gated" to the next reader.

**Phase 4, 2026-09-09.** The phase that changed the design. Five findings, in the
order they were forced on me.

**The first live run crashed, and the crash was a credential leak.** The guard in
`agent.ts` tested `APICallError`; the error thrown was `NoObjectGeneratedError`, which
`APICallError.isInstance` returns false for. It escaped as an unhandled rejection, and
Node prints an error's enumerable own properties — for an AI SDK error that is the
entire request body, every message, the whole JSON schema, the response text and the
response headers, `set-cookie` included. Two things were wrong at once: too narrow a
guard, and a rethrow in `index.ts` that turned a reviewer crash into exit 1, the code
that means "this diff failed review". Fixed with an ordered chain ending in an
`AISDKError` family backstop, and a catch-all that can only exit 2.

Two follow-on leak vectors, both **measured rather than reasoned about**:
`{ cause: error }` on the wrapper reopens it, because `util.inspect` follows
`[cause]`; and `NoObjectGeneratedError.cause` is a `JSONParseError` whose own message
embeds the entire text it failed to parse, so my "First 120 chars" message printed all
1.7 KB of the answer. Hence `brief()` — walk to the deepest cause, then cap anyway.
`failures.test.ts` replays the recorded responses against a local server with three
sentinels (key, `set-cookie`, diff body) and asserts on `util.inspect(error, {depth:
8})`, **not** on `error.message`. That distinction is the whole value of the helper:
the first draft checked the message and passed with both guards deliberately disabled,
because the message was never the vector.

**A free slug held the socket open for over ten minutes** — no bytes, no error, process
alive at 0.6 s of CPU. Nothing in the package bounded it, so the only limit was my
patience. Phase 5 makes this a commit gate, where an unbounded wait is strictly worse
than a failure: a developer can act on a failure and can only kill a hang. Now a
120 s `AbortSignal.timeout` and a `timeout` error kind, reproduced offline in 424 ms.

**The design change: structured output and tools cannot coexist.**
`output: Output.object({schema})` sends `response_format: {type:"json_schema"}` in the
same request as `tools`, and constrained decoding leaves the model no channel in which
to emit a tool call. The tools were advertised and unreachable — so Phase 3's entire
containment layer was dead code, and `stripUnbackedEvidence` was stripping every
citation by construction, since nothing was ever backed. Nothing errors; the run just
answers in one step having read nothing. Tallied over six runs, one model, one
fixture, everything else equal: `output` unset -> 3, 3, 2 steps and 15, 14, 16 files
read; `output` set -> 1, 1, 1 steps and 0, 0, 0 files. No overlap. One run said so in
its own summary — *"to verify whether this is correct, I need to check what port the
backend actually listens on"* — and then could not.

Resolved by making the answer a tool: `submitReview`, whose `inputSchema` **is**
`ModelReview`, with `stopWhen: [hasToolCall('submitReview'), isStepCount(8)]` and no
`output` at all. The schema is still enforced, because the SDK validates tool input;
what goes away is the `response_format` that silenced the tools. Verified live on
`fixtures/cross-file.diff`: 4 steps and 14 files read, then 2 steps and 7 files on a
re-run, finding the port defect that is **invisible inside that diff** and citing a
real path in `evidence` that survived the access-log check.

The trade this makes is real and is not hidden: nothing now forces the model to answer
in the schema at all. It can write the review as prose and never call the tool, which
is reported as `no-output` — an unsubmitted review is an unfinished one, never a pass.
That case has a test, and the previous default slug does it reproducibly (below).

**Two model findings, both measured on the fixtures.** `nex-agi/nex-n2.5-pro:free`,
the default going in, stopped after one step without calling `submitReview` at all,
twice — a fatal trait under this design, so the default is now
`dots-studio/dots-3-note-preview:free`, the slug verified end to end. It is not immune
either: one run in four sent `findings` as a *stringified* array. That failure is
worth recording because it is the one my own error message was too vague to describe —
`brief()` cut at the first newline, and the deepest cause of a schema violation is a
Zod error whose message is a pretty-printed array, so the report read `ZodError: [`.
Collapsing whitespace instead of cutting at the newline makes it name the field. Also
learned: `free-models-per-day` is an **account-wide** cap across slugs, and a day of
probing locks out every free model at once — which is what blocks criteria 4.3 and 4.9
today, on quota rather than on code.

**Two criteria closed against reality rather than against their wording.** 4.4 asks
for a step count above 1 on `fixtures/bad.diff`; the run gave exit 1 and both blockers
in **one** step, having read nothing — which is the right answer for that fixture,
since both rules are decidable from the diff alone and the prompt says "do not use
them to browse". The clause's intent (the tool loop is not structurally dead) belongs
to `cross-file.diff`, and 4.8 met it there. Checked 4.4 for what it verified; the
alternative was a fixture amended to need a tool, which would have cost the thing
`bad.diff` is actually for. 4.3 and 4.9 stay unchecked, blocked on the daily quota
rather than on code, and Phase 4 is committed anyway: Phase 5 does not depend on
either, and holding a verified design change uncommitted overnight is the larger risk.

One SDK behaviour worth its own line, because a `catch` block cannot see it: **invalid
tool input is not thrown.** The SDK records the call with `invalid: true` and an
`error`, hands the failure back to the model, and continues; `hasToolCall` still fires
on it. So the "answered but unreadable" and "never answered" cases are distinguished
where the result is read, not where errors are caught. Both are in `SKILL.md` as
gotchas 8 and 9 — the two that were found by measuring a loop that looked like it
worked.

**Phase 1, 2026-09-09.** Two deviations worth recording.

`tsconfig.json` gained `allowImportingTsExtensions: true`. Splitting one file into
five made this the first change with relative imports, and `nodenext` otherwise
demands `./schema.js` specifiers pointing at a `.ts` file — correct only for code
that gets emitted, and this code never is. Legal because `noEmit` is on.

Criterion 1.3 (the benign direction) was met on `nex-agi/nex-n2.5-mini:free`, not on
the default `nvidia/nemotron-3-super-120b-a12b:free`. The default answered
`HTTP 200 "Upstream error from Nvidia: Service temporarily overloaded"` on four
attempts across ~2 minutes, having succeeded on the rule-violating fixture minutes
earlier. So: the code is fine, the free tier is not — the exact fragility
`src/env.ts` documents, now observed rather than predicted. It is an argument for the
retry/fallback chain the plan deliberately excludes; left excluded, because choosing
a fallback order before the eval work is guessing. Note that a degraded provider
returns HTTP **200** with the error in the body, so a status-code check alone would
have read it as success — `APICallError` caught it because the SDK validates the
body, not the status.

**Phase 3, 2026-09-09.** The deliberate break earned its keep: it found a gap rather
than confirming there wasn't one.

**The first containment mutation changed nothing.** Deleting the `..` escape check
from `insideRepo` left all 50 tests passing. The reason: `path.relative` renders an
escape with a leading `..`, and no string starting with `..` can match the allow-list,
so the allow-list was refusing every escape case and containment was never the thing
under test. The assertions said "refused" without saying why, which would have kept
passing if the allow-list were later broadened — the layer that had silently become
load-bearing is the one nobody was testing. Fixed by pinning the *reason* on each
escape case; the same mutation then failed 3 of 50, and was reverted.

**A related correction to my own test.** `frontend/src/../../.env` is the *root*
`.env` — `frontend/src` up two levels is the repo root — so it never leaves the repo
and containment has nothing to say about it. It is the allow-list that refuses it.
Worth stating plainly, because that is the path to the live OpenRouter key: **the
allow-list, not the containment check, is the layer protecting the key.** Containment
covers the case one level further out (`frontend/src/../../../.env`), and both now
have a test naming their own layer.

`isAbsolute(rel)` is not decoration next to the `..` check. On win32 `path.relative`
returns the target unchanged when the drive letters differ, so `C:\Windows\...` from a
repo on `D:` produces no `..` at all. That case survived the mutation for that reason.

Criterion 3.4's grep (`spawn|execSync|child_process`) matches `tools.ts`'s own comment
saying it does none of those — the same shape as 2.6's `verdict` grep. Verified by
re-running with comment lines excluded.

Two adaptations. `src/tools.test.ts` (13 tests) is beyond the plan's three-file list:
`repo.test.ts` proves the policy refuses the right strings, but only a test of the
tool proves the tool *asks* it, and a bypass mutation confirmed that pairing catches a
policy-free read. And `isDeniedSegment` is exported from `repo.ts` so the search walk
can prune a tree by name — the first draft asked `resolveReadablePath` about
`<dir>/.keep`, which the dot-segment rule refuses, so every directory would have been
pruned. `REPO_ROOT` moved from `env.ts` to `repo.ts`, as flagged in Phase 1.

**Phase 2, 2026-09-09.** Three findings, two of them about the tooling.

`node --import tsx --test src` — the directory form — **hangs indefinitely with no
output at all** on Node v22.22.1 / Windows. Killed after 180 s twice. The glob form
`node --import tsx --test src/*.test.ts` runs 27 tests in ~300 ms. Unquoted in
`package.json` on purpose: bash expands it, cmd.exe passes it through for Node to
expand, and both reach the same two files.

**A zero-collected run exits 0.** `node --import tsx --test src/*.nosuchpattern.ts`
prints `# fail 0` and succeeds. So the runner cannot distinguish "nothing broken"
from "nothing ran" — the dead-gate shape, in the very tool meant to be the gate. The
glob does pick up newly added specs, so the realistic failure ("someone adds a spec
and nobody runs it") is covered; the remaining hole is "every spec disappears". Left
open here because criterion 5.1 already owns it, and closing it needs a guard that
belongs with the gate wiring, not with the rules.

**The model filled `evidence` despite being told not to.** The prompt says "Leave
'evidence' out entirely unless a tool gave you that path". There are no tools yet, and
the model attached evidence to both findings anyway — quoting the diff back, with
`evidence.file` set to the file already in `file`. Output tokens went 502 -> 1352 on
the same fixture, so it is not free either. This is the plan's own thesis arriving as
evidence rather than argument: an instruction in a prompt is not enforcement. Phase 4
removes these structurally via `stripUnbackedEvidence` against the tool-access log,
which is exactly why that criterion exists. Not patched by re-wording the prompt,
because re-wording is the thing that does not work.
