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

