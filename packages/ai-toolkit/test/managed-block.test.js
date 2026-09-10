'use strict';

// The splicer is pure, so every one of these is a string comparison with no temp directory. That
// was the point of keeping fs out of lib/managed-block.js: this is the code that can destroy a
// consumer's hand-written CLAUDE.md, and it is the code that is cheapest to test exhaustively.

const assert = require('node:assert/strict');
const { describe, it } = require('node:test');

const {
  SENTINEL_BEGIN,
  SENTINEL_END,
  ManagedBlockError,
  spliceManagedBlock,
  removeManagedBlock,
  findBlocks,
} = require('../lib/managed-block');

describe('spliceManagedBlock', () => {
  it('creates the block in an empty file', () => {
    const { text, action, collapsed } = spliceManagedBlock('', 'RULES');
    assert.equal(action, 'created');
    assert.equal(collapsed, 0);
    assert.equal(text, `${SENTINEL_BEGIN}\n\nRULES\n\n${SENTINEL_END}\n`);
  });

  it('appends after existing content without touching it', () => {
    const existing = '# My project\n\nHand-written prose.\n';
    const { text, action } = spliceManagedBlock(existing, 'RULES');
    assert.equal(action, 'created');
    assert.ok(text.startsWith(existing), 'the consumer content must survive verbatim');
    assert.ok(text.includes('RULES'));
  });

  it('is idempotent — splicing the same content twice yields the same string', () => {
    const once = spliceManagedBlock('# Project\n', 'RULES').text;
    const twice = spliceManagedBlock(once, 'RULES');
    assert.equal(twice.text, once);
    assert.equal(twice.action, 'unchanged');
  });

  it('replaces the block in place rather than appending a second one', () => {
    const once = spliceManagedBlock('# Project\n', 'OLD RULES').text;
    const { text, action } = spliceManagedBlock(once, 'NEW RULES');
    assert.equal(action, 'updated');
    assert.equal(findBlocks(text).length, 1);
    assert.ok(text.includes('NEW RULES'));
    assert.ok(!text.includes('OLD RULES'));
  });

  it('preserves content that follows the block', () => {
    const existing = [
      '# Project',
      '',
      SENTINEL_BEGIN,
      '',
      'OLD',
      '',
      SENTINEL_END,
      '',
      '## A section the consumer added below the block',
      '',
      'Keep me.',
      '',
    ].join('\n');

    const { text } = spliceManagedBlock(existing, 'NEW');
    assert.ok(text.includes('# Project'));
    assert.ok(text.includes('Keep me.'));
    assert.ok(text.includes('NEW'));
    assert.ok(!text.includes('OLD'));
  });

  it('collapses duplicate blocks into one and reports the count', () => {
    const block = `${SENTINEL_BEGIN}\n\nOLD\n\n${SENTINEL_END}`;
    const existing = `# Project\n\n${block}\n\nmiddle\n\n${block}\n`;

    const { text, collapsed } = spliceManagedBlock(existing, 'NEW');
    assert.equal(collapsed, 1);
    assert.equal(findBlocks(text).length, 1);
    assert.ok(text.includes('middle'), 'content between the duplicates must survive');
  });

  it('refuses a begin marker with no end marker', () => {
    const existing = `# Project\n\n${SENTINEL_BEGIN}\n\nsomething the consumer wrote\n`;
    assert.throws(() => spliceManagedBlock(existing, 'RULES'), ManagedBlockError);
  });
});

describe('removeManagedBlock', () => {
  it('removes the block and reports the count', () => {
    const withBlock = spliceManagedBlock('# Project\n', 'RULES').text;
    const { text, removed } = removeManagedBlock(withBlock);
    assert.equal(removed, 1);
    assert.ok(!text.includes(SENTINEL_BEGIN));
    assert.ok(text.includes('# Project'));
  });

  it('reports zero on a file that never had one', () => {
    const { text, removed } = removeManagedBlock('# Project\n');
    assert.equal(removed, 0);
    assert.equal(text, '# Project\n');
  });

  // The seam test. Without the tidying in removeSpan, every install/uninstall cycle would leave one
  // more blank line in a file the consumer reads, and after ten cycles the diff is noise.
  it('does not accumulate blank lines across repeated install/uninstall cycles', () => {
    const original = '# Project\n\nProse.\n';
    let text = original;
    for (let i = 0; i < 5; i += 1) {
      text = spliceManagedBlock(text, 'RULES').text;
      text = removeManagedBlock(text).text;
    }
    assert.equal(text, original);
  });

  it('restores the exact original when the block was appended to a file without a trailing newline', () => {
    const original = '# Project';
    const spliced = spliceManagedBlock(original, 'RULES').text;
    const { text } = removeManagedBlock(spliced);
    assert.equal(text.trimEnd(), original);
  });
});
