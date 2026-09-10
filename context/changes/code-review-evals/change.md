---
change_id: code-review-evals
title: Evals for the code-review prompt — one prompt, three models, planted flaws
status: planned
created: 2026-09-10
updated: 2026-09-10
archived_at: null
---

## Notes

M5-L3, workstream A. Create the first eval configuration for
`packages/code-reviewer`: run the same code-review prompt against three
different models, on one rather complex test-case diff carrying planted
impactful flaws, with an LLM-as-a-judge verifying whether the review correctly
identified what is broken — plus a static/deterministic assertion that the
review actually fails.

promptfoo is the first pick for the toolkit; if the stack does not align, an
alternative OSS harness is in scope instead. The lesson names two model slugs
(`z-ai/glm-5.1` and `deepseek/deepseek-v4-flash`) and a React 16 → React 19+
migration diff as the fixture subject.

### Scope decisions taken before research (user, 2026-09-10)

- **Runner: `ai-sdk`** (the Vercel AI SDK loop against OpenRouter), as the
  lesson implies. This reverses the pick in
  `context/changes/agent-sdk-reviewer/pick.md`, whose own "What would change
  this" section names credits on the OpenRouter account as *"the most likely
  flip"*. `pick.md` needs a dated follow-up note, and any repo text asserting
  that the evals wrap `agent-sdk` needs correcting.
- **Credits: assume a small OpenRouter top-up.** Paid slugs are in scope, so
  the configuration can be planned exactly as the lesson names it rather than
  being bent around `:free`-only slugs.

### Scope decisions taken after research (user, 2026-09-10)

Answers to three of `research.md`'s eight open questions. The other five are the
plan's to take, with a recommendation recorded there.

- **Fixture: both.** Author the React 16 → 19+ diff the lesson names *and* an
  in-stack Angular/Spring fixture, each with three planted flaws. The React one
  keeps lesson fidelity; the in-stack one exercises all four of the reviewer's
  project rules plus the tool, containment and evidence layers — which a React
  subject leaves idle (`research.md` §7). The pair also measures how much the
  off-domain subject costs, which neither fixture alone can show.
- **Answer key: measure the leak, do not hide it.** `packages/` and `context/`
  are both inside the reviewer's allow-list, so the fixture, the config and this
  file are all readable by the model under eval (`research.md` §8). Keep the
  natural placement and add a deterministic assertion that `accessedPaths`
  contains no eval-directory path — a silent, self-confirming risk converted
  into a check that fails loudly. The allow-list is **not** narrowed for eval
  runs, so the eval measures the reviewer that actually ships.
- **Phase 1 measures before the harness is built.** Run the three paid slugs
  with `plugins: [{id:'response-healing'}]` and `provider.require_parameters`
  on, and record the `malformed-output` rate. Both options are typed and already
  installed (`@openrouter/ai-sdk-provider@3.0.0`) and neither was tried during
  the 32-run comparison, so this is the cheapest way to convert the runner
  reversal from *authorised by `pick.md`* to *evidenced* — and it settles
  whether n=3 repeats are needed at all. Roughly $0.07.
