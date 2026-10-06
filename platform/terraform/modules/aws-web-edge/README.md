# AWS web edge module

This dormant module translates `platform/edge/same-origin-routing.yaml` into a
private S3 origin plus CloudFront behaviors for the static SPA and Kotlin Web
BFF. It is intentionally not called by an environment yet and therefore creates
nothing and costs nothing.

Before wiring it into an environment, approve the CloudFront-scope WAF, a
separate privacy-filtered log bucket, an issued `us-east-1` ACM certificate,
the BFF origin certificate, DNS records, budgets, and the mechanism that denies
direct internet access to the BFF origin. Promotion must upload the verified CI
artifact according to `web-spa-manifest.json`; Terraform does not build or
upload application files.

The module uses S3 Origin Access Control with SigV4 `always` signing, blocks all
public bucket access, disables caching for the SPA shell and every BFF/auth
route, caches only Vite fingerprinted assets, enforces TLS, and keeps the bucket
and distribution recoverable on destroy.

The OAC and origin policy follow the current
[AWS private S3 origin guidance](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html),
and the cache/origin request policies follow the
[CloudFront request-policy model](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/controlling-origin-requests.html).
