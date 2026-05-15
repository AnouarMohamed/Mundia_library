# Contributing

This repository is production application code for Mundiapolis Library. Changes should be small enough to review, explicit about risk, and documented when they affect setup, operations, data, or user workflows.

## Development Flow

1. Create a branch from the intended base branch.
2. Install dependencies with `npm ci`.
3. Copy `.env.example` to `.env.local`.
4. Start local PostgreSQL with Docker Compose.
5. Apply schema and seed data.
6. Make the change.
7. Run the appropriate checks.
8. Update documentation.
9. Open a PR with a clear summary and test evidence.

Setup commands:

```bash
npm ci
cp .env.example .env.local
docker compose up -d db
npm run db:migrate
npm run seed
npm run dev
```

## Quality Gate

Run before opening a production-bound PR:

```bash
npm run ci:quality
```

For API, cache, or database-sensitive changes:

```bash
npm run benchmark:api
```

For query or index changes:

```bash
npm run explain:hot-queries
```

## Pull Request Standard

Each PR should include:

- What changed.
- Why it changed.
- User or admin workflow impact.
- Database impact, if any.
- Environment variable impact, if any.
- Test evidence.
- Screenshots for visible UI changes.
- Rollback or mitigation notes for risky changes.

## Code Review Priorities

Reviewers should look first for:

- Broken auth or authorization.
- Cross-user data access.
- Borrow lifecycle regressions.
- Copy count drift.
- Data loss.
- Missing validation.
- Missing cache invalidation.
- Production config or deployment risk.
- Missing tests for risky logic.
- Docs that no longer match behavior.

## Documentation Rule

Update docs in the same PR when changing:

- Environment variables.
- Setup commands.
- Deployment process.
- Database schema.
- API routes.
- Admin operations.
- Release process.
- Security posture.
- Background jobs or email behavior.

Primary docs live in `docs/`.

## Branch Hygiene

- Do not commit `.env`, `.env.local`, `.vercel`, `.next`, or local release archives unless the release artifact is intentionally tracked.
- Do not revert unrelated user changes.
- Do not commit generated logs.
- Keep schema migrations paired with the code that needs them.
- Keep package lockfile changes only when dependencies changed.

## Security

Never put secrets in:

- Markdown files.
- PR descriptions.
- Screenshots.
- Test fixtures.
- Console logs.
- Release artifacts.

Report vulnerabilities through GitHub Security Advisories, not public issues.

See `SECURITY.md`.

## UI Changes

The product direction is academic and institutional:

- Mundiapolis deep navy as the primary brand color.
- Restrained gold accent.
- Warm off-white page background.
- Serif headings and system sans UI.
- No generic SaaS teal.
- No repeated all-caps micro-labels.
- No decorative stat cards unless the number is real and useful.
- No nested-card layouts.

See:

- `PRODUCT.md`
- `DESIGN.md`

## Database Changes

Before changing schema:

1. Read `database/schema.ts`.
2. Decide whether the migration is additive or destructive.
3. Generate or update migration artifacts.
4. Verify against a local database.
5. Update `docs/DATA_MODEL.md`.
6. Document production migration order if needed.

Production data changes require a backup or restore point.

## Release Changes

If the PR prepares a release:

- Update version when appropriate.
- Run the release gate.
- Write release notes in product language.
- Attach package artifacts only after checking for secrets.

See `docs/RELEASE_PROCESS.md`.
