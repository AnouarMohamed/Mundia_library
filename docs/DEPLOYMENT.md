# Deployment

Mundiapolis Library can be deployed to Vercel, Docker, or a standalone Next.js server bundle. Vercel is the current public deployment path. Docker and standalone packaging are useful for self-hosting, institutional servers, and release artifacts.

## Deployment Prerequisites

Every production deployment needs:

- A PostgreSQL database.
- A stable public URL.
- Production auth secret.
- Production environment variables.
- Applied schema changes.
- At least one approved admin user.
- Monitoring access to application logs and database health.

## Required Production Environment

Minimum:

```bash
DATABASE_URL=
NEXTAUTH_SECRET=
NEXTAUTH_URL=
AUTH_TRUST_HOST=true
NEXT_PUBLIC_API_ENDPOINT=
NEXT_PUBLIC_PROD_API_ENDPOINT=
```

Recommended for full product behavior:

```bash
NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY=
NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT=
IMAGEKIT_PRIVATE_KEY=
UPSTASH_REDIS_URL=
UPSTASH_REDIS_TOKEN=
QSTASH_URL=
QSTASH_TOKEN=
BREVO_API_KEY=
BREVO_SENDER_EMAIL=
BREVO_SENDER_NAME=Mundiapolis Library
RESEND_TOKEN=
ENABLE_WORKFLOWS=true
```

Keep `ENABLE_WORKFLOWS=false` until QStash and email are verified.

## Option 1: Vercel

Vercel is the preferred hosted target for the current app.

### Setup

1. Import the GitHub repository into Vercel.
2. Set the framework preset to Next.js.
3. Configure production and preview environment variables.
4. Connect the production domain.
5. Confirm Node.js version compatibility with the project and CI.

### Build

Vercel runs:

```bash
npm run build
```

The app uses `output: "standalone"` in `next.config.mjs`, but Vercel manages runtime packaging automatically.

### Database Migration

Do not rely on Vercel app startup to mutate production schema.

Preferred production migration flow:

1. Review generated SQL or Drizzle schema diff.
2. Take or confirm a database backup.
3. Apply schema changes from a controlled environment:

```bash
DATABASE_URL="production-url" npm run db:migrate
```

4. Deploy application code.
5. Run smoke tests.

### Smoke Test

After deployment:

```bash
curl -fsS "$NEXT_PUBLIC_PROD_API_ENDPOINT/api/books?limit=1"
curl -fsS "$NEXT_PUBLIC_PROD_API_ENDPOINT/api/books/genres"
```

Then verify in a browser:

- Sign in.
- Open catalog.
- Open a book detail page.
- Submit a borrow request in a test environment, or inspect the path without mutating production data.
- Sign in as admin.
- Open `/admin`.
- Check users, book requests, books, automation, and exports.

## Option 2: Docker

The repository includes a multi-stage Dockerfile and Compose stack.

### Build Image

```bash
docker build -t mundia-library:local .
```

### Run Full Local Stack

```bash
docker compose up --build
```

Services:

| Service | Purpose |
| --- | --- |
| `db` | PostgreSQL 15. |
| `migrate` | Applies Drizzle schema. |
| `app` | Production Next.js standalone server. |
| `seed` | Optional seeded data, behind the `tools` profile. |
| `adminer` | Browser database admin tool. |

Run seed in Compose:

```bash
docker compose --profile tools run --rm seed
```

### Production Docker Notes

- Provide secrets through the platform secret manager, not image layers.
- Use a managed PostgreSQL service for real production.
- Put the app behind HTTPS.
- Set `NEXTAUTH_URL` to the public HTTPS URL.
- Set `AUTH_TRUST_HOST=true` behind a reverse proxy.
- Run migrations as a separate release step before the app rolls forward.

## Option 3: Standalone Package

Next.js standalone output is enabled, so a release package can contain:

- `.next/standalone`
- `.next/static`
- `public`
- a run instruction file

Build:

```bash
npm run build
```

Run from the standalone output:

```bash
cd .next/standalone
NODE_ENV=production PORT=3000 node server.js
```

Release artifacts should not include:

- `.env`
- `.env.*`
- `.vercel`
- `*.pem`
- local database dumps
- build logs containing secrets

Verify an archive before publishing:

```bash
tar -tzf release/mundia-library-v0.2.1-standalone.tar.gz | rg '(^|/)\\.env($|\\.)|\\.pem$|\\.vercel'
```

The command should print nothing.

## Deployment Order

Use this order for production releases:

1. Merge reviewed code.
2. Run `npm run ci:quality`.
3. Confirm environment variables.
4. Backup database or confirm provider recovery point.
5. Apply database schema changes.
6. Deploy application.
7. Run smoke tests.
8. Run API benchmark against target environment if this release touches API, database, cache, or performance-sensitive pages.
9. Tag release.
10. Publish release notes and artifacts.
11. Monitor logs and user-facing flows.

## Rollback

Rollback depends on what changed.

### Code-only rollback

Redeploy the previous Vercel deployment or previous container image.

### Database-compatible rollback

If the new code was backward compatible with the old schema, redeploy previous code.

### Database-incompatible rollback

Do not rollback blindly. Decide one of:

- Roll forward with a fix.
- Restore from backup.
- Apply a reviewed down migration if one exists and data loss is acceptable.

For production, prefer additive schema changes and two-step releases:

1. Add new nullable columns or tables.
2. Deploy code that writes both old and new paths.
3. Backfill.
4. Deploy code that reads the new path.
5. Remove old path later.

## Health Checks

Basic read checks:

```bash
curl -fsS "$BASE_URL/api/books?limit=1"
curl -fsS "$BASE_URL/api/books/genres"
curl -fsS "$BASE_URL/api/books/recommendations?limit=3"
```

Authenticated and admin checks currently require browser/session validation.

## Production Monitoring Expectations

At minimum, monitor:

- HTTP 5xx rate.
- Auth errors and redirect loops.
- Database connection failures.
- Slow `/api/books` and `/api/borrow-records` responses.
- Admin action failures.
- Email provider failures.
- QStash workflow failures.
- Redis/rate-limit failures.
- Vercel or container memory and cold-start behavior.

## Deployment Risks To Watch

- `DATABASE_URL` pointing at the wrong database.
- `NEXTAUTH_URL` mismatch causing auth callback or cookie problems.
- Workflow automation enabled before email sender verification.
- Missing ImageKit keys breaking sign-up or book cover upload.
- Missing Redis credentials causing cache/rate-limit paths to fail in production.
- Schema changes applied after application code starts using the new shape.
