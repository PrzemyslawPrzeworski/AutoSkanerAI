/**
 * The CLI. Reads a unified diff on stdin, prints a structured review as JSON, and
 * exits 0 (pass), 1 (fail) or 2 (setup or provider failure).
 *
 *   git diff | npx tsx src/index.ts
 *
 * This is the ONLY module allowed to touch process.stdin, stdout, console, or
 * process.exit — env.ts reads process.env, which is configuration, not a terminal.
 * Everything below that line is a library, and `reviewDiff` in agent.ts is the whole
 * of the review. What is left here is stdin, JSON, one stderr line, and the exit code.
 */
import { reviewDiff } from './agent.ts';
import { isReviewerError } from './errors.ts';

function fail(message: string): never {
  console.error(`code-reviewer: ${message}`);
  process.exit(2);
}

async function readStdin(): Promise<string> {
  if (process.stdin.isTTY) {
    fail('no diff on stdin. Run it as: git diff | npx tsx src/index.ts');
  }
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString('utf8');
}

async function main(): Promise<void> {
  const diff = await readStdin();

  let run;
  try {
    run = await reviewDiff(diff);
  } catch (error) {
    // Every kind maps to exit 2. The kinds exist for callers that are not a terminal;
    // here they only decide the wording. What matters is that no failure exits 0:
    // "could not review" must never be indistinguishable from "found nothing wrong".
    if (isReviewerError(error)) fail(`[${error.kind}] ${error.message}`);
    // And nothing may escape to exit 1 either, which is the code for "the diff failed
    // review". Rethrowing here is what turned the first live run's crash into something
    // shaped exactly like a rejected diff. `.stack` alone, never the error object: Node's
    // own printer appends an error's enumerable properties, which for an AI SDK error is
    // the entire request body and the response headers.
    fail(
      'unexpected failure — a bug in code-reviewer, not a verdict on the diff:\n' +
        (error instanceof Error ? (error.stack ?? error.message) : String(error)),
    );
  }

  const { review, usage, modelId, steps } = run;
  console.log(JSON.stringify(review, null, 2));

  console.error(
    `code-reviewer: model=${modelId} steps=${steps} ` +
      `in=${usage.inputTokens ?? '?'} out=${usage.outputTokens ?? '?'} total=${usage.totalTokens ?? '?'} tokens` +
      (run.accessedPaths.length > 0 ? ` read=${run.accessedPaths.length} file(s)` : ' read=none'),
  );

  if (review.dropped > 0) {
    console.error(
      `code-reviewer: dropped ${review.dropped} finding(s) naming files outside the diff: ` +
        review.droppedFiles.join(', '),
    );
  }
  if (review.strippedEvidence > 0) {
    console.error(
      `code-reviewer: stripped ${review.strippedEvidence} citation(s) naming files no tool returned`,
    );
  }

  process.exit(review.verdict === 'fail' ? 1 : 0);
}

await main();
