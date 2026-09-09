/**
 * How the library reports failure without owning the process.
 *
 * Before this, every failure path ran `console.error` and `process.exit(2)`, which is
 * correct for a CLI and fatal for anything else: a promptfoo provider that exits the
 * process takes the whole eval run with it. So the library throws and the CLI decides
 * what a throw means.
 *
 * `kind` exists so a caller can branch without matching on message text. The one
 * distinction it is really there to protect: `no-api-key` and `provider` are setup
 * failures, and neither may ever be reported as a passing review.
 */

export type ReviewerErrorKind =
  /** No OPENROUTER_API_KEY in the environment or the repo-root .env. */
  | 'no-api-key'
  /** Nothing on stdin, or only whitespace. */
  | 'empty-diff'
  /** Past MAX_DIFF_CHARS — reviewing a truncated diff would be reviewing a fiction. */
  | 'diff-too-large'
  /** The provider refused, or answered with an error in the body. */
  | 'provider'
  /**
   * The agent stopped without ever calling `submitReview` — the step budget running out
   * mid-tool-loop, or a model that wrote its review as prose. Deliberately an error and
   * not an empty pass: "the reviewer did not finish" and "the reviewer found nothing" are
   * opposite facts.
   *
   * The prose case is new with the tool-call answer channel and is the reason this kind
   * cannot be folded into `malformed-output`. Nothing constrains the model's decoding any
   * more, so "answered, but not where anyone is listening" is a real outcome — see
   * `tools.ts` for why that trade was worth making.
   */
  | 'no-output'
  /**
   * `submitReview` was called and its input did not fit the schema. Separate from
   * `no-output` because the two ask for different responses: an unsubmitted review wants a
   * bigger budget or a better prompt, a broken one wants a different model. Observed on
   * the first live tool-loop run — `nvidia/nemotron-3-super-120b-a12b:free` emitted a
   * doubled opening brace, `{\n{\n  "summary": …`, with correct content inside it.
   *
   * Tool arguments are text, so the format is the model's to hold and a free slug can drop
   * it. Kept distinct so a caller — an eval, later — can count format failures without
   * counting them as reviews.
   */
  | 'malformed-output'
  /**
   * The call exceeded its time budget and was aborted.
   *
   * Added after watching a free slug accept a request and then hold the socket open for
   * over ten minutes with no bytes and no error — low CPU, alive, waiting. There was no
   * timeout anywhere in this package, so the only bound was the operator's patience.
   *
   * That is survivable at a prompt and not survivable in Phase 5, which makes this a
   * commit gate: a hook that hangs forever is worse than one that fails, because a
   * developer can respond to a failure and can only kill a hang. `test-plan.md:372`
   * states the principle for the reporting case; an unbounded wait is the same defect
   * with the report merely deferred indefinitely.
   */
  | 'timeout';

export class ReviewerError extends Error {
  readonly kind: ReviewerErrorKind;

  constructor(kind: ReviewerErrorKind, message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'ReviewerError';
    this.kind = kind;
  }
}

export function isReviewerError(error: unknown): error is ReviewerError {
  return error instanceof ReviewerError;
}
