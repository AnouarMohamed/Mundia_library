# Configuration

Configuration is loaded from environment variables and normalized in `lib/config.ts`. Local scripts also load `.env.local` and sometimes `.env` through `dotenv`.

Use `.env.example` as the safe template. Do not commit real secrets.

## Environment Files

| File                         | Purpose                                              | Commit?           |
| ---------------------------- | ---------------------------------------------------- | ----------------- |
| `.env.example`               | Safe template and documentation.                     | Yes               |
| `.env.local`                 | Local workstation settings and secrets.              | No                |
| `.env`                       | Legacy/local fallback. Avoid for shared development. | No                |
| Vercel environment variables | Preview and production configuration.                | Managed by Vercel |
| GitHub Actions secrets       | CI-only sensitive values.                            | Managed by GitHub |

## Required For Local Development

| Variable                        | Example                                                                | Purpose                                               |
| ------------------------------- | ---------------------------------------------------------------------- | ----------------------------------------------------- |
| `DATABASE_URL`                  | `postgresql://postgres:rootpassword@localhost:5432/library_management` | PostgreSQL connection used by Drizzle and app routes. |
| `NEXTAUTH_SECRET`               | `replace-with-local-secret`                                            | Secret used to sign Auth.js/NextAuth tokens.          |
| `NEXTAUTH_URL`                  | `http://localhost:3000`                                                | Canonical auth callback URL for local development.    |
| `NEXT_PUBLIC_API_ENDPOINT`      | `http://localhost:3000`                                                | Browser-visible API base URL.                         |
| `NEXT_PUBLIC_PROD_API_ENDPOINT` | `http://localhost:3000`                                                | Browser-visible production API base URL fallback.     |

Generate a local auth secret:

```bash
openssl rand -base64 32
```

## Required For Production

Production should set all local required variables plus the service integrations used by enabled features.

| Variable                            | Required when                                | Purpose                                               |
| ----------------------------------- | -------------------------------------------- | ----------------------------------------------------- |
| `AUTH_MODE`                         | Always in staging/production                 | Selects exactly one authority: `oidc` or `local`.     |
| `DATABASE_URL`                      | Always                                       | Production PostgreSQL connection.                     |
| `RATE_LIMIT_BACKEND`                | Always in production                         | Distributed limiter: `redis` or `postgres`.           |
| `NEXTAUTH_SECRET` or `AUTH_SECRET`  | Always                                       | Session signing secret.                               |
| `NEXTAUTH_URL`                      | Always                                       | Public production URL.                                |
| `AUTH_TRUST_HOST`                   | Vercel, proxies, Docker behind reverse proxy | Allows Auth.js to trust forwarded host headers.       |
| `NEXT_PUBLIC_API_ENDPOINT`          | Always                                       | Public base URL used by browser code.                 |
| `NEXT_PUBLIC_PROD_API_ENDPOINT`     | Always                                       | Production base URL used by browser code.             |
| `NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT` | Uploads enabled                              | ImageKit URL endpoint.                                |
| `IMAGEKIT_PRIVATE_KEY`              | Uploads enabled                              | Server-side ImageKit private key.                     |
| `UPSTASH_REDIS_URL`                 | `RATE_LIMIT_BACKEND=redis` or cache enabled  | Upstash Redis REST URL.                               |
| `UPSTASH_REDIS_TOKEN`               | `RATE_LIMIT_BACKEND=redis` or cache enabled  | Upstash Redis token.                                  |
| `QSTASH_URL`                        | Workflows enabled                            | Upstash Workflow base URL.                            |
| `QSTASH_TOKEN`                      | Workflows enabled                            | Upstash QStash token.                                 |
| `QSTASH_CURRENT_SIGNING_KEY`        | Workflows enabled in production              | Current signing key used to verify workflow requests. |
| `QSTASH_NEXT_SIGNING_KEY`           | Workflows enabled in production              | Next signing key used during QStash key rotation.     |
| `BREVO_API_KEY`                     | Brevo email enabled                          | Primary transactional email provider key.             |
| `BREVO_SENDER_EMAIL`                | Brevo email enabled                          | Verified sender address.                              |
| `BREVO_SENDER_NAME`                 | Brevo email enabled                          | Sender display name.                                  |
| `RESEND_TOKEN`                      | Resend fallback/workflow email enabled       | Resend API token.                                     |
| `ENABLE_WORKFLOWS`                  | Background automation enabled                | Toggles workflow features.                            |
| `OIDC_ISSUER`                       | `AUTH_MODE=oidc`                             | Exact HTTPS issuer; matched byte-for-byte to `iss`.   |
| `OIDC_CLIENT_ID`                    | `AUTH_MODE=oidc`                             | Registered confidential BFF client ID.                |
| `OIDC_CLIENT_SECRET`                | `AUTH_MODE=oidc`                             | BFF client secret, sourced from the secret manager.   |
| `OIDC_ALLOWED_EMAIL_DOMAINS`        | `AUTH_MODE=oidc`                             | Exact comma-separated institutional email domains.    |
| `ENABLE_LOCAL_CREDENTIALS`          | `AUTH_MODE=local`                            | Must be explicitly `true`; never enables signup.      |

Protected tiers admit exactly one authentication authority. `AUTH_MODE=oidc`
requires the complete institutional OIDC configuration and rejects local
credentials. `AUTH_MODE=local` requires an explicit
`ENABLE_LOCAL_CREDENTIALS=true`, rejects OIDC configuration, and remains
invitation-only because public signup is forbidden in protected tiers. Local
mode is a transitional deployment option; privileged accounts still require
distributed account/IP throttling, strong rotated passwords, and explicit
administrative capability grants.

Production rate limiting is always distributed. `RATE_LIMIT_BACKEND=redis`
uses Upstash for rate limits and cache. `RATE_LIMIT_BACKEND=postgres` stores
only SHA-256 identifier digests and atomic security budgets in PostgreSQL; the
Redis cache then becomes optional and safely degrades to direct database reads.
The request-boundary middleware covers all API routes and mutating page
requests with read, command, and sensitive budgets. Health probes remain
independent of the limiter so orchestration can diagnose an unavailable store.
Unavailable admission fails closed in staging and production.

OIDC identities are never auto-linked by email. Before a user can sign in, an
administrator must bind the exact issuer/subject tuple to an existing local
user through the privileged CLI described in
[Institutional OIDC](./OIDC_IDENTITY.md).
Encrypted BFF sessions have an eight-hour absolute maximum, and institutional
sessions are rechecked against their opaque live binding on protected access.

## Docker Compose Variables

`docker-compose.yml` also reads:

| Variable            | Default              | Purpose                        |
| ------------------- | -------------------- | ------------------------------ |
| `POSTGRES_USER`     | `postgres`           | Local database user.           |
| `POSTGRES_PASSWORD` | `rootpassword`       | Local database password.       |
| `POSTGRES_DB`       | `library_management` | Local database name.           |
| `POSTGRES_PORT`     | `5432`               | Host port for PostgreSQL.      |
| `APP_PORT`          | `3000`               | Host port for the application. |
| `ADMINER_PORT`      | `8080`               | Host port for Adminer.         |

## CI And Benchmark Variables

GitHub Actions defines safe CI defaults for build and benchmarks. Production secrets can override them.

API benchmark script variables:

| Variable                             | Default                           | Purpose                                |
| ------------------------------------ | --------------------------------- | -------------------------------------- |
| `BENCH_BASE_URL`                     | `http://127.0.0.1:3000`           | App URL to benchmark.                  |
| `BENCH_WARMUP`                       | `3`                               | Warmup requests per route.             |
| `BENCH_ITERATIONS`                   | `15`                              | Measured requests per route per round. |
| `BENCH_ROUNDS`                       | `1`                               | Number of benchmark rounds.            |
| `BENCH_BOOKS_LIST_P95_MS`            | `500`                             | P95 gate for `/api/books`.             |
| `BENCH_BOOKS_GENRES_P95_MS`          | `350`                             | P95 gate for `/api/books/genres`.      |
| `BENCH_BOOKS_RECOMMENDATIONS_P95_MS` | `550`                             | P95 gate for recommendations.          |
| `BENCH_BOOK_DETAILS_P95_MS`          | `450`                             | P95 gate for book details.             |
| `BENCH_SUMMARY_FILE`                 | `/tmp/api-benchmark-summary.md`   | Markdown artifact path.                |
| `BENCH_RESULTS_FILE`                 | `/tmp/api-benchmark-results.json` | JSON artifact path.                    |

Nightly load-test variables:

| Variable                          | Default                 | Purpose                                     |
| --------------------------------- | ----------------------- | ------------------------------------------- |
| `LOADTEST_BASE_URL`               | `http://127.0.0.1:3000` | App URL to test.                            |
| `LOADTEST_TOTAL_REQUESTS`         | `240`                   | Requests per measured route.                |
| `LOADTEST_CONCURRENCY`            | `20`                    | Concurrent workers per route.               |
| `LOADTEST_BOOKS_P95_MS`           | `700`                   | Absolute P95 threshold for book list.       |
| `LOADTEST_GENRES_P95_MS`          | `500`                   | Absolute P95 threshold for genre list.      |
| `LOADTEST_RECOMMENDATIONS_P95_MS` | `800`                   | Absolute P95 threshold for recommendations. |
| `LOADTEST_BOOK_ID_P95_MS`         | `650`                   | Absolute P95 threshold for detail route.    |
| `LOADTEST_MAX_REGRESSION_PERCENT` | `25`                    | Allowed regression over baseline.           |

## Feature Behavior By Missing Config

The app intentionally fails fast for some missing production settings and degrades for others.

| Area     | Missing config behavior                                                                                            |
| -------- | ------------------------------------------------------------------------------------------------------------------ |
| Database | Database access throws a clear `DATABASE_URL` error. Production server config validation should prevent this.      |
| Redis    | Redis access throws when used, but rate limiting bypasses in development or when disabled.                         |
| ImageKit | Server-mediated `/api/uploads` fails closed if keys are missing; the legacy signing endpoint always returns `410`. |
| QStash   | Workflow calls throw a clear QStash configuration error; production workflow endpoints require signing keys.       |
| Brevo    | Email send fails over to Resend when configured.                                                                   |
| Resend   | Fallback email send fails when token is missing.                                                                   |
| OIDC     | Staging/production startup fails; local development keeps only the admitted local credentials path.                |

## Notification Intent Consumer

The Kotlin Notification service keeps Kafka ingestion disabled until
`NOTIFICATION_INTENT_CONSUMER_ENABLED=true`. Enabling it requires a valid
broker configuration. Protected environments default to `SASL_SSL` and reject
missing SASL credentials; `PLAINTEXT` is accepted only when
`NOTIFICATION_INTENT_KAFKA_ALLOW_INSECURE_TRANSPORT=true` is explicitly set for
an isolated local environment.

The principal settings are `NOTIFICATION_INTENT_KAFKA_BOOTSTRAP_SERVERS`,
`NOTIFICATION_INTENT_KAFKA_SECURITY_PROTOCOL`,
`NOTIFICATION_INTENT_KAFKA_SASL_MECHANISM`,
`NOTIFICATION_INTENT_KAFKA_SASL_JAAS_CONFIG`, and the optional truststore and
keystore variables defined in the service's `application.yml`. Topic, schema,
poll, commit, retry, fetch-size, and health-silence limits have bounded defaults
under the `NOTIFICATION_INTENT_CONSUMER_*` prefix.

Circulation publishes hold-ready intents through its transactional outbox. Its
notification destination is configured by `OUTBOX_NOTIFICATION_TOPIC` and
`OUTBOX_NOTIFICATION_SCHEMA_SUBJECT`; these must match the Notification consumer
topic and Protobuf subject. The Circulation producer's broker principal needs
write ACLs for both its domain-event topic and the notification-intent topic,
while the Notification consumer principal needs read access only to the latter.

Scheduled loan reminders are fail-safe disabled until
`LOAN_REMINDER_ENABLED=true`. `LOAN_REMINDER_DUE_SOON_LEAD_TIME` controls the
bounded due-soon window (default `P3D`), while `LOAN_REMINDER_POLL_INTERVAL` and
`LOAN_REMINDER_BATCH_SIZE` bound database work. Every due-soon or overdue
batch uses `FOR UPDATE SKIP LOCKED`, and every decision and its notification
outbox row commit atomically. A durable receipt
keyed by loan, observed due date, and reminder type makes concurrent scheduler
replicas converge; renewing a loan creates a new due-date identity rather than
silently suppressing the new reminder. Enable the scheduler only with outbox
delivery and the Notification consumer configured, monitored, and authorized.

## Notification Preferences

The Notification API exposes caller-bound preference reads and updates under
the `notification.preferences.read` and `notification.preferences.write`
scopes. Updates require the strong `ETag` returned by the read endpoint in an
`If-Match` header. Missing preconditions return `428`, stale conflicting writes
return `409`, and exact retries converge without another version increment.
In-app delivery cannot be disabled; email and category preferences suppress
provider delivery while preserving an auditable delivery row.

## Notification Email Worker

`NOTIFICATION_EMAIL_WORKER_ENABLED` defaults to `false`. When enabled, the
Notification service claims due email deliveries with database leases and uses
bounded attempts, exponential retry with deterministic jitter, stable delivery
correlation IDs, and terminal dead-letter state. Polling, lease, batch, attempt,
timeout, retry, and backlog-objective bounds are configured by the
`NOTIFICATION_EMAIL_WORKER_*` variables in the service's `application.yml`.

Dead-letter replay is deliberately delivery-specific; there is no bulk replay
endpoint. Reserve `notification.dead-letter.replay` for a trusted operations
client or explicit operator role. Each call requires a unique UUID
`Idempotency-Key` and a 20-to-500 character non-PII justification. A successful
replay snapshots the previous attempts, failure code, and dead-letter timestamp
in `notification_email_dead_letter_replay_audit`, increments `replay_count`, and
starts one fresh bounded attempt cycle. A delivery has a hard lifetime ceiling
of three manual replay cycles. Suppressed recipients and deliveries
outside `DEAD_LETTERED` fail closed. Ambiguous `LEASE_EXPIRED` and `INTERNAL`
failures cannot be manually replayed because the provider may already have
accepted the message.

The recipient resolver uses OAuth 2.0 client credentials with only the
`membership.profile.read.any` scope. Configure `NOTIFICATION_MEMBERSHIP_URL`,
`NOTIFICATION_MEMBERSHIP_TOKEN_URI`, `NOTIFICATION_MEMBERSHIP_CLIENT_ID`,
`NOTIFICATION_MEMBERSHIP_CLIENT_SECRET`, and
`NOTIFICATION_MEMBERSHIP_AUDIENCE` (`membership-api` by default). Both endpoints
must use HTTPS unless `NOTIFICATION_MEMBERSHIP_ALLOW_INSECURE_TRANSPORT=true` is
explicitly set for an isolated local environment. Connect, read, and response
size limits are bounded by the remaining `NOTIFICATION_MEMBERSHIP_*` settings.

Member email addresses remain owned by Membership and are never copied into
Kafka events or read through another service's database.

The email provider is AWS SES v2. Set `AWS_REGION`,
`NOTIFICATION_SES_FROM_ADDRESS`, and `NOTIFICATION_SES_CONFIGURATION_SET`;
the sender identity and configuration set must already exist in that region.
`NOTIFICATION_SES_CALL_TIMEOUT` and `NOTIFICATION_SES_ATTEMPT_TIMEOUT` default
to eight and seven seconds and are validated against safe bounds. The SDK's own
request retry is disabled because SES `SendEmail` has no idempotency token; the
durable worker owns bounded retries. The delivery UUID is sent as both
`X-Mundia-Delivery-Id` and the `delivery_id` SES message tag, so SES events can
be reconciled without including member data in tags.

On EKS, grant only `ses:SendEmail` for the verified identity and configuration
set through EKS Pod Identity or IRSA. The SDK uses its default credential chain;
do not set `AWS_ACCESS_KEY_ID` or `AWS_SECRET_ACCESS_KEY` in Kubernetes. Keep the
worker disabled until workload identity, Membership client credentials, SES
production access, sender verification, configuration-set event publishing,
egress policy, and provider timeouts are configured. Delivery is at least once:
an ambiguous network failure after SES accepts a message may cause a duplicate,
so the send path remains at least once even after feedback reconciliation.

### SES feedback reconciliation

Set `NOTIFICATION_SES_FEEDBACK_ENABLED=true` only after provisioning an encrypted
standard SQS queue subscribed to the SES configuration set's standard SNS topic.
Raw message delivery must remain disabled so the signed SNS envelope reaches the
consumer. Configure the topic's `SignatureVersion` attribute as `2` (SHA-256),
then set `NOTIFICATION_SES_FEEDBACK_QUEUE_URL` and
`NOTIFICATION_SES_FEEDBACK_TOPIC_ARN` to their exact regional values. Placeholder,
cross-region, non-HTTPS, oversized, stale, and future-dated inputs fail closed.

The SNS topic policy must allow `ses.amazonaws.com` to publish only when both
`AWS:SourceAccount` and `AWS:SourceArn` match the owning account and exact SES
configuration set. The SQS queue policy must allow `sqs:SendMessage` only from
the exact SNS topic ARN. Configure server-side encryption, a redrive policy and
a separate encrypted DLQ; invalid or uncorrelated events are deliberately not
deleted and are quarantined by that policy. The pod role needs only
`sqs:ReceiveMessage` and `sqs:DeleteMessage` on the feedback queue, plus
`kms:Decrypt` when a customer-managed KMS key is used.

The consumer verifies the exact topic, a fresh Signature Version 2 envelope,
and an HTTPS signing certificate from the exact regional SNS hostname before
parsing SES data. Certificate downloads reject redirects and enforce connect,
read, and response-size bounds. Reconciliation requires both the durable
`delivery_id` tag and SES message ID to match one accepted send. Only hashes and
non-PII correlation metadata are retained. Multiple pods can poll the standard
queue safely: a PostgreSQL receipt keyed by SNS message ID deduplicates replays,
and outcome precedence prevents late events from regressing complaints or
bounces back to delivered. Signed complaints and permanent bounces atomically
create a member-scoped email suppression record and suppress queued or future
email delivery while preserving mandatory in-app notifications. Transient and
undetermined bounces remain observable but do not suppress the member. No email
address or raw provider payload is retained in the suppression record. Keep
suppression removal an authenticated, audited operational action; do not delete
rows directly in routine operation. The authorization server must reserve
`notification.suppression.write` for the trusted operations client or explicit
operator role. Calls require a unique UUID `Idempotency-Key` and a 20-to-500
character non-PII justification; the service snapshots the prior suppression
into `notification_email_suppression_removal_audit` before deletion. Removal
does not requeue previously suppressed deliveries.

## Digital Content service

The Digital Content service uses `DATABASE_URL`, `DATABASE_USERNAME`, and
`DATABASE_PASSWORD` for its runtime-owned PostgreSQL database and the standard
`AUTH_ISSUER_URI`, `AUTH_JWK_SET_URI`, and `AUTH_AUDIENCE` settings. Its audience
defaults to `digital-content-api`; callers need the narrow
`digital-content.availability.read` scope for the availability endpoint. Flyway
is disabled in packaged runtime images and enabled by `bootRun` for local-only
development, matching the other domain services.

CloudFront signing is disabled by default. Production requires
`DOWNLOAD_SIGNING_ENABLED=true`, an exact HTTPS
`DOWNLOAD_CLOUDFRONT_BASE_URL`, `DOWNLOAD_CLOUDFRONT_KEY_PAIR_ID`, and an
absolute `DOWNLOAD_CLOUDFRONT_PRIVATE_KEY_PATH` pointing to an unencrypted
PKCS#8 RSA key of at least 2048 bits. `DOWNLOAD_URL_LIFETIME` defaults to one
minute and cannot exceed five minutes. Mount the signing key from the managed
secret store; never put PEM contents in an environment variable. The BFF must
use the same base URL and a maximum lifetime no greater than the service value.

The signer is local and needs no AWS API credentials. Object storage must
remain private behind CloudFront Origin Access Control, block public access,
require encryption, and emit scan completion before an asset can become
`CLEAN` and `PUBLISHED`. The authorization API rechecks all gates under a row
lock and persists only a SHA-256 actor fingerprint and timestamps.

Quarantine ingestion is separately disabled by default. Enable it only with
`DIGITAL_CONTENT_INGESTION_ENABLED=true` and set `AWS_REGION`,
`AWS_ACCOUNT_ID`, `DIGITAL_CONTENT_QUARANTINE_BUCKET`, and
`DIGITAL_CONTENT_QUARANTINE_KMS_KEY_ID`. Workload identity must grant only
`s3:PutObject` on the `quarantine/digital-content/` prefix plus the minimum KMS
encrypt permissions; no static AWS key is accepted or documented. Upload grants
default to five minutes and are capped at fifteen minutes. They sign the exact
content length, media type, base64 SHA-256 checksum, expected bucket owner,
SSE-KMS key, and `If-None-Match: *`, making each opaque key create-only.

Set `DIGITAL_CONTENT_SCAN_ENABLED=true` and
`DIGITAL_CONTENT_SCAN_QUEUE_URL` only after the private, versioned quarantine
bucket is protected by GuardDuty Malware Protection for S3 and an EventBridge
rule sends only object-scan results to an encrypted SQS standard queue with a
DLQ. The queue policy must restrict `sqs:SendMessage` to the exact EventBridge
rule; the pod role needs only receive/delete/change-visibility on that queue.
The consumer verifies the exact AWS account, region, bucket, event type,
resource type, protected key shape, timestamp, object version, ETag, and result.
It deletes a message only after the receipt and state transition commit.
`NO_THREATS_FOUND` is the only result that becomes `CLEAN`; every other accepted
result becomes `REJECTED`, and malformed/conflicting events redrive rather than
fail open. Promotion to the delivery prefix is intentionally a separate,
still-pending command.

## Secrets Handling

- Never paste secrets into Markdown docs, GitHub issues, PR descriptions, or screenshots.
- Rotate any secret that was ever committed or printed in logs.
- Use environment-scoped secrets for Vercel production and preview.
- Use GitHub Actions repository or environment secrets for CI.
- Keep local `.env.local` out of commits.
- Confirm release packages do not include `.env`, `.env.*`, `.vercel`, or private keys.

## Production Configuration Review

Before enabling a production deployment:

1. Confirm `NEXTAUTH_URL` matches the public deployment URL exactly.
2. Confirm both API endpoint variables point at the intended host.
3. Confirm `DATABASE_URL` points at the production database, not local Docker.
4. Confirm `ENABLE_WORKFLOWS` is only `true` when QStash and email providers are configured.
5. Confirm `QSTASH_CURRENT_SIGNING_KEY` and `QSTASH_NEXT_SIGNING_KEY` are set before enabling workflows in production.
6. Confirm ImageKit upload endpoints and keys are from the production ImageKit project.
7. Confirm sender email is verified in the email provider.
8. Confirm Redis credentials are active and scoped to the intended Upstash database.
9. Confirm exactly one authentication mode is selected. For OIDC, verify the
   exact issuer and `/api/auth/callback/institutional-oidc` callback. For local
   mode, verify public signup and test fixtures are disabled and every standing
   credential has been rotated.
