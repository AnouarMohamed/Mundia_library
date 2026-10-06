variable "name" {
  type        = string
  description = "Short environment-qualified name used for global edge resources."

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,47}$", var.name))
    error_message = "name must be 2-48 lowercase alphanumeric or hyphen characters."
  }
}

variable "spa_bucket_name" {
  type        = string
  description = "Globally unique private S3 bucket for immutable SPA assets."
}

variable "canonical_host" {
  type        = string
  description = "Canonical public hostname without scheme or path."
}

variable "redirect_hosts" {
  type        = set(string)
  description = "Alternate public hosts permanently redirected to canonical_host."
  default     = []
}

variable "aliases" {
  type        = set(string)
  description = "All public hostnames covered by the CloudFront certificate."
}

variable "certificate_arn" {
  type        = string
  description = "Issued ACM certificate ARN in us-east-1 covering every alias."

  validation {
    condition     = can(regex("^arn:aws:acm:us-east-1:[0-9]{12}:certificate/", var.certificate_arn))
    error_message = "CloudFront certificate_arn must be an ACM certificate in us-east-1."
  }
}

variable "bff_origin_domain_name" {
  type        = string
  description = "TLS hostname of the Kubernetes Web BFF ingress origin."
}

variable "web_acl_arn" {
  type        = string
  description = "CloudFront-scope WAFv2 web ACL ARN. WAF must rate-limit viewers and protect the BFF routes."

  validation {
    condition     = can(regex(":global/webacl/", var.web_acl_arn))
    error_message = "web_acl_arn must reference a CloudFront-scope WAFv2 web ACL."
  }
}

variable "log_bucket_domain_name" {
  type        = string
  description = "Dedicated access-log bucket domain name; never the SPA content bucket."
}

variable "log_prefix" {
  type        = string
  description = "Prefix used for privacy-filtered CloudFront standard logs."
  default     = "cloudfront/"
}

variable "price_class" {
  type        = string
  description = "Approved CloudFront price class."
  default     = "PriceClass_100"

  validation {
    condition     = contains(["PriceClass_100", "PriceClass_200", "PriceClass_All"], var.price_class)
    error_message = "price_class must be a supported CloudFront price class."
  }
}

variable "tags" {
  type        = map(string)
  description = "Common ownership and environment tags."
}
