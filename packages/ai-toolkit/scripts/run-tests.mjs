/**
 * Runs this package's specs, and fails when it did not actually run any.
 *
 * Same shape and same reason as `packages/code-reviewer/scripts/run-tests.mjs`: `node --test` on a
 * pattern that matches nothing prints `# fail 0` and exits 0, so a bare `"test": "node --test
 * test/"` in package.json cannot tell "nothing broken" from "nothing ran". A commit gate wired to
 * that is a gate hard-wired to success, which is the failure root CLAUDE.md documents at length.
 *
 * So: the spec list comes from `readdirSync`, not from a shell glob — neither bash nor cmd.exe is
 * involved and an empty list is an error. And the run must report a non-zero test count, read from
 * the runner's own TAP tally rather than inferred from its exit code.
 *
 * Simpler than the code-reviewer's copy in one way: this package is plain CommonJS with no
 * dependencies, so there is no `--import tsx`. That is also why it can run in CI before `npm ci`
 * has anything to install.
 */
import { spawn } from 'node:child_process';
import { readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const ROOT = join(import.meta.dirname, '..');
const TEST_DIR = join(ROOT, 'test');
const TAP = join(tmpdir(), `ai-toolkit-tap-${process.pid}.txt`);

let names = [];
try {
  names = readdirSync(TEST_DIR);
} catch (cause) {
  console.error(`x cannot read ${TEST_DIR}: ${cause.message}`);
  process.exit(1);
}

const specs = names
  .filter((name) => name.endsWith('.test.js'))
  .sort()
  .map((name) => join('test', name));

if (specs.length === 0) {
  console.error(`x no *.test.js files found in ${TEST_DIR} — the suite is missing, not passing.`);
  process.exit(1);
}

const child = spawn(
  process.execPath,
  [
    '--test',
    '--test-reporter=spec',
    '--test-reporter-destination=stdout',
    '--test-reporter=tap',
    `--test-reporter-destination=${TAP}`,
    ...specs,
  ],
  { cwd: ROOT, stdio: 'inherit' },
);

child.on('exit', (code, signal) => {
  let total = null;
  try {
    total = Number(/^# tests (\d+)$/m.exec(readFileSync(TAP, 'utf8'))?.[1] ?? NaN);
  } catch {
    // Falls through to the report below: an unreadable tally is an unreported run.
  }
  rmSync(TAP, { force: true });

  if (signal !== null) {
    console.error(`x the test runner was killed by ${signal}.`);
    process.exit(1);
  }
  if (!Number.isInteger(total) || total === 0) {
    console.error(
      `x ${specs.length} spec file(s) were passed to the runner and it reported ` +
        `${total === null ? 'no tally at all' : `${total} test(s)`}. ` +
        `Exit code ${code} does not mean the suite passed.`,
    );
    process.exit(1);
  }
  process.exit(code ?? 1);
});
