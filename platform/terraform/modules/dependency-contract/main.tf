locals {
  secret_arns = {
    postgres = var.postgres_secret_arn
    kafka    = var.kafka_secret_arn
    redis    = var.redis_secret_arn
    oidc     = var.oidc_secret_arn
    otel     = var.otel_secret_arn
  }

  endpoints = {
    opensearch  = var.opensearch_endpoint
    oidc_issuer = var.oidc_issuer_url
    oidc_jwks   = var.oidc_jwks_url
    otlp        = var.otel_exporter_endpoint
  }
}
