#!/usr/bin/env node
'use strict';

// Uninstall, invoked directly (`node uninstall.js`) or through the CLI.
//
// Deliberately NOT wired to npm's `preuninstall`/`uninstall` lifecycle. npm removed those hooks'
// reliability years ago — they do not run for a transitive removal, and on some flows they run with
// the package already gone. A remover that only sometimes runs is worse than one the consumer
// invokes: it leaves a repo in a state nobody chose. So removal is an explicit act:
// `npx ai-toolkit uninstall`, documented in the README and printed at the end of every install.
//
// Everything it deletes is named in the manifest, and every path is re-checked against `.claude/`
// before removal — see isRemovablePath in lib/toolkit.js.

const { resolveConsumerRoot } = require('./lib/project-root');
const { uninstallToolkit } = require('./lib/toolkit');

const pkg = require('./package.json');

function run(consumerRoot) {
  const report = uninstallToolkit({ consumerRoot });

  if (!report.hadManifest && report.removed.length === 0 && report.rulesRemoved === 0) {
    console.log(`${pkg.name}: nothing to remove in ${consumerRoot} (no manifest, no managed block).`);
    return report;
  }

  console.log(`${pkg.name} removed from ${consumerRoot}`);
  console.log(`  files removed: ${report.removed.length}`);
  if (report.rulesRemoved > 0) {
    console.log(`  rules: removed ${report.rulesRemoved} managed block(s) from CLAUDE.md`);
  }
  if (report.refused.length > 0) {
    // Not a warning to bury: a manifest entry pointing outside `.claude/` means the file was
    // edited by something other than this installer.
    console.warn(`  refused ${report.refused.length} manifest path(s) outside .claude/:`);
    for (const rel of report.refused) console.warn(`    ${rel}`);
  }
  return report;
}

module.exports = { run };

if (require.main === module) {
  try {
    run(resolveConsumerRoot());
  } catch (error) {
    console.error(`${pkg.name}: uninstall failed — ${error && error.message ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
