---
name: ai-sdk
description: Vercel AI SDK v7 API reference for this package — ToolLoopAgent, structured output, tools, stop conditions, usage/cost, and the OpenRouter provider. Use when writing or changing code under packages/code-reviewer/src.
---

# AI SDK v7 — the parts this package uses

Verified against `ai@7.0.94`, `@openrouter/ai-sdk-provider@3.0.0`, `zod@4.5.4`
on 2026-09-09. This file exists because the API is recent enough that guessing
from memory gets three things wrong; each is called out below as a **gotcha**.

## Which layer to reach for

| You want | Use |
|---|---|
| one call, structured result, no tools | `generateObject` |
| one call, structured result, tools allowed | `generateText` + `output: Output.object(...)` |
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
  output: Output.object({ schema: MySchema }),
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

**Structured output needs a model that supports it.** Only 5 of the 18 `:free`
slugs advertise `structured_outputs`. Free slugs also vanish without notice — a
dead one answers HTTP 404 `"This model is unavailable for free"`, not 429. List
the live ones:

```bash
curl -s https://openrouter.ai/api/v1/models \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
      for (const m of JSON.parse(s).data)
        if (m.id.endsWith(":free") && (m.supported_parameters||[]).includes("structured_outputs"))
          console.log(m.id, m.context_length);
    })'
```

Wrap provider calls and catch `APICallError` (`APICallError.isInstance(error)`).
Uncaught, it prints the entire request body — every message, every schema — as an
unhandled rejection.

## Running it here

Node 22, ESM, no build step: `npx tsx src/index.ts`. Secrets come from the
gitignored repo-root `.env` via `process.loadEnvFile()`, and a real environment
variable always wins so CI never depends on an uncommitted file.
