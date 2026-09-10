#!/usr/bin/env node
'use strict';

// npm postinstall entry point.
//
// This file exits 0 unconditionally. That is what the lesson spec asks for — "avoid failing the
// whole npm install" — and it is right: a consumer running `npm install` to get some other
// dependency should not have their install broken because a skill file could not be copied.
//
// But root CLAUDE.md documents exactly what that concession costs when it is the *only* thing you
// do: a hook in this repo was dead from May to September because its failure path was
// `catch -> process.exit(0)` inside `2>/dev/null || true`, and nothing said so. So the exit code is
// given up and everything else is kept:
//
//   - the failure is printed to stderr, framed, unmissable;
//   - the manifest records status: "failed" with the error text, which is the only durable trace
//     once the process is gone;
//   - `ai-toolkit install`, which a human ran on purpose, exits non-zero instead. Same library, and
//     the caller decides what an error means.

const path = require('node:path');

const { resolveConsumerRoot, skipReason } = require('./lib/project-root');
const { installToolkit } = require('./lib/toolkit');
const { buildManifest, writeManifest, readManifest } = require('./lib/manifest');

const pkg = require('./package.json');
const packageDir = __dirname;

function main() {
  const consumerRoot = resolveConsumerRoot();

  const skip = skipReason(packageDir, consumerRoot);
  if (skip) {
    console.log(`${pkg.name}: skipping install — ${skip}.`);
    return;
  }

  const report = installToolkit({
    packageDir,
    consumerRoot,
    packageName: pkg.name,
    version: pkg.version,
    mode: 'postinstall',
  });

  console.log(`${pkg.name} ${pkg.version} installed into ${consumerRoot}`);
  console.log(`  skills: ${report.skills.join(', ') || '(none)'} -> .claude/skills/`);
  console.log(`  files:  ${report.files.length}`);
  if (report.pruned.length > 0) {
    console.log(`  pruned: ${report.pruned.length} file(s) from a previous version`);
  }
  if (report.rules.applied) {
    console.log(`  rules:  CLAUDE.md managed block ${report.rules.action}`);
    if (report.rules.collapsed > 0) {
      console.log(`          collapsed ${report.rules.collapsed} duplicate block(s)`);
    }
  }
  console.log(`  manifest: ${path.relative(consumerRoot, report.manifestPath)}`);
  console.log(`  remove with: npx ai-toolkit uninstall`);
}

try {
  main();
} catch (error) {
  // The loud part. stderr, a frame, the reason, and what to run to see it again with a real exit
  // code — because this process is about to claim success.
  const line = '='.repeat(78);
  console.error(line);
  console.error(`${pkg.name}: POSTINSTALL FAILED — the install did not complete.`);
  console.error(line);
  console.error(`  ${error && error.message ? error.message : String(error)}`);
  console.error('');
  console.error('  npm install itself is left green on purpose, so this is the only warning you');
  console.error('  get. To see the same failure with a non-zero exit code, run:');
  console.error('');
  console.error('      npx ai-toolkit install');
  console.error('');
  console.error(line);

  // Record it. A manifest that only ever held successes would make this state indistinguishable
  // from "never installed".
  //
  // The previous manifest's file list is carried forward rather than replaced with []. A failure
  // here is usually *partial* — installToolkit copies the skills before it touches CLAUDE.md, so a
  // malformed managed block throws with files already on disk. Writing an empty list would make
  // those files untracked, and uninstall would then leave them behind forever. Carrying the old
  // list forward keeps every path uninstall knows about, at the cost of possibly naming a file this
  // version renamed — and a removable stale path is a much smaller problem than an unremovable one.
  try {
    const consumerRoot = resolveConsumerRoot();
    if (!skipReason(packageDir, consumerRoot)) {
      let previous = null;
      try {
        previous = readManifest(consumerRoot);
      } catch {
        // A corrupt manifest is about to be replaced by this one; nothing to carry forward.
      }

      writeManifest(
        consumerRoot,
        buildManifest({
          packageName: pkg.name,
          version: pkg.version,
          files: Array.isArray(previous?.files) ? previous.files : [],
          rulesFile: previous?.rulesFile ?? null,
          status: 'failed',
          error,
          mode: 'postinstall',
        }),
      );
    }
  } catch {
    // Nowhere left to write the record. The stderr block above is the whole signal now.
  }

  process.exitCode = 0;
}
