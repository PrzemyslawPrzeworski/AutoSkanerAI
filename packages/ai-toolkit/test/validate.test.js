'use strict';

// The validator is what both CI pipelines trust instead of a chain of `grep -q`, so its own failure
// mode matters: a validator that returns ok for a broken package is the dead gate again. Half of
// these tests therefore assert that it *fails* on specific damage.

const assert = require('node:assert/strict');
const { describe, it } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { validatePackage, parseFrontmatter, parsePackYaml } = require('../lib/validate');

const PACKAGE_DIR = path.resolve(__dirname, '..');

describe('parseFrontmatter', () => {
  it('reads scalars, stripping quotes', () => {
    const front = parseFrontmatter('---\nname: code-review\ndescription: "Reviews code"\n---\n\n# Body\n');
    assert.equal(front.name, 'code-review');
    assert.equal(front.description, 'Reviews code');
  });

  it('returns null when there is no frontmatter', () => {
    assert.equal(parseFrontmatter('# Just a heading\n'), null);
  });

  // An unterminated block is not "frontmatter with a lot of keys" — every line of the document
  // would read as a field. Treating it as invalid is the safe direction.
  it('returns null when the block is opened but never closed', () => {
    assert.equal(parseFrontmatter('---\nname: code-review\n\n# Body with no closing delimiter\n'), null);
  });
});

describe('parsePackYaml', () => {
  it('reads top-level scalars and ignores nested keys, comments and blanks', () => {
    const text = [
      '# a comment',
      'name: "@scope/ai-toolkit"',
      'version: 0.1.0',
      'namespace: scope',
      '',
      'install:',
      '  skills: .claude/skills',
      '  version: 99.99.99',
    ].join('\n');

    const pack = parsePackYaml(text);
    assert.equal(pack.name, '@scope/ai-toolkit');
    assert.equal(pack.version, '0.1.0', 'the nested version must not shadow the top-level one');
    assert.equal(pack.namespace, 'scope');
    assert.equal('skills' in pack, false);
  });
});

describe('validatePackage', () => {
  it('passes on this package as shipped', () => {
    const { ok, checks } = validatePackage(PACKAGE_DIR);
    const failed = checks.filter((c) => !c.ok);
    assert.deepEqual(
      failed.map((c) => `${c.name}${c.detail ? ` (${c.detail})` : ''}`),
      [],
    );
    assert.equal(ok, true);
    assert.ok(checks.length >= 10, 'a validator that runs two checks is not a validator');
  });

  it('reports a failure rather than throwing when the package is not there', () => {
    const empty = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-toolkit-empty-'));
    try {
      const { ok, checks } = validatePackage(empty);
      assert.equal(ok, false);
      assert.ok(checks.some((c) => c.name === 'package.json exists' && !c.ok));
      assert.ok(checks.some((c) => c.name === 'at least one skill is present' && !c.ok));
    } finally {
      fs.rmSync(empty, { recursive: true, force: true });
    }
  });

  it('catches a skill whose frontmatter name does not match its directory', () => {
    const dir = copyPackageSkeleton();
    try {
      const skillFile = path.join(dir, 'skills', 'code-review', 'SKILL.md');
      const text = fs.readFileSync(skillFile, 'utf8').replace('name: code-review', 'name: reviewer');
      fs.writeFileSync(skillFile, text, 'utf8');

      const { ok, checks } = validatePackage(dir);
      assert.equal(ok, false);
      assert.ok(
        checks.some((c) => c.name === 'skills/code-review frontmatter name matches its directory' && !c.ok),
      );
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it('catches a version that drifted between package.json and pack.yaml', () => {
    const dir = copyPackageSkeleton();
    try {
      const pkgPath = path.join(dir, 'package.json');
      const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
      pkg.version = '9.9.9';
      fs.writeFileSync(pkgPath, `${JSON.stringify(pkg, null, 2)}\n`, 'utf8');

      const { ok, checks } = validatePackage(dir);
      assert.equal(ok, false);
      assert.ok(checks.some((c) => c.name === 'version matches between package.json and pack.yaml' && !c.ok));
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it('catches a files entry that names something absent from the package', () => {
    const dir = copyPackageSkeleton();
    try {
      const pkgPath = path.join(dir, 'package.json');
      const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
      pkg.files.push('prompts/');
      fs.writeFileSync(pkgPath, `${JSON.stringify(pkg, null, 2)}\n`, 'utf8');

      const { ok, checks } = validatePackage(dir);
      assert.equal(ok, false);
      assert.ok(checks.some((c) => c.name === 'files entry "prompts/" exists' && !c.ok));
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});

// A throwaway copy of the real package, so the damage tests above mutate a temp directory and never
// the tree they are run from.
function copyPackageSkeleton() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-toolkit-copy-'));
  for (const entry of ['package.json', 'pack.yaml', 'install.js', 'uninstall.js', 'README.md']) {
    const from = path.join(PACKAGE_DIR, entry);
    if (fs.existsSync(from)) fs.copyFileSync(from, path.join(dir, entry));
  }
  for (const entry of ['skills', 'rules', 'bin', 'lib']) {
    const from = path.join(PACKAGE_DIR, entry);
    if (fs.existsSync(from)) fs.cpSync(from, path.join(dir, entry), { recursive: true });
  }
  return dir;
}
