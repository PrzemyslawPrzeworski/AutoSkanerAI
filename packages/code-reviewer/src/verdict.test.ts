import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Finding, Severity } from './schema.ts';
import { deriveVerdict, partitionByDiffScope, stripUnbackedEvidence } from './verdict.ts';

function finding(severity: Severity, file = 'backend/src/main/java/A.java'): Finding {
  return { file, severity, summary: 's', rationale: 'r' };
}

function cited(evidenceFile: string): Finding {
  return { ...finding('major'), evidence: { file: evidenceFile, note: 'n' } };
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

test('evidence naming a file a tool returned survives', () => {
  const accessed = new Set(['backend/src/main/java/Other.java']);
  const { findings, stripped } = stripUnbackedEvidence(
    [cited('backend/src/main/java/Other.java')],
    accessed,
  );
  assert.equal(stripped, 0);
  assert.equal(findings[0]?.evidence?.file, 'backend/src/main/java/Other.java');
});

test('evidence naming a file no tool returned is stripped', () => {
  const { stripped, findings } = stripUnbackedEvidence(
    [cited('backend/src/main/java/Invented.java')],
    new Set(),
  );
  assert.equal(stripped, 1);
  assert.equal(findings[0]?.evidence, undefined);
});

test('stripping the citation keeps the finding and its severity', () => {
  // An unsupported citation makes a claim unproven, not wrong. Dropping a real
  // blocker over a bad footnote would trade a cosmetic problem for a shipped bug.
  const { findings } = stripUnbackedEvidence([cited('nowhere/at-all.java')], new Set());
  assert.equal(findings.length, 1);
  assert.equal(findings[0]?.severity, 'major');
  assert.equal(deriveVerdict(findings), 'fail');
});

test('a finding with no evidence at all is untouched and uncounted', () => {
  const { findings, stripped } = stripUnbackedEvidence([finding('nit')], new Set());
  assert.equal(stripped, 0);
  assert.deepEqual(findings, [finding('nit')]);
});

test('evidence quoting the diff back at itself is stripped', () => {
  // Exactly what Phase 2's live runs produced with no tools configured: evidence.file
  // set to the file already named in file, presented as a citation of the repo.
  const self = 'backend/src/main/java/A.java';
  const { stripped } = stripUnbackedEvidence(
    [{ ...finding('blocker', self), evidence: { file: self, note: 'the diff says so' } }],
    new Set(),
  );
  assert.equal(stripped, 1);
});

test('a citation is matched on path spelling, not on exact string', () => {
  const { stripped } = stripUnbackedEvidence(
    [cited('./frontend\\src\\app\\app.ts')],
    new Set(['frontend/src/app/app.ts']),
  );
  assert.equal(stripped, 0);
});

test('several findings are counted individually', () => {
  const accessed = new Set(['a/kept.ts'.replace('a/', '')]);
  const { stripped, findings } = stripUnbackedEvidence(
    [cited('kept.ts'), cited('gone.ts'), cited('also-gone.ts'), finding('nit')],
    accessed,
  );
  assert.equal(stripped, 2);
  assert.equal(findings.length, 4);
  assert.equal(findings[0]?.evidence?.file, 'kept.ts');
});
