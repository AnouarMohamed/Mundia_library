output "platform" {
  sensitive = true
  value = {
    cluster_name              = module.environment.cluster_name
    cluster_endpoint          = module.environment.cluster_endpoint
    vpc_id                    = module.environment.vpc_id
    vpc_cidr                  = module.environment.vpc_cidr
    private_subnet_ids        = module.environment.private_subnet_ids
    external_secrets_role_arn = module.environment.external_secrets_role_arn
    dependency_endpoints      = module.environment.dependency_endpoints
    kafka_cluster_arn         = try(module.managed_kafka[0].cluster_arn, null)
    kafka_bootstrap_brokers   = try(module.managed_kafka[0].bootstrap_brokers_sasl_scram, null)
  }
}
