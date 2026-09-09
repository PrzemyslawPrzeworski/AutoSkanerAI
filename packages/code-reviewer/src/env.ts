/**
 * Where the reviewer's configuration comes from.
 *
 * Everything here returns a value or null. Nothing here exits, prints, or throws —
 * a missing key is a fact about the environment, and what it *means* is the
 * caller's decision: exit 2 for the CLI, a thrown error for the library, a skipped
 * test for the suite.
 */
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { REPO_ROOT } from './repo.ts';

/**
 * A free slug, so a review costs nothing — and one of the two the backend already
 * lists in `llm.openrouter.fallback-models`, so both sides fail over to the same
 * places. Free slugs disappear without warning: this reviewer's first choice,
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
export const DEFAULT_MODEL = 'nvidia/nemotron-3-super-120b-a12b:free';

/**
 * The repo keeps secrets in a gitignored root `.env`. Prefer a real environment
 * variable when one is set, so CI never depends on a file that is not committed.
 *
 * This path deliberately bypasses `resolveReadablePath`, which refuses `.env` — the
 * two are not in conflict. `repo.ts` governs paths a *model* chose; this one is
 * hard-coded, and loading our own credentials is the reason the refusal matters.
 */
export function loadRepoEnv(): void {
  if (process.env['OPENROUTER_API_KEY']) return;
  const envFile = resolve(REPO_ROOT, '.env');
  if (!existsSync(envFile)) return;
  process.loadEnvFile(envFile);
}

/** The key, or null when neither the environment nor the root `.env` supplies one. */
export function resolveApiKey(): string | null {
  return process.env['OPENROUTER_API_KEY'] ?? null;
}

export function resolveModelId(): string {
  return process.env['CODE_REVIEW_MODEL'] ?? DEFAULT_MODEL;
}
