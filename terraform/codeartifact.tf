# The domain and its two repositories.
#
# Two, not one, because that is how CodeArtifact proxies npm:
#
#   npm-store  holds the public:npmjs external connection and caches whatever it fetches upstream.
#   npm        is what developers log into and publish to, with npm-store as its upstream.
#
# Attaching the external connection directly to the repository that also holds private packages
# would work for reads and would mix cached public copies into the private repository — so a
# dependency-confusion mistake and a legitimate private package would live in the same namespace,
# and "where did this version come from" would stop having an answer. The split keeps the boundary
# where it can be reasoned about.
#
# A repository accepts an external connection OR upstreams, never both, which is the API-level
# reason this shape is not optional.

resource "aws_codeartifact_domain" "this" {
  domain         = var.domain_name
  encryption_key = aws_kms_key.codeartifact.arn
}

resource "aws_codeartifact_repository" "npm_store" {
  domain      = aws_codeartifact_domain.this.domain
  repository  = var.proxy_repository_name
  description = "Caching proxy for public npm. Nothing is published here directly."

  external_connections {
    external_connection_name = "public:npmjs"
  }
}

resource "aws_codeartifact_repository" "npm" {
  domain      = aws_codeartifact_domain.this.domain
  repository  = var.private_repository_name
  description = "Private npm repository for @${var.package_namespace}/* packages. Falls through to ${var.proxy_repository_name} for everything else."

  upstream {
    repository_name = aws_codeartifact_repository.npm_store.repository
  }
}
