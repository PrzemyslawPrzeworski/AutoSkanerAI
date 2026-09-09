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
