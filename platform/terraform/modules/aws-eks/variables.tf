variable "name" {
  type        = string
  description = "Environment-qualified EKS cluster name."
}

variable "kubernetes_version" {
  type        = string
  description = "Approved EKS minor version, such as 1.35. Never float this value."

  validation {
    condition     = can(regex("^1\\.[0-9]{2}$", var.kubernetes_version))
    error_message = "kubernetes_version must be an explicit 1.xx minor."
  }
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "At least two private subnets are required."
  }
}

variable "endpoint_public_access" {
  type        = bool
  default     = false
  description = "Production and staging must remain false."
}

variable "public_access_cidrs" {
  type        = list(string)
  default     = []
  description = "Approved operator CIDRs when public API access is explicitly enabled."

  validation {
    condition = alltrue([
      for cidr in var.public_access_cidrs :
      can(cidrnetmask(cidr)) && cidr != "0.0.0.0/0"
    ])
    error_message = "Public API CIDRs must be valid and may never include 0.0.0.0/0."
  }
}

variable "administrator_principal_arns" {
  type        = set(string)
  description = "SSO/break-glass IAM role ARNs granted EKS cluster admin."
  default     = []
}

variable "addon_versions" {
  type        = map(string)
  description = "Approved exact EKS add-on versions."

  validation {
    condition = alltrue([
      for name in ["coredns", "kube-proxy", "vpc-cni", "eks-pod-identity-agent"] :
      try(length(var.addon_versions[name]) > 0, false)
    ])
    error_message = "Exact versions are required for coredns, kube-proxy, vpc-cni, and eks-pod-identity-agent."
  }
}

variable "node_instance_types" {
  type        = list(string)
  description = "Approved, capacity-tested node instance types."
}

variable "node_capacity_type" {
  type    = string
  default = "ON_DEMAND"

  validation {
    condition     = contains(["ON_DEMAND", "SPOT"], var.node_capacity_type)
    error_message = "node_capacity_type must be ON_DEMAND or SPOT."
  }
}

variable "node_min_size" {
  type = number
}

variable "node_desired_size" {
  type = number
}

variable "node_max_size" {
  type = number
}

variable "tags" {
  type    = map(string)
  default = {}
}
