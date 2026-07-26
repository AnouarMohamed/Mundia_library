variable "name" {
  type = string
}

variable "cluster_name" {
  type = string
}

variable "namespace" {
  type = string
}

variable "service_account_name" {
  type = string
}

variable "managed_policy_arns" {
  type    = set(string)
  default = []
}

variable "inline_policy_json" {
  type        = string
  default     = null
  nullable    = true
  description = "Least-privilege policy generated from non-secret resource ARNs."

  validation {
    condition     = var.inline_policy_json == null || can(jsondecode(var.inline_policy_json))
    error_message = "inline_policy_json must be valid JSON."
  }
}

variable "tags" {
  type    = map(string)
  default = {}
}

