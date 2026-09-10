---
change_id: agent-sdk-reviewer
title: Build the reviewer a second time on the Claude Agent SDK, then pick
status: implementing
created: 2026-09-09
updated: 2026-09-10
archived_at: null
---

## Notes

build the same code reviewer a second time on the Claude Agent SDK — the ready-made half of M5-L2's ready-made-vs-assemble-it-yourself comparison. Reuse the existing shared schema, prompt, verdict and diff modules from packages/code-reviewer; only the loop, the tools and the auth path differ. Ends in a written pick between the two runners, which is what M5-L3's promptfoo provider will wrap.

## Measurements

Plan steps that produce a number rather than a file. Recorded here because the plan asks
for them and because `pick.md` needs figures it did not invent.

### `settingSources`: the default really does inject CLAUDE.md (plan 3.3)

The plan took `['user', 'project', 'local']` on the documentation's word (sdk.d.ts:2086),
and the whole hermetic-versus-inheriting decision rests on it. Probed with a question whose
answer is in this repo's CLAUDE.md and nowhere a model could guess — the backend listens on
**10000**, not the 8080 of every Spring Boot tutorial — asked with `tools: []` so no read
tool could supply it:

| `settingSources` | answer |
|---|---|
| omitted | `10000` |
| `[]` | `UNKNOWN` |

So the injection is real, silent, and on by default. `agent-sdk.ts` passes `[]`, and the
reason is the comparison rather than isolation: auto-injected project rules are an advantage
the AI SDK runner cannot have at all, so leaving them on would make `pick.md` a measurement
of the harness. Nothing is lost — `repo.ts`'s allow-list names all three CLAUDE.md files, so
`Read` fetches them on demand, and the live run does exactly that.

### The live runs (plan 3.1, 3.2, 3.6)

Bedrock `eu.anthropic.claude-sonnet-5`, `eu-central-1`, one run each on 2026-09-10:

| fixture | turns | verdict | findings | files read | cost |
|---|---|---|---|---|---|
| `bad.diff` | 2 | fail | 2 (both rules) | 0 | $0.0169 |
| `cross-file.diff` | 6 | fail | 1 (the off-diff port) | 1 | $0.0249 |
| `vendor-detail.diff` | 7 | fail | 2 | 9 | $0.0549 |

A misspelled model id (`…-sonnet-5-typo`) exits **2** as `[provider]` naming the 400, not as
an empty review.

**`cross-file.diff` states its own answer, and that is a defect in the fixture.** Its
twenty-line preamble explains that the backend listens on 10000, that CLAUDE.md says so, and
that `playwright.config.ts` probes the same port — inside the file, so it is fed to the model
as part of the diff. The first `accessedPaths` run against it produced a correct review in 6
turns having read **nothing**, citing the preamble. It reads a file on other runs (above),
which is worse than a consistent failure: the assertion would have been flaky rather than
wrong. `agent-sdk.live.test.ts` therefore asserts against the new bare
`fixtures/vendor-detail.diff`, whose rule-3 violation can only be settled by searching the
repo. `cross-file.diff` is left unmodified so the AI SDK runner's already-recorded `read=`
numbers are not retroactively altered — but those numbers are weaker than they looked.

### The output-channel A/B was not run, deliberately (plan 3.4)

The plan's item 4 called for building a `createSdkMcpServer` submit tool first as "the channel
known to work", then switching to `outputFormat` and running three of each. **The order was
inverted** — `outputFormat` was built first, because the "known to work" belief came from the
AI SDK, while the Agent SDK's own declarations say `outputFormat` is an end-turn *tool* rather
than a decoding constraint. That inversion paid for itself immediately: it exposed the
`StructuredOutput` naming collision, which the MCP arm would have hit identically as
`mcp__<server>__submitReview`.

It also settled the A/B before the second arm existed. The criterion was "which channel
preserves tool use"; the AI SDK's suppressed column is 1 step and 0 files on all six of
`tools.ts:87`'s runs, and this runner reads 0 / 1 / 9 files across three fixtures with
`outputFormat` set on every one. Building the other arm to watch it also pass measures
nothing, so `submit-tool.ts` was never created and there is nothing to delete. The tally and
the reasoning live in the `outputFormat` comment in `agent-sdk.ts`.

Given up, and named rather than left to be discovered: this package has no experience of
`createSdkMcpServer`, and `outputFormat`'s five-attempt retry loop is a genuine fragility
where a submit tool would hand the model a tool result it could act on.

### Deviation from criterion 3.8

`grep -r 'canUseTool' src/` returns three matches, and none is an implementation: two are
comments explaining why `permissionMode: 'default'` is safe without one, and the third is
`assert.equal(session().canUseTool, undefined)` — an assertion that the lookalike gate does
**not** exist. Satisfying the criterion literally would mean deleting the test that pins the
criterion's own intent. Kept, and recorded here instead.
