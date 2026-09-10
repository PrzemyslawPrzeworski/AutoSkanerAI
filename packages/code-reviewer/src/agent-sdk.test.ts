/**
 * The agent-sdk runner's configuration, tested without running it.
 *
 * `query()` spawns a `claude` subprocess, so nothing in this file may call it — the gates
 * run `npm test` on every commit and a suite that needs a model, a network or an AWS
 * session is a suite that fails for reasons unrelated to the commit. The live path is
 * `agent-sdk.live.test.ts`.
 *
 * What is left is more than a consolation prize. Every containment claim this runner makes
 * is a property of the `Options` object `buildReviewSession` returns, every claim about what
 * the subprocess knows is a property of `subprocessEnv`'s output, and every way a run can
 * fail to produce a review is a property of a `StreamResult`. All three are values, and a
 * value can be asserted. The one thing these tests cannot show is that the SDK *honours* the
 * object — that a `tools` list really removes `Bash`, that `outputFormat` really leaves the
 * read tools reachable. Those are the live measurements; `agent-sdk.live.test.ts` makes the
 * second one and `SKILL.md` gotcha 8 records the answer.
 *
 * The assertions below are deliberately about the dangerous direction. "`tools` contains
 * Read" is nearly free; "`allowedTools` is absent" is the one that would rot silently,
 * because adding it looks like a fix for a reviewer that cannot read and is instead a
 * bypass of the only policy in the system.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { HookInput, Options } from '@anthropic-ai/claude-agent-sdk';
import {
  AGENT_SDK_TOOLS,
  DEFAULT_BEDROCK_MODEL,
  buildReviewSession,
  readReview,
  requireResult,
  subprocessEnv,
} from './agent-sdk.ts';
import { REPO_ROOT } from './repo.ts';
import type { StreamResult } from './stream.ts';

function session(): Options {
  return buildReviewSession({
    modelId: DEFAULT_BEDROCK_MODEL,
    abortController: new AbortController(),
  });
}

/**
 * The `PreToolUse` callback as the SDK will actually reach it — pulled out of the nested
 * matcher array rather than imported, so a hook wired into the wrong place in the options
 * object is a failing test and not a silently absent policy.
 */
function preToolUseHook(options: Options) {
  const matchers = options.hooks?.PreToolUse;
  assert.ok(matchers !== undefined && matchers.length > 0, 'no PreToolUse matcher is installed');
  const hook = matchers[0]?.hooks[0];
  assert.ok(hook !== undefined, 'the PreToolUse matcher installs no callback');
  return hook;
}

/**
 * A `PreToolUseHookInput`, minimally. Cast because `HookInput` is a 33-way union over a
 * `BaseHookInput` of session bookkeeping the decision does not read; supplying real values
 * for `transcript_path` and friends would be inventing precision.
 */
function preToolUseInput(toolName: string, toolInput: unknown): HookInput {
  return {
    hook_event_name: 'PreToolUse',
    tool_name: toolName,
    tool_input: toolInput,
    tool_use_id: 'tu_1',
    session_id: 's_1',
    transcript_path: '',
    cwd: REPO_ROOT,
  } as HookInput;
}

// --- The environment handed to the subprocess -------------------------------------------

test('subprocessEnv withholds OPENROUTER_API_KEY from the subprocess', () => {
  // `repo.ts` stops the reviewer's *tools* from reading the root `.env`. Nothing about that
  // stops the key being handed to the child process as a variable it can simply print, and
  // the child is a general-purpose coding agent. Two different doors.
  const env = subprocessEnv({ PATH: '/usr/bin', OPENROUTER_API_KEY: 'sk-or-v1-not-a-real-key' });
  assert.equal('OPENROUTER_API_KEY' in env, false);
  assert.deepEqual(Object.values(env), ['/usr/bin']);
});

test('subprocessEnv keeps the variables the subprocess needs to start and authenticate', () => {
  // The spread is load-bearing, not tidy: `options.env` REPLACES the environment rather than
  // merging with it (sdk.d.ts:1512), so dropping `PATH` here would leave the subprocess
  // unable to find its own runtime, and dropping the AWS variables would leave it unable to
  // reach Bedrock — with an error about credentials, not about configuration.
  const env = subprocessEnv({
    PATH: '/usr/bin',
    AWS_PROFILE: 'przemyslawprzeworski',
    AWS_REGION: 'eu-central-1',
    CLAUDE_CODE_USE_BEDROCK: '1',
  });
  assert.deepEqual(env, {
    PATH: '/usr/bin',
    AWS_PROFILE: 'przemyslawprzeworski',
    AWS_REGION: 'eu-central-1',
    CLAUDE_CODE_USE_BEDROCK: '1',
  });
});

test('subprocessEnv drops unset variables rather than passing them as undefined', () => {
  // `process.env` on Node types values as `string | undefined`, and an explicit
  // `{ FOO: undefined }` in the subprocess environment is not the same thing as `FOO` being
  // absent from it.
  const env = subprocessEnv({ PATH: '/usr/bin', NOT_SET: undefined });
  assert.deepEqual(env, { PATH: '/usr/bin' });
});

test('subprocessEnv does not mutate the environment it was given', () => {
  const source = { PATH: '/usr/bin', OPENROUTER_API_KEY: 'sk-or-v1-not-a-real-key' };
  subprocessEnv(source);
  assert.equal(source['OPENROUTER_API_KEY'], 'sk-or-v1-not-a-real-key');
});

test('the session hands the subprocess an environment with no OpenRouter key', () => {
  // The wiring, not the helper: `subprocessEnv` being right is worth nothing if the options
  // object passes `process.env` straight through.
  assert.equal('OPENROUTER_API_KEY' in (session().env ?? {}), false);
});

// --- Containment: what exists, and who decides -----------------------------------------

test('only Read and Grep exist, and Grep is named explicitly', () => {
  // Naming `Grep` is not redundant with allowing it. The SDK's note at sdk.d.ts:1502 says a
  // native build may supply search through `Bash` `find`/`grep` when the dedicated tool is
  // not listed — and `Bash` is refused, so the symptom of omitting `Grep` is a reviewer that
  // silently never searches and therefore cites less.
  assert.deepEqual(session().tools, ['Read', 'Grep']);
});

test('nothing is auto-approved — allowedTools is absent, so no call bypasses the hook', () => {
  // The assertion most likely to be "fixed" by someone debugging a reviewer that reads
  // nothing. `allowedTools` auto-approves by tool name before any policy runs, and whether
  // it short-circuits the PreToolUse hook is undocumented in sdk.d.ts. Adding `Read` here
  // would look like the cure and would remove the only per-path check in the system; the
  // actual cure was making the hook answer 'allow' explicitly.
  assert.equal(session().allowedTools, undefined);
  assert.equal(session().canUseTool, undefined);
});

test('permissionMode is default, which is a refusal rather than a hang when headless', () => {
  const options = session();
  assert.equal(options.permissionMode, 'default');
  // Neither of the two modes that would disarm the policy.
  assert.notEqual(options.permissionMode, 'bypassPermissions');
  assert.notEqual(options.permissionMode, 'acceptEdits');
});

test('the write and execute tools are disallowed by name as well as absent from tools', () => {
  assert.deepEqual(session().disallowedTools, ['Write', 'Edit', 'Bash']);
});

test('the installed hook allows an allow-listed read', async () => {
  // The direction that fails silently. A hook returning `{}` — no opinion — falls through to
  // the normal permission flow, which in a headless run with nothing pre-approved is a
  // refusal; the review then comes back correctly shaped having read nothing, with every
  // citation stripped for want of an access log. So the allow has to be explicit, and this
  // asserts the explicit form arrives through the wiring.
  const hook = preToolUseHook(session());
  const output = await hook(
    preToolUseInput('Read', { file_path: `${REPO_ROOT}/CLAUDE.md` }),
    'tu_1',
    { signal: new AbortController().signal },
  );
  assert.deepEqual(output, {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'allow',
      permissionDecisionReason: 'allow-listed: read-only, inside the repository',
    },
  });
});

test('the installed hook denies a write, carrying the reason the model will read', async () => {
  const hook = preToolUseHook(session());
  const output = await hook(
    preToolUseInput('Write', { file_path: `${REPO_ROOT}/CLAUDE.md`, content: 'x' }),
    'tu_1',
    { signal: new AbortController().signal },
  );
  const decision = (output as { hookSpecificOutput?: Record<string, unknown> }).hookSpecificOutput;
  assert.equal(decision?.['permissionDecision'], 'deny');
  assert.match(String(decision?.['permissionDecisionReason']), /Write is not available/);
});

test('the installed hook refuses an event it cannot judge instead of falling through', async () => {
  // `HookInput` is a 33-way union and this matcher subscribes to one member. Should a future
  // SDK deliver a different event to it, the narrowing branch is what runs — and an input
  // this function cannot read is an input it cannot judge, so the answer is no. Written as a
  // deny rather than as `{}` for the reason above: `{}` is not a refusal.
  const hook = preToolUseHook(session());
  const output = await hook({ hook_event_name: 'PostToolUse' } as HookInput, 'tu_1', {
    signal: new AbortController().signal,
  });
  const decision = (output as { hookSpecificOutput?: Record<string, unknown> }).hookSpecificOutput;
  assert.equal(decision?.['permissionDecision'], 'deny');
  assert.match(String(decision?.['permissionDecisionReason']), /PostToolUse/);
});

// --- Isolation, bounds, and the answer channel -----------------------------------------

test('filesystem settings are disabled, so this repo’s own edit hooks cannot fire', () => {
  // `[]` is SDK isolation mode (sdk.d.ts:2086). Omitting the option loads every filesystem
  // setting, which here includes a `PostToolUse` hook that runs prettier and the whole
  // frontend suite — a build, inside a review, gated only on the tool restriction holding.
  assert.deepEqual(session().settingSources, []);
});

test('the review is bounded in turns and in dollars, because there is no timeout option', () => {
  const options = session();
  assert.equal(options.maxTurns, 8);
  assert.ok(
    typeof options.maxBudgetUsd === 'number' && options.maxBudgetUsd > 0,
    'a runaway loop needs a spend ceiling as well as a turn ceiling',
  );
  // The wall clock is the caller's job — `Options` has no request timeout — so what the SDK
  // gets is the controller, and the timer lives in `reviewDiffWithAgentSdk`.
  assert.ok(options.abortController instanceof AbortController);
});

test('the working directory is the repo root, which is what makes diff paths mean anything', () => {
  assert.equal(session().cwd, REPO_ROOT);
});

test('the system prompt replaces Claude Code’s persona and names the SDK’s own tools', () => {
  const prompt = session().systemPrompt;
  // A bare string, not `{ type: 'preset', append: … }`: the shared prompt is the whole of
  // what this agent is, and appending it to "you are an interactive CLI tool for software
  // engineering tasks" would make the two runners' system prompts differ by a paragraph
  // nobody wrote.
  assert.equal(typeof prompt, 'string');
  const text = String(prompt);
  assert.match(text, /\bRead\b/);
  assert.match(text, /\bGrep\b/);
  // The other runner's tool names must not leak in — a prompt naming a tool the model cannot
  // call is a prompt asking for something that does not exist, and nothing at runtime says so.
  assert.doesNotMatch(text, /readRepoFile|findInRepo|submitReview/);
  // The project rule that outranks style, present in both runners' prompts.
  assert.match(text, /Absence of accident data means UNKNOWN/);
});

test('the answer channel is a JSON schema carrying the shared ModelReview shape', () => {
  const format = session().outputFormat;
  assert.equal(format?.type, 'json_schema');
  const schema = format?.schema as { properties?: Record<string, unknown>; required?: string[] };
  // Converted from the shared Zod schema rather than written out here: `outputFormat.schema`
  // is raw JSON Schema, and a hand-copied version of it is a second definition of the
  // review's shape that would drift from the one the other runner validates against.
  assert.deepEqual(Object.keys(schema.properties ?? {}).sort(), ['findings', 'summary']);
  assert.deepEqual([...(schema.required ?? [])].sort(), ['findings', 'summary']);
});

test('the prompt asks for JSON rather than for a submit tool, matching the channel', () => {
  // `submitTool: null` is what selects the "deliver one JSON object" paragraph. With
  // `outputFormat` set, asking the model to remember to call something would be describing a
  // rule the transport already enforces — and naming a tool that is not registered.
  assert.equal(AGENT_SDK_TOOLS.submitTool, null);
  assert.equal(AGENT_SDK_TOOLS.readFile, 'Read');
  assert.equal(AGENT_SDK_TOOLS.search, 'Grep');
});

test('the default model carries the Bedrock region prefix', () => {
  // `eu.` is a cross-region inference profile, not part of the model name — `Options.model`'s
  // own examples are bare (sdk.d.ts:1808). Dropping it fails at Bedrock, not in the SDK, so
  // the prefix is pinned here rather than trusted to survive a copy-paste.
  assert.match(DEFAULT_BEDROCK_MODEL, /^eu\.anthropic\.claude-/);
});

// --- Reading the result -----------------------------------------------------------------
//
// Every case below is a run that produced no usable review, and the only wrong answer to all
// of them is the same one: a pass. `deriveVerdict` over zero findings returns `pass`, so a
// `readReview` that returned an empty `ModelReview` on any of these would report the diff as
// clean. That is why these are tested on the reason and not merely on "it threw".

/** A successful `StreamResult`, which each test then damages in exactly one way. */
function streamResult(over: Partial<StreamResult> = {}): StreamResult {
  return {
    ok: true,
    subtype: 'success',
    text: null,
    structuredOutput: { summary: 'ok', findings: [] },
    usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 },
    turns: 3,
    denials: [],
    ...over,
  };
}

function kindOf(read: () => unknown): string {
  try {
    read();
  } catch (error) {
    return String((error as { kind?: unknown }).kind);
  }
  return '(did not throw)';
}

test('a stream that ended with no result at all is no-output, not an empty pass', () => {
  assert.equal(kindOf(() => requireResult(null, DEFAULT_BEDROCK_MODEL)), 'no-output');
});

test('an unsubmitted review — no structured output — is no-output', () => {
  // The shape a run takes if `outputFormat` ever stops being honoured: the model answers in
  // prose, which lands in `result.text`, and nothing downstream can parse it. Both spellings
  // are covered because `structured_output` is optional on the result and the SDK is free to
  // send either.
  for (const structuredOutput of [undefined, null]) {
    assert.equal(
      kindOf(() => readReview(streamResult({ structuredOutput }), DEFAULT_BEDROCK_MODEL)),
      'no-output',
    );
  }
});

test('an unreadable submission is malformed-output, and the two are not the same kind', () => {
  // Distinguished on purpose. `no-output` says the review went to the wrong channel;
  // `malformed-output` says it arrived and does not fit `ModelReview`. Collapsing them would
  // lose the one fact that tells a prompt problem from a transport problem.
  for (const structuredOutput of [
    {},
    { summary: 'ok' },
    { summary: 'ok', findings: 'not an array' },
    { summary: 42, findings: [] },
    { summary: 'ok', findings: [{ file: 'a.ts' }] },
    'a string',
  ]) {
    assert.equal(
      kindOf(() => readReview(streamResult({ structuredOutput }), DEFAULT_BEDROCK_MODEL)),
      'malformed-output',
      `${JSON.stringify(structuredOutput)} should not parse as a review`,
    );
  }
});

test('an error result is reported by the SDK subtype, because it arrives as a result', () => {
  // The trap this pins: `error_max_turns` and `error_max_budget_usd` are *result subtypes*
  // (sdk.d.ts:4985), not thrown errors. A runner that only read `structured_output` would see
  // a budget kill as a review with nothing in it. The subtype is carried into the message so
  // the two say different things — one asks for a bigger ceiling, the other a shorter prompt.
  for (const subtype of [
    'error_max_turns',
    'error_max_budget_usd',
    'error_during_execution',
    'error_max_structured_output_retries',
  ]) {
    let message = '';
    try {
      readReview(streamResult({ ok: false, subtype }), DEFAULT_BEDROCK_MODEL);
    } catch (error) {
      assert.equal((error as { kind?: unknown }).kind, 'provider');
      message = String((error as { message?: unknown }).message);
    }
    assert.match(message, new RegExp(subtype), `the failure must name itself: ${message}`);
    assert.match(message, /not a passing one/);
  }
});

test('a successful result with a valid review returns it unchanged', () => {
  // The control. Without it the four tests above pass just as well against a `readReview`
  // that throws unconditionally.
  const review = readReview(
    streamResult({ structuredOutput: { summary: 'looks fine', findings: [] } }),
    DEFAULT_BEDROCK_MODEL,
  );
  assert.equal(review.summary, 'looks fine');
  assert.deepEqual(review.findings, []);
});
