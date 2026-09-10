'use strict';

// Finding the repository this toolkit is being installed into, and deciding whether to install at
// all.
//
// Two failure modes this exists to prevent:
//
//   1. Installing into the wrong directory. During `postinstall` the cwd is the *package's* own
//      directory inside `node_modules`, not the consumer's project. npm sets `INIT_CWD` to where
//      the user actually ran `npm install`, which is the only reliable pointer back out.
//
//   2. Installing into ourselves. `npm install` run inside `packages/ai-toolkit` during
//      development would otherwise create `packages/ai-toolkit/.claude/skills/code-review/` and
//      splice a rules block into a CLAUDE.md that is the source of that very block.

const fs = require('node:fs');
const path = require('node:path');

// Walk up from `startDir` for the first directory that looks like a project root. `.git` wins over
// `package.json`: in a monorepo the nearest package.json is a sub-package, and the consumer's
// CLAUDE.md and .claude/ live at the repository root.
function findProjectRoot(startDir) {
  let dir = path.resolve(startDir);
  let packageJsonFallback = null;

  for (;;) {
    if (fs.existsSync(path.join(dir, '.git'))) return dir;
    if (packageJsonFallback === null && fs.existsSync(path.join(dir, 'package.json'))) {
      packageJsonFallback = dir;
    }

    const parent = path.dirname(dir);
    if (parent === dir) return packageJsonFallback ?? path.resolve(startDir);
    dir = parent;
  }
}

// Where the consumer ran the command. `INIT_CWD` is npm's own answer and is set for every
// lifecycle script; cwd is the fallback for a direct `node install.js`.
function resolveConsumerRoot(env = process.env, cwd = process.cwd()) {
  const start = env.INIT_CWD && env.INIT_CWD.trim() !== '' ? env.INIT_CWD : cwd;
  return findProjectRoot(start);
}

// True when this copy of the package is one npm installed — i.e. it lives under a `node_modules`
// directory. A copy running from a source checkout is a developer working on the toolkit, not a
// consumer installing it.
//
// This is a capability test, not a variable test: it asks where the code actually is rather than
// trusting an environment flag that a wrapper could set wrongly.
function isInstalledCopy(packageDir) {
  return path
    .resolve(packageDir)
    .split(path.sep)
    .includes('node_modules');
}

// The postinstall gate. Returns null to proceed, or a human-readable reason to skip.
function skipReason(packageDir, consumerRoot) {
  if (!isInstalledCopy(packageDir)) {
    return 'running from a source checkout, not from node_modules — nothing to install';
  }
  if (path.resolve(packageDir) === path.resolve(consumerRoot)) {
    return 'the resolved project root is the package itself';
  }
  if (path.resolve(consumerRoot).startsWith(path.resolve(packageDir) + path.sep)) {
    return 'the resolved project root is inside the package';
  }
  return null;
}

module.exports = { findProjectRoot, resolveConsumerRoot, isInstalledCopy, skipReason };
