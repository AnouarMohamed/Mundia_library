output "cluster_name" {
  value = module.cluster.cluster_name
}

output "cluster_endpoint" {
  value = module.cluster.cluster_endpoint
}

output "vpc_id" {
  value = module.network.vpc_id
}

output "vpc_cidr" {
  value = module.network.vpc_cidr
}

output "private_subnet_ids" {
  value = module.network.private_subnet_ids
}

output "external_secrets_role_arn" {
  value = module.external_secrets_identity.role_arn
}

output "dependency_endpoints" {
  value = module.dependencies.endpoints
}

