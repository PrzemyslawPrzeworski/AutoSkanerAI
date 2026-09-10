/**
 * Whether one tool call is allowed, as a pure function.
 *
 * The Agent SDK runs Claude Code in a subprocess with the real Claude Code tools: `Read`
 * opens any absolute path, `Grep` searches whatever it is pointed at, `Bash` runs
 * commands. None of that is bounded by this repo's allow-list until something binds it,
 * and the thing that binds it is a `PreToolUse` hook calling `decideToolUse` below.
 *
 * `decideToolUse` takes a tool name and an unvalidated input and returns a value. No SDK
 * type, no subprocess, no model — which is the only reason the containment claim can be
 * tested at all. `toPreToolUseOutput` is the adapter, and it is four lines so that the
 * decision and the SDK's calling convention can be wrong independently.
 *
 * Three properties of the SDK make this file's shape non-obvious, and each is here because
 * the safe-looking version of it is the wrong one:
 *
 *   1. **A `PreToolUse` hook that returns `{}` has expressed no opinion, not a refusal.**
 *      The call falls back to the normal permission flow, which for an interactive session
 *      means a prompt and for this headless one means a refusal. So a hook shaped like
 *      "recognise the dangerous tools, deny those, fall through otherwise" is not a policy
 *      in either direction: it lets a tool a later SDK version adds past its own judgement,
 *      and it leaves the tools it means to permit at the mercy of `allowedTools`.
 *      `decideToolUse` therefore denies on the default branch, allows only two names, and
 *      `toPreToolUseOutput` states both answers explicitly.
 *   2. **`Grep`'s `path` is optional and defaults to the working directory.** The working
 *      directory is the repo root, which holds the gitignored `.env` and its live
 *      OpenRouter key. An absent `path` is not an incomplete request to be filled in with
 *      something sensible; it is the broadest possible search, and it is denied.
 *   3. **Search may not arrive as `Grep` at all.** The SDK's own note on `Options.tools`
 *      says native builds can provide search through `Bash` `find`/`grep` instead of the
 *      dedicated tools. `Bash` is denied here, so the runner must ask for `Grep` by name —
 *      recorded in `.claude/skills/agent-sdk/SKILL.md`, because the symptom of getting it
 *      wrong is a reviewer that quietly never searches.
 *
 * A refusal is a value, never a throw. Malformed input — a missing field, a number where a
 * string belongs — is denied with a reason the model can read, which is the same shape
 * `tools.ts:11` settled on for the AI SDK runner: an exception would end the whole review
 * over one bad guess at a filename.
 */
import { resolveReadablePath, resolveSearchRoot } from './repo.ts';

/**
 * The tools the reviewer may use, and the only two names that can be allowed.
 *
 * `Grep` is listed even though the AI SDK runner's search tool takes no path, because the
 * built-in one does and the whole question here is what it is pointed at.
 */
export const REVIEWER_TOOLS = ['Read', 'Grep'] as const;

/**
 * The tool the review itself arrives through, and the one name outside `REVIEWER_TOOLS`
 * that must be permitted. **Measured, not read.**
 *
 * `outputFormat: { type: 'json_schema' }` is documented as an "end-turn tool"
 * (sdk.d.ts:1957) and that phrase turns out to be literal: the SDK injects a tool called
 * `StructuredOutput` and the model answers by calling it. Nothing in the declarations names
 * it. `options.tools: ['Read', 'Grep']` does not remove it either — it is not a built-in the
 * runner opts into, it is the answer channel.
 *
 * So the first live run of this package failed like this, after 65 seconds and five
 * attempts:
 *
 *     Failed to provide valid structured output after 5 attempts — last StructuredOutput
 *     error: StructuredOutput is not available to the reviewer. A review reads; it do…
 *
 * That is this file's own default-branch refusal, quoted back by the SDK. The reviewer was
 * denying its own mouth. Worth dwelling on, because it is the good version of this failure:
 * the deny-by-default branch caught a tool nobody had anticipated, said which tool and why,
 * and the run ended as `error_max_structured_output_retries` rather than as an empty
 * passing review. The permissive shape — deny the tools you recognise, allow the rest —
 * would have worked here on the first try and would still be a hole.
 *
 * Kept separate from `REVIEWER_TOOLS` rather than appended to it, because the two are
 * different claims. `REVIEWER_TOOLS` is what the prompt advertises as available for
 * *reading the repo*; adding a name there would make the prompt offer the model a third
 * research tool that does not read anything. This one is allowed unconditionally and needs
 * no path check: it touches no file, runs no command, and its payload is validated against
 * `ModelReview` by the runner before it becomes a review.
 */
export const ANSWER_TOOL = 'StructuredOutput';

export type ToolDecision = { allow: true } | { allow: false; reason: string };

/** Denied, with the reason a model will actually read. */
function deny(reason: string): ToolDecision {
  return { allow: false, reason };
}

/**
 * Pull one named string out of an unvalidated tool input.
 *
 * Returns `undefined` for absent and `null` for present-but-not-a-string, because the two
 * are different refusals: `Grep` with no `path` is a scope decision the caller has to
 * make, while `Grep` with `path: 42` is a malformed call. Collapsing them would let the
 * second be reported as the first.
 */
function readString(input: unknown, field: string): string | undefined | null {
  if (typeof input !== 'object' || input === null) return null;
  const value = (input as Record<string, unknown>)[field];
  if (value === undefined) return undefined;
  return typeof value === 'string' ? value : null;
}

/**
 * Decide a single tool call against this repo's allow-list.
 *
 * The order is: name first, then the path. Denying by name before looking at any input is
 * what keeps `Write`'s `file_path` from being resolved as though the answer might be yes.
 */
export function decideToolUse(toolName: string, toolInput: unknown): ToolDecision {
  if (typeof toolName !== 'string' || toolName === '') {
    return deny('a tool call with no name is refused');
  }

  if (toolName === 'Read') {
    const path = readString(toolInput, 'file_path');
    if (path === undefined) return deny('Read needs a file_path');
    if (path === null) return deny('Read needs file_path to be a string');
    const decision = resolveReadablePath(path);
    // The reason is carried through rather than rewritten. `repo.ts` knows which of its
    // checks refused — containment, the allow-list, a denied segment — and a generic
    // "not permitted" here would erase the one fact a test needs to tell those apart.
    return decision.ok ? { allow: true } : deny(decision.reason);
  }

  if (toolName === 'Grep') {
    const path = readString(toolInput, 'path');
    if (path === undefined) {
      return deny(
        'Grep needs an explicit path. An omitted path searches the whole working ' +
          'directory, which includes .env. Name a subtree, e.g. "backend/src".',
      );
    }
    if (path === null) return deny('Grep needs path to be a string');
    const decision = resolveSearchRoot(path);
    return decision.ok ? { allow: true } : deny(decision.reason);
  }

  // The answer channel. No input check, because there is no path in it and the payload is
  // checked against the schema by the runner rather than by the policy. See `ANSWER_TOOL`
  // for how this rule was found — by the default branch below refusing it.
  if (toolName === ANSWER_TOOL) return { allow: true };

  // The default branch, and the one that matters. Every tool the SDK has or will have
  // lands here: Write, Edit, Bash, WebFetch, Task, and whatever version 0.4 adds.
  return deny(
    `${toolName} is not available to the reviewer. A review reads; it does not write, ` +
      `run commands, or fetch. Available: ${REVIEWER_TOOLS.join(', ')}.`,
  );
}

/**
 * The `PreToolUse` return value for a decision. **Both branches are explicit.**
 *
 * `hookEventName` is required by `PreToolUseHookSpecificOutput` and it is not decoration —
 * `hookSpecificOutput` is a union across every hook event, and that field is how the SDK
 * tells which member it received. Omitting it does not typecheck, which is the good case;
 * the bad case is the plan's original sketch of this function, which omitted it and would
 * have been a deny that never denied.
 *
 * **The allow branch used to return `{}`, and that was wrong in the direction nothing would
 * have reported.** `{}` is not an approval; it is *no opinion*, which hands the call back to
 * the normal permission flow. The contract is spelled out on the sibling hook at
 * sdk.d.ts:2565 — *"Same contract as PreToolUse: allow proceeds (skipping the interactive
 * cache-miss confirm), deny cancels the switch, ask asks the user to confirm (a headless
 * session refuses instead)"* — and this runner is headless with nothing in `allowedTools`
 * and no `canUseTool`, so a fall-through would have become an `ask`, and an `ask` there is a
 * refusal. The symptom would not have looked like a permission bug: the review would have
 * arrived correctly shaped, having read nothing, with every citation stripped for lack of
 * an access log. `agent-sdk.live.test.ts` asserts non-empty `accessedPaths` for this reason.
 *
 * Deciding both directions here is also what removes the ordering question. `allowedTools`
 * auto-approves by name, before any policy, and nothing in `sdk.d.ts` says whether that
 * short-circuits the hook; a runner that needed `allowedTools` to be readable would be
 * relying on an answer it does not have. This hook is the single decision point instead.
 */
export function toPreToolUseOutput(decision: ToolDecision): {
  hookSpecificOutput: {
    hookEventName: 'PreToolUse';
    permissionDecision: 'allow' | 'deny';
    permissionDecisionReason: string;
  };
} {
  if (decision.allow) {
    return {
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        permissionDecision: 'allow',
        permissionDecisionReason: 'allow-listed: read-only, inside the repository',
      },
    };
  }
  return {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason: decision.reason,
    },
  };
}
