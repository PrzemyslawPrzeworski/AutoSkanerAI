'use strict';

// install -> install -> uninstall against a scratch directory.
//
// Deliberately NOT against this repository. Installing into the real root would splice a managed
// block into the CLAUDE.md the whole repo depends on, and a test that mutates the file it is being
// judged by is not a test. Every directory here is under os.tmpdir() and removed afterwards.

const assert = require('node:assert/strict');
const { describe, it, beforeEach, afterEach } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { installToolkit, uninstallToolkit, isRemovablePath, listSkills } = require('../lib/toolkit');
const { readManifest, manifestPath } = require('../lib/manifest');
const { SENTINEL_BEGIN } = require('../lib/managed-block');

const PACKAGE_DIR = path.resolve(__dirname, '..');

let consumerRoot;

beforeEach(() => {
  consumerRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-toolkit-consumer-'));
  fs.mkdirSync(path.join(consumerRoot, '.git'));
  fs.writeFileSync(path.join(consumerRoot, 'CLAUDE.md'), '# Consumer project\n\nHand-written prose.\n', 'utf8');
});

afterEach(() => {
  fs.rmSync(consumerRoot, { recursive: true, force: true });
});

function install() {
  return installToolkit({
    packageDir: PACKAGE_DIR,
    consumerRoot,
    packageName: '@przemyslawprzeworski/ai-toolkit',
    version: '0.1.0',
    mode: 'test',
  });
}

describe('installToolkit', () => {
  it('copies every shipped skill into .claude/skills/', () => {
    const report = install();
    assert.deepEqual(report.skills, listSkills(PACKAGE_DIR));
    assert.ok(report.skills.includes('code-review'));

    const skillFile = path.join(consumerRoot, '.claude', 'skills', 'code-review', 'SKILL.md');
    assert.ok(fs.existsSync(skillFile), 'SKILL.md must land in the consumer');
    assert.ok(fs.readFileSync(skillFile, 'utf8').startsWith('---'), 'and arrive intact');
  });

  it('splices the rules block into CLAUDE.md without losing the consumer prose', () => {
    const report = install();
    assert.equal(report.rules.applied, true);
    assert.equal(report.rules.action, 'created');

    const text = fs.readFileSync(path.join(consumerRoot, 'CLAUDE.md'), 'utf8');
    assert.ok(text.includes('Hand-written prose.'));
    assert.ok(text.includes(SENTINEL_BEGIN));
  });

  it('writes a manifest naming every installed file', () => {
    const report = install();
    const manifest = readManifest(consumerRoot);

    assert.equal(manifest.status, 'ok');
    assert.equal(manifest.version, '0.1.0');
    assert.equal(manifest.rulesFile, 'CLAUDE.md');
    assert.equal(manifest.files.length, report.files.length);
    // POSIX separators, so a manifest written on Windows reads on Linux.
    for (const rel of manifest.files) assert.ok(!rel.includes('\\'), `${rel} must use / separators`);
  });

  it('is idempotent — a second install changes nothing', () => {
    install();
    const claudeAfterFirst = fs.readFileSync(path.join(consumerRoot, 'CLAUDE.md'), 'utf8');

    const second = install();
    assert.equal(second.rules.action, 'unchanged');
    assert.equal(second.pruned.length, 0);
    assert.equal(fs.readFileSync(path.join(consumerRoot, 'CLAUDE.md'), 'utf8'), claudeAfterFirst);
  });

  // The orphan case: a previous version shipped a file this one does not. Without pruning, a
  // renamed skill would be present twice and the stale copy would keep being loaded.
  it('prunes files a previous version installed that this one no longer ships', () => {
    install();

    const stale = path.join('.claude', 'skills', 'retired-skill', 'SKILL.md');
    const staleAbs = path.join(consumerRoot, stale);
    fs.mkdirSync(path.dirname(staleAbs), { recursive: true });
    fs.writeFileSync(staleAbs, '# retired\n', 'utf8');

    const manifest = readManifest(consumerRoot);
    manifest.files.push(stale.split(path.sep).join('/'));
    fs.writeFileSync(manifestPath(consumerRoot), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

    const report = install();
    assert.deepEqual(report.pruned, [stale.split(path.sep).join('/')]);
    assert.equal(fs.existsSync(staleAbs), false);
    assert.equal(fs.existsSync(path.dirname(staleAbs)), false, 'the emptied directory goes too');
  });

  it('recovers from a corrupt manifest instead of refusing to install', () => {
    install();
    fs.writeFileSync(manifestPath(consumerRoot), 'not json at all', 'utf8');

    const report = install();
    assert.ok(report.files.length > 0);
    assert.equal(readManifest(consumerRoot).status, 'ok');
  });
});

describe('uninstallToolkit', () => {
  it('removes every file the manifest names and the managed block', () => {
    const installed = install();
    const report = uninstallToolkit({ consumerRoot });

    assert.equal(report.hadManifest, true);
    assert.equal(report.removed.length, installed.files.length);
    assert.equal(report.rulesRemoved, 1);
    assert.deepEqual(report.refused, []);

    assert.equal(fs.existsSync(path.join(consumerRoot, '.claude')), false, '.claude/ is emptied and pruned');
    assert.equal(
      fs.readFileSync(path.join(consumerRoot, 'CLAUDE.md'), 'utf8'),
      '# Consumer project\n\nHand-written prose.\n',
      'CLAUDE.md returns to exactly what the consumer wrote',
    );
  });

  it('reports nothing to do when never installed', () => {
    const report = uninstallToolkit({ consumerRoot });
    assert.equal(report.hadManifest, false);
    assert.deepEqual(report.removed, []);
    assert.equal(report.rulesRemoved, 0);
  });

  // A manifest is a file on the consumer's disk that uninstall treats as a delete list. If it can
  // be pointed at package.json, this tool is a file remover with a text-file trigger.
  it('refuses a manifest path that escapes .claude/', () => {
    install();

    const victim = path.join(consumerRoot, 'package.json');
    fs.writeFileSync(victim, '{"name":"consumer"}', 'utf8');

    const manifest = readManifest(consumerRoot);
    manifest.files.push('.claude/../package.json');
    fs.writeFileSync(manifestPath(consumerRoot), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

    const report = uninstallToolkit({ consumerRoot });
    assert.deepEqual(report.refused, ['.claude/../package.json']);
    assert.ok(fs.existsSync(victim), 'the escaping path must survive');
  });
});

describe('isRemovablePath', () => {
  const root = path.join(os.tmpdir(), 'ai-toolkit-guard');

  it('allows paths under .claude/', () => {
    assert.equal(isRemovablePath(root, path.join('.claude', 'skills', 'x', 'SKILL.md')), true);
    assert.equal(isRemovablePath(root, '.claude'), true);
  });

  it('refuses traversal, siblings and absolute paths outside the root', () => {
    assert.equal(isRemovablePath(root, '.claude/../package.json'), false);
    assert.equal(isRemovablePath(root, '../elsewhere/.claude/x'), false);
    assert.equal(isRemovablePath(root, 'CLAUDE.md'), false);
    assert.equal(isRemovablePath(root, path.join('.claudex', 'file')), false);
    assert.equal(isRemovablePath(root, path.resolve(os.tmpdir(), 'other', 'file')), false);
  });
});
