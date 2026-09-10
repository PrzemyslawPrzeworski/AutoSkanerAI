# Outputs.
#
# The login command is an output rather than a line in a runbook because it carries the appendix's
# one real trap: --namespace takes the npm scope, --domain takes the domain, and here those are two
# unrelated strings. A command rendered from the same variables the infrastructure was built from
# cannot drift from it; a command copied into a README can, and the resulting failure looks like a
# missing package rather than a wrong flag.

output "domain_name" {
  description = "CodeArtifact domain. Not the npm scope."
  value       = aws_codeartifact_domain.this.domain
}

output "domain_owner" {
  description = "Account that owns the domain; needed by any cross-account consumer."
  value       = aws_codeartifact_domain.this.owner
}

output "private_repository_name" {
  description = "Repository to log into and publish to."
  value       = aws_codeartifact_repository.npm.repository
}

output "registry_endpoint" {
  description = "npm registry URL for the private repository, for a .npmrc that maps the scope."
  value       = "https://${aws_codeartifact_domain.this.domain}-${aws_codeartifact_domain.this.owner}.d.codeartifact.${var.aws_region}.amazonaws.com/npm/${aws_codeartifact_repository.npm.repository}/"
}

output "npm_scope" {
  description = "The npm package scope, with the @ that CodeArtifact does not want."
  value       = "@${var.package_namespace}"
}

output "scoped_login_command" {
  description = "Route only this scope through CodeArtifact and leave every other dependency on public npm."
  value = join(" ", [
    "aws codeartifact login --tool npm",
    "--domain ${aws_codeartifact_domain.this.domain}",
    "--repository ${aws_codeartifact_repository.npm.repository}",
    "--namespace ${var.package_namespace}",
    "--region ${var.aws_region}",
  ])
}

output "developer_policy_arn" {
  description = "Managed policy attached to the GitHub Actions role."
  value       = aws_iam_policy.codeartifact_developer.arn
}

output "kms_key_alias" {
  description = "Alias of the key encrypting domain assets. The domain's key cannot be changed after creation."
  value       = aws_kms_alias.codeartifact.name
}
