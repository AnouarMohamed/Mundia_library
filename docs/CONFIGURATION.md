# Configuration

Configuration is loaded from environment variables and normalized in `lib/config.ts`. Local scripts also load `.env.local` and sometimes `.env` through `dotenv`.

Use `.env.example` as the safe template. Do not commit real secrets.

## Environment Files

| File | Purpose | Commit? |
| --- | --- | --- |
| `.env.example` | Safe template and documentation. | Yes |
| `.env.local` | Local workstation settings and secrets. | No |
| `.env` | Legacy/local fallback. Avoid for shared development. | No |
| Vercel environment variables | Preview and production configuration. | Managed by Vercel |
| GitHub Actions secrets | CI-only sensitive values. | Managed by GitHub |

## Required For Local Development

| Variable | Example | Purpose |
| --- | --- | --- |
| `DATABASE_URL` | `postgresql://postgres:rootpassword@localhost:5432/library_management` | PostgreSQL connection used by Drizzle and app routes. |
| `NEXTAUTH_SECRET` | `replace-with-local-secret` | Secret used to sign Auth.js/NextAuth tokens. |
| `NEXTAUTH_URL` | `http://localhost:3000` | Canonical auth callback URL for local development. |
| `NEXT_PUBLIC_API_ENDPOINT` | `http://localhost:3000` | Browser-visible API base URL. |
| `NEXT_PUBLIC_PROD_API_ENDPOINT` | `http://localhost:3000` | Browser-visible production API base URL fallback. |

Generate a local auth secret:

```bash
openssl rand -base64 32
```

## Required For Production

Production should set all local required variables plus the service integrations used by enabled features.

| Variable | Required when | Purpose |
| --- | --- | --- |
| `DATABASE_URL` | Always | Production PostgreSQL connection. |
| `NEXTAUTH_SECRET` or `AUTH_SECRET` | Always | Session signing secret. |
| `NEXTAUTH_URL` | Always | Public production URL. |
| `AUTH_TRUST_HOST` | Vercel, proxies, Docker behind reverse proxy | Allows Auth.js to trust forwarded host headers. |
| `NEXT_PUBLIC_API_ENDPOINT` | Always | Public base URL used by browser code. |
| `NEXT_PUBLIC_PROD_API_ENDPOINT` | Always | Production base URL used by browser code. |
| `NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY` | Uploads enabled | Public ImageKit key. |
| `NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT` | Uploads enabled | ImageKit URL endpoint. |
| `IMAGEKIT_PRIVATE_KEY` | Uploads enabled | Server-side ImageKit private key. |
| `UPSTASH_REDIS_URL` | Rate limiting, cache | Upstash Redis REST URL. |
| `UPSTASH_REDIS_TOKEN` | Rate limiting, cache | Upstash Redis token. |
| `QSTASH_URL` | Workflows enabled | Upstash Workflow base URL. |
| `QSTASH_TOKEN` | Workflows enabled | Upstash QStash token. |
| `BREVO_API_KEY` | Brevo email enabled | Primary transactional email provider key. |
| `BREVO_SENDER_EMAIL` | Brevo email enabled | Verified sender address. |
| `BREVO_SENDER_NAME` | Brevo email enabled | Sender display name. |
| `RESEND_TOKEN` | Resend fallback/workflow email enabled | Resend API token. |
| `ENABLE_WORKFLOWS` | Background automation enabled | Toggles workflow features. |

## Docker Compose Variables

`docker-compose.yml` also reads:

| Variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_USER` | `postgres` | Local database user. |
| `POSTGRES_PASSWORD` | `rootpassword` | Local database password. |
| `POSTGRES_DB` | `library_management` | Local database name. |
| `POSTGRES_PORT` | `5432` | Host port for PostgreSQL. |
| `APP_PORT` | `3000` | Host port for the application. |
| `ADMINER_PORT` | `8080` | Host port for Adminer. |

## CI And Benchmark Variables

GitHub Actions defines safe CI defaults for build and benchmarks. Production secrets can override them.

API benchmark script variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `BENCH_BASE_URL` | `http://127.0.0.1:3000` | App URL to benchmark. |
| `BENCH_WARMUP` | `3` | Warmup requests per route. |
| `BENCH_ITERATIONS` | `15` | Measured requests per route per round. |
| `BENCH_ROUNDS` | `1` | Number of benchmark rounds. |
| `BENCH_BOOKS_LIST_P95_MS` | `500` | P95 gate for `/api/books`. |
| `BENCH_BOOKS_GENRES_P95_MS` | `350` | P95 gate for `/api/books/genres`. |
| `BENCH_BOOKS_RECOMMENDATIONS_P95_MS` | `550` | P95 gate for recommendations. |
| `BENCH_BOOK_DETAILS_P95_MS` | `450` | P95 gate for book details. |
| `BENCH_SUMMARY_FILE` | `/tmp/api-benchmark-summary.md` | Markdown artifact path. |
| `BENCH_RESULTS_FILE` | `/tmp/api-benchmark-results.json` | JSON artifact path. |

Nightly load-test variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `LOADTEST_BASE_URL` | `http://127.0.0.1:3000` | App URL to test. |
| `LOADTEST_TOTAL_REQUESTS` | `240` | Requests per measured route. |
| `LOADTEST_CONCURRENCY` | `20` | Concurrent workers per route. |
| `LOADTEST_BOOKS_P95_MS` | `700` | Absolute P95 threshold for book list. |
| `LOADTEST_GENRES_P95_MS` | `500` | Absolute P95 threshold for genre list. |
| `LOADTEST_RECOMMENDATIONS_P95_MS` | `800` | Absolute P95 threshold for recommendations. |
| `LOADTEST_BOOK_ID_P95_MS` | `650` | Absolute P95 threshold for detail route. |
| `LOADTEST_MAX_REGRESSION_PERCENT` | `25` | Allowed regression over baseline. |

## Feature Behavior By Missing Config

The app intentionally fails fast for some missing production settings and degrades for others.

| Area | Missing config behavior |
| --- | --- |
| Database | Database access throws a clear `DATABASE_URL` error. Production server config validation should prevent this. |
| Redis | Redis access throws when used, but rate limiting bypasses in development or when disabled. |
| ImageKit | `/api/auth/imagekit` returns an error if ImageKit keys are missing. |
| QStash | Workflow calls throw a clear QStash configuration error. |
| Brevo | Email send fails over to Resend when configured. |
| Resend | Fallback email send fails when token is missing. |

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
5. Confirm ImageKit upload endpoints and keys are from the production ImageKit project.
6. Confirm sender email is verified in the email provider.
7. Confirm Redis credentials are active and scoped to the intended Upstash database.
