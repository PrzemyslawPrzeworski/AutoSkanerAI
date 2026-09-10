# The managed policy CI/CD needs, and its attachment to the role that already exists.
#
# The role is a data source, not a resource. GitHub's OIDC provider, the trust policy and the
# `sub` condition that decides which repository may assume the role are owned by whoever set up
# OIDC for the account; recreating them here would either fight that owner or, worse, succeed and
# quietly widen the trust policy. `tf-registry/SKILL.md` sanctions exactly this: "The GitHub Actions
# role can be referenced as an existing role when the org already owns OIDC setup."

data "aws_iam_role" "github_actions" {
  name = var.github_actions_role_name
}

data "aws_iam_policy_document" "codeartifact_developer" {
  # The one people forget. `aws codeartifact login` and `codeartifact:GetAuthorizationToken` both
  # end in a bearer token minted by STS, and without this statement the login fails with an STS
  # error that says nothing about CodeArtifact. Resource must be "*" — the API takes no resource
  # here — and the condition is what keeps that "*" from being a general bearer-token grant.
  statement {
    sid       = "MintCodeArtifactBearerToken"
    effect    = "Allow"
    actions   = ["sts:GetServiceBearerToken"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "sts:AWSServiceName"
      values   = ["codeartifact.amazonaws.com"]
    }
  }

  statement {
    sid       = "AuthenticateToDomain"
    effect    = "Allow"
    resources = [local.domain_arn]
    actions = [
      "codeartifact:GetAuthorizationToken",
      "codeartifact:GetDomainPermissionsPolicy",
      "codeartifact:ListRepositoriesInDomain",
    ]
  }

  # Repository-level reads. Scoped to the private repository only: nothing in CI has a reason to
  # touch npm-store directly, since resolution reaches it through the upstream on `npm`.
  statement {
    sid       = "ReadRepository"
    effect    = "Allow"
    resources = [local.repository_arn]
    actions = [
      "codeartifact:DescribeRepository",
      "codeartifact:GetRepositoryEndpoint",
      "codeartifact:ListPackages",
      "codeartifact:ReadFromRepository",
    ]
  }

  # Publish, and only inside this scope. The package ARN ends in `/*`, so the grant covers every
  # package under @<namespace> and nothing outside it — a compromised CI token cannot publish over
  # somebody else's scope in the same repository.
  statement {
    sid       = "PublishOwnScope"
    effect    = "Allow"
    resources = [local.package_arn]
    actions = [
      "codeartifact:DescribePackageVersion",
      "codeartifact:ListPackageVersions",
      "codeartifact:PublishPackageVersion",
      "codeartifact:PutPackageMetadata",
      "codeartifact:ReadFromRepository",
    ]
  }

  # Deliberately absent: DeletePackageVersions, DisposePackageVersions, UpdatePackageVersionsStatus.
  # A published version is immutable by contract, and a CI role that can retract one can also make a
  # consumer's lockfile unresolvable. Yanking a bad version is a human decision with a human's
  # credentials.
}

resource "aws_iam_policy" "codeartifact_developer" {
  name        = "${var.domain_name}-codeartifact-developer"
  description = "Authenticate to the ${var.domain_name} domain, read ${var.private_repository_name}, publish under @${var.package_namespace}. No delete."
  policy      = data.aws_iam_policy_document.codeartifact_developer.json
}

resource "aws_iam_role_policy_attachment" "github_actions_codeartifact" {
  role       = data.aws_iam_role.github_actions.name
  policy_arn = aws_iam_policy.codeartifact_developer.arn
}
