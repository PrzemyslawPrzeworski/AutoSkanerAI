# Terraform settings, state, provider and the tags every resource inherits.
#
# NOTHING IN THIS DIRECTORY HAS BEEN APPLIED. See terraform/README.md for what was verified and
# what was not — the second list is longer.

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.30, < 5.40"
    }
  }

  # Native S3 locking, so there is no DynamoDB table to provision and no second thing to keep in
  # sync with this file. It needs Terraform >= 1.10, which required_version already pins above;
  # on an older CLI `use_lockfile` is silently ignored rather than rejected, and a lock that is
  # silently absent is the failure mode this whole repo keeps writing down.
  backend "s3" {
    bucket       = "10xdevs-terraform-state"
    key          = "codeartifact/terraform.tfstate"
    region       = "eu-central-1"
    encrypt      = true
    use_lockfile = true
  }
}

# No access_key, secret_key, profile or assume_role here on purpose. Credentials come from the
# environment — OIDC in CI, an SSO profile locally — so this file is safe to commit and there is
# no credential to leak or rotate. `tf-registry/SKILL.md`: "Terraform should not contain hardcoded
# personal credentials."
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.tags
  }
}

locals {
  tags = {
    Project     = var.project_name
    ManagedBy   = "terraform"
    Environment = "demo"
  }

  # Every CodeArtifact ARN this configuration needs, built once. The package ARN's shape is the
  # part worth reading twice: package/<domain>/<repository>/npm/<scope>/<name>, where <scope> is
  # the npm scope with no `@`. Two different names sit next to each other here — the CodeArtifact
  # domain (devs10x) and the npm scope (przemyslawprzeworski) — and confusing them is the
  # documented gotcha of this whole appendix.
  domain_arn     = "arn:aws:codeartifact:${var.aws_region}:${var.aws_account_id}:domain/${var.domain_name}"
  repository_arn = "arn:aws:codeartifact:${var.aws_region}:${var.aws_account_id}:repository/${var.domain_name}/${var.private_repository_name}"
  package_arn    = "arn:aws:codeartifact:${var.aws_region}:${var.aws_account_id}:package/${var.domain_name}/${var.private_repository_name}/npm/${var.package_namespace}/*"
}

data "aws_caller_identity" "current" {}

# aws_account_id is an input because the spec makes it one, and an input that is never checked is a
# comment. If the operator's credentials point at a different account, every ARN in iam.tf silently
# grants nothing — a policy that parses, attaches, and denies. Fail on the mismatch instead.
check "account_id_matches_credentials" {
  assert {
    condition = var.aws_account_id == data.aws_caller_identity.current.account_id
    error_message = join("", [
      "var.aws_account_id is ${var.aws_account_id} but the active credentials belong to ",
      "${data.aws_caller_identity.current.account_id}. The IAM policy ARNs would name an account ",
      "you are not authenticated to, so the policy would attach and grant nothing.",
    ])
  }
}
