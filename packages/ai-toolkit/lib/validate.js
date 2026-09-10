'use strict';

// The five checks both lesson specs ask for, in one place.
//
// The GitHub Packages spec and the CodeArtifact spec each describe a validation job in shell:
// does package.json have a name and a version, does skills/code-review/SKILL.md exist, does its
// frontmatter carry name and description, does the frontmatter name match the directory, does
// `npm pack --dry-run` succeed. Written twice in YAML those drift, and a `grep -q` in a workflow
// reports the same green whether it matched or the file was missing. So the checks live here, both
// workflows call `ai-toolkit validate`, and the exit code is the signal.
//
// Frontmatter is read with a targeted scalar reader, not a YAML parser. That is a deliberate
// limit, stated rather than hidden: this package has no dependencies and adding `yaml` to check
// two string fields would be the largest thing in it. The reader handles `key: value`, optional
// quotes, and nothing else — a folded block or a nested mapping under `name:` reads as invalid
// here, which is the safe direction.

const fs = require('node:fs');
const path = require('node:path');

const { listSkills } = require('./toolkit');

function parseFrontmatter(text) {
  const lines = text.split(/\r?\n/);
  if (lines[0]?.trim() !== '---') return null;

  const out = {};
  for (let i = 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.trim() === '---') return out;
    const match = /^([A-Za-z0-9_-]+):[ \t]*(.*)$/.exec(line);
    if (!match) continue;
    let value = match[2].trim();
    if (
      (value.startsWith('"') && value.endsWith('"') && value.length >= 2) ||
      (value.startsWith("'") && value.endsWith("'") && value.length >= 2)
    ) {
      value = value.slice(1, -1);
    }
    out[match[1]] = value;
  }
  return null; // opened but never closed
}

// A scalar reader for the handful of top-level keys pack.yaml declares. Same limits as above.
function parsePackYaml(text) {
  const out = {};
  for (const line of text.split(/\r?\n/)) {
    if (/^\s/.test(line) || line.trim() === '' || line.trimStart().startsWith('#')) continue;
    const match = /^([A-Za-z0-9_-]+):[ \t]*(.*)$/.exec(line);
    if (!match) continue;
    let value = match[2].trim();
    if (value === '') continue; // a mapping or list header, not a scalar
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    out[match[1]] = value;
  }
  return out;
}

// Returns { ok, checks: [{name, ok, detail}] }. Never throws for a validation failure — a thrown
// error and a failed check are different things, and only the first should look like a bug.
function validatePackage(packageDir) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok, detail });

  // 1. package.json and its required fields.
  let pkg = null;
  const pkgPath = path.join(packageDir, 'package.json');
  if (!fs.existsSync(pkgPath)) {
    add('package.json exists', false, `${pkgPath} not found`);
  } else {
    try {
      pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
      add('package.json parses', true);
    } catch (cause) {
      add('package.json parses', false, cause.message);
    }
  }

  if (pkg) {
    for (const field of ['name', 'version']) {
      add(`package.json has ${field}`, typeof pkg[field] === 'string' && pkg[field] !== '');
    }
    const registry = pkg.publishConfig && pkg.publishConfig.registry;
    add(
      'package.json has publishConfig.registry',
      typeof registry === 'string' && registry.startsWith('https://'),
      registry ? `= ${registry}` : 'missing',
    );
  }

  // 2. pack.yaml and its required fields.
  const packPath = path.join(packageDir, 'pack.yaml');
  let pack = null;
  if (!fs.existsSync(packPath)) {
    add('pack.yaml exists', false, `${packPath} not found`);
  } else {
    add('pack.yaml exists', true);
    pack = parsePackYaml(fs.readFileSync(packPath, 'utf8'));
    for (const field of ['name', 'version', 'description', 'namespace']) {
      add(`pack.yaml has ${field}`, typeof pack[field] === 'string' && pack[field] !== '');
    }
  }

  // 3. pack.yaml and package.json agree. Two copies of a version number that can disagree is a
  //    release that publishes one number and documents another.
  if (pkg && pack) {
    add('name matches between package.json and pack.yaml', pkg.name === pack.name, `${pkg.name} vs ${pack.name}`);
    add(
      'version matches between package.json and pack.yaml',
      pkg.version === pack.version,
      `${pkg.version} vs ${pack.version}`,
    );
    if (typeof pkg.name === 'string' && pkg.name.startsWith('@')) {
      const scope = pkg.name.slice(1).split('/')[0];
      add('pack.yaml namespace matches the package scope', scope === pack.namespace, `${scope} vs ${pack.namespace}`);
    }
  }

  // 4. At least one skill, and every skill's frontmatter is valid and matches its directory.
  const skills = listSkills(packageDir);
  add('at least one skill is present', skills.length > 0, skills.length === 0 ? 'skills/ is empty or missing' : skills.join(', '));

  for (const skill of skills) {
    const skillFile = path.join(packageDir, 'skills', skill, 'SKILL.md');
    if (!fs.existsSync(skillFile)) {
      add(`skills/${skill}/SKILL.md exists`, false);
      continue;
    }
    const front = parseFrontmatter(fs.readFileSync(skillFile, 'utf8'));
    if (!front) {
      add(`skills/${skill}/SKILL.md has closed YAML frontmatter`, false, 'no --- delimited block at the top');
      continue;
    }
    add(`skills/${skill} frontmatter has name`, typeof front.name === 'string' && front.name !== '');
    add(
      `skills/${skill} frontmatter has description`,
      typeof front.description === 'string' && front.description !== '',
    );
    add(`skills/${skill} frontmatter name matches its directory`, front.name === skill, `${front.name} vs ${skill}`);
  }

  // 5. The files the package claims to publish are actually there. `files` in package.json is the
  //    published surface; a typo there ships an empty package that installs cleanly and does
  //    nothing.
  if (pkg && Array.isArray(pkg.files)) {
    for (const entry of pkg.files) {
      const target = path.join(packageDir, entry.replace(/\/$/, ''));
      add(`files entry "${entry}" exists`, fs.existsSync(target));
    }
  }

  return { ok: checks.every((c) => c.ok), checks };
}

module.exports = { validatePackage, parseFrontmatter, parsePackYaml };
