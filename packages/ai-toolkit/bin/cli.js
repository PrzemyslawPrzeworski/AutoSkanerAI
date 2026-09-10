#!/usr/bin/env node
'use strict';

// The human-invoked entry point: `npx ai-toolkit <command>`.
//
// The difference between this and install.js is the exit code, and that is the whole reason both
// exist. `postinstall` runs as a side effect of a command about something else and must not break
// it, so it exits 0 and shouts. This runs because somebody typed it, so a failure is theirs to see
// and it exits non-zero. Both call the same functions in lib/ — the behaviour cannot drift, only
// the reporting.

const path = require('node:path');

const { resolveConsumerRoot } = require('../lib/project-root');
const { installToolkit, uninstallToolkit } = require('../lib/toolkit');
const { validatePackage } = require('../lib/validate');
const { readManifest } = require('../lib/manifest');

const packageDir = path.resolve(__dirname, '..');
const pkg = require('../package.json');

const USAGE = `${pkg.name} ${pkg.version}

  npx ai-toolkit install    [--target <dir>]   copy skills into .claude/, splice CLAUDE.md rules
  npx ai-toolkit uninstall  [--target <dir>]   remove everything the manifest names
  npx ai-toolkit status     [--target <dir>]   print the installed manifest, if any
  npx ai-toolkit validate                      check this package before publishing

  --target defaults to the project root above $INIT_CWD, or above the current directory.
`;

function parseArgs(argv) {
  const args = { command: null, target: null };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--target' || arg === '-t') {
      args.target = argv[i + 1] ?? null;
      i += 1;
    } else if (arg === '--help' || arg === '-h') {
      args.command = 'help';
    } else if (arg === '--version' || arg === '-v') {
      args.command = 'version';
    } else if (!args.command) {
      args.command = arg;
    }
  }
  return args;
}

// An explicit --target is taken as given (resolved, not root-walked): somebody naming a directory
// means that directory, and silently installing into its parent because that is where the .git
// happens to be would be the surprise. Without --target we walk, because npm's cwd is not the
// consumer's project root.
function resolveTarget(target) {
  if (target) return path.resolve(target);
  return resolveConsumerRoot();
}

function cmdInstall(target) {
  const consumerRoot = resolveTarget(target);
  const report = installToolkit({
    packageDir,
    consumerRoot,
    packageName: pkg.name,
    version: pkg.version,
    mode: 'cli',
  });

  console.log(`${pkg.name} ${pkg.version} -> ${consumerRoot}`);
  for (const skill of report.skills) console.log(`  skill: ${skill}`);
  console.log(`  files: ${report.files.length}`);
  if (report.pruned.length > 0) {
    for (const stale of report.pruned) console.log(`  pruned: ${stale}`);
  }
  if (report.rules.applied) {
    console.log(`  rules: CLAUDE.md managed block ${report.rules.action}`);
    if (report.rules.collapsed > 0) {
      console.log(`  rules: collapsed ${report.rules.collapsed} duplicate block(s)`);
    }
  }
  console.log(`  manifest: ${path.relative(consumerRoot, report.manifestPath)}`);
  return 0;
}

function cmdUninstall(target) {
  const consumerRoot = resolveTarget(target);
  const report = uninstallToolkit({ consumerRoot });

  if (!report.hadManifest && report.removed.length === 0 && report.rulesRemoved === 0) {
    console.log(`${pkg.name}: nothing to remove in ${consumerRoot}.`);
    return 0;
  }

  console.log(`${pkg.name} removed from ${consumerRoot}`);
  for (const rel of report.removed) console.log(`  removed: ${rel}`);
  if (report.rulesRemoved > 0) console.log(`  removed ${report.rulesRemoved} CLAUDE.md managed block(s)`);

  // A refusal is a failure, not a note. The manifest named a path this tool is not allowed to
  // delete, which means something edited it — the consumer should look before trusting the rest.
  if (report.refused.length > 0) {
    console.error(`  refused ${report.refused.length} manifest path(s) outside .claude/:`);
    for (const rel of report.refused) console.error(`    ${rel}`);
    return 1;
  }
  return 0;
}

function cmdStatus(target) {
  const consumerRoot = resolveTarget(target);
  let manifest;
  try {
    manifest = readManifest(consumerRoot);
  } catch (error) {
    console.error(`${pkg.name}: ${error.message}`);
    return 1;
  }

  if (!manifest) {
    console.log(`${pkg.name}: not installed in ${consumerRoot}.`);
    return 0;
  }

  console.log(`${manifest.package} ${manifest.version} in ${consumerRoot}`);
  console.log(`  installed: ${manifest.installedAt} (${manifest.mode})`);
  console.log(`  status:    ${manifest.status}`);
  console.log(`  files:     ${Array.isArray(manifest.files) ? manifest.files.length : 0}`);
  if (manifest.rulesFile) console.log(`  rules in:  ${manifest.rulesFile}`);

  // The failed-postinstall case this command exists for. install.js could not report it with an
  // exit code; here we can.
  if (manifest.status !== 'ok') {
    console.error(`  error:     ${manifest.error ?? '(no detail recorded)'}`);
    console.error('');
    console.error(`  This install did not complete. Re-run: npx ai-toolkit install`);
    return 1;
  }
  return 0;
}

function cmdValidate() {
  const { ok, checks } = validatePackage(packageDir);
  for (const check of checks) {
    const mark = check.ok ? 'ok  ' : 'FAIL';
    console.log(`  ${mark} ${check.name}${check.detail ? ` (${check.detail})` : ''}`);
  }
  const failed = checks.filter((c) => !c.ok).length;
  console.log(`${checks.length} check(s), ${failed} failed`);
  return ok ? 0 : 1;
}

function main(argv) {
  const { command, target } = parseArgs(argv);

  switch (command) {
    case 'install':
      return cmdInstall(target);
    case 'uninstall':
      return cmdUninstall(target);
    case 'status':
      return cmdStatus(target);
    case 'validate':
      return cmdValidate();
    case 'version':
      console.log(pkg.version);
      return 0;
    case 'help':
    case null:
      console.log(USAGE);
      return command === null ? 1 : 0;
    default:
      console.error(`${pkg.name}: unknown command "${command}"`);
      console.error(USAGE);
      return 1;
  }
}

try {
  process.exitCode = main(process.argv.slice(2));
} catch (error) {
  console.error(`${pkg.name}: ${error && error.message ? error.message : String(error)}`);
  process.exitCode = 1;
}
