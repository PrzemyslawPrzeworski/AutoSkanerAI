/**
 * The two rules that used to live only in the prompt.
 *
 * Both were English sentences the model was asked to obey, which means neither was
 * enforced. This repo has a name for that shape: the per-edit hook whose signal was
 * hard-wired to success and stayed dead for four months. A rule stated in a prompt
 * is a rule stated to something that may decline.
 */
import type { Finding, Verdict } from './schema.ts';

/**
 * fail iff at least one finding is a blocker or a major.
 *
 * `minor` and `nit` never fail a review. That is the whole rule, and it is now
 * three lines of code instead of one sentence of prose.
 */
export function deriveVerdict(findings: readonly Finding[]): Verdict {
  const blocking = findings.some(
    (finding) => finding.severity === 'blocker' || finding.severity === 'major',
  );
  return blocking ? 'fail' : 'pass';
}

/**
 * Splits findings by whether `file` names a path the diff actually changes.
 *
 * A finding about a file the diff never touched is not a review of this diff — it is
 * a review of the repository, arriving unasked. Dropping it is enforcement; counting
 * it is what stops the enforcement from being silent.
 *
 * Matching is deliberately forgiving about how the path is spelled — backslashes,
 * a leading `./`, a surviving `a/` or `b/` prefix, letter case. Being strict here
 * would discard genuine findings over a cosmetic slip, and the cost of being
 * lenient is at most keeping a finding whose path differs only in case.
 */
export function partitionByDiffScope(
  findings: readonly Finding[],
  changedFiles: readonly string[],
): { kept: Finding[]; dropped: Finding[] } {
  const allowed = new Set(changedFiles.map(normalisePath));
  const kept: Finding[] = [];
  const dropped: Finding[] = [];

  for (const finding of findings) {
    if (allowed.has(normalisePath(finding.file))) kept.push(finding);
    else dropped.push(finding);
  }

  return { kept, dropped };
}

function normalisePath(path: string): string {
  let value = path.trim().replace(/\\/g, '/');
  while (value.startsWith('./')) value = value.slice(2);
  if (value.startsWith('a/') || value.startsWith('b/')) value = value.slice(2);
  return value.toLowerCase();
}
