'use strict';

// Install and uninstall, as a library.
//
// Nothing here prints or calls process.exit. `install.js`, `uninstall.js` and `bin/cli.js` are the
// shells that do that; this module returns a report and throws on failure. The split is what lets
// `postinstall` (which must exit 0) and the CLI (which must not) share one implementation instead
// of drifting into two.

const fs = require('node:fs');
const path = require('node:path');

const { spliceManagedBlock, removeManagedBlock } = require('./managed-block');
const { buildManifest, writeManifest, readManifest, removeManifest } = require('./manifest');

const SKILLS_DIR = 'skills';
const RULES_FILE = path.join('rules', 'CLAUDE.md');
const CONSUMER_SKILLS_DIR = path.join('.claude', 'skills');
const CONSUMER_RULES_FILE = 'CLAUDE.md';

// Every file under `dir`, as paths relative to `dir`, POSIX-ordered for a stable manifest.
function listFiles(dir, prefix = '') {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const rel = path.join(prefix, entry.name);
    if (entry.isDirectory()) out.push(...listFiles(path.join(dir, entry.name), rel));
    else if (entry.isFile()) out.push(rel);
  }
  return out.sort();
}

function listSkills(packageDir) {
  const root = path.join(packageDir, SKILLS_DIR);
  if (!fs.existsSync(root)) return [];
  return fs
    .readdirSync(root, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => e.name)
    .sort();
}

// A manifest is a file the consumer (or something pretending to be them) can edit, and uninstall
// deletes what it names. So every path is re-checked against the one directory this tool is
// allowed to remove from, rather than being trusted because we wrote it once. `..` in a manifest
// entry must not be able to reach outside `.claude/`.
function isRemovablePath(consumerRoot, relative) {
  const allowedRoot = path.resolve(consumerRoot, '.claude');
  const resolved = path.resolve(consumerRoot, relative);
  return resolved === allowedRoot || resolved.startsWith(allowedRoot + path.sep);
}

function installToolkit({ packageDir, consumerRoot, packageName, version, mode }) {
  const skills = listSkills(packageDir);
  const installed = [];

  for (const skill of skills) {
    const from = path.join(packageDir, SKILLS_DIR, skill);
    for (const rel of listFiles(from)) {
      const target = path.join(consumerRoot, CONSUMER_SKILLS_DIR, skill, rel);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.copyFileSync(path.join(from, rel), target);
      installed.push(path.join(CONSUMER_SKILLS_DIR, skill, rel));
    }
  }

  // Drop files a previous version installed that this one no longer ships. Without this, "install
  // twice" is idempotent for the files that still exist and leaves orphans for the ones that do
  // not — a renamed skill would appear twice, and the stale copy would keep being loaded.
  const pruned = [];
  const previous = safeReadManifest(consumerRoot);
  if (previous && Array.isArray(previous.files)) {
    const current = new Set(installed.map((f) => f.split(path.sep).join('/')));
    for (const stale of previous.files) {
      const staleNative = stale.split('/').join(path.sep);
      if (current.has(stale) || !isRemovablePath(consumerRoot, staleNative)) continue;
      const abs = path.resolve(consumerRoot, staleNative);
      if (fs.existsSync(abs)) {
        fs.rmSync(abs);
        pruned.push(stale);
      }
    }
    pruneEmptyDirs(path.join(consumerRoot, CONSUMER_SKILLS_DIR));
  }

  const rules = installRules(packageDir, consumerRoot);

  const manifest = buildManifest({
    packageName,
    version,
    files: installed,
    rulesFile: rules.applied ? CONSUMER_RULES_FILE : null,
    status: 'ok',
    mode,
  });
  const manifestFile = writeManifest(consumerRoot, manifest);

  return { skills, files: installed, pruned, rules, manifestPath: manifestFile };
}

function installRules(packageDir, consumerRoot) {
  const source = path.join(packageDir, RULES_FILE);
  if (!fs.existsSync(source)) return { applied: false, action: 'absent', collapsed: 0 };

  const content = fs.readFileSync(source, 'utf8');
  const target = path.join(consumerRoot, CONSUMER_RULES_FILE);
  const existing = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : '';

  const { text, action, collapsed } = spliceManagedBlock(existing, content);
  if (text !== existing) fs.writeFileSync(target, text, 'utf8');
  return { applied: true, action, collapsed, target };
}

function uninstallToolkit({ consumerRoot }) {
  const manifest = safeReadManifest(consumerRoot);
  const removed = [];
  const refused = [];

  if (manifest && Array.isArray(manifest.files)) {
    for (const rel of manifest.files) {
      const native = rel.split('/').join(path.sep);
      if (!isRemovablePath(consumerRoot, native)) {
        refused.push(rel);
        continue;
      }
      const abs = path.resolve(consumerRoot, native);
      if (fs.existsSync(abs)) {
        fs.rmSync(abs);
        removed.push(rel);
      }
    }
    pruneEmptyDirs(path.join(consumerRoot, CONSUMER_SKILLS_DIR));
  }

  const target = path.join(consumerRoot, CONSUMER_RULES_FILE);
  let rulesRemoved = 0;
  if (fs.existsSync(target)) {
    const existing = fs.readFileSync(target, 'utf8');
    const { text, removed: count } = removeManagedBlock(existing);
    if (text !== existing) fs.writeFileSync(target, text, 'utf8');
    rulesRemoved = count;
  }

  const hadManifest = removeManifest(consumerRoot);
  pruneEmptyDirs(path.join(consumerRoot, '.claude'));

  return { hadManifest, removed, refused, rulesRemoved };
}

// A manifest that exists but is corrupt must not stop an install — the whole point of installing
// again is to get back to a known state. The parse error is swallowed here and only here, and the
// fresh manifest written afterwards replaces it.
function safeReadManifest(consumerRoot) {
  try {
    return readManifest(consumerRoot);
  } catch {
    return null;
  }
}

function pruneEmptyDirs(dir) {
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) pruneEmptyDirs(path.join(dir, entry.name));
  }
  if (fs.readdirSync(dir).length === 0) fs.rmdirSync(dir);
}

module.exports = {
  installToolkit,
  uninstallToolkit,
  listSkills,
  listFiles,
  isRemovablePath,
  SKILLS_DIR,
  RULES_FILE,
  CONSUMER_SKILLS_DIR,
  CONSUMER_RULES_FILE,
};
