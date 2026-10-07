locals {
  static_origin_id = "${var.name}-spa"
  bff_origin_id    = "${var.name}-web-bff"
  bff_paths = {
    api            = "/api/v1/*"
    oauth_start    = "/oauth2/authorization/*"
    oauth_callback = "/login/oauth2/code/*"
    error          = "/error"
  }
}

resource "aws_s3_bucket" "spa" {
  bucket        = var.spa_bucket_name
  force_destroy = false
  tags          = var.tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "spa" {
  bucket = aws_s3_bucket.spa.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "spa" {
  bucket = aws_s3_bucket.spa.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# This bucket contains only immutable public SPA artifacts. SSE-S3 encrypts them
# at rest without introducing a paid customer-managed KMS key or request fees.
#trivy:ignore:AVD-AWS-0132
resource "aws_s3_bucket_server_side_encryption_configuration" "spa" {
  bucket = aws_s3_bucket.spa.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "spa" {
  bucket = aws_s3_bucket.spa.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "spa" {
  bucket = aws_s3_bucket.spa.id

  rule {
    id     = "expire-noncurrent-releases"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }

  depends_on = [aws_s3_bucket_versioning.spa]
}

resource "aws_cloudfront_origin_access_control" "spa" {
  name                              = "${var.name}-spa"
  description                       = "Signed read-only access to the private SPA bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_function" "router" {
  name    = "${var.name}-router"
  runtime = "cloudfront-js-2.0"
  comment = "Canonical-host redirect and static SPA route rewrite"
  publish = true
  code = templatefile("${path.module}/edge-router.js.tftpl", {
    canonical_host      = var.canonical_host
    redirect_hosts_json = jsonencode(sort(tolist(var.redirect_hosts)))
  })
}

resource "aws_cloudfront_cache_policy" "disabled" {
  name        = "${var.name}-cache-disabled"
  comment     = "Never cache browser session, auth, API, or SPA shell responses"
  default_ttl = 0
  max_ttl     = 0
  min_ttl     = 0

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = true
    enable_accept_encoding_gzip   = true
    cookies_config { cookie_behavior = "none" }
    headers_config { header_behavior = "none" }
    query_strings_config { query_string_behavior = "none" }
  }
}

resource "aws_cloudfront_cache_policy" "immutable_assets" {
  name        = "${var.name}-immutable-assets"
  comment     = "Cache Vite fingerprinted assets for one year"
  default_ttl = 31536000
  max_ttl     = 31536000
  min_ttl     = 31536000

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = true
    enable_accept_encoding_gzip   = true
    cookies_config { cookie_behavior = "none" }
    headers_config { header_behavior = "none" }
    query_strings_config { query_string_behavior = "none" }
  }
}

resource "aws_cloudfront_origin_request_policy" "bff_api" {
  name    = "${var.name}-bff-api"
  comment = "Forward only the browser state required by the session-bound API"

  cookies_config {
    cookie_behavior = "whitelist"
    cookies { items = ["MUNDIA_SESSION", "XSRF-TOKEN"] }
  }
  headers_config {
    header_behavior = "whitelist"
    headers { items = ["Accept", "Content-Type", "Origin", "X-XSRF-TOKEN"] }
  }
  query_strings_config { query_string_behavior = "all" }
}

resource "aws_cloudfront_origin_request_policy" "bff_oauth" {
  name    = "${var.name}-bff-oauth"
  comment = "Forward OAuth state and callback parameters without caching"

  cookies_config { cookie_behavior = "all" }
  headers_config {
    header_behavior = "whitelist"
    headers { items = ["Accept", "Content-Type"] }
  }
  query_strings_config { query_string_behavior = "all" }
}

resource "aws_cloudfront_response_headers_policy" "browser_security" {
  name    = "${var.name}-browser-security"
  comment = "Browser security baseline for the SPA and BFF"

  security_headers_config {
    content_security_policy {
      content_security_policy = "default-src 'self'; base-uri 'self'; connect-src 'self'; font-src 'self'; form-action 'self'; frame-ancestors 'none'; img-src 'self' data: https://archive.org https://directory.doabooks.org https://www.gutenberg.org; object-src 'none'; script-src 'self'; style-src 'self'; upgrade-insecure-requests"
      override                = true
    }
    content_type_options { override = true }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "same-origin"
      override        = true
    }
    strict_transport_security {
      access_control_max_age_sec = 63072000
      include_subdomains         = true
      preload                    = true
      override                   = true
    }
  }

  custom_headers_config {
    items {
      header   = "Permissions-Policy"
      value    = "camera=(), geolocation=(), microphone=(), payment=(), usb=()"
      override = true
    }
  }
}

resource "aws_cloudfront_distribution" "web" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${var.name} same-origin static SPA and Kotlin BFF"
  default_root_object = "index.html"
  aliases             = sort(tolist(var.aliases))
  price_class         = var.price_class
  retain_on_delete    = true
  web_acl_id          = var.web_acl_arn
  http_version        = "http2and3"

  origin {
    domain_name              = aws_s3_bucket.spa.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.spa.id
    origin_id                = local.static_origin_id
  }

  origin {
    domain_name = var.bff_origin_domain_name
    origin_id   = local.bff_origin_id

    custom_origin_config {
      http_port                = 80
      https_port               = 443
      origin_protocol_policy   = "https-only"
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_keepalive_timeout = 5
      origin_read_timeout      = 15
    }
  }

  default_cache_behavior {
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    target_origin_id           = local.static_origin_id
    viewer_protocol_policy     = "redirect-to-https"
    compress                   = true
    cache_policy_id            = aws_cloudfront_cache_policy.disabled.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.browser_security.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.router.arn
    }
  }

  ordered_cache_behavior {
    path_pattern               = "/assets/*"
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    target_origin_id           = local.static_origin_id
    viewer_protocol_policy     = "redirect-to-https"
    compress                   = true
    cache_policy_id            = aws_cloudfront_cache_policy.immutable_assets.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.browser_security.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.router.arn
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.bff_paths
    content {
      path_pattern               = ordered_cache_behavior.value
      allowed_methods            = ordered_cache_behavior.key == "api" ? ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"] : ["GET", "HEAD", "OPTIONS"]
      cached_methods             = ["GET", "HEAD"]
      target_origin_id           = local.bff_origin_id
      viewer_protocol_policy     = "redirect-to-https"
      compress                   = true
      cache_policy_id            = aws_cloudfront_cache_policy.disabled.id
      origin_request_policy_id   = ordered_cache_behavior.key == "api" ? aws_cloudfront_origin_request_policy.bff_api.id : aws_cloudfront_origin_request_policy.bff_oauth.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.browser_security.id

      function_association {
        event_type   = "viewer-request"
        function_arn = aws_cloudfront_function.router.arn
      }
    }
  }

  logging_config {
    bucket          = var.log_bucket_domain_name
    include_cookies = false
    prefix          = var.log_prefix
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }

  viewer_certificate {
    acm_certificate_arn      = var.certificate_arn
    minimum_protocol_version = "TLSv1.2_2021"
    ssl_support_method       = "sni-only"
  }

  tags = var.tags

  lifecycle {
    precondition {
      condition     = contains(var.aliases, var.canonical_host)
      error_message = "aliases must contain canonical_host."
    }
    precondition {
      condition     = length(setsubtract(var.redirect_hosts, var.aliases)) == 0
      error_message = "redirect_hosts must be a subset of aliases."
    }
    precondition {
      condition     = var.log_bucket_domain_name != aws_s3_bucket.spa.bucket_domain_name
      error_message = "CloudFront access logs must use a bucket separate from SPA content."
    }
  }
}

data "aws_iam_policy_document" "spa" {
  statement {
    sid       = "AllowCloudFrontReadOnly"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.spa.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.web.arn]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.spa.arn, "${aws_s3_bucket.spa.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "spa" {
  bucket = aws_s3_bucket.spa.id
  policy = data.aws_iam_policy_document.spa.json

  depends_on = [aws_s3_bucket_public_access_block.spa]
}
