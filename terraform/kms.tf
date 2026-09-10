# The customer-managed key CodeArtifact encrypts assets with.
#
# A customer-managed key rather than the AWS-managed default, because the domain's encryption key
# cannot be changed after creation: choosing the default now and wanting key rotation, a key policy,
# or an audit trail later means recreating the domain and re-publishing every version in it.

resource "aws_kms_key" "codeartifact" {
  description         = "Encrypts assets in the ${var.domain_name} CodeArtifact domain."
  enable_key_rotation = true

  # Deliberately long: deleting this key makes every stored package version unreadable, and the
  # window is the only thing standing between a mistaken `terraform destroy` and that outcome.
  deletion_window_in_days = var.kms_deletion_window_in_days
}

resource "aws_kms_alias" "codeartifact" {
  name          = "alias/${var.domain_name}-codeartifact"
  target_key_id = aws_kms_key.codeartifact.key_id
}
