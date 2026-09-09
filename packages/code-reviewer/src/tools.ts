/**
 * The agent's read-only sight of the repo.
 *
 * Two tools, both bounded three ways: in path (every access goes through
 * `resolveReadablePath`), in size (a cap on returned characters), and in count (a cap
 * on matches and on files walked). `execute` is where a tool call stops being text
 * and starts being an effect, so the bounds live here rather than in the prompt —
 * Phase 2 already produced the evidence that an instruction in a prompt is not
 * enforcement.
 *
 * Neither tool throws. A refusal is a value the model can read and act on; an
 * exception would end the run over a guess at a filename.
 *
 * Neither tool spawns a process. `findInRepo` walks with `fs` and matches with
 * `String.includes` — a literal substring, not a regular expression — so a
 * model-supplied query cannot become a shell command or a catastrophically
 * backtracking pattern.
 */
import { readFileSync, readdirSync, statSync, type Dirent } from 'node:fs';
import { extname, join } from 'node:path';
import { tool } from 'ai';
import { z } from 'zod';
import {
  ALLOWED_ROOT_FILES,
  ALLOWED_SUBTREES,
  REPO_ROOT,
  isDeniedSegment,
  resolveReadablePath,
} from './repo.ts';

/** Roughly 8k tokens of one file: enough to judge a class, not enough to fill the window. */
const MAX_FILE_CHARS = 32_000;

/** Matches returned when the model does not ask for a specific number. */
const DEFAULT_MAX_MATCHES = 20;
const MAX_MAX_MATCHES = 100;

/** A walk bound, so a search cannot become the cost of the review. */
const MAX_FILES_SCANNED = 5_000;

/** Skipped when searching: reading these as UTF-8 produces noise, not evidence. */
const BINARY_EXTENSIONS = new Set([
  '.png',
  '.jpg',
  '.jpeg',
  '.gif',
  '.webp',
  '.ico',
  '.svg',
  '.pdf',
  '.zip',
  '.gz',
  '.jar',
  '.class',
  '.woff',
  '.woff2',
  '.ttf',
  '.eot',
  '.mp4',
  '.exe',
  '.dll',
  '.so',
  '.bin',
]);

/** Files above this are skipped by the search; a 2 MB fixture is not read line by line. */
const MAX_SEARCHABLE_BYTES = 512 * 1024;

/** One line of a search hit, capped so twenty of them stay readable. */
const MAX_MATCH_LINE_CHARS = 200;

export interface ReviewTools {
  tools: {
    readRepoFile: ReturnType<typeof buildReadRepoFile>;
    findInRepo: ReturnType<typeof buildFindInRepo>;
  };
  /**
   * Repo-relative paths a tool actually returned content for. This is the log Phase 4
   * checks a finding's `evidence` against: the model may claim it read a file, and
   * only this set knows whether it did.
   */
  accessedPaths: Set<string>;
}

/**
 * Fresh tools per review, because `accessedPaths` is per review. Sharing one set
 * across runs would let an earlier review's reads vouch for a later one's evidence.
 */
export function createTools(): ReviewTools {
  const accessedPaths = new Set<string>();
  return {
    tools: {
      readRepoFile: buildReadRepoFile(accessedPaths),
      findInRepo: buildFindInRepo(accessedPaths),
    },
    accessedPaths,
  };
}

function buildReadRepoFile(accessedPaths: Set<string>) {
  return tool({
    description:
      'Read one file from this repository, to check how the code the diff changes is ' +
      'actually used. Returns the file text, or a refusal explaining why that path is ' +
      'not readable. Only source trees are readable: build output, dependencies, and ' +
      'dotfiles are refused.',
    inputSchema: z.object({
      path: z
        .string()
        .describe(
          'Repo-relative path, e.g. "backend/src/main/java/com/example/autoskaner_ai/' +
            'analysis/ListingFetchService.java". Not a path from the diff header — drop ' +
            'any leading "a/" or "b/".',
        ),
    }),
    execute: async ({ path }) => {
      const decision = resolveReadablePath(path);
      if (!decision.ok) return { denied: true, reason: decision.reason };

      let text: string;
      try {
        text = readFileSync(decision.absolute, 'utf8');
      } catch {
        // Shaped like a refusal so the model has one failure shape to handle, but
        // worded as absence: a path that is allowed and missing is not a policy matter.
        return { denied: true, reason: `no such file in this repo: ${decision.relative}` };
      }

      const truncated = text.length > MAX_FILE_CHARS;
      accessedPaths.add(decision.relative);
      return {
        path: decision.relative,
        text: truncated ? text.slice(0, MAX_FILE_CHARS) : text,
        truncated,
      };
    },
  });
}

function buildFindInRepo(accessedPaths: Set<string>) {
  return tool({
    description:
      'Find a literal string in this repository, to check whether a symbol, rule, or ' +
      'pattern the diff relies on exists elsewhere. Case-insensitive substring match, ' +
      'not a regular expression. Searches source trees only.',
    inputSchema: z.object({
      query: z
        .string()
        .describe('Literal text to find. Not a regular expression — wildcards match themselves.'),
      maxMatches: z
        .number()
        .int()
        .min(1)
        .max(MAX_MAX_MATCHES)
        .nullish()
        .describe(`Stop after this many matches. Default ${DEFAULT_MAX_MATCHES}.`),
    }),
    execute: async ({ query, maxMatches }) => {
      const needle = query.trim();
      if (needle === '') return { matches: [], truncated: false, note: 'empty query' };

      const limit = maxMatches ?? DEFAULT_MAX_MATCHES;
      const folded = needle.toLowerCase();
      const matches: { file: string; line: number; text: string }[] = [];
      let scanned = 0;
      let truncated = false;

      for (const relativePath of searchableFiles()) {
        if (matches.length >= limit || scanned >= MAX_FILES_SCANNED) {
          truncated = true;
          break;
        }
        scanned += 1;

        const absolute = join(REPO_ROOT, relativePath);
        let text: string;
        try {
          if (statSync(absolute).size > MAX_SEARCHABLE_BYTES) continue;
          text = readFileSync(absolute, 'utf8');
        } catch {
          continue; // unreadable or vanished mid-walk: not this tool's problem to report
        }
        if (!text.toLowerCase().includes(folded)) continue;

        const lines = text.split(/\r?\n/);
        for (let index = 0; index < lines.length; index += 1) {
          if (matches.length >= limit) {
            truncated = true;
            break;
          }
          const line = lines[index] ?? '';
          if (!line.toLowerCase().includes(folded)) continue;
          matches.push({
            file: relativePath,
            line: index + 1,
            text: line.trim().slice(0, MAX_MATCH_LINE_CHARS),
          });
          accessedPaths.add(relativePath);
        }
      }

      return { matches, truncated };
    },
  });
}

/**
 * Every file the search may look at, repo-relative with `/` separators.
 *
 * The walk is seeded from the allow-list rather than from the repo root and filtered
 * afterwards — a root-first walk would descend into `node_modules` and `.git` to
 * discard them, which is both slow and one bug away from reading them.
 *
 * Each candidate is still put through `resolveReadablePath`. Enumerating from the
 * allow-list already implies the answer, and asking anyway means the policy has one
 * gate rather than one gate and one lookalike.
 */
function* searchableFiles(): Generator<string> {
  for (const file of ALLOWED_ROOT_FILES) {
    if (resolveReadablePath(file).ok) yield file;
  }
  for (const subtree of ALLOWED_SUBTREES) {
    yield* walk(subtree);
  }
}

function* walk(relativeDir: string): Generator<string> {
  let entries: Dirent[];
  try {
    entries = readdirSync(join(REPO_ROOT, relativeDir), { withFileTypes: true });
  } catch {
    return; // an allow-listed subtree that does not exist in this checkout
  }
  for (const entry of entries) {
    const child = `${relativeDir}/${entry.name}`;
    if (entry.isDirectory()) {
      // Prune by name, so node_modules and target are never descended into at all
      // rather than descended into and discarded file by file.
      if (!isDeniedSegment(entry.name)) yield* walk(child);
      continue;
    }
    if (!entry.isFile()) continue; // symlinks and devices: not evidence
    if (BINARY_EXTENSIONS.has(extname(entry.name).toLowerCase())) continue;
    if (resolveReadablePath(child).ok) yield child;
  }
}
