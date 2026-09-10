/**
 * The one test that spawns a `claude` subprocess and reaches Bedrock.
 *
 * Everything the agent-sdk runner claims about *configuration* is asserted offline in
 * `agent-sdk.test.ts`. This file asserts the two things a value cannot show: that the SDK
 * honours the options object, and that the model can still read files while answering
 * through `outputFormat`.
 *
 * **The second is the assertion this file exists for.** `../ai-sdk/SKILL.md` gotcha 8
 * records what happened on the other runner: a constrained output format suppressed tool
 * calls entirely, and the read tools became unreachable. Nothing in the Agent SDK's
 * declarations says `outputFormat` does that — it is an end-turn tool, not a decoding
 * constraint — but nothing says it does not, and the regression is invisible in the output.
 * A run that read nothing answers in perfect shape; it just has no evidence behind any
 * finding, and `stripUnbackedEvidence` reports "0 stripped" because the access log is empty,
 * which is indistinguishable from a model that cited honestly. So: `accessedPaths` must be
 * non-empty. A schema-shaped answer is not the thing being tested.
 *
 * **The fixture is `vendor-detail.diff`, and NOT `cross-file.diff`, which is the fixture
 * written for exactly this purpose and cannot serve it.** `cross-file.diff` opens with
 * twenty lines of prose explaining that the backend listens on 10000, that the root
 * CLAUDE.md says so, and that `playwright.config.ts` probes the same port — and that prose
 * is inside the file, so it is fed to the model as part of the diff. The first live run
 * against it answered correctly in 6 turns having read nothing, citing "application.
 * properties and CLAUDE.md" straight out of the preamble. A fixture that hands over the
 * answer cannot measure whether the tools are reachable. (`cross-file.diff` is left alone:
 * it is the other runner's measured fixture, and rewriting it would invalidate the numbers
 * already recorded against it. The leak is worth knowing about for those numbers too.)
 *
 * `vendor-detail.diff` is bare — no prose at all — and carries two findings from the
 * prompt's own rules. One is visible inside the diff (`catch (Exception e) { return true; }`
 * is rule 2, a fallback reporting success when its dependency is gone). The other is not:
 * whether `https://openrouter.ai/api/v1` and that header set are a *second* copy of vendor
 * detail (rule 3) can only be settled by searching the repo, where the same value already
 * lives in `application-openrouter.properties`.
 *
 * It SKIPS WITH A REASON, always printed, never silently absent — the same discipline
 * `injection.test.ts` explains, and for the same reason: a hook in this repo sat dead from
 * May to September because its signal was hard-wired to success.
 *
 * **The credential is deliberately NOT part of the skip condition**, which is a departure
 * from `injection.test.ts`. There, `resolveApiKey() === null` is a real capability check: the
 * key is either present or it is not. Here there is no variable whose value proves anything.
 * An expired SSO session leaves `AWS_PROFILE` set, `~/.aws/config` intact, and every
 * non-empty-variable check passing — so a skip gated on those would skip for a reason it
 * cannot know, or worse, run and report a credential failure as a skip. The attempt IS the
 * check, and an asked-for live run with no session is a failure worth seeing. That is the
 * same correction this repo's `require_java` had to make when `JAVA_HOME` pointed at a JRE
 * with no `javac`: look for the capability, not for the variable.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { test } from 'node:test';
import { DEFAULT_BEDROCK_MODEL, reviewDiffWithAgentSdk } from './agent-sdk.ts';
import { changedFiles } from './diff.ts';

const vendorDetailDiff = readFileSync(
  resolve(import.meta.dirname, '../fixtures/vendor-detail.diff'),
  'utf8',
);

/**
 * `npm_lifecycle_event` is npm's own name for the running script, so `test:live` and `test`
 * are distinguishable with no shell syntax and no dependency — `VAR=1 cmd` is a POSIX-ism
 * cmd.exe mangles silently. `CODE_REVIEW_LIVE=1` still works when node is invoked directly.
 */
function liveRunRequested(): boolean {
  return (
    process.env['CODE_REVIEW_LIVE'] === '1' || process.env['npm_lifecycle_event'] === 'test:live'
  );
}

function whySkipped(): string | false {
  if (!liveRunRequested()) {
    return (
      'offline run — this test spawns a claude subprocess and calls Bedrock. ' +
      'Run `npm run test:live` to include it (an active AWS SSO session is required).'
    );
  }
  return false;
}

test(
  'the agent-sdk runner reviews a diff, reads the repo, and answers in the schema',
  {
    skip: whySkipped(),
    // A subprocess spawn plus a multi-turn Sonnet review, against the runner's own 120 s
    // internal bound — this has to be the looser of the two or the test kills the thing it
    // is measuring before the runner can report a timeout by name.
    timeout: 240_000,
  },
  async () => {
    const run = await reviewDiffWithAgentSdk(vendorDetailDiff);
    const { review, usage, modelId, runner, steps, accessedPaths } = run;

    assert.equal(runner, 'agent-sdk', 'the run must stamp its own provenance');
    assert.equal(modelId, DEFAULT_BEDROCK_MODEL);

    // The answer arrived through the end-turn tool attachment and parsed as `ModelReview`.
    // `reviewDiffWithAgentSdk` throws on anything else, so reaching here is most of the
    // claim; this pins the shape it reached here with.
    assert.ok(typeof review.summary === 'string' && review.summary.trim() !== '');
    assert.ok(Array.isArray(review.findings));
    assert.ok(review.verdict === 'pass' || review.verdict === 'fail');

    // THE ASSERTION THIS FILE IS FOR. See the module comment: an empty access log with a
    // correctly shaped answer is exactly what a suppressed-tool-use regression looks like,
    // and it cannot be seen from the review.
    assert.ok(
      accessedPaths.length > 0,
      `the reviewer answered without reading anything. Either outputFormat suppressed tool ` +
        `use, or the permission hook refused every call. verdict=${review.verdict} ` +
        `turns=${steps} summary: ${review.summary}`,
    );

    // Containment, checked against the outcome rather than against the policy: nothing the
    // hook refuses can appear in the log, and `.env` is the path the whole allow-list exists
    // for. This asserts the two layers agree, not that the model behaved.
    for (const path of accessedPaths) {
      assert.doesNotMatch(path, /(^|\/)\.env/, `a tool returned ${path}`);
      assert.doesNotMatch(path, /(^|\/)(node_modules|target|dist|build)(\/|$)/, `read ${path}`);
    }

    // Every surviving finding names a file this diff changes. `partitionByDiffScope` enforces
    // it, so this is a check on the wiring — that the enforcement runs in this runner too,
    // and before the verdict is derived rather than after.
    const changed = new Set(changedFiles(vendorDetailDiff).map((file) => file.toLowerCase()));
    for (const finding of review.findings) {
      assert.ok(
        changed.has(finding.file.replace(/\\/g, '/').toLowerCase()),
        `finding names ${finding.file}, which this diff does not change`,
      );
    }

    assert.ok(steps > 0, 'a review that took no turns did not happen');

    // The prompt's rules reached the model. One bit, and the loosest form of it that is still
    // worth failing on: this fixture ends `catch (Exception e) { return true; }`, which is
    // rule 2 verbatim — a fallback reporting success when its dependency is gone — so a review
    // of it with nothing to say means the rules did not arrive, not that the code is fine.
    // Counted as `findings + dropped` rather than `findings`, because the honest thing to
    // assert here is that the MODEL produced findings; whether `partitionByDiffScope` then
    // kept them depends on which file the model named for the duplicated base URL, and that
    // is scoping working as designed, not a failure of the run.
    assert.ok(
      review.findings.length + review.dropped > 0,
      `the reviewer found nothing in a diff that swallows an exception and returns true. ` +
        `The rules are probably not reaching the model. summary: ${review.summary}`,
    );

    // Printed on success as well as failure. This is the only place the two runners' costs
    // can be compared, and `pick.md` needs the numbers more than the assertions do —
    // `costUsd` is the figure the AI SDK runner cannot produce at all.
    console.error(
      `agent-sdk.live: model=${modelId} turns=${steps} verdict=${review.verdict} ` +
        `findings=${review.findings.length} read=${accessedPaths.length} ` +
        `in=${usage.inputTokens ?? '?'} out=${usage.outputTokens ?? '?'} ` +
        `cost=${usage.costUsd === undefined ? 'n/a' : `$${usage.costUsd.toFixed(4)}`} ` +
        `dropped=${review.dropped} stripped=${review.strippedEvidence}`,
    );
    console.error(`agent-sdk.live: read ${accessedPaths.join(', ')}`);
  },
);

test('an unreviewable diff is refused before anything is spawned', async () => {
  // Offline, and it belongs here rather than in `agent-sdk.test.ts` because it is a claim
  // about `reviewDiffWithAgentSdk` — the function that spawns — and not about the options
  // object. An empty diff must never reach the subprocess, and must never be reported as a
  // review that found nothing wrong.
  await assert.rejects(
    () => reviewDiffWithAgentSdk('   \n  '),
    (error: unknown) => {
      assert.equal((error as { kind?: string }).kind, 'empty-diff');
      return true;
    },
  );
});
