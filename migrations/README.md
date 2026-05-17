# Database Migrations

`migrations/postgres` is the only active migration path. The project is now
PostgreSQL-first for local development, Vercel, and production releases.

`migrations/legacy-mysql` is archived reference material only. Do not point
Drizzle, CI, or deployment scripts at it.

Use:

```bash
npm run db:generate
npm run db:migrate
```

`npm run db:push` is for local development only and must not be used as the
production release path.
