/**
 * Step 1 spike (M5-L2): the smallest thing that proves the wiring.
 *
 *   git diff | npx tsx src/index.ts
 *
 * Reads a unified diff on stdin, asks one model for a structured verdict, prints
 * it as JSON, and exits non-zero when the verdict is `fail`.
 *
 * Deliberately ONE file. Lesson step 2 converts it into a modular ToolLoopAgent
 * with the schema and the prompt extracted; keeping them inline here is what
 * makes that refactor a real one.
 */
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { createOpenRouter } from '@openrouter/ai-sdk-provider';
import { APICallError, generateObject } from 'ai';
import { z } from 'zod';

// --- config -----------------------------------------------------------------

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '../../..');

/**
 * A free slug, so a review costs nothing — and one of the two the backend already
 * lists in `llm.openrouter.fallback-models`, so both sides fail over to the same
 * places. Free slugs disappear without warning: this spike's first choice,
 * `meta-llama/llama-3.3-70b-instruct:free`, answered 404 "unavailable for free"
 * on 2026-09-09, which is exactly the fragility
 * `application-openrouter.properties` documents. When this one goes, list the
 * live ones with `curl -s https://openrouter.ai/api/v1/models` and filter for
 * `:free` with `structured_outputs` in `supported_parameters` — only 5 of 18 free
 * models have it, and this reviewer needs it.
 *
 * Override with CODE_REVIEW_MODEL rather than editing this. Not OPENROUTER_MODEL:
 * that variable already means "the model the Spring app analyses listings with",
 * and one name for two budgets is how a shared default silently changes an
 * unrelated thing.
 */
const DEFAULT_MODEL = 'nvidia/nemotron-3-super-120b-a12b:free';

/** Guards against a runaway diff: cost and context are both bounded by this. */
const MAX_DIFF_CHARS = 60_000;

// --- output schema ----------------------------------------------------------

const Severity = z.enum(['blocker', 'major', 'minor', 'nit']);

const Finding = z.object({
  file: z.string().describe('Repo-relative path, exactly as it appears in the diff'),
  severity: Severity,
  summary: z.string().describe('One sentence: what is wrong'),
  rationale: z.string().describe('Why it is wrong here, referencing the diff or a project rule'),
});

const Review = z.object({
  verdict: z.enum(['pass', 'fail']),
  summary: z.string().describe('Two sentences at most, covering the diff as a whole'),
  findings: z.array(Finding),
});

type Review = z.infer<typeof Review>;

// --- prompt -----------------------------------------------------------------

const SYSTEM_PROMPT = `You review diffs for AutoSkanerAI, an AI-powered used-car listing analyzer for the Polish market (Spring Boot 4 + Java 21 backend, Angular 21 + TypeScript frontend).

Review only what the diff changes. Do not comment on code that merely appears as context, and do not ask for work the diff does not touch.

Project rules that outrank general style preferences:

1. Absence of accident data means UNKNOWN, never "clean". Any prompt text, API response, or UI copy that presents missing history as confirmation of a clean history is a blocker.
2. A layer must fail loudly when its own dependency or toolchain is missing. A swallowed error, an empty catch, or a fallback that reports success is a blocker, not a nit.
3. Vendor detail (a third-party URL prefix, header set, or SDK type) belongs in one adapter. A second copy of it is a major finding.
4. Tests: no waitForTimeout and no CSS/XPath locators in E2E specs; assert on behaviour, not on implementation shape.

Set verdict to "fail" if and only if at least one finding is a blocker or a major. Return an empty findings array when the diff is fine — inventing a nit to look thorough is a failure of this review, not a courtesy.`;

// --- runtime ----------------------------------------------------------------

/**
 * The repo keeps secrets in a gitignored root `.env`. Prefer a real environment
 * variable when one is set, so CI never depends on a file that is not committed.
 */
function loadRepoEnv(): void {
  if (process.env['OPENROUTER_API_KEY']) return;
  const envFile = resolve(REPO_ROOT, '.env');
  if (!existsSync(envFile)) return;
  process.loadEnvFile(envFile);
}

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

  const apiKey = process.env['OPENROUTER_API_KEY'];
  if (!apiKey) {
    fail(
      'OPENROUTER_API_KEY is not set, and the repo root .env does not supply it. ' +
        'A reviewer that cannot reach a model must say so, not pass the diff.',
    );
  }

  const diff = (await readStdin()).trim();
  if (diff === '') fail('the diff on stdin is empty — nothing to review.');
  if (diff.length > MAX_DIFF_CHARS) {
    fail(
      `diff is ${diff.length} chars, over the ${MAX_DIFF_CHARS} limit. ` +
        'Review it in smaller commits rather than raising the cap blindly.',
    );
  }

  const modelId = process.env['CODE_REVIEW_MODEL'] ?? DEFAULT_MODEL;
  const openrouter = createOpenRouter({ apiKey });

  let object: Review;
  let usage: { inputTokens?: number; outputTokens?: number; totalTokens?: number };
  try {
    ({ object, usage } = await generateObject({
      model: openrouter.chat(modelId),
      schema: Review,
      system: SYSTEM_PROMPT,
      prompt: `Review this diff.\n\n\`\`\`diff\n${diff}\n\`\`\``,
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
