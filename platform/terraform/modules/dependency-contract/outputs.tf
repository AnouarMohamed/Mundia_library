output "secret_arns" {
  value       = local.secret_arns
  description = "Non-secret resource identifiers consumed by IAM and ExternalSecret definitions."
}

output "object_storage_bucket_arn" {
  value = var.object_storage_bucket_arn
}

output "endpoints" {
  value = local.endpoints
}

