variable "name" {
  type        = string
  description = "Environment-qualified MSK cluster name."
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Private subnets spanning every broker availability zone."

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "MSK requires at least two private subnets."
  }
}

variable "client_security_group_ids" {
  type        = set(string)
  description = "Security groups allowed to reach the TLS/SCRAM broker listener."

  validation {
    condition     = length(var.client_security_group_ids) > 0
    error_message = "At least one explicitly approved client security group is required."
  }
}

variable "kafka_version" {
  type        = string
  description = "Explicitly approved MSK Kafka version; never a floating value."

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+$", var.kafka_version))
    error_message = "kafka_version must be an explicit semantic version such as 3.9.1."
  }
}

variable "broker_instance_type" {
  type        = string
  description = "Capacity-tested MSK broker instance type."

  validation {
    condition     = startswith(var.broker_instance_type, "kafka.")
    error_message = "broker_instance_type must be an MSK kafka.* instance type."
  }
}

variable "broker_count" {
  type        = number
  description = "Total brokers; must be a multiple of the subnet count."

  validation {
    condition     = var.broker_count >= 2
    error_message = "At least two brokers are required."
  }
}

variable "broker_volume_gib" {
  type        = number
  description = "Encrypted EBS storage per broker."

  validation {
    condition     = var.broker_volume_gib >= 100
    error_message = "MSK broker storage must be at least 100 GiB."
  }
}

variable "replication_factor" {
  type        = number
  description = "Default topic replication factor. Staging should use three."

  validation {
    condition     = var.replication_factor >= 2
    error_message = "Kafka topics require at least two replicas."
  }
}

variable "minimum_in_sync_replicas" {
  type        = number
  description = "Minimum replicas that must acknowledge durable writes."

  validation {
    condition     = var.minimum_in_sync_replicas >= 2
    error_message = "At least two in-sync replicas are required."
  }
}

variable "at_rest_kms_key_arn" {
  type        = string
  description = "Approved customer-managed KMS key for broker storage."

  validation {
    condition     = can(regex("^arn:[^:]+:kms:[^:]+:[0-9]{12}:key/.+$", var.at_rest_kms_key_arn))
    error_message = "at_rest_kms_key_arn must be a customer-managed KMS key ARN."
  }
}

variable "log_kms_key_arn" {
  type        = string
  description = "Approved customer-managed KMS key whose policy permits CloudWatch Logs encryption."

  validation {
    condition     = can(regex("^arn:[^:]+:kms:[^:]+:[0-9]{12}:key/.+$", var.log_kms_key_arn))
    error_message = "log_kms_key_arn must be a customer-managed KMS key ARN."
  }
}

variable "scram_secret_arns" {
  type        = set(string)
  description = "Pre-created AmazonMSK_ Secrets Manager ARNs encrypted with a customer-managed key."

  validation {
    condition = length(var.scram_secret_arns) > 0 && alltrue([
      for arn in var.scram_secret_arns : can(regex("^arn:[^:]+:secretsmanager:[^:]+:[0-9]{12}:secret:AmazonMSK_.+$", arn))
    ])
    error_message = "At least one AmazonMSK_ Secrets Manager ARN is required."
  }
}

variable "log_retention_days" {
  type        = number
  default     = 90
  description = "CloudWatch broker-log retention."
}

variable "tags" {
  type = map(string)
}
