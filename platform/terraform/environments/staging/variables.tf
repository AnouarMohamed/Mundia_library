variable "aws_account_id" {
  type        = string
  description = "Dedicated staging AWS account ID."
  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id must contain exactly 12 digits."
  }
}

variable "deployment_role_arn" {
  type        = string
  description = "Protected Terraform plan/apply role in the staging account."
}

variable "region" {
  type = string
}

variable "configuration" {
  type = object({
    vpc_cidr                     = string
    availability_zone_count      = number
    nat_gateway_per_az           = bool
    kubernetes_version           = string
    endpoint_public_access       = bool
    public_access_cidrs          = list(string)
    administrator_principal_arns = set(string)
    addon_versions               = map(string)
    node_instance_types          = list(string)
    node_min_size                = number
    node_desired_size            = number
    node_max_size                = number
    dependency_contract = object({
      postgres_secret_arn         = string
      kafka_secret_arn            = string
      redis_secret_arn            = string
      oidc_secret_arn             = string
      otel_secret_arn             = string
      object_storage_bucket_arn   = string
      opensearch_endpoint         = string
      oidc_issuer_url             = string
      oidc_jwks_url               = string
      otel_exporter_endpoint      = string
      secret_manager_kms_key_arns = list(string)
    })
  })
}
