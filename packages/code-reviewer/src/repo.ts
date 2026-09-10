/**
 * Which paths this process will read, and which it will not.
 *
 * This is the first path-containment check in the repo, and it exists because the
 * next phase hands path selection to a language model reading a diff that an
 * outsider may have written. The default answer is no: a path is readable only if
 * it survives every check below, and anything unrecognised is refused.
 *
 * What is actually being protected: the gitignored root `.env` holds a live
 * OpenRouter key. `.env` is one `../` away from every subtree the reviewer legitimately
 * reads, so "resolve, then confirm we are still inside" is the whole game — a
 * string-prefix check on the input would pass `frontend/src/../../.env`.
 *
 * Nothing here reads file contents. It answers one question about one string.
 */
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { realpathSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

/** `src` -> `code-reviewer` -> `packages` -> the repo root. */
export const REPO_ROOT = resolve(HERE, '../../..');

/**
 * The root with symlinks already resolved, so containment compares like with like.
 * A symlinked checkout would otherwise make every real path look "outside".
 */
const REPO_ROOT_REAL = tryRealpath(REPO_ROOT);

/**
 * Source trees a code reviewer has business reading. Deliberately not the whole
 * repo: `backend/src` rather than `backend`, because `backend/target` is build
 * output and `backend/.mvn` is tooling, and neither informs a review.
 */
export const ALLOWED_SUBTREES = [
  'backend/src',
  'frontend/src',
  'frontend/e2e',
  'context',
  'packages',
  '.githooks',
  '.github',
] as const;

/**
 * Individual files outside those trees. The `CLAUDE.md` files are here because they
 * are where the project's rules are written — a reviewer that cannot read the rules
 * it is enforcing is guessing. `render.yaml` describes the deploy the rules protect.
 */
export const ALLOWED_ROOT_FILES = [
  'CLAUDE.md',
  'backend/CLAUDE.md',
  'frontend/CLAUDE.md',
  'render.yaml',
] as const;

/** Build output and dependency trees: large, uninformative, and inside allowed subtrees. */
export const DENIED_SEGMENTS = ['node_modules', 'target', 'dist', 'build'] as const;

/**
 * Every path segment beginning with `.` is refused except these two. Dotfiles are
 * where secrets and machine state live (`.env`, `.git/config`, `.idea`), so the rule
 * is stated as "deny the class, list the exceptions" rather than as a list of the
 * dotfiles thought of today.
 */
const ALLOWED_DOT_SEGMENTS = ['.githooks', '.github'] as const;

export type PathDecision =
  | { ok: true; absolute: string; relative: string }
  | { ok: false; reason: string };

/**
 * Decide whether a model-supplied path may be read.
 *
 * The order of the checks is the point:
 *
 *   1. reject a path that cannot be reasoned about at all (empty, NUL byte);
 *   2. resolve it against the repo root, which collapses `..` and `.`;
 *   3. resolve symlinks — of the deepest existing ancestor, so a symlinked parent
 *      directory cannot smuggle a non-existent child out of the repo;
 *   4. confirm the result is still inside the repo;
 *   5. confirm it is allow-listed;
 *   6. confirm no segment is denied.
 *
 * Doing (5) before (2) would be the classic mistake: `backend/src/../../.env` starts
 * with an allow-listed prefix and points at the key. Doing (6) before (5) would be
 * harmless but would report the less useful of two true reasons.
 */
export function resolveReadablePath(input: string): PathDecision {
  if (typeof input !== 'string' || input.trim() === '') {
    return { ok: false, reason: 'no path given' };
  }
  if (input.includes('\0')) {
    return { ok: false, reason: 'path contains a NUL byte' };
  }

  const absolute = realpathDeepest(resolve(REPO_ROOT_REAL, input.trim()));

  const relativePath = insideRepo(absolute);
  if (relativePath === null) {
    return { ok: false, reason: `outside the repository: ${input}` };
  }
  if (relativePath === '') {
    return { ok: false, reason: 'the repository root is not a file' };
  }

  if (!isAllowListed(relativePath)) {
    return {
      ok: false,
      reason:
        `not in the reviewer's allow-list: ${relativePath}. ` +
        `Readable: ${ALLOWED_SUBTREES.join(', ')}, plus ${ALLOWED_ROOT_FILES.join(', ')}`,
    };
  }

  const denied = firstDeniedSegment(relativePath);
  if (denied !== null) {
    return { ok: false, reason: `refused: "${denied}" is not readable (${relativePath})` };
  }

  // A directory is not a read error waiting to happen — say so plainly instead.
  if (isDirectory(absolute)) {
    return { ok: false, reason: `${relativePath} is a directory, not a file` };
  }

  return { ok: true, absolute, relative: relativePath };
}

/**
 * Decide whether a model-supplied path may be *searched*.
 *
 * The same policy as `resolveReadablePath` with one difference, and the difference is the
 * reason this function exists rather than a flag: a search root is normally a directory,
 * and `resolveReadablePath` answers `"backend/src is a directory, not a file"` — a true
 * statement and a useless one when the question was "may I grep here".
 *
 * Written here rather than in the caller on purpose. Containment is `insideRepo`'s
 * `path.relative` idiom plus the allow-list plus the denied segments, and a second copy of
 * that reasoning next to the SDK's permission hook would be exactly the shape project rule
 * 3 exists to catch — vendor-adjacent code re-deciding a policy that already has one home.
 * The two entry points differ in what they permit at the end, not in how they contain.
 *
 * The repo root is refused. That is the whole point: the built-in `Grep` defaults its
 * `path` to the working directory, so an unscoped search reads `.env`, and the caller
 * refuses an absent `path` for the same reason this refuses an explicit `.`.
 */
export function resolveSearchRoot(input: string): PathDecision {
  if (typeof input !== 'string' || input.trim() === '') {
    return { ok: false, reason: 'no search path given' };
  }
  if (input.includes('\0')) {
    return { ok: false, reason: 'path contains a NUL byte' };
  }

  const absolute = realpathDeepest(resolve(REPO_ROOT_REAL, input.trim()));

  const relativePath = insideRepo(absolute);
  if (relativePath === null) {
    return { ok: false, reason: `outside the repository: ${input}` };
  }
  if (relativePath === '') {
    return {
      ok: false,
      reason:
        'the repository root is too broad to search — it contains .env. ' +
        `Search one of: ${ALLOWED_SUBTREES.join(', ')}`,
    };
  }

  if (!isAllowListed(relativePath)) {
    return {
      ok: false,
      reason:
        `not in the reviewer's allow-list: ${relativePath}. ` +
        `Searchable: ${ALLOWED_SUBTREES.join(', ')}, plus ${ALLOWED_ROOT_FILES.join(', ')}`,
    };
  }

  const denied = firstDeniedSegment(relativePath);
  if (denied !== null) {
    return { ok: false, reason: `refused: "${denied}" is not readable (${relativePath})` };
  }

  return { ok: true, absolute, relative: relativePath };
}

/**
 * Repo-relative, `/`-separated. `null` when the path escapes the repo, and `''` — a
 * distinct answer, not a synonym for `null` — when it *is* the repo root.
 *
 * The two are kept apart because both callers must refuse the root and neither may call
 * it an escape. "Outside the repository: ." is a false statement about the one path that
 * is most obviously inside it, and a model reading that reason would try a different
 * spelling of the same request instead of narrowing its scope.
 */
function insideRepo(absolute: string): string | null {
  // path.relative is the containment idiom: a naive `startsWith(root)` also accepts
  // a sibling directory whose name merely begins with the root's name.
  const rel = relative(REPO_ROOT_REAL, absolute);
  if (rel === '') return '';
  // `..` catches a traversal within one drive; `isAbsolute` catches a different
  // drive, where win32's `relative` returns the target unchanged rather than a
  // sequence of `..`. Removing either one was watched passing 47 of 50 tests.
  if (rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) return null;
  return rel.split(sep).join('/');
}

function isAllowListed(relativePath: string): boolean {
  const path = fold(relativePath);
  if (ALLOWED_ROOT_FILES.some((file) => fold(file) === path)) return true;
  return ALLOWED_SUBTREES.some((subtree) => {
    const prefix = fold(subtree);
    return path === prefix || path.startsWith(`${prefix}/`);
  });
}

function firstDeniedSegment(relativePath: string): string | null {
  for (const segment of relativePath.split('/')) {
    if (isDeniedSegment(segment)) return segment;
  }
  return null;
}

/**
 * Whether one path segment is refused on its own name — build output, a dependency
 * tree, or a dotfile that is not one of the two exceptions.
 *
 * Exported so a directory walk can prune a whole tree by its name instead of
 * re-deciding the policy: `resolveReadablePath` answers about files, and a walker
 * asking it about a directory would either get "is a directory" or have to invent a
 * filename to append.
 */
export function isDeniedSegment(segment: string): boolean {
  if (DENIED_SEGMENTS.some((denied) => fold(denied) === fold(segment))) return true;
  return (
    segment.startsWith('.') && !ALLOWED_DOT_SEGMENTS.some((ok) => fold(ok) === fold(segment))
  );
}

/**
 * Case folding is applied to both the allow-list and the deny-list on Windows,
 * because each direction is a hazard on its own: a case-sensitive deny-list lets
 * `.ENV` through on a filesystem that will happily open it, and a case-sensitive
 * allow-list refuses `Backend/Src/...`, which is the same file. Off Windows, case
 * is meaningful and is left alone.
 */
function fold(value: string): string {
  return process.platform === 'win32' ? value.toLowerCase() : value;
}

/**
 * Resolve symlinks as far down as the filesystem allows, then re-attach the
 * segments that do not exist yet. Plain `realpathSync` throws on a missing target,
 * and skipping the call in that case would leave a hole: `context/link/../../.env`
 * where `context/link` is a symlink resolves differently before and after the link
 * is followed.
 */
function realpathDeepest(absolute: string): string {
  let current = absolute;
  const missing: string[] = [];
  for (;;) {
    const real = tryRealpath(current, null);
    if (real !== null) {
      return missing.length === 0 ? real : resolve(real, ...missing.reverse());
    }
    const parent = dirname(current);
    if (parent === current) return absolute; // walked up to a root that does not exist
    missing.push(basename(current));
    current = parent;
  }
}

function tryRealpath(path: string): string;
function tryRealpath(path: string, fallback: null): string | null;
function tryRealpath(path: string, fallback: string | null = path): string | null {
  try {
    return realpathSync(path);
  } catch {
    return fallback;
  }
}

function isDirectory(absolute: string): boolean {
  try {
    return statSync(absolute).isDirectory();
  } catch {
    return false; // does not exist: not a directory, and the caller reports the miss
  }
}
