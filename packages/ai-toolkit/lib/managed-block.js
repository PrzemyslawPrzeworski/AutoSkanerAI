'use strict';

// Splicing a managed block into a file somebody else owns.
//
// This is the only part of the installer that edits a file the consumer wrote by hand, so it is
// the only part that can destroy work. It is therefore pure: strings in, strings out, no fs, no
// console, no process. `toolkit.js` does the reading and writing; everything here is decidable by
// a test with no temp directory.
//
// Scanning is index-based rather than regex-based on purpose. The markers are HTML comments
// containing `@`, `/`, `-` and `.`, a package name can change, and a regex built from an
// interpolated package name is one escaping mistake away from either matching nothing or matching
// far too much. `indexOf` cannot be tricked by the content of the file.

const SENTINEL_BEGIN = '<!-- BEGIN @przemyslawprzeworski/ai-toolkit -->';
const SENTINEL_END = '<!-- END @przemyslawprzeworski/ai-toolkit -->';

class ManagedBlockError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ManagedBlockError';
  }
}

// Every [start, endExclusive) span in `text` that is a complete managed block, in order.
function findBlocks(text) {
  const spans = [];
  let from = 0;

  for (;;) {
    const begin = text.indexOf(SENTINEL_BEGIN, from);
    if (begin === -1) return spans;

    const end = text.indexOf(SENTINEL_END, begin + SENTINEL_BEGIN.length);
    if (end === -1) {
      // An opening marker with no closing one. Everything after it might be the consumer's own
      // prose, so there is no safe span to replace: refuse rather than guess where it ended.
      throw new ManagedBlockError(
        `found "${SENTINEL_BEGIN}" with no matching "${SENTINEL_END}". ` +
          'The managed block is malformed — remove the stray marker (or add the closing one) and re-run.',
      );
    }

    spans.push({ start: begin, endExclusive: end + SENTINEL_END.length });
    from = end + SENTINEL_END.length;
  }
}

function renderBlock(content) {
  return [SENTINEL_BEGIN, '', content.trim(), '', SENTINEL_END].join('\n');
}

// Insert or replace the managed block. Idempotent: calling this with the same content twice
// yields the same string, and calling it with new content replaces the old block rather than
// appending a second one.
//
// Returns { text, action, collapsed }:
//   action    'created' | 'updated' | 'unchanged'
//   collapsed how many *extra* blocks were removed. Non-zero means the file already had
//             duplicates — from a hand-paste or an older buggy installer. They are collapsed
//             into one rather than reported as an error, because refusing here would break a
//             consumer's `npm install` over a mess that this function can safely clean up. The
//             count is returned so the caller can say it happened out loud.
function spliceManagedBlock(existing, content) {
  const block = renderBlock(content);
  const text = typeof existing === 'string' ? existing : '';
  const spans = findBlocks(text);

  if (spans.length === 0) {
    if (text.trim() === '') return { text: `${block}\n`, action: 'created', collapsed: 0 };
    const separator = text.endsWith('\n') ? '\n' : '\n\n';
    return { text: `${text}${separator}${block}\n`, action: 'created', collapsed: 0 };
  }

  // Replace the first block in place; drop the rest. Work back to front so earlier indices stay
  // valid as later spans are removed.
  let next = text;
  for (let i = spans.length - 1; i >= 1; i -= 1) {
    next = removeSpan(next, spans[i]);
  }
  const first = findBlocks(next)[0];
  const before = next.slice(0, first.start);
  const after = next.slice(first.endExclusive);
  const result = `${before}${block}${after}`;

  const collapsed = spans.length - 1;
  const action = result === text ? 'unchanged' : 'updated';
  return { text: result, action, collapsed };
}

// Remove every managed block, leaving the consumer's own content untouched.
// Returns { text, removed } where `removed` is how many blocks were taken out.
function removeManagedBlock(existing) {
  const text = typeof existing === 'string' ? existing : '';
  const spans = findBlocks(text);
  if (spans.length === 0) return { text, removed: 0 };

  let next = text;
  for (let i = spans.length - 1; i >= 0; i -= 1) {
    next = removeSpan(next, spans[i]);
  }
  return { text: next, removed: spans.length };
}

// Cut a span out and tidy the seam, so repeated install/uninstall cycles do not accumulate blank
// lines in a file the consumer reads.
function removeSpan(text, span) {
  let start = span.start;
  let end = span.endExclusive;

  while (start > 0 && (text[start - 1] === ' ' || text[start - 1] === '\t')) start -= 1;
  if (start > 0 && text[start - 1] === '\n') start -= 1;
  while (end < text.length && text[end] === '\n') end += 1;

  const before = text.slice(0, start);
  const after = text.slice(end);
  if (before === '') return after;
  if (after === '') return before.endsWith('\n') ? before : `${before}\n`;
  return `${before}\n\n${after}`;
}

module.exports = {
  SENTINEL_BEGIN,
  SENTINEL_END,
  ManagedBlockError,
  spliceManagedBlock,
  removeManagedBlock,
  findBlocks,
  renderBlock,
};
