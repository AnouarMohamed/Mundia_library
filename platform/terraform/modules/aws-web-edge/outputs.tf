output "spa_bucket" {
  value = {
    id  = aws_s3_bucket.spa.id
    arn = aws_s3_bucket.spa.arn
  }
}

output "distribution" {
  value = {
    id          = aws_cloudfront_distribution.web.id
    arn         = aws_cloudfront_distribution.web.arn
    domain_name = aws_cloudfront_distribution.web.domain_name
  }
}

output "deployment_contract" {
  description = "Non-secret inputs required by the immutable SPA promotion job."
  value = {
    bucket_id       = aws_s3_bucket.spa.id
    distribution_id = aws_cloudfront_distribution.web.id
    assets_prefix   = "assets/"
    manifest_schema = 1
  }
}
