variable "environment" {
  type = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging, or prod."
  }
}

variable "region" {
  type = string
}

variable "vpc_cidr" {
  type = string
}

variable "availability_zone_count" {
  type = number
}

variable "nat_gateway_per_az" {
  type = bool
}

variable "kubernetes_version" {
  type = string
}

variable "endpoint_public_access" {
  type = bool
}

variable "public_access_cidrs" {
  type    = list(string)
  default = []
}

variable "administrator_principal_arns" {
  type = set(string)
}

variable "addon_versions" {
  type = map(string)
}

variable "node_instance_types" {
  type = list(string)
}

variable "node_min_size" {
  type = number
}

variable "node_desired_size" {
  type = number
}

variable "node_max_size" {
  type = number
}

variable "dependency_contract" {
  description = "Only resource identifiers and non-secret endpoints. Never pass secret values."
  type = object({
    postgres_secret_arn           = string
    postgres_migration_secret_arn = string
    kafka_secret_arn              = string
    redis_secret_arn              = string
    oidc_secret_arn               = string
    otel_secret_arn               = string
    object_storage_bucket_arn     = string
    opensearch_endpoint           = string
    oidc_issuer_url               = string
    oidc_jwks_url                 = string
    otel_exporter_endpoint        = string
    secret_manager_kms_key_arns   = list(string)
  })
}

variable "tags" {
  type = map(string)
}
