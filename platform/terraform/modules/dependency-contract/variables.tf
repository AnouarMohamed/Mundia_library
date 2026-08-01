variable "postgres_secret_arn" {
  type        = string
  description = "Secrets Manager ARN holding service-specific PostgreSQL URL/user/password."
}

variable "postgres_migration_secret_arn" {
  type        = string
  description = "Secrets Manager ARN holding dedicated short-lived schema-owner PostgreSQL credentials."
}

variable "kafka_secret_arn" {
  type        = string
  description = "Secrets Manager ARN holding TLS/SASL Kafka client material."
}

variable "redis_secret_arn" {
  type        = string
  description = "Secrets Manager ARN holding the TLS Redis connection details."
}

variable "oidc_secret_arn" {
  type        = string
  description = "Secrets Manager ARN holding confidential OIDC client material, if any."
}

variable "otel_secret_arn" {
  type        = string
  description = "Secrets Manager ARN holding OTLP exporter authentication material."
}

variable "object_storage_bucket_arn" {
  type        = string
  description = "Private, encrypted, versioned object bucket ARN."
}

variable "opensearch_endpoint" {
  type        = string
  description = "Private TLS OpenSearch endpoint; never credentials."

  validation {
    condition     = startswith(var.opensearch_endpoint, "https://")
    error_message = "OpenSearch must use an HTTPS endpoint."
  }
}

variable "oidc_issuer_url" {
  type        = string
  description = "Exact institutional issuer URL."

  validation {
    condition     = startswith(var.oidc_issuer_url, "https://")
    error_message = "OIDC issuer must use HTTPS."
  }
}

variable "oidc_jwks_url" {
  type        = string
  description = "Exact institutional JWKS URL."

  validation {
    condition     = startswith(var.oidc_jwks_url, "https://")
    error_message = "OIDC JWKS URL must use HTTPS."
  }
}

variable "otel_exporter_endpoint" {
  type        = string
  description = "TLS OTLP gateway/backend endpoint."

  validation {
    condition     = startswith(var.otel_exporter_endpoint, "https://")
    error_message = "OTLP export must use HTTPS."
  }
}
