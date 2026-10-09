locals {
  common_tags = merge(var.tags, {
    Name       = var.name
    ManagedBy  = "terraform"
    Repository = "Mundia_library/platform"
  })
}

resource "terraform_data" "invariants" {
  input = var.name

  lifecycle {
    precondition {
      condition     = var.broker_count % length(var.private_subnet_ids) == 0
      error_message = "broker_count must be a multiple of the private subnet count."
    }
    precondition {
      condition     = var.replication_factor <= var.broker_count
      error_message = "replication_factor cannot exceed broker_count."
    }
    precondition {
      condition     = var.minimum_in_sync_replicas < var.replication_factor
      error_message = "minimum_in_sync_replicas must be lower than replication_factor."
    }
  }
}

resource "aws_security_group" "broker" {
  name_prefix = "${var.name}-broker-"
  description = "Private TLS/SCRAM access to the Mundiapolis Kafka brokers"
  vpc_id      = var.vpc_id

  egress {
    description = "Broker-to-broker and response traffic inside the VPC"
    protocol    = "-1"
    from_port   = 0
    to_port     = 0
    cidr_blocks = [data.aws_vpc.selected.cidr_block]
  }

  tags = local.common_tags
}

data "aws_vpc" "selected" {
  id = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "scram" {
  for_each = var.client_security_group_ids

  security_group_id            = aws_security_group.broker.id
  referenced_security_group_id = each.value
  description                  = "TLS/SCRAM clients from an approved workload security group"
  ip_protocol                  = "tcp"
  from_port                    = 9096
  to_port                      = 9096
}

resource "aws_vpc_security_group_ingress_rule" "broker_internal" {
  security_group_id            = aws_security_group.broker.id
  referenced_security_group_id = aws_security_group.broker.id
  description                  = "Broker replication and control traffic within the broker group"
  ip_protocol                  = "-1"
}

resource "aws_cloudwatch_log_group" "broker" {
  name              = "/aws/msk/${var.name}/broker"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  skip_destroy      = true
  tags              = local.common_tags
}

resource "aws_msk_configuration" "this" {
  name              = "${var.name}-configuration"
  kafka_versions    = [var.kafka_version]
  server_properties = <<-PROPERTIES
    auto.create.topics.enable=false
    default.replication.factor=${var.replication_factor}
    min.insync.replicas=${var.minimum_in_sync_replicas}
    num.partitions=3
    offsets.topic.replication.factor=${var.replication_factor}
    transaction.state.log.min.isr=${var.minimum_in_sync_replicas}
    transaction.state.log.replication.factor=${var.replication_factor}
    unclean.leader.election.enable=false
  PROPERTIES
}

resource "aws_msk_cluster" "this" {
  cluster_name           = var.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_count
  enhanced_monitoring    = "PER_BROKER"

  broker_node_group_info {
    client_subnets  = var.private_subnet_ids
    instance_type   = var.broker_instance_type
    security_groups = [aws_security_group.broker.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.broker_volume_gib
      }
    }
  }

  client_authentication {
    sasl {
      scram = true
    }
    unauthenticated = false
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = var.at_rest_kms_key_arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.broker.name
      }
    }
  }

  tags = local.common_tags

  depends_on = [terraform_data.invariants]
}

resource "aws_msk_scram_secret_association" "this" {
  cluster_arn     = aws_msk_cluster.this.arn
  secret_arn_list = sort(tolist(var.scram_secret_arns))
}
