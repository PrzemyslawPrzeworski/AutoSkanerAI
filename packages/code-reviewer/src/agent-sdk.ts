/**
 * The same reviewer, on the Claude Agent SDK against Bedrock.
 *
 * This is the "ready-made" half of the comparison. `agent.ts` assembles a loop out of AI
 * SDK parts — a model, a tool registry, a step budget, a submit tool — and owns every
 * mechanism in it. Here the loop, the tools, the transport and the credential all belong
 * to a `claude` subprocess, and what this module owns is *bounding* it: which tools exist,
 * which calls are permitted, how long it may run, what it may spend, and what it is
 * allowed to know about this machine.
 *
 * Everything that decides whether a review is any good is shared with the other runner and
 * imported, not reimplemented: the schema, the prompt, the diff scope check, the evidence
 * check, the verdict arithmetic. That is the only way `pick.md` can be about the two SDKs
 * rather than about two different reviewers.
 *
 * Three things are genuinely different here, and each one is a bound this file has to
 * supply by hand:
 *
 *   1. **There is no timeout option.** `abortController` (sdk.d.ts:1401) plus a timer is
 *      the caller's job. `ReviewOptions.timeoutMs` is part of the shared contract, so a
 *      wall-clock bound the AI SDK gave away for free is assembled below.
 *   2. **The environment is inherited by a child process.** `options.env` REPLACES rather
 *      than merges (sdk.d.ts:1512), and the thing being spawned is a general-purpose agent
 *      running in a repository whose root holds a live API key. See `subprocessEnv`.
 *   3. **The credential is a short-lived SSO session, not a secret.** It cannot be put in
 *      CI, and its expiry looks exactly like working configuration. That asymmetry is the
 *      single most consequential row in the comparison, and it is recorded in
 *      `.claude/skills/agent-sdk/SKILL.md` under "The auth path".
 *
 * Offline by construction, this module is not: `query()` spawns a subprocess. Nothing here
 * may be exercised by `npm test`, which the gates run on every commit. The live path lives
 * in `agent-sdk.live.test.ts` behind the same switch `injection.test.ts` uses; the pure
 * pieces — `subprocessEnv`, `buildReviewSession`, `readReview` — are tested offline in
 * `agent-sdk.test.ts`, which is where the option object's shape is pinned.
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import type { Options } from '@anthropic-ai/claude-agent-sdk';
import { z } from 'zod';
import { changedFiles, validateDiff } from './diff.ts';
import { ReviewerError } from './errors.ts';
import { decideToolUse, toPreToolUseOutput } from './permission.ts';
import { buildSystemPrompt, buildUserPrompt } from './prompt.ts';
import type { ToolNaming } from './prompt.ts';
import { REPO_ROOT } from './repo.ts';
import type { ReviewOptions, ReviewRun, ReviewUsage } from './reviewer.ts';
import { ModelReview } from './schema.ts';
import type { ReviewOutcome } from './schema.ts';
import { createStreamCollector } from './stream.ts';
import type { StreamResult } from './stream.ts';
import { deriveVerdict, partitionByDiffScope, stripUnbackedEvidence } from './verdict.ts';

export type { ReviewOptions, ReviewRun, ReviewUsage } from './reviewer.ts';

const RUNNER_ID = 'agent-sdk' as const;

/**
 * Bedrock's id for Sonnet 5 in `eu-central-1`, verified ACTIVE there on 2026-09-10 via
 * `aws bedrock list-foundation-models`.
 *
 * The `eu.` prefix is a Bedrock cross-region inference profile, not part of the model name:
 * `Options.model`'s own examples are bare (`'claude-sonnet-5'`, sdk.d.ts:1808), so the
 * prefix is a property of the provider this runner happens to reach the model through.
 * Drop it and the call fails at Bedrock, not in the SDK.
 *
 * Overridden with `CODE_REVIEW_BEDROCK_MODEL`, deliberately NOT with `CODE_REVIEW_MODEL`.
 * That variable holds an OpenRouter slug for the other runner, and a comparison run that
 * set it once for both would hand `dots-studio/dots-3-note-preview:free` to Bedrock. One
 * variable naming two providers' models is the failure `env.ts:44` already documents for
 * `OPENROUTER_MODEL`.
 */
export const DEFAULT_BEDROCK_MODEL = 'eu.anthropic.claude-sonnet-5';

/**
 * Turn budget. Eight, to match `agent.ts`'s `STEP_BUDGET` — and the two units are not the
 * same thing.
 *
 * An AI SDK step is one model call plus its tool results. An Agent SDK turn is a user
 * message and the assistant response to it, which may contain several tool calls. So eight
 * turns is a *looser* bound than eight steps, not an equal one. It is matched anyway
 * because the number's job is to stop a runaway loop, and picking two different numbers
 * would put a knob in the comparison that nobody set deliberately. `ReviewRun.steps`
 * carries `num_turns` and `reviewer.ts` says in as many words that the unit is the
 * runner's own.
 */
const TURN_BUDGET = 8;

/** Wall-clock bound, matching `agent.ts` so the two runners fail slow calls alike. */
const DEFAULT_TIMEOUT_MS = 120_000;

/**
 * Spend ceiling for one review, enforced by the SDK (`maxBudgetUsd`, sdk.d.ts:1778).
 *
 * A Sonnet review of a small diff costs cents; fifty of them is the point at which
 * something has gone wrong rather than the point at which a review gets expensive. Note
 * the failure mode: exceeding this returns an `error_max_budget_usd` **result**, not a
 * thrown error, so a runner that only read `result.result` would report a budget kill as
 * an empty review. `readReview` refuses on any non-success subtype for that reason.
 */
const MAX_BUDGET_USD = 0.5;

/**
 * What the prompt calls the tools — the SDK's own built-in names, and no submit tool.
 *
 * `submitTool: null` selects the prompt's "deliver one JSON object" paragraph, which is
 * the honest description of what `outputFormat` does: the transport constrains the answer,
 * so asking the model to remember to call something would be describing a rule it cannot
 * break. `prompt.ts:64` is where that fork is documented.
 *
 * `Grep` has to be named here and in `options.tools` both. The SDK's note at
 * sdk.d.ts:1502 says native builds may supply search through `Bash` `find`/`grep` instead
 * of the dedicated tool — and `Bash` is refused by `decideToolUse`, so the symptom of
 * getting this wrong is not an error. It is a reviewer that quietly never searches.
 */
export const AGENT_SDK_TOOLS: ToolNaming = {
  readFile: 'Read',
  search: 'Grep',
  submitTool: null,
};

/**
 * The environment the `claude` subprocess is allowed to see.
 *
 * Two facts make this a security boundary rather than plumbing. First, `options.env`
 * REPLACES the subprocess environment entirely (sdk.d.ts:1512, verbatim: *"it is not
 * merged with `process.env`. Spread `process.env` yourself if the subprocess still needs
 * inherited variables like `PATH`, `HOME`"*) — so setting one variable unsets `PATH` and
 * every AWS credential at once, and the spread below is load-bearing rather than tidy.
 * Second, what is spread in is a general-purpose coding agent's whole view of this
 * machine.
 *
 * `OPENROUTER_API_KEY` is deleted, and the reason is narrow enough to state exactly.
 * `repo.ts` stops the *tools* from reading the root `.env`; nothing about that stops the
 * key from being handed to the subprocess as a variable it can print. The two layers guard
 * different doors. The key is also of no use to this runner — it authenticates the other
 * one — so there is no cost to withholding it, which is the whole argument: a credential
 * with no purpose in a process should not be in it.
 *
 * Everything else is kept, and kept knowingly. `PATH` because the subprocess has to find
 * its own runtime; `AWS_PROFILE` / `AWS_REGION` / `CLAUDE_CODE_USE_BEDROCK` because they
 * *are* the credential path. This is not an allow-list, and it should not be read as one:
 * it is `process.env` minus one name. An allow-list here would be a better boundary and a
 * worse one to maintain blind — the subprocess needs variables (`HOME`, `USERPROFILE`,
 * `APPDATA`, proxy settings, the SSO cache location) that are platform-specific, and a
 * list missing one fails as a puzzling startup error rather than as a missing entry.
 */
export function subprocessEnv(source: NodeJS.ProcessEnv = process.env): Record<string, string> {
  const env: Record<string, string> = {};
  for (const [name, value] of Object.entries(source)) {
    if (name === 'OPENROUTER_API_KEY') continue;
    if (value !== undefined) env[name] = value;
  }
  return env;
}

/**
 * Everything the SDK is told about this review, as one value.
 *
 * The unit of reuse in this SDK is an `Options` object — there is no agent to construct
 * and nothing with a `.generate()`. Building it in a named exported function rather than
 * inline in `reviewDiffWithAgentSdk` is what lets `agent-sdk.test.ts` assert the
 * containment configuration without spawning anything: every claim this runner makes about
 * what the subprocess can do is a property of the object returned here.
 */
export function buildReviewSession(session: {
  modelId: string;
  abortController: AbortController;
}): Options {
  return {
    model: session.modelId,
    abortController: session.abortController,

    // A bare string, so the reviewer's instructions REPLACE Claude Code's own persona
    // rather than being appended to it. The shared prompt is the whole of what this agent
    // is; inheriting "you are an interactive CLI tool for software engineering tasks" on
    // top of it would make the two runners' system prompts differ by a paragraph nobody
    // wrote.
    systemPrompt: buildSystemPrompt(AGENT_SDK_TOOLS),

    // `[]` — SDK isolation mode (sdk.d.ts:2086). Deliberate, and the trade is real: this
    // subprocess loads no `.claude/settings.json` and no CLAUDE.md.
    //
    // Losing the settings is pure gain. This repo's own `PostToolUse` hook runs prettier
    // and the entire frontend suite after every edit; a reviewer cannot edit, so it should
    // never fire, and inheriting it would mean relying on the tool restriction being right
    // in order for a *build* not to run inside a review.
    //
    // Losing the auto-injected CLAUDE.md sounds like the bigger loss and is not, because
    // the rules stay reachable: `repo.ts`'s allow-list names all three CLAUDE.md files
    // precisely so `Read` can fetch them on demand. Auto-injection would also be an
    // advantage the other runner cannot have, and a comparison where one reviewer is
    // silently handed the project's rules is not measuring the SDKs.
    settingSources: [],

    // Which tools EXIST. Not a policy — `tools` cannot say "Read, but only under
    // backend/src" — and not a substitute for the hook, which is why both are here
    // (SKILL.md gotcha 3). A tool that does not exist cannot be argued into existence;
    // a hook can be misconfigured. Two mechanisms, two ways to be wrong, and they would
    // have to fail together.
    tools: ['Read', 'Grep'],

    // Redundant given `tools` above — these three are already absent — and kept as the
    // second lock rather than as a claim. It starts mattering the moment `tools` is
    // widened or replaced with the `claude_code` preset, which is exactly when someone
    // would forget.
    disallowedTools: ['Write', 'Edit', 'Bash'],

    // Nothing is auto-approved. `allowedTools` bypasses the prompt by tool name, and
    // whether it also bypasses the hook is undocumented (SKILL.md gotcha 4); leaving it
    // empty means no configuration here depends on that answer. The hook decides both
    // directions explicitly instead, which is why `permissionMode: 'default'` does not
    // hang: with no `canUseTool` handler, an 'ask' is terminal (sdk.d.ts:4879), and no
    // call reaches 'ask' anyway.
    permissionMode: 'default',

    hooks: {
      PreToolUse: [
        {
          hooks: [
            // `HookCallback` is declared as returning a Promise (sdk.d.ts:859), so this is
            // `async` for the signature and not because anything here waits.
            async (input) => {
              // `HookInput` is a 33-way union and `tool_name` exists on one member, so the
              // narrowing is required by the compiler and not defensive. The refusal on the
              // impossible branch still matters: an input this function cannot read is an
              // input it cannot judge, and the safe answer to "I don't know what this is"
              // is no.
              if (input.hook_event_name !== 'PreToolUse') {
                return toPreToolUseOutput({
                  allow: false,
                  reason: `the reviewer's permission hook was invoked for ${input.hook_event_name}, which it cannot judge`,
                });
              }
              return toPreToolUseOutput(decideToolUse(input.tool_name, input.tool_input));
            },
          ],
        },
      ],
    },

    maxTurns: TURN_BUDGET,
    maxBudgetUsd: MAX_BUDGET_USD,

    // The repo root, which is what makes every relative path in the review mean what the
    // diff means by it. It is also the directory holding `.env`, which is why `Grep` with
    // no `path` — a search of exactly this directory — is refused rather than defaulted.
    cwd: REPO_ROOT,
    env: subprocessEnv(),

    // Raw JSON Schema, not the Zod object: `OutputFormat` (sdk.d.ts:2290) has one member
    // and its `schema` is `Record<string, unknown>`. `draft-7` because that is what tool
    // input schemas are validated as.
    //
    // This is implemented as an end-turn tool (sdk.d.ts:1957), which has two consequences
    // worth having in the same comment as the option that causes them: the review arrives
    // in `result.structured_output` and NOT in `result.result`, and — unlike the AI SDK's
    // `Output.object` — it does NOT stop the model reading files first.
    //
    // THE OUTPUT-CHANNEL DECISION, and why there is no second column to compare against.
    // `tools.ts:87` records the AI SDK measurement this option was suspected of repeating:
    // `Output.object` sent `response_format` alongside `tools` and constrained decoding left
    // the model no channel for a tool call, so the read tools were advertised and
    // unreachable — six runs, no overlap, `output` set gave 1 step and 0 files every time.
    // The planned answer here was an A/B against a `createSdkMcpServer` submit tool, three
    // runs each, keep whichever preserved tool use.
    //
    // It does not reproduce. Measured on 2026-09-10, `eu.anthropic.claude-sonnet-5`, this
    // option set on every run, one run per fixture:
    //
    //     bad.diff           2 turns,  0 files read   (nothing in it needs the repo)
    //     cross-file.diff    6 turns,  1 file  read   (CLAUDE.md, cited with a line number)
    //     vendor-detail.diff 7 turns,  9 files read   (found the duplicated base URL)
    //
    // Against the AI SDK's suppressed column — 1 step, 0 files, invariably — that is not a
    // close result, and the two zeroes differ in kind: `bad.diff` carries both its defects
    // inside the diff, and a reviewer that reads nothing to review it is being efficient
    // rather than blind.
    //
    // So the submit-tool arm was never built. The A/B's decision criterion was "which
    // channel preserves tool use", `outputFormat` satisfies it, and building the other arm
    // to watch it also pass measures nothing — a tally of one configuration against a
    // documented, measured, differently-shaped defect in the other SDK is the comparison.
    // Two things are given up by that and are worth naming rather than discovering later:
    // this package has no experience of `createSdkMcpServer`, and `outputFormat`'s retry
    // loop is a real fragility — it makes five attempts and then returns
    // `error_max_structured_output_retries`, where a submit tool would hand the model a tool
    // result it could act on. `readReview` reports that subtype by name for exactly this
    // reason; see `ANSWER_TOOL` in `permission.ts` for the run that found it.
    //
    // `agent-sdk.live.test.ts` asserts `accessedPaths` is non-empty on every live run,
    // because the regression this decision rules out is invisible in the output: a run that
    // read nothing answers in perfect shape, and an empty access log makes
    // `stripUnbackedEvidence` report "0 stripped" exactly as an honest citation would.
    outputFormat: { type: 'json_schema', schema: z.toJSONSchema(ModelReview, { target: 'draft-7' }) },
  };
}

/**
 * A diff in, a review out. Satisfies `Reviewer`; the proof is the assignment in `index.ts`.
 *
 * The post-model order is the same three steps as `agent.ts:357`, in the same sequence, and
 * the sequence is load-bearing:
 *
 *   1. drop findings about files this diff does not touch
 *   2. strip citations no tool actually returned
 *   3. derive the verdict from what survived
 *
 * Deriving before dropping would let a finding about an untouched file fail the review.
 */
export async function reviewDiffWithAgentSdk(
  diff: string,
  options: ReviewOptions = {},
): Promise<ReviewRun> {
  const trimmed = diff.trim();
  const unreviewable = validateDiff(trimmed);
  if (unreviewable !== null) {
    throw new ReviewerError(trimmed === '' ? 'empty-diff' : 'diff-too-large', unreviewable);
  }

  const modelId = options.modelId ?? resolveBedrockModel();
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  // Hand-rolled, because there is no timeout on `Options`. `AbortSignal.timeout` — what
  // `agent.ts` uses — is not enough here: the SDK takes a controller, not a signal, so the
  // timer has to call `.abort()` itself, and it has to be cleared in a `finally` or a
  // 120-second handle keeps the process alive after a fast review has already returned.
  const abortController = new AbortController();
  const timer = setTimeout(() => abortController.abort(), timeoutMs);

  const collector = createStreamCollector();
  try {
    for await (const message of query({
      prompt: buildUserPrompt(trimmed),
      options: buildReviewSession({ modelId, abortController }),
    })) {
      collector.observe(message);
    }
  } catch (error) {
    // The subprocess boundary changes what failure looks like. There is no error class
    // carrying a request body here — the AI SDK's leak, which `agent.ts:294` exists to
    // contain, has no analogue — so these messages are built from `.message` and nothing
    // is deliberately withheld. What replaces that hazard is a vaguer one: a spawn
    // failure, a missing `claude` binary and an expired SSO session all arrive as plain
    // errors, and only the text tells them apart.
    if (abortController.signal.aborted) {
      throw new ReviewerError(
        'timeout',
        `model ${modelId} did not finish within ${timeoutMs} ms and was aborted. ` +
          'A slow model is a failed review, not a passing one.',
      );
    }
    if (looksLikeCredentialFailure(error)) {
      // The dangerous case, and the reason this branch exists at all: an expired SSO
      // session leaves `AWS_PROFILE` set, `~/.aws/config` intact, and every check that
      // tests for a non-empty variable passing. There is no variable to look at that would
      // have caught it — the capability is what failed, so the attempt is the check. Same
      // shape as this repo's `require_java`, which had to start looking for `javac`
      // instead of for `JAVA_HOME`.
      throw new ReviewerError(
        'no-api-key',
        `no usable AWS credential for Bedrock (model ${modelId}): ${brief(error)}. ` +
          'A short-lived SSO session that has expired looks exactly like working ' +
          'configuration; re-authenticate rather than editing the profile. ' +
          'A reviewer that cannot reach a model must say so, not pass the diff.',
      );
    }
    throw new ReviewerError('provider', `model ${modelId} failed: ${brief(error)}`);
  } finally {
    clearTimeout(timer);
  }

  const result = requireResult(collector.result, modelId);
  const review = readReview(result, modelId);
  const accessedPaths = collector.accessedPaths;

  const { kept, dropped } = partitionByDiffScope(review.findings, changedFiles(trimmed));
  const { findings, stripped } = stripUnbackedEvidence(kept, accessedPaths);

  const outcome: ReviewOutcome = {
    verdict: deriveVerdict(findings),
    summary: review.summary,
    findings,
    dropped: dropped.length,
    droppedFiles: dropped.map((finding) => finding.file),
    strippedEvidence: stripped,
  };

  return {
    review: outcome,
    // `total_cost_usd` comes through here and the other runner leaves `costUsd` absent.
    // That is the asymmetry `ReviewUsage` documents, not an oversight in one of them.
    usage: result.usage,
    modelId,
    runner: RUNNER_ID,
    steps: result.turns,
    accessedPaths: [...accessedPaths],
  };
}

/**
 * The terminal result, or a refusal. A stream that ended without one is not a review.
 *
 * Separate from `readReview` only so the type narrows — `readReview` throwing on `null`
 * would leave every later use of the result nullable to the compiler, and widening it back
 * with a non-null assertion would be the same claim made where nothing checks it.
 *
 * Exported for the same reason `decideToolUse` is: every branch below turns a shape into a
 * named refusal, and each of those refusals is a case where the alternative is reporting a
 * clean pass. A `StreamResult` is a plain value, so all of it can be asserted offline; going
 * through `reviewDiffWithAgentSdk` to reach it would need a subprocess and a credential to
 * test claims that involve neither.
 */
export function requireResult(result: StreamResult | null, modelId: string): StreamResult {
  if (result === null) {
    throw new ReviewerError(
      'no-output',
      `model ${modelId} produced no result message. The stream ended without a verdict, ` +
        'which is an unfinished review rather than an approval.',
    );
  }
  return result;
}

/**
 * The review, out of the terminal result — or a `ReviewerError` naming what arrived instead.
 *
 * Two failures are distinguished on purpose, because they ask for different responses and
 * either could otherwise be reported as a clean pass:
 *
 *   - an error subtype, including `error_max_budget_usd` and `error_max_turns` — these
 *     arrive as *results*, not as thrown errors, so nothing else would notice;
 *   - output that is absent from the structured channel, or present and not `ModelReview`.
 */
export function readReview(result: StreamResult, modelId: string): ModelReview {
  if (!result.ok) {
    // Reported by the SDK's own name for it. `error_max_budget_usd` and `error_max_turns`
    // are the two expected members and they mean different things to whoever reads this —
    // one asks for a bigger ceiling, the other for a shorter prompt — so the subtype is
    // carried verbatim instead of being flattened into "the provider failed".
    throw new ReviewerError(
      'provider',
      `model ${modelId} ended on "${result.subtype}" after ${result.turns} turn(s). ` +
        (result.subtype === 'error_max_budget_usd'
          ? `The budget is $${MAX_BUDGET_USD}. `
          : result.subtype === 'error_max_turns'
            ? `The turn budget is ${TURN_BUDGET}. `
            : '') +
        'An unfinished review is not a passing one.',
    );
  }

  if (result.structuredOutput === undefined || result.structuredOutput === null) {
    // Where a working `outputFormat` run lands if the option ever stops being honoured —
    // the answer would then be prose in `result.text`, which nothing downstream can parse.
    // Named as `no-output` rather than `malformed-output` because there is nothing wrong
    // with what the model said; it went to the wrong channel.
    throw new ReviewerError(
      'no-output',
      `model ${modelId} returned no structured output after ${result.turns} turn(s). ` +
        'The review is read from the end-turn tool attachment, not from the final message; ' +
        `${result.text === null ? 'no' : `${result.text.length} chars of`} prose arrived instead.`,
    );
  }

  const parsed = ModelReview.safeParse(result.structuredOutput);
  if (!parsed.success) {
    throw new ReviewerError(
      'malformed-output',
      `model ${modelId} returned structured output that does not fit the schema: ` +
        `${parsed.error.issues.map((issue) => `${issue.path.join('.')}: ${issue.message}`).join('; ')}`,
    );
  }
  return parsed.data;
}

/** The Bedrock model id, from the environment or the verified default. */
function resolveBedrockModel(): string {
  const configured = process.env['CODE_REVIEW_BEDROCK_MODEL'];
  return configured === undefined || configured === '' ? DEFAULT_BEDROCK_MODEL : configured;
}

/**
 * Whether a thrown error is the credential path failing rather than the model.
 *
 * Text matching, which is as weak as it looks — the subprocess reports auth failures as
 * prose and there is no typed error to branch on. It is worth having anyway: the
 * consequence of a miss is a `provider` error instead of a `no-api-key` one, both of which
 * exit 2 and neither of which can be mistaken for a review. Only the wording of the advice
 * is at stake, so the pattern is allowed to be approximate.
 */
function looksLikeCredentialFailure(error: unknown): boolean {
  const message = brief(error, 600).toLowerCase();
  return (
    message.includes('expiredtoken') ||
    message.includes('token has expired') ||
    message.includes('credential') ||
    message.includes('unable to locate') ||
    message.includes('sso session') ||
    message.includes('accessdenied') ||
    message.includes('unrecognizedclient') ||
    message.includes('not authorized')
  );
}

/**
 * An error reduced to one short line.
 *
 * Deliberately NOT `agent.ts`'s `brief`, and not shared with it. That one walks the
 * `.cause` chain because an AI SDK error hides the useful text several levels down and
 * carries a request body it must not print; nothing on this path does either. Two small
 * functions with the same name and different reasons is not the duplication project rule 3
 * is about — that rule is about vendor detail, and importing the other runner's private
 * helper would couple the two modules the comparison depends on keeping apart.
 */
function brief(error: unknown, limit = 200): string {
  const text = error instanceof Error ? error.message : String(error);
  const collapsed = text.replace(/\s+/g, ' ').trim();
  return collapsed.length > limit ? `${collapsed.slice(0, limit)}…` : collapsed;
}
