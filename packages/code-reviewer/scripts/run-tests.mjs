/**
 * Runs this package's specs, and fails when it did not actually run any.
 *
 * `node --import tsx --test src/*.test.ts` was the direct invocation, and it has the
 * dead-gate shape in the very tool meant to be a gate: `--test src/*.nosuchpattern.ts`
 * prints `# fail 0` and exits 0, so the runner cannot distinguish "nothing broken" from
 * "nothing ran". Phase 2 recorded that hole and left it to the phase that wires the
 * hooks, because a guard only matters once something depends on the signal.
 *
 * Two things move here, and both remove a way for the gate to report success wrongly:
 *
 *   1. The spec list comes from `readdirSync`, not from a shell glob. `package.json`
 *      previously relied on bash expanding the pattern and cmd.exe passing it through
 *      for Node to expand — the same two files by luck of both paths agreeing. Now
 *      neither shell is involved, and an empty list is an error rather than a silent
 *      success.
 *   2. The run is required to report a non-zero test count. The TAP reporter writes to
 *      a temp file while the spec reporter keeps stdout readable, so this reads the
 *      runner's own tally instead of trusting its exit code.
 *
 * What this deliberately does NOT do is check the count against an expected number.
 * A pinned total is a second place to update on every new test, and it fails as a
 * merge conflict rather than as a finding. `> 0` is the property that distinguishes a
 * dead gate from a live one; the rest is the suite's job.
 */
import { spawn } from 'node:child_process';
import { readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const SRC = join(import.meta.dirname, '..', 'src');
const TAP = join(tmpdir(), `code-reviewer-tap-${process.pid}.txt`);

const specs = readdirSync(SRC)
  .filter((name) => name.endsWith('.test.ts'))
  .sort()
  .map((name) => join('src', name));

if (specs.length === 0) {
  console.error(`x no *.test.ts files found in ${SRC} — the suite is missing, not passing.`);
  process.exit(1);
}

const child = spawn(
  process.execPath,
  [
    '--import',
    'tsx',
    '--test',
    '--test-reporter=spec',
    '--test-reporter-destination=stdout',
    '--test-reporter=tap',
    `--test-reporter-destination=${TAP}`,
    ...specs,
  ],
  { cwd: join(import.meta.dirname, '..'), stdio: 'inherit' },
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
