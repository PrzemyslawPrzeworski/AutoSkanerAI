/**
 * The tools, exercised against this repo as it actually is. Offline: no model, no
 * network, nothing written.
 *
 * The point of these is the pairing. `repo.test.ts` proves the policy refuses the
 * right strings; these prove the tools ask it — a tool that resolved paths its own
 * way would pass every test in that file and still read the key.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createTools } from './tools.ts';

/**
 * The SDK's tool type is generic over its input schema, and a test only wants the
 * returned value. `execute` also takes a call context the SDK supplies at runtime;
 * a toolCallId and an empty message list is all these tools look at (nothing).
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function call(tool: any, input: unknown): Promise<any> {
  return await tool.execute(input, { toolCallId: 'test-call', messages: [] });
}

const A_REAL_FILE = 'packages/code-reviewer/src/verdict.ts';
const A_LONG_FILE = 'context/foundation/test-plan.md'; // ~69 KB, well past the 32 000 cap

test('readRepoFile returns a real file and records the read', async () => {
  const { tools, accessedPaths } = createTools();
  const result = await call(tools.readRepoFile, { path: A_REAL_FILE });

  assert.equal(result.denied, undefined);
  assert.equal(result.path, A_REAL_FILE);
  assert.equal(result.truncated, false);
  assert.match(result.text, /export function deriveVerdict/);
  assert.deepEqual([...accessedPaths], [A_REAL_FILE]);
});

test('readRepoFile refuses the root .env, and does not record it', async () => {
  const { tools, accessedPaths } = createTools();
  const result = await call(tools.readRepoFile, { path: '.env' });

  assert.equal(result.denied, true);
  assert.equal(result.text, undefined);
  // The point of the whole phase: the key is not in the returned value, and the
  // access log does not claim it was read either.
  assert.equal(accessedPaths.size, 0);
});

test('readRepoFile refuses a traversal out of the repo', async () => {
  const { tools } = createTools();
  const result = await call(tools.readRepoFile, { path: '../.env' });

  assert.equal(result.denied, true);
  assert.match(result.reason, /outside the repository/);
});

test('readRepoFile refuses a diff-style a/ prefix rather than reading the wrong thing', async () => {
  // "a/frontend/src/main.ts" is not a path in this repo, so it must not resolve. The
  // tool's description tells the model to strip the prefix; this is what happens when
  // it does not.
  const { tools } = createTools();
  const result = await call(tools.readRepoFile, { path: `a/${A_REAL_FILE}` });
  assert.equal(result.denied, true);
});

test('readRepoFile reports a missing allowed file as absence, not as policy', async () => {
  const { tools, accessedPaths } = createTools();
  const result = await call(tools.readRepoFile, { path: 'frontend/src/nope/not-here.ts' });

  assert.equal(result.denied, true);
  assert.match(result.reason, /no such file/);
  assert.doesNotMatch(result.reason, /allow-list/);
  assert.equal(accessedPaths.size, 0);
});

test('readRepoFile truncates a long file and says so', async () => {
  const { tools } = createTools();
  const result = await call(tools.readRepoFile, { path: A_LONG_FILE });

  assert.equal(result.truncated, true);
  assert.equal(result.text.length, 32_000);
});

test('findInRepo finds a literal and records the file it came from', async () => {
  const { tools, accessedPaths } = createTools();
  const result = await call(tools.findInRepo, { query: 'MAX_DIFF_CHARS', maxMatches: 50 });

  assert.ok(result.matches.length > 0, 'expected at least one match');
  assert.ok(
    result.matches.some((match: { file: string }) => match.file === 'packages/code-reviewer/src/diff.ts'),
    `expected a hit in diff.ts, got ${JSON.stringify(result.matches.map((m: { file: string }) => m.file))}`,
  );
  for (const match of result.matches) {
    assert.ok(match.line >= 1);
    assert.ok(accessedPaths.has(match.file));
  }
});

test('findInRepo honours maxMatches and reports the cut', async () => {
  const { tools } = createTools();
  const result = await call(tools.findInRepo, { query: 'the', maxMatches: 3 });

  assert.equal(result.matches.length, 3);
  assert.equal(result.truncated, true);
});

test('findInRepo never walks into node_modules', async () => {
  const { tools } = createTools();
  // A string that certainly appears in dependency manifests. Every hit must come from
  // a source tree; the walk is seeded from the allow-list and prunes by segment name.
  const result = await call(tools.findInRepo, { query: '"license"', maxMatches: 100 });

  for (const match of result.matches) {
    assert.doesNotMatch(match.file, /node_modules/);
  }
});

test('findInRepo treats the query literally, not as a regular expression', async () => {
  const { tools } = createTools();
  // As a regex this matches almost every line in the repo. As a literal it matches
  // nothing, which is what makes a model-supplied query safe to accept.
  const result = await call(tools.findInRepo, { query: '.*', maxMatches: 5 });

  for (const match of result.matches) {
    assert.ok(match.text.includes('.*'), `expected a literal ".*" in: ${match.text}`);
  }
});

test('findInRepo returns nothing for an empty query instead of everything', async () => {
  const { tools } = createTools();
  const result = await call(tools.findInRepo, { query: '   ' });

  assert.deepEqual(result.matches, []);
  assert.equal(result.truncated, false);
});

test('findInRepo defaults to a bounded number of matches', async () => {
  const { tools } = createTools();
  // No maxMatches given: the cap must come from the tool, not from the model.
  const result = await call(tools.findInRepo, { query: 'the' });
  assert.ok(result.matches.length <= 20, `unbounded: ${result.matches.length} matches`);
});

test('each createTools call gets its own access log', async () => {
  // Sharing the set across reviews would let an earlier run's reads vouch for a later
  // run's evidence, which is exactly the check Phase 4 builds on it.
  const first = createTools();
  const second = createTools();
  await call(first.tools.readRepoFile, { path: A_REAL_FILE });

  assert.equal(first.accessedPaths.size, 1);
  assert.equal(second.accessedPaths.size, 0);
});
