---
name: ai-sdk
description: Vercel AI SDK v7 API reference for this package — ToolLoopAgent, structured output, tools, stop conditions, usage/cost, and the OpenRouter provider. Use when writing or changing code under packages/code-reviewer/src.
---

# AI SDK v7 — the parts this package uses

Verified against `ai@7.0.94`, `@openrouter/ai-sdk-provider@3.0.0`, `zod@4.5.4`
on 2026-09-09. This file exists because the API is recent enough that guessing
from memory gets nine things wrong; each is called out below as a **gotcha**.
Gotchas 1–3 were found by reading; 4 by `tsc`; **5–7 by a live run failing, and
all three of those are secret-hygiene bugs, not ergonomics.** 8 and 9 were found by
measuring a loop that looked like it worked: read **gotcha 8 first if you are
combining tools with structured output**, because it makes the tools unreachable
and nothing reports an error.

## Which layer to reach for

| You want | Use |
|---|---|
| one call, structured result, no tools | `generateObject` |
| one call, structured result, tools the model may **not** need | `generateText` + `output: Output.object(...)` — read gotcha 8 |
| a loop that must both **use tools** and return a schema | tools only; make the final answer a tool (gotcha 8) |
| a **reusable** configured agent that loops over tools | `new ToolLoopAgent({...})` |

`ToolLoopAgent` is the reusable one: construct it once at module scope, call
`.generate()` many times. That is what makes it exportable for evals — the config
travels with the object instead of being re-spelled at each call site.

## ToolLoopAgent

```ts
import { ToolLoopAgent, Output, isStepCount, tool } from 'ai';
import { z } from 'zod';

const agent = new ToolLoopAgent({
  model,                       // required: a LanguageModel
  instructions: 'You are …',   // the system prompt
  tools: { readFile },         // Record<string, Tool>
  toolChoice: 'auto',          // 'auto' | 'none' | 'required' | { type:'tool', toolName }
  stopWhen: isStepCount(8),    // default is isStepCount(20)
  output: Output.object({ schema: MySchema }),   // but see gotcha 8: this kills `tools`
});

const { output, text, steps, usage } = await agent.generate({ prompt });
```

**Gotcha 1 — the field is `instructions`, not `system`.** `generateText` and
`generateObject` take `system`; `ToolLoopAgent` takes `instructions`. Passing
`system` to the constructor is silently ignored, so the agent runs with no
system prompt at all and still returns plausible output.

**Gotcha 2 — structured output arrives as `result.output`, not `result.object`.**
`generateObject` returns `{ object }`. `ToolLoopAgent.generate()` and
`generateText` return `{ output }` when `output: Output.object({schema})` is set.

**Gotcha 3 — generating the structured output consumes a step.** If the agent
needs N tool round-trips, `stopWhen: isStepCount(N)` leaves no step for the final
object. Budget N+1 at minimum.

**Gotcha 4 — never annotate a factory's return type as bare `ToolLoopAgent`.**
The class is generic over the output schema, so the unparameterised name resolves
that parameter to `never` and `result.output` is `never` at every call site:

```ts
function build(): ToolLoopAgent { … }   // WRONG: output becomes never
function build() { … }                  // right: let it infer
```

Unlike gotchas 1–3 this one is caught by `tsc` — but only where `output` is *used*,
so it reads as a bug in the consumer rather than in the factory.

`allowSystemInMessages` defaults to off: a `role: 'system'` message inside
`prompt`/`messages` is rejected. That is prompt-injection mitigation — leave it
off, and never place reviewed content (a diff, a file body) in a system message.

## Tools

```ts
const readFile = tool({
  description: 'Read a repo-relative text file',   // the model picks tools by this
  inputSchema: z.object({
    path: z.string().describe('Repo-relative path, forward slashes'),
  }),
  execute: async ({ path }) => ({ path, text: await load(path) }),
});
```

- `inputSchema`, not `parameters` (v4 name).
- `.describe()` on each field is load-bearing: it is the only instruction the
  model gets about the argument's shape.
- `execute` returns a value that is serialised back into the conversation. Large
  returns accumulate in message history across steps — cap the size inside
  `execute` rather than trusting the model to ask for less.
- **`execute` is a security boundary.** It runs with the process's full
  privileges on a path the model chose. Resolve and confirm containment before
  touching the filesystem.

## Stop conditions

`stopWhen` takes a `StopCondition` or an array (OR-combined):

```ts
import { isStepCount, hasToolCall } from 'ai';
stopWhen: [isStepCount(10), hasToolCall('finalAnswer')]
```

## Cost and metrics

`usage` on the result carries `inputTokens`, `outputTokens`, `totalTokens` — each
possibly `undefined`, so never format one without a fallback. `totalUsage`
aggregates across steps. Lifecycle callbacks on `.generate()` give per-step
numbers without wrapping anything:

```ts
await agent.generate({
  prompt,
  onStepEnd({ stepNumber, usage, finishReason, toolCalls }) { … },
  onEnd({ usage, steps }) { … },
});
```

There is no `total_cost_usd` on the AI SDK result — that field belongs to the
Claude Agent SDK. With OpenRouter, price is per-model and comes from the models
endpoint, not the response.

## OpenRouter provider

```ts
import { createOpenRouter } from '@openrouter/ai-sdk-provider';
const openrouter = createOpenRouter({ apiKey });
const model = openrouter.chat('nvidia/nemotron-3-super-120b-a12b:free');
```

**The capability you filter on depends on gotcha 8.** For a tool loop it is `tools`;
`structured_outputs` only matters if you are calling `generateObject` without tools.
Only 5 of the 18 `:free` slugs advertise both. Free slugs also vanish without notice
— a dead one answers HTTP 404 `"This model is unavailable for free"`, not 429, and a
*degraded* one answers HTTP 200 with the error in the body. List the live ones:

```bash
curl -s https://openrouter.ai/api/v1/models \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
      for (const m of JSON.parse(s).data)
        if (m.id.endsWith(":free") && (m.supported_parameters||[]).includes("tools"))
          console.log(m.id, m.context_length);
    })'
```

Advertising `tools` does not mean the model will answer *on* the tool channel — see
gotcha 8, point 3. Budget for the free daily quota too: `free-models-per-day` is an
account-wide cap, and it applies across slugs, so a day of probing can lock out every
free model at once.

## Errors leak the whole call — catch the family, and do not keep the cause

**Gotcha 5 — `APICallError` is not the error you will actually get.** The advice
"catch `APICallError`" is too narrow. A tool loop with `output: Output.object`
does **not** constrain decoding provider-side, so a model that returns valid
content in invalid JSON throws **`NoObjectGeneratedError`**, which
`APICallError.isInstance` returns false for. Observed on the first live run here:
`nvidia/nemotron-3-super-120b-a12b:free` emitted a doubled opening brace,
`{\n{\n  "summary": …`, reproducibly. Catch `AISDKError.isInstance` as the
backstop — every SDK error extends it — and narrow the interesting ones first.

Why it matters: these errors carry the call as **enumerable own properties**, so
anything that prints one prints the request body, every message, the whole
schema, the response text, and the response headers — `set-cookie` included.

**Gotcha 6 — `{ cause: originalError }` reopens the leak.** Wrapping the SDK
error in your own short-message error looks like the fix and is not:
`util.inspect` follows `[cause]`, so any printer walks straight back to the
request body. Measured, not assumed. Put what you need in the message instead.

**Gotcha 7 — `NoObjectGeneratedError.cause` is a `JSONParseError` whose message
embeds the entire text it failed to parse.** So `${error.cause.message}` is a
dump wearing a summary's clothing. Walk to the *deepest* cause — the
`SyntaxError`, whose message is the one useful line (`Expected property name or
'}' at position 2`) — and cap the result anyway.

Useful non-leaking fields on `NoObjectGeneratedError`: `text` (the raw answer —
slice it), `finishReason`, `usage`.

## Structured output and tools do not coexist

**Gotcha 8 — `output: Output.object(...)` suppresses tool calls entirely.** Both
are sent in the same request — the captured body carries `tools: […]`,
`tool_choice: "auto"` *and* `response_format: {"type":"json_schema", …}` — and
constrained decoding then leaves the model no channel in which to emit a tool call.
The tools are advertised and unreachable. Nothing warns; the run simply answers in
one step, correctly shaped, having read nothing.

Measured here, one model and one fixture, everything else equal:

| `output` | steps | files read |
|---|---|---|
| unset | 3, 3, 2 | 15, 14, 16 |
| set | 1, 1, 1 | 0, 0, 0 |

One run said so itself — *"to verify whether this is correct, I need to check what
port the backend actually listens on"* — and then could not.

**The fix is to make the final answer a tool.** Drop `output`; add a tool whose
`inputSchema` *is* the answer schema, and stop on it:

```ts
const submitReview = tool({
  description: 'Submit the finished review. Call exactly once, last.',
  inputSchema: ModelReview,
  execute: async () => ({ received: true }),
});

new ToolLoopAgent({
  model, instructions,
  tools: { readRepoFile, findInRepo, submitReview },
  stopWhen: [hasToolCall('submitReview'), isStepCount(8)],
  // no `output:` -> no response_format -> the read tools are usable
});
```

The schema is still enforced — the SDK validates tool input before `execute` — and
`response_format` is never sent. Three consequences to plan for:

1. The answer is read from the tool call, not `result.output`: scan
   `result.steps[].toolCalls` for the call and take its `input`.
2. **Nothing forces the model to answer at all.** It can write the review as prose
   and never call the tool. Say so in the prompt, and report a missing call as a
   failure — treating "no answer parsed" as an empty result turns a broken run into
   an approval. A free slug did exactly this, reproducibly.
3. Model selection changes: `structured_outputs` in `supported_parameters` stops
   mattering and `tools` starts mattering, and neither tells you whether the model
   will *use* the tool channel. That is measurable only by running it.

**Gotcha 9 — invalid tool input is not thrown; it is handed back to the model.**
With no `experimental_repairToolCall`, a call whose arguments fail the schema does
**not** reject `generate()`. The SDK records the call with `invalid: true`, an
`error` (an `InvalidToolInputError`), and `input` as the raw *string* rather than an
object, then feeds the failure back as a tool result and continues the loop. So a
`catch` block never sees it — and note `hasToolCall` still fires on the invalid
call, which ends the run. Distinguish the two outcomes where you read the result:

```ts
if (call.invalid === true) …        // answered, unreadable  -> malformed
else if (call === undefined) …     // never answered         -> no output
```

Also: the deepest cause of a schema violation is a Zod error whose message is a
pretty-printed array, so a "first line only" summary yields the useless
`ZodError: [`. Collapse whitespace and cap instead — then the message names the
offending path.

## Running it here

Node 22, ESM, no build step: `npx tsx src/index.ts`. Secrets come from the
gitignored repo-root `.env` via `process.loadEnvFile()`, and a real environment
variable always wins so CI never depends on an uncommitted file.
