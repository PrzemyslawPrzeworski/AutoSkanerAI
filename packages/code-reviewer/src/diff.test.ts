import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { MAX_DIFF_CHARS, changedFiles, validateDiff } from './diff.ts';

const FIXTURES = resolve(dirname(fileURLToPath(import.meta.url)), '../fixtures');

test('an empty diff is unreviewable', () => {
  assert.match(validateDiff('') ?? '', /empty/);
  assert.match(validateDiff('   \n\t ') ?? '', /empty/);
});

test('an oversized diff is unreviewable', () => {
  assert.match(validateDiff('x'.repeat(MAX_DIFF_CHARS + 1)) ?? '', /over the/);
});

test('a diff at exactly the cap is reviewable', () => {
  assert.equal(validateDiff('x'.repeat(MAX_DIFF_CHARS)), null);
});

test('an ordinary edit yields one path', () => {
  const diff = [
    'diff --git a/frontend/src/app/app.ts b/frontend/src/app/app.ts',
    'index 1111111..2222222 100644',
    '--- a/frontend/src/app/app.ts',
    '+++ b/frontend/src/app/app.ts',
    '@@ -1,3 +1,4 @@',
    ' unchanged',
    '+added',
  ].join('\n');
  assert.deepEqual(changedFiles(diff), ['frontend/src/app/app.ts']);
});

test('a deletion is named by its removed side, not by /dev/null', () => {
  const diff = [
    'diff --git a/frontend/src/gone.ts b/frontend/src/gone.ts',
    'deleted file mode 100644',
    '--- a/frontend/src/gone.ts',
    '+++ /dev/null',
    '@@ -1,2 +0,0 @@',
    '-was here',
  ].join('\n');
  assert.deepEqual(changedFiles(diff), ['frontend/src/gone.ts']);
});

test('a new file is named by its added side, not by /dev/null', () => {
  const diff = [
    'diff --git a/frontend/src/fresh.ts b/frontend/src/fresh.ts',
    'new file mode 100644',
    '--- /dev/null',
    '+++ b/frontend/src/fresh.ts',
    '@@ -0,0 +1,2 @@',
    '+brand new',
  ].join('\n');
  assert.deepEqual(changedFiles(diff), ['frontend/src/fresh.ts']);
});

test('a rename yields the new path, because that is the file a reviewer can open', () => {
  const diff = [
    'diff --git a/frontend/src/old-name.ts b/frontend/src/new-name.ts',
    'similarity index 92%',
    'rename from frontend/src/old-name.ts',
    'rename to frontend/src/new-name.ts',
    '--- a/frontend/src/old-name.ts',
    '+++ b/frontend/src/new-name.ts',
    '@@ -1,2 +1,2 @@',
    '-old',
    '+new',
  ].join('\n');
  assert.deepEqual(changedFiles(diff), ['frontend/src/new-name.ts']);
});

test('a pure rename with no hunk still yields the new path', () => {
  const diff = [
    'diff --git a/context/a.md b/context/b.md',
    'similarity index 100%',
    'rename from context/a.md',
    'rename to context/b.md',
  ].join('\n');
  assert.deepEqual(changedFiles(diff), ['context/b.md']);
});

test('a mode-only change yields its path, though it has no --- / +++ pair', () => {
  const diff = [
    'diff --git a/.githooks/pre-push b/.githooks/pre-push',
    'old mode 100644',
    'new mode 100755',
  ].join('\n');
  assert.deepEqual(changedFiles(diff), ['.githooks/pre-push']);
});

test('a binary change yields its path', () => {
  const diff = [
    'diff --git a/frontend/vision/shot.png b/frontend/vision/shot.png',
    'index 3333333..4444444 100644',
    'Binary files a/frontend/vision/shot.png and b/frontend/vision/shot.png differ',
  ].join('\n');
  assert.deepEqual(changedFiles(diff), ['frontend/vision/shot.png']);
});

test('a quoted path containing a space is unquoted', () => {
  const diff = [
    'diff --git "a/context/notes for later.md" "b/context/notes for later.md"',
    'index 5555555..6666666 100644',
    '--- "a/context/notes for later.md"',
    '+++ "b/context/notes for later.md"',
    '@@ -1 +1 @@',
    '-a',
    '+b',
  ].join('\n');
  assert.deepEqual(changedFiles(diff), ['context/notes for later.md']);
});

test('several files come back in order, de-duplicated', () => {
  const one = [
    'diff --git a/a.ts b/a.ts',
    '--- a/a.ts',
    '+++ b/a.ts',
    '@@ -1 +1 @@',
    '-x',
    '+y',
  ];
  const two = [
    'diff --git a/b.ts b/b.ts',
    '--- a/b.ts',
    '+++ b/b.ts',
    '@@ -1 +1 @@',
    '-x',
    '+y',
  ];
  assert.deepEqual(changedFiles([...one, ...two, ...one].join('\n')), ['a.ts', 'b.ts']);
});

test('CRLF line endings parse the same as LF', () => {
  const diff = [
    'diff --git a/frontend/src/app/app.ts b/frontend/src/app/app.ts',
    '--- a/frontend/src/app/app.ts',
    '+++ b/frontend/src/app/app.ts',
    '@@ -1 +1 @@',
    '-x',
    '+y',
  ].join('\r\n');
  assert.deepEqual(changedFiles(diff), ['frontend/src/app/app.ts']);
});

test('an added line whose text begins with ++ is not read as a header', () => {
  const diff = [
    'diff --git a/context/notes.md b/context/notes.md',
    '--- a/context/notes.md',
    '+++ b/context/notes.md',
    '@@ -1,2 +1,3 @@',
    ' prose',
    '+++ a bullet that starts with two plus signs',
  ].join('\n');
  assert.deepEqual(changedFiles(diff), ['context/notes.md']);
});

test('no diff content yields no files rather than throwing', () => {
  assert.deepEqual(changedFiles(''), []);
  assert.deepEqual(changedFiles('not a diff at all\njust prose\n'), []);
});

test('the committed bad.diff fixture names exactly the file it plants violations in', () => {
  const fixture = readFileSync(resolve(FIXTURES, 'bad.diff'), 'utf8');
  assert.deepEqual(changedFiles(fixture), [
    'backend/src/main/java/com/example/autoskaner_ai/analysis/HistorySummaryService.java',
  ]);
});
