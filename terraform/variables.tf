# Inputs.
#
# Every constraint the spec states as prose is a `validation` block here instead. "Must start with a
# lowercase letter" in a table is something a reader honours; in a validation block it is something
# `terraform plan` honours — and a CodeArtifact domain rejected by the API halfway through an apply
# leaves a KMS key behind with nothing using it.

variable "aws_region" {
  description = "Region the CodeArtifact domain and repositories live in."
  type        = string
  default     = "eu-central-1"

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.aws_region))
    error_message = "aws_region must look like a region id, e.g. eu-central-1."
  }
}

variable "aws_account_id" {
  description = "Account that owns the domain. Used to build the CodeArtifact ARNs in the IAM policy; asserted against the active credentials in main.tf."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id must be exactly 12 digits. Leave the placeholder in terraform.tfvars.example unfilled rather than guessing."
  }
}

variable "domain_name" {
  description = "CodeArtifact domain name. NOT the npm package scope — see package_namespace."
  type        = string
  default     = "devs10x"

  # The spec's "must start with a lowercase letter" is the API's rule, not a style preference.
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,49}$", var.domain_name))
    error_message = "domain_name must start with a lowercase letter and contain only lowercase letters, digits and hyphens (2-50 chars)."
  }
}

variable "private_repository_name" {
  description = "Repository developers log into and publish to."
  type        = string
  default     = "npm"
}

variable "proxy_repository_name" {
  description = "Repository holding the public:npmjs external connection, used as the private repository's upstream."
  type        = string
  default     = "npm-store"
}

variable "package_namespace" {
  description = <<-EOT
    The npm package scope, WITHOUT the leading @.

    This is the string `aws codeartifact login --namespace` wants, and it is not the domain: the
    scope here is forced by GitHub Packages (which requires the scope to equal the owning account),
    while the domain comes from the lesson spec. Passing the domain to --namespace scopes nothing
    you own, and the resulting failure looks like "the package does not exist".
  EOT
  type        = string
  default     = "przemyslawprzeworski"

  validation {
    condition     = !startswith(var.package_namespace, "@")
    error_message = "package_namespace must not include the leading @ — CodeArtifact takes the bare scope."
  }
}

variable "project_name" {
  description = "Value of the Project tag applied to every resource."
  type        = string
  default     = "webinar-demo"
}

variable "github_actions_role_name" {
  description = "Pre-existing IAM role GitHub Actions assumes through OIDC. Referenced as a data source, never created here — OIDC provider setup is owned elsewhere."
  type        = string
  default     = "github-actions-codeartifact"
}

variable "kms_deletion_window_in_days" {
  description = "Waiting period before the CodeArtifact KMS key is actually destroyed. Long enough that destroying the key by accident is recoverable."
  type        = number
  default     = 30

  validation {
    condition     = var.kms_deletion_window_in_days >= 7 && var.kms_deletion_window_in_days <= 30
    error_message = "AWS accepts a KMS deletion window between 7 and 30 days."
  }
}
