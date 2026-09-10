/**
 * The permission bridge, tested in both directions and on the reason.
 *
 * Every refusal below asserts **which layer refused it**, not merely that something did.
 * That is not thoroughness for its own sake: `tool-loop-agent`'s Phase 3 deleted a
 * load-bearing containment check and watched the suite stay green, because a different
 * layer happened to refuse the same paths and the assertions only said "refused". A test
 * that cannot tell two layers apart cannot notice one of them dying.
 *
 * Offline. No SDK, no subprocess, no model, no file written.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resolve } from 'node:path';
import { ANSWER_TOOL, REVIEWER_TOOLS, decideToolUse, toPreToolUseOutput } from './permission.ts';
import { REPO_ROOT } from './repo.ts';

function refusal(toolName: string, toolInput: unknown): string {
  const decision = decideToolUse(toolName, toolInput);
  assert.equal(decision.allow, false, `expected ${toolName} to be refused, it was allowed`);
  assert.ok(decision.allow === false);
  return decision.reason;
}

function allowed(toolName: string, toolInput: unknown): void {
  const decision = decideToolUse(toolName, toolInput);
  assert.ok(
    decision.allow,
    `expected ${toolName} to be allowed, refused with: ${!decision.allow && decision.reason}`,
  );
}

/** The built-in `Read` takes an absolute path, so that is what these tests hand it. */
function inRepo(relativePath: string): string {
  return resolve(REPO_ROOT, relativePath);
}

// --- Read -------------------------------------------------------------------------------

test('Read of an allow-listed source file is allowed', () => {
  allowed('Read', { file_path: inRepo('frontend/src/main.ts') });
});

test('Read of an allow-listed root file is allowed', () => {
  allowed('Read', { file_path: inRepo('CLAUDE.md') });
});

test('Read of the root .env is refused by the allow-list, not by containment', () => {
  // The distinction this pins is the one that protects the key. `.env` never leaves the
  // repo, so the containment check has nothing to say about it — the allow-list is the
  // only layer standing between a persuaded model and a live OpenRouter key. If this
  // assertion ever starts matching /outside the repository/, the layers have been
  // reordered and the key is being protected by accident.
  assert.match(refusal('Read', { file_path: inRepo('.env') }), /allow-list/);
});

test('Read of a traversal past the repo root is refused for leaving the repo', () => {
  assert.match(
    refusal('Read', { file_path: inRepo('frontend/src/../../../.env') }),
    /outside the repository/,
  );
});

test('Read of build output is refused naming the denied segment', () => {
  assert.match(refusal('Read', { file_path: inRepo('backend/target/classes/X.class') }), /target/);
});

test('Read with no file_path is refused for the missing field, not for a path', () => {
  const reason = refusal('Read', {});
  assert.match(reason, /file_path/);
  assert.doesNotMatch(reason, /allow-list/);
});

test('Read with a non-string file_path is refused without throwing', () => {
  assert.match(refusal('Read', { file_path: 42 }), /string/);
  assert.match(refusal('Read', { file_path: null }), /string/);
  assert.match(refusal('Read', { file_path: ['.env'] }), /string/);
});

// --- Grep -------------------------------------------------------------------------------

test('Grep scoped to an allow-listed subtree is allowed', () => {
  // A directory, which `resolveReadablePath` would refuse for being one. The search path
  // has its own resolver for exactly this case.
  allowed('Grep', { pattern: 'ListingFetchService', path: inRepo('backend/src') });
});

test('Grep scoped to a nested directory inside a subtree is allowed', () => {
  allowed('Grep', { pattern: 'analyse', path: inRepo('frontend/src/app') });
});

test('Grep with NO path is refused, and the reason names the working directory', () => {
  // The inversion that makes this the most dangerous input in the file: `path` is optional
  // on `GrepInput`, and omitting it searches the whole working directory, which is the
  // repo root, which holds `.env`. An absent field is the broadest request, not an
  // incomplete one.
  //
  // Pinned on the wording and not merely on the refusal, because `resolveSearchRoot` also
  // refuses the repo root — so a version of `decideToolUse` that dropped this rule and let
  // an absent path default to `.`, exactly as the SDK does, would still be refused, by a
  // different layer, with a reason that never tells the model to name a path. Verified by
  // mutation: that rewrite fails here and nowhere else in the suite.
  const reason = refusal('Grep', { pattern: 'sk-or-v1' });
  assert.match(reason, /needs an explicit path/, `must ask for a path, got: ${reason}`);
  assert.match(reason, /\.env/, `must say what is at stake, got: ${reason}`);
});

test('Grep of the repository root is refused for being too broad, not for escaping', () => {
  const reason = refusal('Grep', { pattern: 'sk-or-v1', path: REPO_ROOT });
  assert.match(reason, /too broad|root/);
  assert.doesNotMatch(reason, /outside the repository/);
});

test('Grep outside the repository is refused for leaving it', () => {
  const outside = process.platform === 'win32' ? 'C:\\Windows\\System32' : '/etc';
  assert.match(refusal('Grep', { pattern: 'password', path: outside }), /outside the repository/);
});

test('Grep of a non-allow-listed directory inside the repo is refused by the allow-list', () => {
  assert.match(refusal('Grep', { pattern: 'x', path: inRepo('backend/target') }), /allow-list/);
});

test('Grep with a non-string path is refused without throwing', () => {
  assert.match(refusal('Grep', { pattern: 'x', path: 7 }), /string/);
});

// --- Everything else --------------------------------------------------------------------

test('the write and execute tools are refused by name', () => {
  for (const toolName of ['Write', 'Edit', 'NotebookEdit', 'Bash', 'BashOutput', 'KillShell']) {
    const reason = refusal(toolName, { file_path: inRepo('frontend/src/main.ts') });
    assert.match(reason, new RegExp(toolName), `reason should name ${toolName}: ${reason}`);
    assert.match(reason, /Read, Grep/, `reason should name what IS available: ${reason}`);
  }
});

test('a tool nobody has heard of is refused, because the default branch denies', () => {
  // The property under test is the shape of the function, not this name. A `PreToolUse`
  // hook that returns `{}` has allowed the call, so a bridge written as "deny the tools I
  // recognise as dangerous" is permissive for every tool a later SDK version adds. This
  // stands in for that tool.
  assert.match(refusal('SomeToolAddedInVersion0_4', { anything: true }), /not available/);
});

test('the network tools are refused — a review reads this repo, not the internet', () => {
  for (const toolName of ['WebFetch', 'WebSearch', 'Task', 'Agent']) {
    assert.match(refusal(toolName, {}), /not available/);
  }
});

test('a nameless tool call is refused', () => {
  assert.match(refusal('', { file_path: inRepo('CLAUDE.md') }), /no name/);
});

test('a non-object tool input is refused rather than throwing', () => {
  for (const input of [undefined, null, 'string', 42, []]) {
    // The assertion is that this returns at all. A throw here would end a whole review
    // over one malformed tool call, where a refusal is something the model can read and
    // retry — the same choice `tools.ts:11` made for the AI SDK runner.
    assert.doesNotThrow(() => decideToolUse('Read', input));
    assert.equal(decideToolUse('Read', input).allow, false);
  }
});

test('REVIEWER_TOOLS lists exactly the two names offered for reading the repo', () => {
  // Pins the pair the reason strings advertise against the pair actually implemented, so a
  // tool added to one and not the other is a failure rather than a lie in a refusal.
  assert.deepEqual([...REVIEWER_TOOLS], ['Read', 'Grep']);
  for (const toolName of REVIEWER_TOOLS) {
    assert.doesNotMatch(refusal(toolName, {}), /not available/);
  }
  // The answer channel is NOT in that list, and this asserts the separation rather than
  // merely tolerating it: `REVIEWER_TOOLS` is interpolated into the prompt as the tools
  // available for checking the repo, so a name added here would offer the model a third
  // research tool that reads nothing.
  assert.equal((REVIEWER_TOOLS as readonly string[]).includes(ANSWER_TOOL), false);
});

test('the answer channel is allowed — the SDK delivers outputFormat as a tool call', () => {
  // Measured, and the measurement cost a 65-second failed run. `outputFormat` is an
  // "end-turn tool" (sdk.d.ts:1957) in the literal sense: the SDK injects a tool named
  // `StructuredOutput` and the model answers by calling it. It is not a built-in the runner
  // opts into, so `options.tools: ['Read', 'Grep']` does not remove it, and the default
  // branch below refused it five times until the SDK gave up — the reviewer denying its own
  // mouth. Asserted by the constant AND by the literal name, because the SDK owns the
  // spelling and a rename there is a total outage of this runner that no offline test would
  // otherwise catch.
  allowed(ANSWER_TOOL, { summary: 'anything', findings: [] });
  allowed('StructuredOutput', {});
});

test('allowing the answer channel did not widen anything else', () => {
  // The narrow-exception guard. `StructuredOutput` is permitted with no path check because
  // it carries no path; the risk of a rule like that is that it gets generalised into "tools
  // whose input we do not understand are fine".
  assert.match(refusal('StructuredOutputWriter', {}), /not available/);
  assert.match(refusal('structuredoutput', {}), /not available/);
  assert.match(refusal('Bash', { command: 'cat .env' }), /not available/);
});

// --- The SDK adapter --------------------------------------------------------------------

test('an allow decision says allow explicitly — {} would be no opinion, which is a refusal', () => {
  // This assertion was inverted for one commit: it required `{}`, on the belief that an
  // empty return is how a `PreToolUse` hook says yes. It is how the hook says *nothing*,
  // and the call then falls through to the normal permission flow — a prompt when there is
  // a human, and a refusal in a headless run with nothing pre-approved. The failure that
  // would have caused is the quiet kind: a review that arrives correctly shaped having read
  // no files, with every citation stripped because the access log is empty.
  assert.deepEqual(toPreToolUseOutput({ allow: true }), {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'allow',
      permissionDecisionReason: 'allow-listed: read-only, inside the repository',
    },
  });
});

test('a deny decision carries hookEventName, which is what makes the deny apply', () => {
  // `hookSpecificOutput` is a union across every hook event and `hookEventName` is its
  // discriminant. A deny without it does not typecheck, and the plan's original sketch of
  // this adapter omitted it — a refusal that would have read as a refusal and allowed the
  // call. Asserted at runtime as well as at compile time because the cost of being wrong
  // here is the containment claim itself.
  const output = toPreToolUseOutput({ allow: false, reason: 'because .env' });
  assert.deepEqual(output, {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason: 'because .env',
    },
  });
});
