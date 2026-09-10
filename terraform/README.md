# CodeArtifact registry — authored, never applied

This directory is the **AWS appendix** to M5-L4 (`m5l4-codeartifact-spec-terraform.md`):
the same private-npm-registry problem solved as managed infrastructure instead of
GitHub Packages.

**Nothing here has ever been applied, and the state it describes does not exist.**
That is a deliberate scope decision, not an oversight:

- Terraform is not installed on the machine this was written on, so
  `terraform validate` — which needs `terraform init` and a provider download —
  could not run either. `tf-registry/SKILL.md` covers exactly this case: *"If
  Terraform is not installed or provider initialization is unavailable, say so and
  still run static checks over the generated files."*
- The only AWS credential available here is a short-lived corporate SSO profile
  used for Bedrock. Applying this would create billable resources on an account
  that is not mine to shape, and `terraform apply` requires an explicit ask.
- The path that had to actually work is GitHub Packages
  (`.github/workflows/publish-ai-toolkit.yml`), and it does — it is verified end to
  end, including a real `npm install` of the packed tarball.

So: **read this as a design, not as infrastructure.** What was verified is listed
under "Verification" below; what was not is listed under "Not verified", and the
second list is the longer one.

## What it describes

| File | Holds |
|---|---|
| `main.tf` | `terraform` block, S3 backend with native locking, provider, default tags, the account-id assertion |
| `variables.tf` | every input, with the validation rules the spec's constraints imply |
| `kms.tf` | the customer-managed key and `alias/devs10x-codeartifact` |
| `codeartifact.tf` | the domain, the `npm-store` proxy with its `public:npmjs` connection, and the `npm` repository upstream of it |
| `iam.tf` | the `devs10x-codeartifact-developer` managed policy and its attachment to the pre-existing GitHub Actions role |
| `outputs.tf` | the registry endpoint and the exact scoped-login command |
| `terraform.tfvars.example` | the four values an operator must supply |

Two repositories, not one, because that is how CodeArtifact proxies npm: `npm-store`
holds the `public:npmjs` external connection and caches upstream packages; `npm` is
what developers log into and publish to, with `npm-store` as its upstream. A single
repository with the external connection attached directly would work for reads and
mix cached public packages into the repository that also holds private ones.

## The gotcha worth keeping

```bash
aws codeartifact login --tool npm --domain devs10x --repository npm \
  --namespace przemyslawprzeworski
```

`--namespace` takes the **npm package scope without the `@`**, not the CodeArtifact
domain. Here those are two unrelated strings: the scope is
`przemyslawprzeworski` (forced by GitHub Packages, which requires the scope to
equal the owning account) and the domain is `devs10x` (from the lesson spec). Pass
the domain to `--namespace` and the login silently scopes nothing you own, so every
install still resolves from public npm and the failure looks like "the package does
not exist".

A scoped login is the right default: it routes only this scope through
CodeArtifact and leaves every other dependency on public npm.

## Verification

Run here, and passing:

- `terraform fmt -check` equivalent — canonical formatting checked by hand against
  the fmt rules (two-space indent, aligned `=` within a block, one blank line
  between blocks); see `context/changes/ai-toolkit-distribution/change.md` for the
  exact commands run.
- Static checks over the generated files: every `var.` reference resolves to a
  declared variable, every `local.` to a declared local, every cross-file resource
  reference to a declared resource, no hardcoded account id, no credential of any
  kind, no `access_key`/`secret_key` in a provider block.

## Not verified

- `terraform init`, `validate`, `plan`, `apply` — Terraform is not installed.
- The provider version constraint `>= 5.30, < 5.40` against the real provider: the
  attribute names used here (`external_connections`, `upstream`, `encryption_key`)
  are from the AWS provider docs for that range, not from a resolved schema.
- The IAM policy actually granting enough for `npm publish` — the action list is
  assembled from the CodeArtifact documentation, and `sts:GetServiceBearerToken`
  (the one people forget) is included, but only an apply proves the set is
  sufficient and minimal.
- That the pre-existing role `github-actions-codeartifact` and the state bucket
  `10xdevs-terraform-state` exist at all. Both are referenced as given.

## If you ever do apply it

```bash
cp terraform.tfvars.example terraform.tfvars   # then fill in aws_account_id
terraform init
terraform fmt -check
terraform validate
terraform plan -out tfplan
# read the plan, then and only then:
terraform apply tfplan
```

`terraform.tfvars` is gitignored — it carries an account id, which is not a secret
but is not mine to publish either.
