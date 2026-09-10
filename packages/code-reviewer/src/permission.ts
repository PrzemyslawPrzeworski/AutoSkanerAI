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
 *   1. **A `PreToolUse` hook that returns `{}` has ALLOWED the call.** Deny is the
 *      explicit case. So a hook shaped like "recognise the dangerous tools, deny those,
 *      fall through otherwise" is permissive by construction, and a tool added to a later
 *      SDK version passes through it unseen. `decideToolUse` therefore denies on the
 *      default branch and allows only two names.
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

  // The default branch, and the one that matters. Every tool the SDK has or will have
  // lands here: Write, Edit, Bash, WebFetch, Task, and whatever version 0.4 adds.
  return deny(
    `${toolName} is not available to the reviewer. A review reads; it does not write, ` +
      `run commands, or fetch. Available: ${REVIEWER_TOOLS.join(', ')}.`,
  );
}

/**
 * The `PreToolUse` return value for a decision.
 *
 * `hookEventName` is required by `PreToolUseHookSpecificOutput` and it is not decoration —
 * `hookSpecificOutput` is a union across every hook event, and that field is how the SDK
 * tells which member it received. Omitting it does not typecheck, which is the good case;
 * the bad case is the plan's original sketch of this function, which omitted it and would
 * have been a deny that never denied.
 */
export function toPreToolUseOutput(decision: ToolDecision): {
  hookSpecificOutput?: {
    hookEventName: 'PreToolUse';
    permissionDecision: 'deny';
    permissionDecisionReason: string;
  };
} {
  if (decision.allow) return {};
  return {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason: decision.reason,
    },
  };
}
