module "environment" {
  source = "../../modules/environment"

  environment                  = "staging"
  region                       = var.region
  vpc_cidr                     = var.configuration.vpc_cidr
  availability_zone_count      = var.configuration.availability_zone_count
  nat_gateway_per_az           = var.configuration.nat_gateway_per_az
  kubernetes_version           = var.configuration.kubernetes_version
  endpoint_public_access       = var.configuration.endpoint_public_access
  public_access_cidrs          = var.configuration.public_access_cidrs
  administrator_principal_arns = var.configuration.administrator_principal_arns
  addon_versions               = var.configuration.addon_versions
  node_instance_types          = var.configuration.node_instance_types
  node_min_size                = var.configuration.node_min_size
  node_desired_size            = var.configuration.node_desired_size
  node_max_size                = var.configuration.node_max_size
  dependency_contract          = var.configuration.dependency_contract
  tags = {
    CostCenter = "REPLACE_COST_CENTER"
    Owner      = "REPLACE_PLATFORM_OWNER"
  }
}

module "managed_kafka" {
  count  = var.managed_kafka.enabled ? 1 : 0
  source = "../../modules/aws-msk"

  name                      = "mundia-staging-events"
  vpc_id                    = module.environment.vpc_id
  private_subnet_ids        = module.environment.private_subnet_ids
  client_security_group_ids = [module.environment.cluster_security_group_id]
  kafka_version             = var.managed_kafka.kafka_version
  broker_instance_type      = var.managed_kafka.broker_instance_type
  broker_count              = var.managed_kafka.broker_count
  broker_volume_gib         = var.managed_kafka.broker_volume_gib
  replication_factor        = var.managed_kafka.replication_factor
  minimum_in_sync_replicas  = var.managed_kafka.minimum_in_sync_replicas
  at_rest_kms_key_arn       = var.managed_kafka.at_rest_kms_key_arn
  log_kms_key_arn           = var.managed_kafka.log_kms_key_arn
  scram_secret_arns         = var.managed_kafka.scram_secret_arns

  tags = {
    CostCenter  = "REPLACE_COST_CENTER"
    Environment = "staging"
    Owner       = "REPLACE_PLATFORM_OWNER"
    Product     = "mundiapolis-library"
  }
}
