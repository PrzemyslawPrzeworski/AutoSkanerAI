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
 * A free slug, so a review costs nothing — and free slugs disappear without warning:
 * this reviewer's first choice, `meta-llama/llama-3.3-70b-instruct:free`, answered
 * 404 "unavailable for free" on 2026-09-09, exactly the fragility
 * `application-openrouter.properties` documents. When this one goes, list the live
 * ones with `curl -s https://openrouter.ai/api/v1/models` and filter `:free` for
 * `tools` in `supported_parameters` — `structured_outputs` is no longer required,
 * because the review is submitted as a tool call rather than as a response format
 * (see `tools.ts`). Five free slugs had both on 2026-09-09.
 *
 * The one requirement that replaces `structured_outputs` is harder to read off a
 * capability list: the model has to actually *use* the tool channel. Two candidates
 * failed that, both measured on `fixtures/bad.diff`:
 *
 * - `nex-agi/nex-n2.5-pro:free` — the previous default — stopped after one step
 *   without calling `submitReview` at all, twice. A review that arrives as prose is
 *   reported as `no-output`, which is correct and is also no review.
 * - `nvidia/nemotron-3-super-120b-a12b:free` advertises `structured_outputs` and
 *   REPRODUCIBLY breaks JSON in a tool loop: a doubled opening brace,
 *   `{\n{\n  "summary": …`, with entirely correct content inside, twice with
 *   `finishReason: 'stop'`. It also attached fabricated `evidence` to every finding
 *   after its own reasoning trace said not to.
 *
 * This one is the slug verified end to end after the switch to a tool-call answer:
 * it read 14 files in 4 steps on `fixtures/cross-file.diff`, found the defect that is
 * invisible inside that diff, and cited a real path in `evidence` that survived the
 * access-log check. It is not immune to the format problem — one run in four sent
 * `findings` as a stringified array, reported as `malformed-output` naming the field —
 * which is what a free tier costs. `nex-agi/nex-n2.5-mini:free` remains an untested
 * fallback under this design; its daily quota was spent before it could be measured.
 *
 * Override with CODE_REVIEW_MODEL rather than editing this. Not OPENROUTER_MODEL:
 * that variable already means "the model the Spring app analyses listings with",
 * and one name for two budgets is how a shared default silently changes an
 * unrelated thing.
 */
export const DEFAULT_MODEL = 'dots-studio/dots-3-note-preview:free';

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
