locals {
  name = "mundia-${var.environment}"
  tags = merge(var.tags, {
    Environment = var.environment
    Product     = "mundiapolis-library"
    DataClass   = "confidential"
  })
}

resource "terraform_data" "production_invariants" {
  input = var.environment

  lifecycle {
    precondition {
      condition     = var.environment != "prod" || var.availability_zone_count >= 3
      error_message = "Production requires at least three availability zones."
    }
    precondition {
      condition     = var.environment != "prod" || var.nat_gateway_per_az
      error_message = "Production requires one NAT gateway per availability zone."
    }
    precondition {
      condition     = var.environment == "dev" || !var.endpoint_public_access
      error_message = "Staging and production require a private EKS API."
    }
    precondition {
      condition     = var.environment != "prod" || var.node_min_size >= 3
      error_message = "Production requires at least three worker nodes."
    }
  }
}

module "network" {
  source = "../aws-network"

  name                    = local.name
  vpc_cidr                = var.vpc_cidr
  availability_zone_count = var.availability_zone_count
  nat_gateway_per_az      = var.nat_gateway_per_az
  tags                    = local.tags
}

module "cluster" {
  source = "../aws-eks"

  name                         = local.name
  kubernetes_version           = var.kubernetes_version
  vpc_id                       = module.network.vpc_id
  private_subnet_ids           = module.network.private_subnet_ids
  endpoint_public_access       = var.endpoint_public_access
  public_access_cidrs          = var.public_access_cidrs
  administrator_principal_arns = var.administrator_principal_arns
  addon_versions               = var.addon_versions
  node_instance_types          = var.node_instance_types
  node_min_size                = var.node_min_size
  node_desired_size            = var.node_desired_size
  node_max_size                = var.node_max_size
  tags                         = local.tags

  depends_on = [terraform_data.production_invariants]
}

module "dependencies" {
  source = "../dependency-contract"

  postgres_secret_arn           = var.dependency_contract.postgres_secret_arn
  postgres_migration_secret_arn = var.dependency_contract.postgres_migration_secret_arn
  kafka_secret_arn              = var.dependency_contract.kafka_secret_arn
  redis_secret_arn              = var.dependency_contract.redis_secret_arn
  oidc_secret_arn               = var.dependency_contract.oidc_secret_arn
  otel_secret_arn               = var.dependency_contract.otel_secret_arn
  object_storage_bucket_arn     = var.dependency_contract.object_storage_bucket_arn
  opensearch_endpoint           = var.dependency_contract.opensearch_endpoint
  oidc_issuer_url               = var.dependency_contract.oidc_issuer_url
  oidc_jwks_url                 = var.dependency_contract.oidc_jwks_url
  otel_exporter_endpoint        = var.dependency_contract.otel_exporter_endpoint
}

data "aws_iam_policy_document" "external_secrets" {
  statement {
    sid    = "ReadOnlyApprovedSecrets"
    effect = "Allow"
    actions = [
      "secretsmanager:DescribeSecret",
      "secretsmanager:GetSecretValue",
    ]
    resources = values(module.dependencies.secret_arns)
  }

  statement {
    sid       = "DecryptApprovedSecretKeys"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = var.dependency_contract.secret_manager_kms_key_arns
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.region}.amazonaws.com"]
    }
  }
}

module "external_secrets_identity" {
  source = "../aws-workload-identity"

  name                 = "${local.name}-external-secrets"
  cluster_name         = module.cluster.cluster_name
  namespace            = "external-secrets"
  service_account_name = "external-secrets"
  inline_policy_json   = data.aws_iam_policy_document.external_secrets.json
  tags                 = local.tags
}
