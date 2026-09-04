/**
 * Per-edit quality gate — the innermost of three local layers (per-edit → pre-commit → pre-push).
 *
 * Runs on PostToolUse for Write|Edit. Formats the edited frontend file and, when the edit could
 * change behaviour (.ts / .html), runs the frontend suite so a break surfaces while the agent still
 * has the change in context.
 *
 * <h2>Why this replaces an inline one-liner</h2>
 *
 * The hook here before had a trigger, a matcher and a handler but no signal: `catch (e) {
 * process.exit(0) }` wrapped in `2>/dev/null || true`. It could not fail. It had in fact been dead
 * since May — node was not on PATH — and the symptom only surfaced when `prettier --check` reported
 * 23 of 23 files unformatted. A gate that cannot report is worse than no gate: it reads as coverage.
 *
 * <h2>Scope decisions, both measured rather than assumed</h2>
 *
 * - **The whole suite, not the related spec.** `npx vitest related` cannot run these specs at all —
 *   plain Vitest has no Angular transform, so it collects the files and dies at `describe(...)`.
 *   `ng test --include <component>.ts` does resolve to the matching spec, but it costs 5.9 s against
 *   6.4 s for all 39 tests: the price is the Angular bundle build, not the test count. Scoping buys
 *   half a second and gives up the cross-component break — editing A can fail B's spec.
 * - **Backend edits are not gated here.** `./mvnw -o test` is 14.7 s, which is a pre-commit cost,
 *   not a per-edit one. Java edits exit 0 and the next layer catches them.
 */
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';

/** stdout is capped by the harness; a Vitest failure tail is the useful end of the output. */
const MAX_REPORTED_CHARS = 4000;

const PROJECT_DIR = process.env.CLAUDE_PROJECT_DIR
  ? path.resolve(process.env.CLAUDE_PROJECT_DIR)
  : path.resolve(import.meta.dirname, '..', '..');
const FRONTEND = path.join(PROJECT_DIR, 'frontend');

/** Formatting is cheap and safe on all three; only .ts and .html can change behaviour. */
const FORMATTABLE = new Set(['.ts', '.html', '.scss']);
const BEHAVIOURAL = new Set(['.ts', '.html']);

/**
 * A blocking signal. Exit 2 is the one code the agent is guaranteed to see the text of — 0 is
 * success and everything else is logged and discarded, which is how the previous hook disappeared.
 */
function block(lines) {
  const text = lines.join('\n');
  process.stderr.write(`${text}\n`);
  process.stdout.write(`${text}\n`);
  process.exit(2);
}

/** Non-blocking context injection: says something happened without claiming it was a failure. */
function inform(message) {
  process.stdout.write(
    `${JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PostToolUse',
        additionalContext: message
      }
    })}\n`
  );
  process.exit(0);
}

function run(command) {
  try {
    return {
      ok: true,
      out: execSync(command, { cwd: FRONTEND, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
    };
  } catch (error) {
    return { ok: false, out: `${error.stdout ?? ''}${error.stderr ?? ''}` };
  }
}

function tail(text) {
  const trimmed = (text ?? '').trim();
  return trimmed.length > MAX_REPORTED_CHARS ? `…${trimmed.slice(-MAX_REPORTED_CHARS)}` : trimmed;
}

let payload;
try {
  payload = JSON.parse(readFileSync(0, 'utf8') || '{}');
} catch {
  // No parseable payload means no file to check. Not a quality failure — stay quiet.
  process.exit(0);
}

const edited = payload.tool_input?.file_path ?? payload.tool_response?.filePath ?? '';
if (!edited) process.exit(0);

const absolute = path.resolve(edited);
const relative = path.relative(PROJECT_DIR, absolute).split(path.sep).join('/');
const extension = path.extname(relative).toLowerCase();

if (!relative.startsWith('frontend/src/') || !FORMATTABLE.has(extension)) process.exit(0);

const formatted = run(`npx prettier --write "${absolute}"`);
if (!formatted.ok) {
  // prettier only exits non-zero here when it cannot parse the file, which means the edit left it
  // syntactically broken. Worth blocking on: every later check would fail for the same reason.
  block([
    `[post-edit] prettier could not parse ${relative} — the edit likely left it syntactically invalid.`,
    tail(formatted.out)
  ]);
}

if (BEHAVIOURAL.has(extension)) {
  const tested = run('npm test -- --watch=false');
  if (!tested.ok) {
    block([
      `[post-edit] the frontend suite FAILED after editing ${relative}.`,
      tail(tested.out),
      '',
      'Reproduce with: cd frontend && npm test -- --watch=false'
    ]);
  }
}

// `--write` prints "(unchanged)" when it had nothing to do. When it did rewrite the file, say so:
// the agent's next Edit would otherwise match against a stale copy of the text it just wrote.
if (!formatted.out.includes('(unchanged)')) {
  inform(`prettier reformatted ${relative}. Re-read it before your next Edit — the on-disk text has changed.`);
}
