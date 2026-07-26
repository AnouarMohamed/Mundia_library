terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  backend "s3" {}

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0.0, < 7.0.0"
    }
  }
}

provider "aws" {
  region              = var.region
  allowed_account_ids = [var.aws_account_id]

  assume_role {
    role_arn     = var.deployment_role_arn
    session_name = "mundia-platform-prod"
  }

  default_tags {
    tags = {
      Environment = "prod"
      ManagedBy   = "terraform"
      Product     = "mundiapolis-library"
    }
  }
}
