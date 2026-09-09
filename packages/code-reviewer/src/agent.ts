/**
 * The reviewer, as a library.
 *
 * This module is the deliverable: `reviewDiff` is a plain async function that takes a
 * string and returns a value. No stdin, no printing, no `process.exit`. That is not
 * tidiness — a promptfoo custom provider is a module whose `callApi(prompt, …)` is
 * handed a string and must return `{ output }`, and a reviewer that read stdin or
 * exited the process could not be driven by one at all. The eval environment is out
 * of scope for this change; being *callable* by it is the whole point of the change.
 *
 * `createReviewAgent` is separate from `reviewDiff` because `ToolLoopAgent` is the
 * reusable layer of the AI SDK: constructed once, `.generate()`d many times. An eval
 * sweeping one model over thirty diffs builds one agent, not thirty.
 */
import { createOpenRouter } from '@openrouter/ai-sdk-provider';
import {
  AISDKError,
  APICallError,
  ToolLoopAgent,
  hasToolCall,
  isStepCount,
} from 'ai';
import { changedFiles, validateDiff } from './diff.ts';
import { ReviewerError } from './errors.ts';
import { loadRepoEnv, resolveApiKey, resolveModelId } from './env.ts';
import { SYSTEM_PROMPT, buildUserPrompt } from './prompt.ts';
import { ModelReview, type ReviewOutcome } from './schema.ts';
import { SUBMIT_TOOL_NAME, createTools } from './tools.ts';
import { deriveVerdict, partitionByDiffScope, stripUnbackedEvidence } from './verdict.ts';

/**
 * Seven tool round-trips plus the step that answers.
 *
 * The answer is itself a tool call now, so the budget has to leave a step for it: an
 * agent that spends all eight looking at files stops with tools called and nothing
 * submitted, which surfaces here as `no-output`. Seven rounds is generous for the
 * question "does this diff break a project rule"; the budget bounds cost, it does not
 * hurry the model.
 *
 * The stop condition, not this number, is what normally ends a run — `hasToolCall`
 * fires the moment the review arrives. This is the backstop for a model that reads
 * forever, and `isStepCount` is second in the array for that reason.
 */
const STEP_BUDGET = 8;

/**
 * The wall-clock budget for one review, tool rounds included.
 *
 * Two minutes is generous — the longest successful run measured here was under 40
 * seconds — and generosity is the point: this bound exists to convert a hang into a
 * report, not to hurry a slow model. Raise it with `timeoutMs` for a large diff rather
 * than removing it.
 *
 * It is here because a `:free` slug accepted a request and then held the socket for over
 * ten minutes: no bytes, no error, process alive at 0.6 s of CPU. Nothing in this package
 * bounded that. Phase 5 wires the reviewer into a git hook, where an unbounded wait is
 * strictly worse than a failure — a developer can act on a failure and can only kill a hang.
 */
const DEFAULT_TIMEOUT_MS = 120_000;

/**
 * An abort is not an AI SDK error and does not always arrive as one class.
 * `AbortSignal.timeout` produces a `TimeoutError`; a caller's own signal produces an
 * `AbortError`; either may reach us wrapped. Matching on `name` covers all three, and
 * matching it BEFORE the AISDKError family matters: a wrapped abort reported as
 * `provider` would send someone reading the message to look at the wrong system.
 */
function isAbort(error: unknown): boolean {
  for (let current: unknown = error; current instanceof Error; current = current.cause) {
    if (current.name === 'TimeoutError' || current.name === 'AbortError') return true;
    if (current.cause === undefined || current.cause === null) break;
  }
  return false;
}

/**
 * The shortest true description of why something failed.
 *
 * Both halves of this are load-bearing, and both were learned from a message that was
 * supposed to be one line and printed a whole model response instead:
 *
 * - **Walk to the deepest cause.** `NoObjectGeneratedError.cause` is a `JSONParseError`
 *   whose own message *embeds the entire text it failed to parse* — so the wrapper reads
 *   like a summary and interpolates a dump. One level further down is the `SyntaxError`,
 *   whose message is the actual answer: `Expected property name or '}' at position 2`.
 * - **Then cap it anyway.** The walk fixes the class observed; the cap fixes the class
 *   not yet observed. Any error whose message quotes its input defeats a message budget
 *   that trusts the message, and there are three dozen error classes upstream here.
 *
 * It collapses whitespace rather than cutting at the first newline, and that is a third
 * measured detail. Cutting at the newline produced the message `ZodError: [` for a review
 * missing a required field — the deepest cause there is a Zod error whose message is a
 * pretty-printed array, so the informative part starts on line 2. Flattening keeps the
 * result one line while letting the cap decide how much of it survives, which for that
 * case is enough to name the field: `expected array … path ["findings"]`.
 */
function brief(error: unknown, limit = 160): string {
  let current: unknown = error;
  while (current instanceof Error && current.cause !== undefined && current.cause !== null) {
    current = current.cause;
  }
  const text = current instanceof Error ? `${current.name}: ${current.message}` : String(current);
  const line = text.replace(/\s+/g, ' ').trim();
  return line.length > limit ? `${line.slice(0, limit)}…` : line;
}

export interface ReviewAgentOptions {
  modelId?: string;
  apiKey?: string;
  /** Injected so a test can drive the loop without the filesystem. Defaults to the real tools. */
  tools?: Record<string, unknown>;
  /**
   * Overrides the provider endpoint. This exists for one reason: the failure paths below
   * are the ones that must not leak, and the only honest way to check a failure handler is
   * to watch it handle the failure. `failures.test.ts` points this at a local server that
   * replays the exact malformed response a free slug returned on the first live run —
   * deterministic, offline, and free, where reproducing it against the real endpoint is
   * none of the three.
   */
  baseURL?: string;
  /** Wall-clock budget for the whole loop. Defaults to {@link DEFAULT_TIMEOUT_MS}. */
  timeoutMs?: number;
}

export interface ReviewRun {
  review: ReviewOutcome;
  usage: { inputTokens?: number; outputTokens?: number; totalTokens?: number };
  modelId: string;
  steps: number;
  /** Repo-relative paths the tools actually returned during this run. */
  accessedPaths: string[];
}

/**
 * A configured agent, reusable across diffs.
 *
 * `instructions` — not `system`. `ToolLoopAgent` silently ignores `system`, so that
 * mistake produces an agent with no system prompt that still returns confident,
 * plausible reviews with none of this project's rules applied (SKILL.md:42). It is the
 * failure with no symptom, which is why it is named here rather than trusted to memory.
 *
 * There is no `output:` here, and its absence is the load-bearing part. Structured
 * output sends `response_format: {type: "json_schema"}` in the same request as `tools`,
 * and a model under constrained decoding cannot emit a tool call at all — so the agent
 * answered in one step, having read nothing, every single time. The schema did not go
 * away; it moved to `submitReview`'s `inputSchema`, where the SDK still validates it and
 * nothing suppresses the read tools. `tools.ts` carries the six-run tally.
 */
export function createReviewAgent(options: {
  modelId: string;
  apiKey: string;
  tools: Record<string, unknown>;
  baseURL?: string;
}) {
  const openrouter = createOpenRouter({
    apiKey: options.apiKey,
    ...(options.baseURL === undefined ? {} : { baseURL: options.baseURL }),
  });
  return new ToolLoopAgent({
    model: openrouter.chat(options.modelId),
    instructions: SYSTEM_PROMPT,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- the SDK's Tool type is generic over each tool's schema
    tools: options.tools as any,
    toolChoice: 'auto',
    stopWhen: [hasToolCall(SUBMIT_TOOL_NAME), isStepCount(STEP_BUDGET)],
  });
}

/** How a run ended, from the reviewer's point of view rather than the loop's. */
type Submission =
  | { status: 'submitted'; review: ModelReview }
  /** A `submitReview` call arrived and could not be read as a review. */
  | { status: 'malformed'; raw: string; detail: string }
  /** No `submitReview` call at any step. */
  | { status: 'missing' };

/**
 * Classify what the model actually submitted.
 *
 * A schema violation does NOT arrive as a thrown error, which is the one thing worth
 * knowing here. The SDK records the bad call with `invalid: true` and an `error`, hands
 * the model the failure as a tool result, and carries on — so nothing reaches the catch
 * block below, and the naive read ("no valid review") would report every broken shape as
 * `no-output`. `errors.ts` explains why those must stay apart: an unsubmitted review
 * wants a bigger budget or a better prompt, a broken one wants a different model.
 *
 * On an invalid call `input` is the raw argument *string* rather than an object, so the
 * message can quote what the model actually sent.
 *
 * The *last* `submitReview` call wins. `hasToolCall` ends the run at the step the call
 * arrives in, so there is normally exactly one — but a model that emits two in one step
 * gets its final word honoured, the same rule a human reviewer's last comment gets.
 */
function classifySubmission(steps: readonly { toolCalls?: readonly unknown[] }[]): Submission {
  let latest: Submission = { status: 'missing' };
  for (const step of steps) {
    for (const call of step.toolCalls ?? []) {
      const { toolName, input, invalid, error } = call as {
        toolName?: string;
        input?: unknown;
        invalid?: boolean;
        error?: unknown;
      };
      if (toolName !== SUBMIT_TOOL_NAME) continue;

      // Validated already by the SDK before `execute` ran; parsing again is not distrust
      // of that, it is how the value acquires a type where the tools record is `any`.
      const parsed = invalid === true ? undefined : ModelReview.safeParse(input);
      latest =
        parsed?.success === true
          ? { status: 'submitted', review: parsed.data }
          : {
              status: 'malformed',
              raw: typeof input === 'string' ? input : JSON.stringify(input) ?? '',
              detail: brief(error ?? parsed?.error),
            };
    }
  }
  return latest;
}

/**
 * Review one diff. The exported reviewer.
 *
 * Throws `ReviewerError` for every failure it can name, and never exits. The order of
 * work after the model answers is deliberate:
 *
 *   1. drop findings naming files outside the diff  (a review of this diff, or nothing)
 *   2. strip evidence no tool produced             (a citation, or no citation)
 *   3. derive the verdict from what survived        (arithmetic, not the model's word)
 *
 * Deriving before dropping would let a finding about an untouched file fail the review.
 */
export async function reviewDiff(diff: string, options: ReviewAgentOptions = {}): Promise<ReviewRun> {
  loadRepoEnv();

  const apiKey = options.apiKey ?? resolveApiKey();
  if (apiKey === null || apiKey === undefined || apiKey === '') {
    throw new ReviewerError(
      'no-api-key',
      'OPENROUTER_API_KEY is not set, and the repo root .env does not supply it. ' +
        'A reviewer that cannot reach a model must say so, not pass the diff.',
    );
  }

  const trimmed = diff.trim();
  const unreviewable = validateDiff(trimmed);
  if (unreviewable !== null) {
    throw new ReviewerError(trimmed === '' ? 'empty-diff' : 'diff-too-large', unreviewable);
  }

  const modelId = options.modelId ?? resolveModelId();
  const { tools, accessedPaths } = createTools();
  const agent = createReviewAgent({
    modelId,
    apiKey,
    tools: options.tools ?? tools,
    ...(options.baseURL === undefined ? {} : { baseURL: options.baseURL }),
  });

  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  let submission: Submission;
  let usage: ReviewRun['usage'];
  let steps: number;
  try {
    const result = await agent.generate({
      prompt: buildUserPrompt(trimmed),
      abortSignal: AbortSignal.timeout(timeoutMs),
    });
    // Not `result.output` — there is no structured output any more. The review is the
    // input of the `submitReview` call the model made.
    submission = classifySubmission(result.steps ?? []);
    usage = result.usage ?? {};
    steps = result.steps?.length ?? 0;
  } catch (error) {
    // Every AI SDK error is narrowed to a message here, and the reason is not tidiness.
    // These errors carry the call as enumerable own properties, so whatever prints them
    // prints the request body, every message, the whole schema, and the response headers
    // — `set-cookie` included. SKILL.md:143 names APICallError for this; that guard was
    // too narrow, and the first live run proved it by throwing a different class through
    // the gap. The rule that replaces it: an AISDKError never leaves this function.
    //
    // NOTE THE ABSENT `{ cause: error }`. Attaching the original is the obvious, wrong
    // thing: `util.inspect` follows `[cause]`, so a wrapper with a two-line message
    // reprints the request body and the cookie the moment anything logs the wrapper
    // rather than its `.message` — a test runner, an unhandled rejection, a promptfoo
    // provider's error path. That was measured, not assumed. What debugging actually
    // needs is below in the message: the finish reason and the first 120 characters.
    if (isAbort(error)) {
      throw new ReviewerError(
        'timeout',
        `model ${modelId} did not finish within ${timeoutMs} ms and was aborted. ` +
          'A slow model is a failed review, not a passing one.',
      );
    }
    // No branch for `InvalidToolInputError`: a schema violation in `submitReview` never
    // reaches here. The SDK records it on the tool call and continues the loop, so it is
    // `classifySubmission` that notices. The family backstop below would still catch it
    // if a future version of the SDK started throwing instead.
    if (APICallError.isInstance(error)) {
      throw new ReviewerError(
        'provider',
        `model ${modelId} refused the call (HTTP ${error.statusCode ?? '?'}): ${error.message}`,
      );
    }
    // A degraded free slug answers HTTP 200 with the error in the body, so the SDK's own
    // validation is what notices rather than a status check — and it does so through
    // whichever of its three dozen error classes fits. Catching the family means a new
    // one cannot reopen the leak.
    if (AISDKError.isInstance(error)) {
      throw new ReviewerError('provider', `model ${modelId} failed: ${error.name}: ${brief(error)}`);
    }
    // Not the SDK's: our own bug. It carries no request body, so it is allowed to escape
    // whole — the stack is the useful part and nothing here can improve on it.
    throw error;
  }

  if (submission.status === 'malformed') {
    throw new ReviewerError(
      'malformed-output',
      `model ${modelId} called ${SUBMIT_TOOL_NAME} with input that does not fit the ` +
        `schema: ${submission.detail}. ` +
        `First 120 chars: ${JSON.stringify(submission.raw.slice(0, 120))}`,
    );
  }
  if (submission.status === 'missing') {
    // Two different runs land here and the message has to fit both: a budget exhausted
    // mid-tool-loop, and a model that wrote its review as prose and never called
    // `submitReview` at all. Neither is a pass.
    throw new ReviewerError(
      'no-output',
      `model ${modelId} stopped after ${steps} step(s) without calling ${SUBMIT_TOOL_NAME}. ` +
        `The step budget is ${STEP_BUDGET}; an unsubmitted review is an unfinished review, not a pass.`,
    );
  }

  const output = submission.review;
  const { kept, dropped } = partitionByDiffScope(output.findings, changedFiles(trimmed));
  const { findings, stripped } = stripUnbackedEvidence(kept, accessedPaths);

  const review: ReviewOutcome = {
    verdict: deriveVerdict(findings),
    summary: output.summary,
    findings,
    dropped: dropped.length,
    droppedFiles: dropped.map((finding) => finding.file),
    strippedEvidence: stripped,
  };

  return { review, usage, modelId, steps, accessedPaths: [...accessedPaths] };
}
