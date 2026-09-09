/**
 * The contract, and the only thing a caller of this package should have to know.
 *
 * There are two reviewers here now — one assembled from AI SDK parts against OpenRouter,
 * one on the Claude Agent SDK against Bedrock — and they exist side by side to be
 * compared, not because either is a fallback for the other. This module is what makes
 * them comparable: a caller that depends on `Reviewer` can be handed either, and the
 * promptfoo custom provider that arrives with the evals wraps this type rather than a
 * file.
 *
 * Nothing in here names a provider, an SDK, a model family, or a transport. That is a
 * constraint on this file specifically: the moment one runner's vocabulary leaks in, the
 * other has to fill fields shaped for someone else, and the comparison starts measuring
 * the adapter instead of the runners.
 */
import type { ReviewOutcome } from './schema.ts';

/**
 * Which reviewer produced a run.
 *
 * Stamped on every `ReviewRun` rather than inferred by the caller, because the two
 * runners' numbers end up in one table and a row with no provenance is not evidence.
 */
export type RunnerId = 'ai-sdk' | 'agent-sdk';

/**
 * What one review cost, in whatever terms its runner can honestly report.
 *
 * **Every field is optional, and that is the design rather than caution.** These two
 * runners do not report the same set: the AI SDK path gets token counts and no price,
 * the Agent SDK path reports a dollar figure the other cannot produce. A runner that
 * cannot supply a number leaves the field absent — never zero. `0 tokens` and `unknown
 * tokens` are different facts, and the comparison table has a column that depends on the
 * difference; defaulting to `0` would turn "not reported" into "free".
 */
export interface ReviewUsage {
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
  /** Prompt tokens served from a cache, where the runner distinguishes them. */
  cachedInputTokens?: number;
  /** The run's price, where the runner reports one. Absent is not zero. */
  costUsd?: number;
}

/**
 * What a caller may ask of any reviewer.
 *
 * Deliberately thin. Each runner extends this with its own knobs — an endpoint override,
 * an injected tool set, a credential — and those stay in the runner's own module, because
 * an option only one implementation understands is not part of a shared contract.
 */
export interface ReviewOptions {
  /** Provider-specific model identifier. Each runner supplies its own default. */
  modelId?: string;
  /** Wall-clock budget for the whole review. Each runner supplies its own default. */
  timeoutMs?: number;
}

export interface ReviewRun {
  review: ReviewOutcome;
  usage: ReviewUsage;
  modelId: string;
  runner: RunnerId;
  /**
   * Model turns, **however this runner counts them**.
   *
   * The two are related and not identical — one counts AI SDK steps, the other counts
   * what its harness calls turns — so the number is comparable in shape and not to the
   * unit. Each runner states its own meaning where it fills this in; the comparison
   * writes that down instead of implying the units match.
   */
  steps: number;
  /**
   * Repo-relative paths whose tool result actually carried content back during this run.
   *
   * A denied path is not here. An allow-listed path that does not exist is not here. This
   * is the set `stripUnbackedEvidence` checks citations against, so an over-broad answer
   * lets fabricated evidence through and an empty one strips every citation — which looks
   * like a strict reviewer and is a dead check.
   */
  accessedPaths: string[];
}

/**
 * A reviewer: a diff in, a review out. No stdin, no printing, no `process.exit`.
 *
 * Both runners must satisfy this exactly, and the compile-time proof is `index.ts`,
 * where each one is assigned to this type before being called.
 */
export type Reviewer = (diff: string, options?: ReviewOptions) => Promise<ReviewRun>;
