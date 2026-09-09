import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Finding, Severity } from './schema.ts';
import { deriveVerdict, partitionByDiffScope } from './verdict.ts';

function finding(severity: Severity, file = 'backend/src/main/java/A.java'): Finding {
  return { file, severity, summary: 's', rationale: 'r' };
}

test('no findings passes', () => {
  assert.equal(deriveVerdict([]), 'pass');
});

test('a nit alone passes', () => {
  assert.equal(deriveVerdict([finding('nit')]), 'pass');
});

test('a minor alone passes — minor is not major', () => {
  assert.equal(deriveVerdict([finding('minor')]), 'pass');
});

test('a major fails', () => {
  assert.equal(deriveVerdict([finding('major')]), 'fail');
});

test('one blocker among nits fails', () => {
  const findings = [finding('nit'), finding('blocker'), finding('nit')];
  assert.equal(deriveVerdict(findings), 'fail');
});

test('every non-blocking severity together still passes', () => {
  assert.equal(deriveVerdict([finding('minor'), finding('nit'), finding('minor')]), 'pass');
});

test('a finding on a changed file is kept', () => {
  const { kept, dropped } = partitionByDiffScope(
    [finding('blocker', 'frontend/src/app/app.ts')],
    ['frontend/src/app/app.ts'],
  );
  assert.equal(kept.length, 1);
  assert.equal(dropped.length, 0);
});

test('a finding on an untouched file is dropped', () => {
  const { kept, dropped } = partitionByDiffScope(
    [finding('blocker', 'backend/src/main/java/Elsewhere.java')],
    ['frontend/src/app/app.ts'],
  );
  assert.equal(kept.length, 0);
  assert.deepEqual(
    dropped.map((f) => f.file),
    ['backend/src/main/java/Elsewhere.java'],
  );
});

test('dropping every finding yields pass, because nothing about this diff was wrong', () => {
  const { kept, dropped } = partitionByDiffScope(
    [finding('blocker', 'somewhere/else.ts'), finding('major', 'other/place.ts')],
    ['frontend/src/app/app.ts'],
  );
  assert.equal(dropped.length, 2);
  assert.equal(deriveVerdict(kept), 'pass');
});

test('cosmetic path spellings still match', () => {
  const spellings = [
    'frontend\\src\\app\\app.ts',
    './frontend/src/app/app.ts',
    'b/frontend/src/app/app.ts',
    'Frontend/Src/App/App.ts',
  ];
  for (const spelling of spellings) {
    const { kept } = partitionByDiffScope([finding('nit', spelling)], ['frontend/src/app/app.ts']);
    assert.equal(kept.length, 1, `expected ${spelling} to match`);
  }
});

test('an empty changed-file set drops everything', () => {
  const { kept, dropped } = partitionByDiffScope([finding('blocker')], []);
  assert.equal(kept.length, 0);
  assert.equal(dropped.length, 1);
});
