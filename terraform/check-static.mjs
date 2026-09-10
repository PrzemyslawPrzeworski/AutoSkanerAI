/**
 * Static checks over terraform/*.tf, for a machine with no Terraform on PATH.
 *
 * `tf-registry/SKILL.md` says: "If Terraform is not installed or provider initialization is
 * unavailable, say so and still run static checks over the generated files." This is that. It is
 * NOT a substitute for `terraform validate` — it does not know the AWS provider schema, so it
 * cannot catch a misspelled attribute or a wrong argument type. What it does catch is the class of
 * mistake that survives careful reading: a `var.` that was renamed in one file and not another, a
 * resource reference to something that does not exist, a credential pasted into a provider block.
 *
 * Run: node terraform/check-static.mjs
 *
 * Exits non-zero on any failure. Its own dead-gate guard is the file count: zero .tf files found is
 * a failure, not an empty pass.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const DIR = import.meta.dirname;

const files = readdirSync(DIR)
  .filter((n) => n.endsWith('.tf'))
  .sort();

const failures = [];
const fail = (msg) => failures.push(msg);
const ok = (msg) => console.log(`  ok   ${msg}`);

if (files.length === 0) {
  console.error(`x no .tf files in ${DIR} — nothing was checked, which is not the same as passing.`);
  process.exit(1);
}
console.log(`${files.length} file(s): ${files.join(', ')}\n`);

const sources = new Map(files.map((f) => [f, readFileSync(join(DIR, f), 'utf8')]));
const all = [...sources.values()].join('\n');

// --- 1. Balanced braces per file. A stray brace is the one syntax error that reads as correct. ---
for (const [file, text] of sources) {
  const stripped = text
    .replace(/#[^\n]*/g, '')
    .replace(/\/\/[^\n]*/g, '')
    .replace(/"(?:[^"\\\n]|\\.)*"/g, '""');
  const open = (stripped.match(/\{/g) ?? []).length;
  const close = (stripped.match(/\}/g) ?? []).length;
  if (open !== close) fail(`${file}: ${open} '{' vs ${close} '}' — unbalanced`);
}
if (!failures.length) ok('braces balance in every file');

// --- 2. Every var. reference is declared. ---
const declaredVars = new Set([...all.matchAll(/^variable\s+"([^"]+)"/gm)].map((m) => m[1]));
const usedVars = new Set([...all.matchAll(/\bvar\.([a-z_][a-z0-9_]*)/g)].map((m) => m[1]));
for (const v of usedVars) if (!declaredVars.has(v)) fail(`var.${v} is used but never declared`);
for (const v of declaredVars) if (!usedVars.has(v)) fail(`variable "${v}" is declared but never used`);
ok(`${declaredVars.size} variables declared, ${usedVars.size} referenced, all matched`);

// --- 3. Every local. reference is declared. ---
const localsBlocks = [...all.matchAll(/^locals\s*\{([\s\S]*?)^\}/gm)].map((m) => m[1]);
const declaredLocals = new Set(
  localsBlocks.flatMap((b) => [...b.matchAll(/^\s{2}([a-z_][a-z0-9_]*)\s*=/gm)].map((m) => m[1])),
);
const usedLocals = new Set([...all.matchAll(/\blocal\.([a-z_][a-z0-9_]*)/g)].map((m) => m[1]));
for (const l of usedLocals) if (!declaredLocals.has(l)) fail(`local.${l} is used but never declared`);
ok(`${declaredLocals.size} locals declared, all ${usedLocals.size} references resolve`);

// --- 4. Every resource/data cross-reference points at something declared. ---
const declaredResources = new Set(
  [...all.matchAll(/^resource\s+"([^"]+)"\s+"([^"]+)"/gm)].map((m) => `${m[1]}.${m[2]}`),
);
const declaredData = new Set([...all.matchAll(/^data\s+"([^"]+)"\s+"([^"]+)"/gm)].map((m) => `${m[1]}.${m[2]}`));

const KNOWN_PREFIXES = ['aws_', 'terraform_'];
for (const m of all.matchAll(/\bdata\.([a-z][a-z0-9_]*)\.([a-z][a-z0-9_]*)/g)) {
  const ref = `${m[1]}.${m[2]}`;
  if (!declaredData.has(ref)) fail(`data.${ref} is referenced but no such data source is declared`);
}
for (const m of all.matchAll(/(?<!\w)(?<!data\.)(aws_[a-z0-9_]+)\.([a-z][a-z0-9_]*)\.[a-z]/g)) {
  const ref = `${m[1]}.${m[2]}`;
  if (declaredResources.has(ref) || declaredData.has(ref)) continue;
  if (KNOWN_PREFIXES.some((p) => ref.startsWith(p))) fail(`${ref} is referenced but never declared`);
}
ok(`${declaredResources.size} resources + ${declaredData.size} data sources declared, all references resolve`);

// --- 5. No credential of any kind. The rule tf-registry states, checked instead of promised. ---
const FORBIDDEN = [
  [/\baccess_key\s*=/, 'access_key in a provider block'],
  [/\bsecret_key\s*=/, 'secret_key in a provider block'],
  [/AKIA[0-9A-Z]{16}/, 'an AWS access key id'],
  [/aws_secret_access_key/i, 'an AWS secret access key'],
  [/\bprofile\s*=\s*"/, 'a named local AWS profile (credentials must come from the environment)'],
];
for (const [pattern, what] of FORBIDDEN) {
  for (const [file, text] of sources) if (pattern.test(text)) fail(`${file} contains ${what}`);
}
ok('no credentials, keys or personal profiles in any file');

// --- 6. No hardcoded 12-digit account id outside a comment. ---
for (const [file, text] of sources) {
  const code = text.replace(/#[^\n]*/g, '');
  const hit = /(?<![\d.])\d{12}(?![\d.])/.exec(code);
  if (hit) fail(`${file} hardcodes what looks like an account id (${hit[0]}) — it belongs in tfvars`);
}
ok('no hardcoded account id');

// --- 7. The spec's fixed values are actually present. ---
const REQUIRED = [
  ['>= 1.10', 'required_version >= 1.10'],
  ['>= 5.30, < 5.40', 'AWS provider version constraint'],
  ['10xdevs-terraform-state', 'S3 state bucket'],
  ['codeartifact/terraform.tfstate', 'state key'],
  ['use_lockfile = true', 'native S3 locking'],
  ['public:npmjs', 'external connection'],
  ['alias/${var.domain_name}-codeartifact', 'KMS alias (renders to alias/devs10x-codeartifact)'],
  ['${var.domain_name}-codeartifact-developer', 'IAM policy name (renders to devs10x-codeartifact-developer)'],
  ['aws_iam_role_policy_attachment', 'attachment to the pre-existing CI role'],
  ['sts:GetServiceBearerToken', 'the STS action codeartifact login needs'],
];
for (const [needle, what] of REQUIRED) {
  if (!all.includes(needle)) fail(`missing ${what} (expected to find "${needle}")`);
}
ok(`all ${REQUIRED.length} values required by the spec are present`);

// --- 8. Tags. Three keys, from the spec's table. ---
for (const [key, value] of [
  ['Project', 'var.project_name'],
  ['ManagedBy', '"terraform"'],
  ['Environment', '"demo"'],
]) {
  if (!new RegExp(`${key}\\s*=\\s*${value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`).test(all)) {
    fail(`default_tags is missing ${key} = ${value}`);
  }
}
ok('default_tags carries Project, ManagedBy and Environment');

// --- 9. fmt-adjacent formatting: tabs, trailing whitespace, CRLF, missing final newline. ---
for (const [file, text] of sources) {
  if (text.includes('\t')) fail(`${file} contains a tab (terraform fmt uses two spaces)`);
  if (text.includes('\r')) fail(`${file} has CRLF line endings`);
  if (/[ \t]+\n/.test(text)) fail(`${file} has trailing whitespace`);
  if (!text.endsWith('\n')) fail(`${file} has no final newline`);
  if (/\n{3,}/.test(text)) fail(`${file} has more than one consecutive blank line`);
}
ok('no tabs, CRLF, trailing whitespace or double blank lines');

console.log('');
if (failures.length > 0) {
  console.error(`${failures.length} static check(s) FAILED:`);
  for (const f of failures) console.error(`  x ${f}`);
  console.error('');
  console.error('Note: these are static checks only. `terraform validate` was not run — Terraform');
  console.error('is not installed here — so provider schema errors would not be caught by this.');
  process.exit(1);
}
console.log('All static checks passed.');
console.log('NOT run: terraform init/validate/plan/apply — Terraform is not on PATH. See terraform/README.md.');
