# Better Database Admin Options Than phpMyAdmin + XAMPP

For this PostgreSQL project, use one of these options depending on workflow:

## Recommended primary option: DBeaver

Why:

- Strong query profiler and execution plan tooling
- Better large-result handling than phpMyAdmin
- Multiple connections and saved environments
- Works well for both development and production read-only sessions

## Best built-in option for this repo: Drizzle Studio

Run:

```bash
npm run db:studio
```

Why:

- Uses your existing Drizzle schema
- Fast for CRUD inspection and quick validation
- No extra local stack like XAMPP required

## Easy browser-based option in Docker: Adminer

This repo now includes an Adminer service in Docker Compose.

Start:

```bash
docker compose up --build
```

Open:

- http://localhost:8080

Connection values:

- System: PostgreSQL
- Server: db
- Username: value from POSTGRES_USER
- Password: value from POSTGRES_PASSWORD
- Database: value from POSTGRES_DB

## Recommendation by use case

- Daily query + diagnostics: DBeaver
- Schema-aware app-centric inspection: Drizzle Studio
- Quick browser access in containerized local setup: Adminer
