/**
 * The structured output the reviewer asks a model for.
 *
 * This lives in its own module because it has three consumers, not one: the
 * reviewer itself, the tests that pin its degenerate cases, and — later — the
 * promptfoo evals that compare models against the same shape. A schema inlined
 * next to the call site is a schema that gets re-spelled at the second call site.
 */
import { z } from 'zod';

export const Severity = z.enum(['blocker', 'major', 'minor', 'nit']);

export type Severity = z.infer<typeof Severity>;

export const Finding = z.object({
  file: z.string().describe('Repo-relative path, exactly as it appears in the diff'),
  severity: Severity,
  summary: z.string().describe('One sentence: what is wrong'),
  rationale: z.string().describe('Why it is wrong here, referencing the diff or a project rule'),
});

export type Finding = z.infer<typeof Finding>;

/**
 * `verdict` is still here in phase 1 because this phase moves code without
 * changing behaviour. Phase 2 removes it: a conclusion the model states is a
 * conclusion nothing can check, and "fail iff at least one blocker or major" is
 * a rule that belongs in code.
 */
export const Review = z.object({
  verdict: z.enum(['pass', 'fail']),
  summary: z.string().describe('Two sentences at most, covering the diff as a whole'),
  findings: z.array(Finding),
});

export type Review = z.infer<typeof Review>;
