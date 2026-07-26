variable "name" {
  description = "Stable environment-qualified network name."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,40}$", var.name))
    error_message = "name must be a lowercase DNS-style identifier (3-41 characters)."
  }
}

variable "vpc_cidr" {
  description = "RFC1918 CIDR allocated by organizational IPAM."
  type        = string

  validation {
    condition     = can(cidrnetmask(var.vpc_cidr)) && startswith(var.vpc_cidr, "10.")
    error_message = "vpc_cidr must be a valid, organization-approved 10/8 CIDR."
  }
}

variable "availability_zone_count" {
  description = "Number of AZs. Production must pass 3."
  type        = number
  default     = 3

  validation {
    condition     = var.availability_zone_count >= 2 && var.availability_zone_count <= 4
    error_message = "availability_zone_count must be between 2 and 4."
  }
}

variable "nat_gateway_per_az" {
  description = "Use one NAT gateway per AZ to avoid a cross-AZ production dependency."
  type        = bool
  default     = true
}

variable "flow_log_retention_days" {
  description = "CloudWatch VPC flow-log retention."
  type        = number
  default     = 90
}

variable "flow_log_kms_key_arn" {
  description = "Approved KMS key ARN for flow logs. Null uses CloudWatch service encryption."
  type        = string
  default     = null
  nullable    = true
}

variable "tags" {
  description = "Mandatory organizational tags."
  type        = map(string)
  default     = {}
}

