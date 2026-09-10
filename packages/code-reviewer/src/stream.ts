/**
 * What actually happened during an Agent SDK run, reconstructed from its message stream.
 *
 * The SDK does not hand back an access log. It streams messages, and the fact this module
 * exists to recover — *which files did a tool really return content for* — is spread across
 * two of them: the `tool_use` block in an assistant message says what was asked, and the
 * `tool_result` block in the following user message says whether it worked. Neither alone
 * is the answer. A `tool_use` the hook denied looks identical to one that succeeded until
 * its result arrives.
 *
 * That set is not a metric. It is the input to `stripUnbackedEvidence`, which removes any
 * finding citation naming a file no tool returned — so this module decides whether the
 * evidence check works at all, and **both of its failure modes are silent**:
 *
 *   - too broad: fabricated citations pass, and the reviewer vouches for files it never saw;
 *   - too narrow: every citation is stripped, the review still looks complete, and a check
 *     that reports "0 stripped" because it recorded nothing is indistinguishable from a
 *     model that cited honestly.
 *
 * The second is why `stream.test.ts` asserts the non-empty direction first. It is the same
 * reasoning that made `scripts/run-tests.mjs` refuse a run that collected zero tests.
 *
 * `observe` takes `unknown` rather than the SDK's `SDKMessage`. That is deliberate: the
 * union has forty members and constructing a valid one by hand costs more than the test is
 * worth, so the specs use minimal literal fixtures and this module narrows. The tie to the
 * SDK is therefore by field name, and every field name below is cited to the declaration it
 * came from so a version bump can be re-checked rather than re-guessed.
 */
import { resolveReadablePath } from './repo.ts';
import type { ReviewUsage } from './reviewer.ts';

/** Tools whose results may contribute to the access log. Matches `permission.ts`. */
const READ_TOOLS = new Set(['Read', 'Grep']);

/**
 * The terminal `result` message, reduced to what a `ReviewRun` needs.
 *
 * `text` is the assistant's final prose (`SDKResultSuccess.result`) and `structuredOutput`
 * is `structured_output`, which is populated only when the runner asked for an
 * `outputFormat`. Phase 3 measures which of the two channels the review should arrive on,
 * so both are carried here and neither is privileged.
 */
export interface StreamResult {
  /** `subtype: 'success'`, as opposed to an error result such as `error_max_budget_usd`. */
  ok: boolean;
  /** The result subtype verbatim, so a failure can be reported by its own name. */
  subtype: string;
  text: string | null;
  structuredOutput: unknown;
  usage: ReviewUsage;
  /** `num_turns` — the Agent SDK's own count, which is not an AI SDK step. */
  turns: number;
  /**
   * Tool calls the permission hook refused, from `permission_denials`.
   *
   * The SDK records these for us, which is worth more than it looks: it means a
   * containment claim can be checked against the run's own report rather than against a
   * log line this code chose to write.
   */
  denials: { toolName: string; toolUseId: string }[];
}

/**
 * One `system` / `api_retry` notice whose failure the provider classified as authentication.
 *
 * **Measured, and the reason this type exists is that ignoring it cost 127 seconds and the
 * wrong diagnosis.** With static AWS keys the provider rejects, the subprocess reports
 * `{ type: 'system', subtype: 'api_retry', attempt: 1, max_retries: 10, error_status: 403,
 * error: 'authentication_failed' }` at 1.9 s and then backs off — 0.6 s, 1.2 s, 2.2 s,
 * 4.8 s, 9.5 s, 18.2 s — a schedule whose ten attempts add up past any wall clock a
 * reviewer would set. So the run does not fail; it *hangs*, and the only thing that ends it
 * is the runner's own `AbortController`, which reports a slow model.
 *
 * That is the expired-SSO-session case, and it is the one `errors.ts` calls the dangerous
 * one: the profile resolves, the credentials load, and the provider refuses the signature.
 * An absent profile fails cleanly in 2.7 s with "could not load credentials"; a *rejected*
 * credential is indistinguishable from slowness unless this notice is read.
 */
export interface AuthRetryNotice {
  /** Which attempt failed — 1-based, as the SDK numbers them. */
  attempt: number;
  /** The provider's HTTP status, where the notice carries one. */
  status: number | null;
  /** The SDK's own classification string, e.g. `authentication_failed`. */
  error: string | null;
}

export interface StreamCollector {
  observe(message: unknown): void;
  /** Repo-relative, `/`-separated paths whose tool result carried content back. */
  readonly accessedPaths: Set<string>;
  /**
   * Every retry the provider refused on authentication, in order.
   *
   * Recorded rather than acted on: how many of these is enough to call the credential
   * unusable is a policy, and it belongs to the runner that has to decide whether to keep
   * waiting. This module's job is to stop the fact being thrown away.
   */
  readonly authRetries: AuthRetryNotice[];
  /** The terminal result, or `null` if the stream ended without one. */
  readonly result: StreamResult | null;
}

/** A `tool_use` seen but not yet answered, keyed by its id. */
interface PendingCall {
  name: string;
  input: unknown;
}

export function createStreamCollector(): StreamCollector {
  const accessedPaths = new Set<string>();
  const authRetries: AuthRetryNotice[] = [];
  const pending = new Map<string, PendingCall>();
  let result: StreamResult | null = null;

  function record(rawPath: unknown): void {
    if (typeof rawPath !== 'string' || rawPath.trim() === '') return;
    // Normalised through the allow-list, not through `path.relative`, and that is a second
    // independent gate rather than a convenience. `stripUnbackedEvidence` compares
    // repo-relative `/`-separated spellings (`verdict.test.ts:133` pins that), and running
    // the path back through the policy means a path the hook somehow let through still
    // cannot enter the log — so a bypassed hook degrades the reviewer's sight rather than
    // its integrity.
    const decision = resolveReadablePath(rawPath);
    if (decision.ok) accessedPaths.add(decision.relative);
  }

  function observeToolUse(blocks: unknown): void {
    for (const block of asArray(blocks)) {
      if (field(block, 'type') !== 'tool_use') continue;
      const id = field(block, 'id');
      const name = field(block, 'name');
      if (typeof id !== 'string' || typeof name !== 'string') continue;
      pending.set(id, { name, input: (block as { input?: unknown }).input });
    }
  }

  function observeToolResult(message: Record<string, unknown>): void {
    const inner = message['message'];
    const blocks = asArray(field(inner, 'content'));
    const results = blocks.filter((block) => field(block, 'type') === 'tool_result');

    // `tool_use_result` is a sibling of `message`, not a member of it, and it is singular —
    // so it can only be attributed when the message carries exactly one `tool_result`.
    // With two, which one it describes is unknowable, and guessing would put a filename
    // into an access log on the strength of message ordering.
    const structured = results.length === 1 ? message['tool_use_result'] : undefined;

    for (const block of results) {
      const id = field(block, 'tool_use_id');
      if (typeof id !== 'string') continue;
      const call = pending.get(id);
      // No matching `tool_use`: correlation is by id and never by position, so an
      // uncorrelated result is dropped rather than attributed to the last call seen.
      if (call === undefined) continue;
      pending.delete(id);

      if (!READ_TOOLS.has(call.name)) continue;
      // A denied call arrives as an error result, so this one line covers both the refusal
      // and the genuine read failure. Neither returned content; neither may back a citation.
      if (field(block, 'is_error') === true) continue;
      if (!carriesContent(field(block, 'content'))) continue;

      if (call.name === 'Read') {
        // `FileReadInput.file_path` (sdk-tools.d.ts:805). The structured
        // `FileReadOutput.file.filePath` (sdk-tools.d.ts:208) is preferred when present
        // because it is what the tool actually opened rather than what was asked for.
        record(field(field(structured, 'file'), 'filePath') ?? field(call.input, 'file_path'));
        continue;
      }

      // `GrepOutput.filenames` (sdk-tools.d.ts:3443) is the only structured record of which
      // files a search touched; the `path` in `GrepInput` is the subtree it searched, not a
      // file it found, and recording that would vouch for every file underneath it.
      //
      // Without the structured output a Grep therefore contributes NOTHING. That is the
      // conservative direction — citations get stripped rather than fabricated evidence
      // admitted — but it is a real gap, not a design: it makes a grep-only run's citations
      // unbackable. Parsing the text was rejected rather than forgotten. A content-mode hit
      // is `path:line:text`, and splitting on the first colon takes `D` off a Windows
      // absolute path, so the parser would be wrong exactly where this project runs.
      for (const filename of asArray(field(structured, 'filenames'))) record(filename);
    }
  }

  /**
   * A `system` message, of which exactly one subtype is read.
   *
   * Only the authentication classification is recorded. A 429 or a 5xx is the provider
   * being busy, and a retry is the correct answer to it — treating those as terminal would
   * turn a throttle into a failed review, which is the opposite mistake and the one this
   * package already decided against for OpenRouter quota.
   */
  function observeSystem(envelope: Record<string, unknown>): void {
    if (envelope['subtype'] !== 'api_retry') return;
    const status = numberOrUndefined(envelope['error_status']) ?? null;
    const error = typeof envelope['error'] === 'string' ? envelope['error'] : null;
    if (error !== 'authentication_failed' && status !== 401 && status !== 403) return;
    authRetries.push({
      attempt: numberOrUndefined(envelope['attempt']) ?? authRetries.length + 1,
      status,
      error,
    });
  }

  return {
    observe(message: unknown): void {
      if (typeof message !== 'object' || message === null) return;
      const envelope = message as Record<string, unknown>;
      switch (envelope['type']) {
        case 'system':
          // The one message type outside the tool loop that carries a fact this reviewer
          // cannot afford to discard. See `AuthRetryNotice`.
          observeSystem(envelope);
          return;
        case 'assistant':
          // `SDKAssistantMessage.message` is a Messages API message; tool calls are
          // `tool_use` blocks in its `content` (sdk.d.ts:SDKAssistantMessage).
          observeToolUse(field(envelope['message'], 'content'));
          return;
        case 'user':
          observeToolResult(envelope);
          return;
        case 'result':
          // The LAST result wins. Usage and cost are documented as cumulative running
          // totals — each result carries the total so far — so summing across results
          // would double-count a multi-turn session.
          result = readResult(envelope);
          return;
        default:
          // Every other member of `SDKMessage`: partial assistant, status, notifications.
          // Ignored by name-of-what-we-want rather than by a deny-list, so a new message
          // type in a later SDK cannot accidentally contribute a path.
          return;
      }
    },
    accessedPaths,
    authRetries,
    get result() {
      return result;
    },
  };
}

function readResult(envelope: Record<string, unknown>): StreamResult {
  const subtype = typeof envelope['subtype'] === 'string' ? envelope['subtype'] : 'unknown';
  const text = typeof envelope['result'] === 'string' ? envelope['result'] : null;
  const turns = typeof envelope['num_turns'] === 'number' ? envelope['num_turns'] : 0;

  const denials: { toolName: string; toolUseId: string }[] = [];
  for (const denial of asArray(envelope['permission_denials'])) {
    const toolName = field(denial, 'tool_name');
    const toolUseId = field(denial, 'tool_use_id');
    if (typeof toolName === 'string') {
      denials.push({ toolName, toolUseId: typeof toolUseId === 'string' ? toolUseId : '' });
    }
  }

  return {
    ok: subtype === 'success' && envelope['is_error'] !== true,
    subtype,
    text,
    structuredOutput: envelope['structured_output'],
    usage: readUsage(envelope),
    turns,
    denials,
  };
}

/**
 * Token counts and price, from `modelUsage` when it is there and from `usage` when it is not.
 *
 * `modelUsage` first because the SDK's own comment says so: `usage` is "MAIN AGENT LOOP
 * ONLY", excluding subagent and internal calls, and the declaration ends with "Prefer
 * modelUsage for token/cost accounting". This reviewer spawns no subagents, so today the
 * two should agree — reading the documented field anyway costs nothing and stops a future
 * change from quietly under-reporting.
 *
 * The two are spelled differently and that is not a typo below: `ModelUsage` is camelCase
 * (`inputTokens`, `cacheReadInputTokens`, `costUSD`) while `usage` is a `BetaUsage`, which
 * is snake_case (`input_tokens`, `cache_read_input_tokens`).
 *
 * A field that neither source supplies stays **absent**. `ReviewUsage` documents why: `0`
 * tokens and unknown tokens are different facts, and `pick.md` has a column that depends on
 * telling them apart.
 */
function readUsage(envelope: Record<string, unknown>): ReviewUsage {
  const models = envelope['modelUsage'];
  let inputTokens: number | undefined;
  let outputTokens: number | undefined;
  let cachedInputTokens: number | undefined;

  if (typeof models === 'object' && models !== null) {
    for (const entry of Object.values(models as Record<string, unknown>)) {
      inputTokens = add(inputTokens, field(entry, 'inputTokens'));
      outputTokens = add(outputTokens, field(entry, 'outputTokens'));
      cachedInputTokens = add(cachedInputTokens, field(entry, 'cacheReadInputTokens'));
    }
  }

  if (inputTokens === undefined && outputTokens === undefined) {
    const usage = envelope['usage'];
    inputTokens = numberOrUndefined(field(usage, 'input_tokens'));
    outputTokens = numberOrUndefined(field(usage, 'output_tokens'));
    cachedInputTokens = numberOrUndefined(field(usage, 'cache_read_input_tokens'));
  }

  const totalTokens =
    inputTokens === undefined && outputTokens === undefined
      ? undefined
      : (inputTokens ?? 0) + (outputTokens ?? 0);

  return {
    inputTokens,
    outputTokens,
    totalTokens,
    cachedInputTokens,
    // `total_cost_usd` is an estimate the SDK computes, not a billing statement, and it is
    // the one number the AI SDK runner cannot produce at all — which is exactly why
    // `ReviewUsage.costUsd` is optional instead of defaulted.
    costUsd: numberOrUndefined(envelope['total_cost_usd']),
  };
}

function add(total: number | undefined, value: unknown): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value)) return total;
  return (total ?? 0) + value;
}

function numberOrUndefined(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

/** One property of a value that may not be an object at all. */
function field(value: unknown, name: string): unknown {
  if (typeof value !== 'object' || value === null) return undefined;
  return (value as Record<string, unknown>)[name];
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

/**
 * Whether a `tool_result`'s content actually carried something back.
 *
 * A result whose content is an empty string, an empty array, or a single empty text block
 * is a call that returned nothing, and a file that returned nothing cannot back a citation.
 * Anthropic's content field is `string | Array<ContentBlock>`, so both shapes are real.
 */
function carriesContent(content: unknown): boolean {
  if (typeof content === 'string') return content.trim() !== '';
  if (!Array.isArray(content)) return false;
  return content.some((block) => {
    const text = field(block, 'text');
    if (typeof text === 'string') return text.trim() !== '';
    // A non-text block — an image, say — is content even though it has no text.
    return field(block, 'type') !== undefined;
  });
}
