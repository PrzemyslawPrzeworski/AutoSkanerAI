/**
 * What counts as a reviewable diff.
 *
 * The size policy lives here rather than in the CLI that reports it, because the
 * limit is a property of the review, not of the terminal: a promptfoo eval feeding
 * the reviewer a 200 kB diff must hit the same wall.
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
