'use strict';

// Root resolution and the postinstall gate. These need real directories because the functions ask
// the filesystem questions — but they only ever create empty marker files, so they are fast.

const assert = require('node:assert/strict');
const { describe, it, before, after } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { findProjectRoot, resolveConsumerRoot, isInstalledCopy, skipReason } = require('../lib/project-root');

let tmp;

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-toolkit-roots-'));
});

after(() => {
  fs.rmSync(tmp, { recursive: true, force: true });
});

function make(...segments) {
  const dir = path.join(tmp, ...segments);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

describe('findProjectRoot', () => {
  it('finds the nearest .git ancestor', () => {
    const root = make('repo-a');
    fs.mkdirSync(path.join(root, '.git'));
    const deep = make('repo-a', 'src', 'nested');
    assert.equal(findProjectRoot(deep), root);
  });

  // The monorepo case, and the reason .git is checked before package.json: this repo's consumers
  // have a package.json in frontend/ and one in packages/*, and CLAUDE.md lives above both.
  it('prefers .git over a nearer package.json', () => {
    const root = make('repo-b');
    fs.mkdirSync(path.join(root, '.git'));
    const sub = make('repo-b', 'packages', 'thing');
    fs.writeFileSync(path.join(sub, 'package.json'), '{}');
    assert.equal(findProjectRoot(sub), root);
  });

  it('falls back to the outermost package.json when there is no .git', () => {
    const outer = make('loose');
    fs.writeFileSync(path.join(outer, 'package.json'), '{}');
    const inner = make('loose', 'inner');
    fs.writeFileSync(path.join(inner, 'package.json'), '{}');
    // The walk records the first package.json it meets going up, which is the innermost — that is
    // the documented fallback, and it is only reached when nothing in the tree is a git repo.
    assert.equal(findProjectRoot(inner), inner);
  });
});

describe('resolveConsumerRoot', () => {
  it('prefers INIT_CWD over the process cwd', () => {
    const root = make('repo-c');
    fs.mkdirSync(path.join(root, '.git'));
    const elsewhere = make('unrelated');
    assert.equal(resolveConsumerRoot({ INIT_CWD: root }, elsewhere), root);
  });

  it('falls back to cwd when INIT_CWD is absent or blank', () => {
    const root = make('repo-d');
    fs.mkdirSync(path.join(root, '.git'));
    assert.equal(resolveConsumerRoot({}, root), root);
    assert.equal(resolveConsumerRoot({ INIT_CWD: '   ' }, root), root);
  });
});

describe('isInstalledCopy', () => {
  it('is true under node_modules and false in a source checkout', () => {
    assert.equal(isInstalledCopy(path.join(tmp, 'repo', 'node_modules', '@scope', 'ai-toolkit')), true);
    assert.equal(isInstalledCopy(path.join(tmp, 'repo', 'packages', 'ai-toolkit')), false);
  });

  // A directory merely *named* like the marker is not the marker. Path segments, not substrings.
  it('is not fooled by a directory whose name merely contains node_modules', () => {
    assert.equal(isInstalledCopy(path.join(tmp, 'repo', 'my_node_modules_backup', 'ai-toolkit')), false);
  });
});

describe('skipReason', () => {
  it('skips a source checkout', () => {
    const packageDir = path.join(tmp, 'repo', 'packages', 'ai-toolkit');
    const consumerRoot = path.join(tmp, 'repo');
    assert.match(skipReason(packageDir, consumerRoot), /source checkout/);
  });

  it('proceeds for an installed copy in a separate root', () => {
    const consumerRoot = path.join(tmp, 'repo');
    const packageDir = path.join(consumerRoot, 'node_modules', '@scope', 'ai-toolkit');
    assert.equal(skipReason(packageDir, consumerRoot), null);
  });

  // The self-install guard. Without it, `npm install` inside the package's own directory would
  // splice a rules block into the very CLAUDE.md that is the source of that block.
  it('skips when the resolved root is the package itself', () => {
    const dir = path.join(tmp, 'repo', 'node_modules', '@scope', 'ai-toolkit');
    assert.match(skipReason(dir, dir), /is the package itself/);
  });

  it('skips when the resolved root is inside the package', () => {
    const packageDir = path.join(tmp, 'repo', 'node_modules', '@scope', 'ai-toolkit');
    const consumerRoot = path.join(packageDir, 'fixtures', 'demo');
    assert.match(skipReason(packageDir, consumerRoot), /inside the package/);
  });
});
