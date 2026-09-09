/**
 * What counts as a reviewable diff, and which files it changes.
 *
 * The changed-file set is the authority behind one rule: a finding must name a file
 * the diff touches. That rule was previously a sentence in the prompt, which is to
 * say it was not enforced at all.
 */

/** Guards against a runaway diff: cost and context are both bounded by this. */
export const MAX_DIFF_CHARS = 60_000;

/**
 * Returns the reason this diff cannot be reviewed, or null when it can.
 *
 * A message, not an exception and not an exit: the caller decides whether that
 * means a non-zero exit code, a thrown error, or a recorded eval failure.
 */
export function validateDiff(diff: string): string | null {
  if (diff.trim() === '') {
    return 'the diff on stdin is empty — nothing to review.';
  }
  if (diff.length > MAX_DIFF_CHARS) {
    return (
      `diff is ${diff.length} chars, over the ${MAX_DIFF_CHARS} limit. ` +
      'Review it in smaller commits rather than raising the cap blindly.'
    );
  }
  return null;
}

/**
 * Every repo-relative path this diff changes, in first-seen order, de-duplicated.
 *
 * Two sources, because neither alone is enough:
 *
 *   * a `--- ` / `+++ ` pair gives the path for an ordinary edit, and for a
 *     deletion (`+++ /dev/null`) the `--- ` side supplies it;
 *   * a `diff --git a/<old> b/<new>` header is the ONLY source when there is no
 *     pair at all — a mode-only change and a binary change both emit a header and
 *     then nothing.
 *
 * For a rename the header's `b/` side is the new path, which is the one that counts:
 * that is the file a reviewer can open.
 *
 * Known limit: a `--- ` line immediately followed by a `+++ ` line is read as a
 * header pair. In a diff OF a diff, a removed line beginning `-- ` followed by an
 * added line beginning `++ ` would be misread as one. The reviewer's input is git
 * output, so this is documented rather than defended.
 */
export function changedFiles(diff: string): string[] {
  const files: string[] = [];
  const seen = new Set<string>();

  const add = (path: string | null): void => {
    if (path === null || path === '' || seen.has(path)) return;
    seen.add(path);
    files.push(path);
  };

  const lines = diff.split(/\r?\n/);

  /** The `b/` path of the most recent header that has not yet produced a pair. */
  let unresolvedHeader: string | null = null;

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i] ?? '';

    if (line.startsWith('diff --git ')) {
      // The previous header never reached a `+++` line: mode-only or binary.
      add(unresolvedHeader);
      unresolvedHeader = parseGitHeaderTarget(line.slice('diff --git '.length));
      continue;
    }

    if (line.startsWith('--- ') && (lines[i + 1] ?? '').startsWith('+++ ')) {
      const removed = parseSide(line.slice(4));
      const added = parseSide((lines[i + 1] ?? '').slice(4));
      // `/dev/null` on the added side means a deletion; the removed side names it.
      add(added ?? removed);
      unresolvedHeader = null;
      i += 1;
    }
  }

  add(unresolvedHeader);
  return files;
}

/**
 * One side of a `---`/`+++` line: strips the `a/` or `b/` prefix, unquotes, and
 * reports `/dev/null` as null. Anything after a tab is a timestamp, not a path.
 */
function parseSide(raw: string): string | null {
  let value = raw.split('\t')[0] ?? '';
  value = value.trimEnd();
  if (value === '/dev/null') return null;
  value = unquote(value);
  if (value.startsWith('a/') || value.startsWith('b/')) value = value.slice(2);
  return value === '' ? null : value;
}

/**
 * The `b/` path out of a `diff --git` header's two operands.
 *
 * Both operands quoted is unambiguous. Unquoted, the pair is genuinely ambiguous
 * when a filename contains a space — git's own parsers have the same problem — so
 * this takes the LAST ` b/`, which is right unless a path itself contains ` b/`.
 */
function parseGitHeaderTarget(operands: string): string | null {
  const trimmed = operands.trimEnd();

  if (trimmed.startsWith('"')) {
    const closing = findClosingQuote(trimmed, 0);
    if (closing !== -1) {
      const second = trimmed.indexOf('"', closing + 1);
      if (second !== -1) {
        const end = findClosingQuote(trimmed, second);
        if (end !== -1) return stripSidePrefix(unquote(trimmed.slice(second, end + 1)));
      }
    }
    return null;
  }

  const marker = trimmed.lastIndexOf(' b/');
  if (marker === -1) return null;
  const value = trimmed.slice(marker + ' b/'.length);
  return value === '' ? null : value;
}

function stripSidePrefix(value: string): string | null {
  const stripped = value.startsWith('a/') || value.startsWith('b/') ? value.slice(2) : value;
  return stripped === '' ? null : stripped;
}

/** Index of the quote closing the one at `start`, honouring backslash escapes. */
function findClosingQuote(value: string, start: number): number {
  for (let i = start + 1; i < value.length; i += 1) {
    if (value[i] === '\\') {
      i += 1;
      continue;
    }
    if (value[i] === '"') return i;
  }
  return -1;
}

/** git quotes paths containing spaces or specials; undo the common escapes. */
function unquote(value: string): string {
  if (!value.startsWith('"') || !value.endsWith('"') || value.length < 2) return value;
  return value.slice(1, -1).replace(/\\(["\\])/g, '$1');
}
