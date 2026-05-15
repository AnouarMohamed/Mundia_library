# AGENTS.md — Mundiapolis Library

## Quick start

```bash
npm ci
cp .env.example .env.local
docker compose up -d db
npm run db:migrate && npm run seed
npm run dev
```

Local accounts: `test@user.com` / `12345678` (student), `test@admin.com` / `12345678` (admin).

## Commands

| Command | Purpose |
|---------|---------|
| `npm run lint` | ESLint with `--max-warnings=0` |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run test` | Vitest |
| `npm run ci:quality` | lint → typecheck → test → build (run before PR) |
| `npm run dev:turbo` | Next.js with Turbopack |
| `npm run db:migrate` | `drizzle-kit push` (not the deprecated `run-migrations.js`) |
| `npm run db:generate` | `drizzle-kit generate` |
| `npm run seed` | Seed books + test users (run after migrate) |

## Architecture

- **Next.js 15 App Router**, React 19, TypeScript, Drizzle ORM, PostgreSQL.
- **NextAuth v5** credentials provider, JWT sessions. Auth file (`auth.ts`) uses lazy DB imports for Edge runtime compatibility.
- **Middleware** only guards `/admin/*` (reads JWT directly, no DB). All other routes protected by layouts/page guards.
- **Server actions** in `lib/actions/` (student) and `lib/admin/actions/` (admin).
- **API routes** in `app/api/`.
- **DB driver**: `database/drizzle.ts` auto-selects `pg` (Pool) for localhost hosts, Neon HTTP driver for remote URLs.
- **Rate limiting**: Upstash Redis via `lib/ratelimit.ts`, bypassed in dev or when `DISABLE_RATE_LIMIT=true`.
- **Cache**: Next.js `unstable_cache` with tags + Upstash Redis stale-while-revalidate (`lib/cache/`).
- **Background workflows**: Upstash QStash/Workflow (`lib/workflow.ts`). Toggle via `ENABLE_WORKFLOWS=false`.

## Conventions

- Path alias `@/*` → project root.
- Tests are colocated as `*.test.ts` next to source. Vitest with `environment: "node"`.
- Tests: `lib/utils.test.ts`, `lib/actions/book.test.ts`, `lib/actions/renewal.test.ts`, `lib/admin/actions/borrow.test.ts`, `lib/admin/actions/recommendations.test.ts`, `lib/cache/redis-cache.test.ts`, `lib/services/apiError.fuzz.test.ts`.
- ESLint ignores `.next/`, `node_modules/`, `out/`, `build/`, `dist/`, `coverage/`, `artifacts/`, `.agents/`, `.cursor/`.
- `scripts/`, `database/seed.ts`, `database/migrate-from-csv.ts` excluded from tsconfig.
- `next.config.mjs` has `eslint.ignoreDuringBuilds: true` (CI still runs lint separately) and `output: "standalone"`.
- `components.json` exists for shadcn/ui setup (if editing UI primitives).

## Key areas

| Directory | Ownership |
|-----------|-----------|
| `app/(root)/` | Student/faculty pages (browse, borrow, review, profile) |
| `app/admin/` | Admin pages (dashboard, catalog, approvals, users) |
| `app/api/` | JSON endpoints (books, borrows, reviews, auth, notifications, workflows) |
| `lib/actions/` | Student server actions |
| `lib/admin/actions/` | Admin server actions |
| `lib/services/` | Business logic (books, borrows, reviews, email, search, notifications) |
| `database/schema.ts` | Drizzle schema (single file, 365 lines) |
| `components/` | React components (shared + admin) |
| `docs/` | Full operating manual (architecture, dev, deploy, operations, testing) |
| `scripts/` | Utility scripts (benchmarks, load tests, data fixes, migrations) |

## Database

- Schema: `database/schema.ts`. Drizzle config: `drizzle.config.ts` (reads `.env.local`).
- Migrations in `migrations/postgres/`. Use `npm run db:generate` then review before committing.
- `npm run db:migrate` = `drizzle-kit push` (applies schema directly, suitable for dev).
- Borrow lifecycle: `PENDING` → `BORROWED` → `RETURNED`. `books.availableCopies` must stay in sync.
- User roles: `USER` (student) and `ADMIN`. Account statuses: `PENDING`, `APPROVED`, `REJECTED`.

## CI

- CI in `.github/workflows/ci.yml` runs: lint, typecheck, test, build, Docker build.
- Required env vars for build: `DATABASE_URL`, `NEXTAUTH_SECRET`, `NEXTAUTH_URL`, ImageKit/Upstash/Brevo vars (dummy values work for CI).
- Security workflows: CodeQL, secret scan, dependency review, npm audit, container scan, Scorecard.

## Design

- Academic/institutional style: deep navy primary, restrained gold accent, warm off-white background.
- Serif headings, system sans UI. See `PRODUCT.md` and `DESIGN.md` before broad UI changes.
