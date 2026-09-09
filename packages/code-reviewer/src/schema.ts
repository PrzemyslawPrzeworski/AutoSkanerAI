/**
 * The structured output the reviewer asks a model for, and the outcome the
 * reviewer returns.
 *
 * Those are deliberately two different shapes. What the model produces is
 * observations; what the reviewer returns is a conclusion. `verdict` used to sit
 * in the model's schema, where nothing could check it — a model was free to answer
 * "pass" with three blockers listed underneath, and the exit code followed it.
 * The rule ("fail iff at least one blocker or major") is now code, in `verdict.ts`,
 * and the model is no longer asked a question it cannot be held to.
 *
 * This module has three consumers: the reviewer, the tests that pin its degenerate
 * cases, and — later — the promptfoo evals that compare models against one shape.
 */
import { z } from 'zod';

export const Severity = z.enum(['blocker', 'major', 'minor', 'nit']);

export type Severity = z.infer<typeof Severity>;

/**
 * A path the model did not get from the diff, plus what it shows.
 *
 * Rule 3 — "vendor detail belongs in one adapter" — is structurally unanswerable
 * from a diff alone, because the *first* copy of the duplicated detail is never in
 * it. Once the agent has tools (phase 3), "this prefix already exists at
 * MarketPriceFetchService:28" becomes its most valuable finding, and that file is
 * off-diff by definition. So off-diff paths get a field of their own rather than
 * being either banned or silently mixed into `file`.
 *
 * Phase 4 validates these against the paths the tools actually returned. Until the
 * tools exist there is nothing to validate against, so the prompt tells the model
 * to leave it out.
 */
export const Evidence = z.object({
  file: z.string().describe('Repo-relative path a tool returned, NOT a path from the diff'),
  note: z.string().describe('What that file shows, in one sentence'),
});

export type Evidence = z.infer<typeof Evidence>;

export const Finding = z.object({
  file: z.string().describe('Repo-relative path THIS DIFF CHANGES, exactly as it appears in the diff'),
  severity: Severity,
  summary: z.string().describe('One sentence: what is wrong'),
  rationale: z.string().describe('Why it is wrong here, referencing the diff or a project rule'),
  evidence: Evidence.nullish().describe('Omit unless a tool gave you the path'),
});

export type Finding = z.infer<typeof Finding>;

/** What the model is asked for: observations, no conclusion. */
export const ModelReview = z.object({
  summary: z.string().describe('Two sentences at most, covering the diff as a whole'),
  findings: z.array(Finding),
});

export type ModelReview = z.infer<typeof ModelReview>;

export type Verdict = 'pass' | 'fail';

/**
 * What the reviewer returns: the model's observations plus a derived conclusion.
 *
 * The three counters are not diagnostics. Each records the model producing something
 * the rules then removed, which is the only way to tell "the reviewer found nothing"
 * from "the reviewer's findings were all discarded" — a distinction a bare verdict
 * hides, and the reason enforcement here is counted rather than silent.
 */
export interface ReviewOutcome {
  verdict: Verdict;
  summary: string;
  findings: Finding[];
  /** Findings discarded because `file` was not a path this diff changes. */
  dropped: number;
  /**
   * The paths those discarded findings named. Named and not merely counted: a dropped
   * finding is either a hallucinated path or a real problem in an untouched file, and
   * a count alone cannot tell the two apart.
   */
  droppedFiles: string[];
  /** Citations removed because no tool ever returned that path. */
  strippedEvidence: number;
}
