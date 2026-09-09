/**
 * The CLI. Reads a unified diff on stdin, prints a structured review as JSON, and
 * exits 0 (pass), 1 (fail) or 2 (setup or provider failure).
 *
 *   git diff | npx tsx src/index.ts
 *
 * This is the ONLY module allowed to touch process.stdin, stdout, console, or
 * process.exit. Everything below that line is a library, which is what makes the
 * reviewer callable from a promptfoo provider that has no terminal. Phase 4 moves
 * the model call itself below the line too; phase 1 only draws it.
 */
import { createOpenRouter } from '@openrouter/ai-sdk-provider';
import { APICallError, generateObject } from 'ai';
import { validateDiff } from './diff.ts';
import { loadRepoEnv, resolveApiKey, resolveModelId } from './env.ts';
import { SYSTEM_PROMPT, buildUserPrompt } from './prompt.ts';
import { Review } from './schema.ts';

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
  loadRepoEnv();

  const apiKey = resolveApiKey();
  if (apiKey === null) {
    fail(
      'OPENROUTER_API_KEY is not set, and the repo root .env does not supply it. ' +
        'A reviewer that cannot reach a model must say so, not pass the diff.',
    );
  }

  const diff = (await readStdin()).trim();
  const unreviewable = validateDiff(diff);
  if (unreviewable !== null) fail(unreviewable);

  const modelId = resolveModelId();
  const openrouter = createOpenRouter({ apiKey });

  let object: Review;
  let usage: { inputTokens?: number; outputTokens?: number; totalTokens?: number };
  try {
    ({ object, usage } = await generateObject({
      model: openrouter.chat(modelId),
      schema: Review,
      system: SYSTEM_PROMPT,
      prompt: buildUserPrompt(diff),
    }));
  } catch (error) {
    // A provider failure must be legible, not a wall of request body. It must also
    // never be mistaken for a passing review, so this exits 2 like any other
    // setup failure.
    if (APICallError.isInstance(error)) {
      fail(`model ${modelId} refused the call (HTTP ${error.statusCode ?? '?'}): ${error.message}`);
    }
    throw error;
  }

  const review: Review = object;
  console.log(JSON.stringify(review, null, 2));
  console.error(
    `code-reviewer: model=${modelId} in=${usage.inputTokens ?? '?'} out=${usage.outputTokens ?? '?'} total=${usage.totalTokens ?? '?'} tokens`,
  );

  process.exit(review.verdict === 'fail' ? 1 : 0);
}

await main();
