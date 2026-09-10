/**
 * The access log, reconstructed from hand-written message fixtures.
 *
 * The **non-empty direction comes first**, and that ordering is the whole point of the file.
 * A collector that records nothing passes every refusal test here — an error result
 * contributes nothing, a denied call contributes nothing, a `Write` contributes nothing — and
 * then makes `stripUnbackedEvidence` delete every citation in every review while reporting a
 * clean run. Same failure shape `scripts/run-tests.mjs` guards against by refusing a run that
 * collected zero tests: absence of evidence rendered as evidence of absence.
 *
 * The fixtures are minimal literals rather than real `SDKMessage` values. `observe` takes
 * `unknown` precisely so they can be — building a valid member of a forty-way union by hand
 * would test the fixture, not the collector. The cost is that these fixtures could drift from
 * the SDK's real shape without a test noticing, which is why every field name in `stream.ts`
 * carries the declaration it was read from.
 *
 * Offline. No SDK, no subprocess, no model.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resolve } from 'node:path';
import { createStreamCollector } from './stream.ts';
import { REPO_ROOT } from './repo.ts';

/** The SDK's `Read` reports absolute paths, so the fixtures do too. */
function abs(relativePath: string): string {
  return resolve(REPO_ROOT, relativePath);
}

/** Two real, allow-listed, existing files — `resolveReadablePath` rejects a directory. */
const FILE_A = 'packages/code-reviewer/src/repo.ts';
const FILE_B = 'packages/code-reviewer/src/verdict.ts';

function toolUse(id: string, name: string, input: unknown): unknown {
  return { type: 'assistant', message: { content: [{ type: 'tool_use', id, name, input }] } };
}

function toolResult(id: string, content: unknown, extra: Record<string, unknown> = {}): unknown {
  return {
    type: 'user',
    message: { content: [{ type: 'tool_result', tool_use_id: id, content }] },
    ...extra,
  };
}

function paths(messages: unknown[]): string[] {
  const collector = createStreamCollector();
  for (const message of messages) collector.observe(message);
  return [...collector.accessedPaths].sort();
}

// --- The direction that must work ------------------------------------------------------

test('two Reads that returned content yield both paths', () => {
  assert.deepEqual(
    paths([
      toolUse('tu_1', 'Read', { file_path: abs(FILE_A) }),
      toolResult('tu_1', 'export const REPO_ROOT = ...'),
      toolUse('tu_2', 'Read', { file_path: abs(FILE_B) }),
      toolResult('tu_2', 'export function stripUnbackedEvidence(...)'),
    ]),
    [FILE_A, FILE_B].sort(),
  );
});

test('an absolute Windows-or-POSIX path is recorded repo-relative with forward slashes', () => {
  // The spelling matters because `stripUnbackedEvidence` compares against citations the model
  // wrote as `packages/code-reviewer/src/repo.ts`. An absolute `D:\...` in this set would
  // match nothing and strip everything.
  const [recorded] = paths([
    toolUse('tu_1', 'Read', { file_path: abs(FILE_A) }),
    toolResult('tu_1', 'contents'),
  ]);
  assert.equal(recorded, FILE_A);
});

test('a Grep contributes every distinct file its structured output names', () => {
  assert.deepEqual(
    paths([
      toolUse('tu_1', 'Grep', { pattern: 'REPO_ROOT', path: abs('packages') }),
      toolResult('tu_1', 'found 2 files', {
        // `GrepOutput.filenames`, the only structured record of which files a search touched.
        tool_use_result: { numFiles: 2, filenames: [abs(FILE_A), abs(FILE_B)] },
      }),
    ]),
    [FILE_A, FILE_B].sort(),
  );
});

test('the structured filePath wins over the requested file_path', () => {
  // What the tool opened, not what was asked for. They differ whenever a path was resolved
  // through a symlink or normalised, and vouching for the request rather than the result
  // would put an unopened filename into the log.
  assert.deepEqual(
    paths([
      toolUse('tu_1', 'Read', { file_path: abs(FILE_A) }),
      toolResult('tu_1', 'contents', {
        tool_use_result: { type: 'text', file: { filePath: abs(FILE_B), content: 'x' } },
      }),
    ]),
    [FILE_B],
  );
});

test('a content-block array counts as content, not just a plain string', () => {
  assert.deepEqual(
    paths([
      toolUse('tu_1', 'Read', { file_path: abs(FILE_A) }),
      toolResult('tu_1', [{ type: 'text', text: 'export const REPO_ROOT' }]),
    ]),
    [FILE_A],
  );
});

// --- The directions that must not ------------------------------------------------------

test('an error tool_result contributes nothing', () => {
  assert.deepEqual(
    paths([
      toolUse('tu_1', 'Read', { file_path: abs(FILE_A) }),
      { type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: 'tu_1', content: 'ENOENT', is_error: true }] } },
    ]),
    [],
  );
});

test('a denied call contributes nothing, and the run says it was denied', () => {
  // The pair that makes containment auditable: the hook's refusal comes back as an error
  // result (no path) AND as an entry in the SDK's own `permission_denials`. The second is
  // worth more than a log line this code chose to write, because it is the subprocess's
  // account of what it was refused rather than ours.
  const collector = createStreamCollector();
  collector.observe(toolUse('tu_1', 'Read', { file_path: abs('.env') }));
  collector.observe({
    type: 'user',
    message: {
      content: [
        { type: 'tool_result', tool_use_id: 'tu_1', content: 'not in the allow-list', is_error: true },
      ],
    },
  });
  collector.observe({
    type: 'result',
    subtype: 'success',
    num_turns: 2,
    result: 'done',
    permission_denials: [{ tool_name: 'Read', tool_use_id: 'tu_1', tool_input: { file_path: '.env' } }],
  });

  assert.deepEqual([...collector.accessedPaths], []);
  assert.deepEqual(collector.result?.denials, [{ toolName: 'Read', toolUseId: 'tu_1' }]);
});

test('an empty result contributes nothing — a file that returned nothing backs no citation', () => {
  for (const content of ['', '   ', [], [{ type: 'text', text: '' }]]) {
    assert.deepEqual(
      paths([toolUse('tu_1', 'Read', { file_path: abs(FILE_A) }), toolResult('tu_1', content)]),
      [],
      `content ${JSON.stringify(content)} should contribute nothing`,
    );
  }
});

test('a successful non-read tool contributes nothing', () => {
  // Nothing should be able to write during a review, but the collector does not get to
  // assume that: if a `Write` ever succeeded, its target is a file the reviewer produced,
  // not a file it read, and citing it would be vouching for its own output.
  assert.deepEqual(
    paths([
      toolUse('tu_1', 'Write', { file_path: abs(FILE_A) }),
      toolResult('tu_1', 'wrote 40 lines'),
    ]),
    [],
  );
});

test('a path outside the allow-list is refused a second time, at the log', () => {
  // The hook should never have allowed this. The collector re-runs the policy anyway, so a
  // bypassed or misconfigured hook costs the reviewer its sight rather than its integrity.
  assert.deepEqual(
    paths([
      toolUse('tu_1', 'Read', { file_path: abs('.env') }),
      toolResult('tu_1', 'OPENROUTER_API_KEY=sk-or-v1-REDACTED'),
    ]),
    [],
  );
});

test('a Grep with no structured output contributes nothing — the documented gap', () => {
  // Asserting the gap rather than pretending it is not there. Text output is not parsed
  // because a content-mode hit is `path:line:text` and splitting on the first colon takes
  // `D` off a Windows absolute path. Conservative direction (citations stripped, not
  // fabricated evidence admitted) but a real limitation: if this ever starts returning
  // paths, the parser was added and this test is the place that says so.
  assert.deepEqual(
    paths([
      toolUse('tu_1', 'Grep', { pattern: 'REPO_ROOT', path: abs('packages') }),
      toolResult('tu_1', `${abs(FILE_A)}:23:export const REPO_ROOT`),
    ]),
    [],
  );
});

test('a tool_result with no preceding tool_use contributes nothing', () => {
  assert.deepEqual(paths([toolResult('tu_unknown', 'contents')]), []);
});

test('tool_use_result is ignored when the message carries two tool_results', () => {
  // `tool_use_result` is singular and sits beside `message`, so with two results in one
  // message there is no way to know which it describes. The Reads still register from their
  // own `file_path`; what is dropped is the structured claim, not the whole message.
  assert.deepEqual(
    paths([
      toolUse('tu_1', 'Grep', { pattern: 'x', path: abs('packages') }),
      toolUse('tu_2', 'Grep', { pattern: 'y', path: abs('packages') }),
      {
        type: 'user',
        message: {
          content: [
            { type: 'tool_result', tool_use_id: 'tu_1', content: 'hit' },
            { type: 'tool_result', tool_use_id: 'tu_2', content: 'hit' },
          ],
        },
        tool_use_result: { filenames: [abs(FILE_A)] },
      },
    ]),
    [],
  );
});

// --- Correlation -----------------------------------------------------------------------

test('correlation is by tool_use_id, so out-of-order results attribute correctly', () => {
  // Two calls issued in one turn, answered in reverse order, and the failing one is the
  // FIRST issued. Positional correlation would credit `.env`'s slot to the file that
  // succeeded, or drop the file that succeeded — either way the log would be wrong while
  // still being non-empty, which is the failure mode no count-based assertion catches.
  const collector = createStreamCollector();
  collector.observe({
    type: 'assistant',
    message: {
      content: [
        { type: 'tool_use', id: 'tu_bad', name: 'Read', input: { file_path: abs('.env') } },
        { type: 'tool_use', id: 'tu_ok', name: 'Read', input: { file_path: abs(FILE_A) } },
      ],
    },
  });
  collector.observe(toolResult('tu_ok', 'export const REPO_ROOT'));
  collector.observe({
    type: 'user',
    message: {
      content: [{ type: 'tool_result', tool_use_id: 'tu_bad', content: 'denied', is_error: true }],
    },
  });

  assert.deepEqual([...collector.accessedPaths], [FILE_A]);
});

test('a second result for the same id contributes nothing the second time', () => {
  const collector = createStreamCollector();
  collector.observe(toolUse('tu_1', 'Read', { file_path: abs(FILE_A) }));
  collector.observe(toolResult('tu_1', 'contents'));
  collector.observe(toolResult('tu_1', 'contents again'));
  assert.deepEqual([...collector.accessedPaths], [FILE_A]);
});

test('a message that is not an object, or is a type we do not model, is ignored', () => {
  const collector = createStreamCollector();
  for (const message of [null, undefined, 42, 'result', [], { type: 'system' }, {}]) {
    assert.doesNotThrow(() => collector.observe(message));
  }
  assert.deepEqual([...collector.accessedPaths], []);
  assert.equal(collector.result, null);
});

// --- The terminal result ---------------------------------------------------------------

test('usage comes from modelUsage, summed across models, with cost from total_cost_usd', () => {
  const collector = createStreamCollector();
  collector.observe({
    type: 'result',
    subtype: 'success',
    num_turns: 7,
    result: '{"findings":[]}',
    structured_output: { findings: [] },
    total_cost_usd: 0.0413,
    // camelCase — `ModelUsage`. Two entries because summing is the documented shape even
    // though this reviewer runs one model.
    modelUsage: {
      'eu.anthropic.claude-sonnet-5': {
        inputTokens: 1000,
        outputTokens: 200,
        cacheReadInputTokens: 300,
        costUSD: 0.04,
      },
      'claude-haiku-4-5': { inputTokens: 50, outputTokens: 10, cacheReadInputTokens: 5 },
    },
  });

  const result = collector.result;
  assert.ok(result);
  assert.equal(result.ok, true);
  assert.equal(result.subtype, 'success');
  assert.equal(result.text, '{"findings":[]}');
  assert.deepEqual(result.structuredOutput, { findings: [] });
  assert.equal(result.turns, 7);
  assert.equal(result.usage.inputTokens, 1050);
  assert.equal(result.usage.outputTokens, 210);
  assert.equal(result.usage.cachedInputTokens, 305);
  assert.equal(result.usage.totalTokens, 1260);
  assert.equal(result.usage.costUsd, 0.0413);
});

test('usage falls back to the snake_case usage block when modelUsage is absent', () => {
  // The two spellings are not a typo in `stream.ts`: `ModelUsage` is camelCase and `usage`
  // is a `BetaUsage`, which is snake_case. A reader who "fixes" one of them to match the
  // other silently zeroes the comparison table `pick.md` is built from.
  const collector = createStreamCollector();
  collector.observe({
    type: 'result',
    subtype: 'success',
    num_turns: 3,
    result: 'ok',
    usage: { input_tokens: 400, output_tokens: 60, cache_read_input_tokens: 20 },
  });

  assert.equal(collector.result?.usage.inputTokens, 400);
  assert.equal(collector.result?.usage.outputTokens, 60);
  assert.equal(collector.result?.usage.cachedInputTokens, 20);
  assert.equal(collector.result?.usage.totalTokens, 460);
});

test('a field no source supplied stays absent rather than becoming zero', () => {
  // `ReviewUsage` documents why: "0 tokens" and "unknown tokens" are different facts, and
  // `pick.md` compares the two runners on a column where one of them genuinely cannot say.
  const collector = createStreamCollector();
  collector.observe({ type: 'result', subtype: 'success', num_turns: 1, result: 'ok' });

  const usage = collector.result?.usage;
  assert.ok(usage);
  assert.equal(usage.inputTokens, undefined);
  assert.equal(usage.outputTokens, undefined);
  assert.equal(usage.totalTokens, undefined);
  assert.equal(usage.cachedInputTokens, undefined);
  assert.equal(usage.costUsd, undefined);
});

test('an error result is not ok, and reports its own subtype', () => {
  const collector = createStreamCollector();
  collector.observe({
    type: 'result',
    subtype: 'error_max_budget_usd',
    is_error: true,
    num_turns: 12,
    total_cost_usd: 0.5,
  });

  assert.equal(collector.result?.ok, false);
  assert.equal(collector.result?.subtype, 'error_max_budget_usd');
  assert.equal(collector.result?.text, null);
  // Cost survives the failure: a run that burned the budget is exactly the run whose price
  // the comparison needs.
  assert.equal(collector.result?.usage.costUsd, 0.5);
});

test('the LAST result wins, because usage and cost are cumulative running totals', () => {
  // Summing them would double-count. `total_cost_usd` on the second result already includes
  // the first, so "add them up" reports 0.03 for a review that cost 0.02.
  const collector = createStreamCollector();
  collector.observe({ type: 'result', subtype: 'success', num_turns: 2, total_cost_usd: 0.01 });
  collector.observe({ type: 'result', subtype: 'success', num_turns: 5, total_cost_usd: 0.02 });

  assert.equal(collector.result?.turns, 5);
  assert.equal(collector.result?.usage.costUsd, 0.02);
});

test('a stream that ended without a result leaves result null', () => {
  // Distinguishable from a failed result. A killed subprocess and a budget-exceeded run are
  // different reports, and Phase 4 raises them as different errors.
  const collector = createStreamCollector();
  collector.observe(toolUse('tu_1', 'Read', { file_path: abs(FILE_A) }));
  collector.observe(toolResult('tu_1', 'contents'));
  assert.equal(collector.result, null);
  assert.deepEqual([...collector.accessedPaths], [FILE_A]);
});
