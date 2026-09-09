/**
 * The containment check, tested against what it is for.
 *
 * Every denial below is a path a model could plausibly ask for: because the diff
 * mentioned it, because it guessed at a convention, or because the diff text told it
 * to. Offline — no model, no network, and no file is written.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resolve } from 'node:path';
import { ALLOWED_ROOT_FILES, REPO_ROOT, resolveReadablePath } from './repo.ts';

const onWindows = process.platform === 'win32';

function denialReason(input: string): string {
  const decision = resolveReadablePath(input);
  assert.equal(decision.ok, false, `expected ${input} to be refused, it was allowed`);
  assert.ok(decision.ok === false);
  return decision.reason;
}

function allowedRelative(input: string): string {
  const decision = resolveReadablePath(input);
  assert.ok(decision.ok, `expected ${input} to be allowed, refused with: ${!decision.ok && decision.reason}`);
  return decision.relative;
}

test('the root .env is refused', () => {
  denialReason('.env');
});

/**
 * Every escape case asserts the *containment* reason, not merely that the path was
 * refused. Learned by mutation: with the `..` check deleted, all of these still
 * passed, because `path.relative` renders an escape with a leading `..` and no such
 * string can match the allow-list. That is defence in depth working, but it also
 * meant containment itself was untested — and would stay untested if the allow-list
 * were ever broadened. Pinning the reason makes the two layers distinguishable.
 */
test('an escape to the parent of the repo is refused for leaving the repo', () => {
  assert.match(denialReason('../.env'), /outside the repository/);
});

test('a traversal from an allowed subtree back to the root .env is refused', () => {
  // The whole reason the check resolves before it compares: this starts with an
  // allow-listed prefix and lands on the OpenRouter key. It never leaves the repo,
  // so containment has nothing to say — the allow-list is the layer that stops it,
  // which is worth knowing about the key specifically.
  assert.match(denialReason('frontend/src/../../.env'), /allow-list/);
});

test('a traversal from an allowed subtree past the repo root is refused', () => {
  assert.match(denialReason('frontend/src/../../../.env'), /outside the repository/);
});

test('a traversal out of the repo entirely is refused for leaving the repo', () => {
  assert.match(
    denialReason('../../Windows/System32/drivers/etc/hosts'),
    /outside the repository/,
  );
});

test('an absolute path outside the repo is refused for leaving the repo', () => {
  const outside = onWindows ? 'C:\\Windows\\System32\\drivers\\etc\\hosts' : '/etc/passwd';
  assert.match(denialReason(outside), /outside the repository/);
});

test('an absolute path inside the repo is accepted and normalised', () => {
  // Not a hole: it resolves inside the repo and into an allowed subtree. Refusing it
  // would only teach the model to spell the same file differently.
  const absolute = resolve(REPO_ROOT, 'frontend/src/main.ts');
  assert.equal(allowedRelative(absolute), 'frontend/src/main.ts');
});

test('node_modules is refused even inside an allowed subtree', () => {
  const reason = denialReason('packages/code-reviewer/node_modules/ai/package.json');
  assert.match(reason, /node_modules/);
});

test('.git/config is refused', () => {
  denialReason('.git/config');
});

test('build output under an allowed subtree is refused', () => {
  const reason = denialReason('backend/target/classes/x.class');
  assert.match(reason, /allow-list|target/);
});

test('a dotfile inside an allowed subtree is refused', () => {
  // The rule is "deny every dot segment except .githooks and .github", so a dotfile
  // nobody thought of is refused by default rather than by being listed.
  denialReason('context/.secrets');
});

test('the repo root itself is refused', () => {
  denialReason('.');
});

test('an empty path is refused', () => {
  denialReason('   ');
});

test('a path with a NUL byte is refused', () => {
  const reason = denialReason('frontend/src/main.ts\0.env');
  assert.match(reason, /NUL/);
});

test('a directory is refused, and says so', () => {
  const reason = denialReason('frontend/src');
  assert.match(reason, /directory/);
});

test('a backend source file is allowed', () => {
  assert.equal(
    allowedRelative(
      'backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java',
    ),
    'backend/src/main/java/com/example/autoskaner_ai/analysis/ListingFetchService.java',
  );
});

test('a frontend source file is allowed', () => {
  assert.equal(allowedRelative('frontend/src/main.ts'), 'frontend/src/main.ts');
});

test('a context document is allowed', () => {
  assert.equal(
    allowedRelative('context/foundation/test-plan.md'),
    'context/foundation/test-plan.md',
  );
});

test('every allow-listed root file resolves', () => {
  for (const file of ALLOWED_ROOT_FILES) {
    assert.equal(allowedRelative(file), file);
  }
});

test('a githooks file is allowed — dot segments are an exception list, not a blanket deny', () => {
  assert.equal(allowedRelative('.githooks/pre-commit'), '.githooks/pre-commit');
});

test('backslashes and ./ are accepted as spellings of the same path', () => {
  for (const spelling of ['./frontend/src/main.ts', 'frontend\\src\\main.ts']) {
    assert.equal(allowedRelative(spelling), 'frontend/src/main.ts', `spelling: ${spelling}`);
  }
});

test('a file that does not exist is allowed to resolve — absence is the caller\u2019s report', () => {
  // resolveReadablePath answers "may this be read", not "is this there". Conflating
  // the two would report a missing file as a policy refusal.
  assert.equal(
    allowedRelative('frontend/src/nowhere/does-not-exist.ts'),
    'frontend/src/nowhere/does-not-exist.ts',
  );
});

// Windows opens .ENV and .env as the same file, so a case-sensitive deny-list would
// be bypassed by shouting. Off Windows the two are genuinely different names.
test(
  'on Windows, case does not evade the rules',
  { skip: onWindows ? false : 'case-insensitive matching only applies on win32' },
  () => {
    denialReason('.ENV');
    assert.match(denialReason('PACKAGES/CODE-REVIEWER/NODE_MODULES/ai/package.json'), /NODE_MODULES/i);
    assert.equal(allowedRelative('Frontend/Src/Main.ts').toLowerCase(), 'frontend/src/main.ts');
  },
);
