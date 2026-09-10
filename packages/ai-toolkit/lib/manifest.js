'use strict';

// The install manifest.
//
// Uninstall reads this file instead of guessing paths. That is the whole reason it exists: a
// remover that globs for `.claude/skills/*` would delete skills the consumer installed from
// somewhere else, and one that hardcodes today's file list would orphan yesterday's when the
// package changes shape.
//
// It also records failures. A `postinstall` that could not finish exits 0 so the consumer's
// `npm install` still succeeds, which means the exit code carries no information — the manifest is
// then the only durable trace that something went wrong, so `status: "failed"` is written with the
// error text. A manifest that only ever recorded successes would make a broken install look
// identical to no install at all.

const fs = require('node:fs');
const path = require('node:path');

const MANIFEST_RELATIVE = path.join('.claude', '.ai-toolkit-manifest.json');

function manifestPath(consumerRoot) {
  return path.join(consumerRoot, MANIFEST_RELATIVE);
}

function buildManifest({ packageName, version, files, rulesFile, status, error, mode }) {
  const manifest = {
    package: packageName,
    version,
    installedAt: new Date().toISOString(),
    mode,
    status,
    // Repo-relative, POSIX separators, so a manifest written on Windows is readable on Linux.
    files: [...files].map((f) => f.split(path.sep).join('/')).sort(),
  };
  if (rulesFile) manifest.rulesFile = rulesFile.split(path.sep).join('/');
  if (error) manifest.error = String(error && error.message ? error.message : error);
  return manifest;
}

function writeManifest(consumerRoot, manifest) {
  const target = manifestPath(consumerRoot);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  return target;
}

// Returns null when there is no manifest, and throws only when one exists but cannot be parsed —
// an unreadable manifest is a real problem worth reporting, whereas an absent one just means
// "never installed".
function readManifest(consumerRoot) {
  const target = manifestPath(consumerRoot);
  if (!fs.existsSync(target)) return null;
  const raw = fs.readFileSync(target, 'utf8');
  try {
    return JSON.parse(raw);
  } catch (cause) {
    throw new Error(`${target} exists but is not valid JSON: ${cause.message}`);
  }
}

function removeManifest(consumerRoot) {
  const target = manifestPath(consumerRoot);
  if (!fs.existsSync(target)) return false;
  fs.rmSync(target);
  return true;
}

module.exports = {
  MANIFEST_RELATIVE,
  manifestPath,
  buildManifest,
  writeManifest,
  readManifest,
  removeManifest,
};
