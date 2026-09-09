/**
 * The three rules that used to live only in the prompt.
 *
 * All three were English sentences the model was asked to obey, which means none was
 * enforced. This repo has a name for that shape: the per-edit hook whose signal was
 * hard-wired to success and stayed dead for four months. A rule stated in a prompt
 * is a rule stated to something that may decline.
 *
 * The third one is not hypothetical. Phase 2's live runs asked for "leave evidence out
 * unless a tool gave you that path", there were no tools at all, and the model attached
 * evidence to every finding anyway — quoting the diff back at itself. That is the
 * argument for this file arriving as a fact.
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

/**
 * Removes `evidence` that no tool actually produced, keeping the finding.
 *
 * `evidence.file` is supposed to mean "a tool returned this path to me". Nothing made
 * that true until now: the model can write any path there, and in Phase 2 it wrote the
 * file already named in `file`, which is a citation of the diff dressed as a citation
 * of the repo. The access log from `createTools()` is the only witness to what was
 * really read, so it is the arbiter.
 *
 * The finding survives the strip. An unsupported citation makes the claim unproven,
 * not wrong, and discarding a real blocker over a bad footnote would trade a
 * cosmetic problem for a shipped bug.
 *
 * Runs after `partitionByDiffScope`, so it only ever examines findings that survived
 * scope — no point validating a citation on a finding already dropped.
 */
export function stripUnbackedEvidence(
  findings: readonly Finding[],
  accessedPaths: ReadonlySet<string>,
): { findings: Finding[]; stripped: number } {
  const accessed = new Set([...accessedPaths].map(normalisePath));
  let stripped = 0;

  const checked = findings.map((finding) => {
    const evidence = finding.evidence;
    if (evidence === null || evidence === undefined) return finding;
    if (accessed.has(normalisePath(evidence.file))) return finding;

    stripped += 1;
    const { evidence: _discarded, ...rest } = finding;
    return rest;
  });

  return { findings: checked, stripped };
}

function normalisePath(path: string): string {
  let value = path.trim().replace(/\\/g, '/');
  while (value.startsWith('./')) value = value.slice(2);
  if (value.startsWith('a/') || value.startsWith('b/')) value = value.slice(2);
  return value.toLowerCase();
}
