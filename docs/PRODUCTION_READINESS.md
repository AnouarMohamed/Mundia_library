# Production Readiness

This checklist defines what "ready to ship" means for Mundiapolis Library.

## Readiness Summary

The product is production-capable when:

- Core student workflows work reliably.
- Core admin workflows work reliably.
- Production environment variables are complete.
- Database schema is applied and backed up.
- Auth and admin access are verified.
- API and page performance are acceptable.
- Security automation is passing.
- Release process and rollback path are known.
- Operations runbook exists and is usable.

## Product Workflow Checklist

### Student And Faculty

- [ ] User can sign up.
- [ ] User can sign in.
- [ ] Pending account state is understandable.
- [ ] Approved user can browse catalog.
- [ ] Search returns relevant results.
- [ ] Genre, availability, rating, sort, and pagination work.
- [ ] Book detail page shows availability and metadata clearly.
- [ ] User can request a borrow when copies are available.
- [ ] User cannot request unavailable books in an invalid state.
- [ ] User can view active borrows.
- [ ] User can view borrowing history.
- [ ] User can request renewal for eligible active loans.
- [ ] User can write a review only when eligible.
- [ ] Empty states suggest a useful next action.

### Admin And Library Staff

- [ ] Admin can sign in.
- [ ] Non-admin cannot access `/admin`.
- [ ] Admin dashboard loads.
- [ ] Admin can approve and reject account requests.
- [ ] Admin can approve borrow requests.
- [ ] Admin can process returns.
- [ ] Book copy counts remain accurate after approval and return.
- [ ] Admin can create, edit, and deactivate catalog records.
- [ ] Admin can review renewal requests.
- [ ] Admin can configure fines.
- [ ] Admin can run overdue fine updates.
- [ ] Admin can send due and overdue reminders when email is configured.
- [ ] Admin exports work and are handled securely.
- [ ] Admin table actions are clear and not visually destructive by default.

## Technical Checklist

### Environment

- [ ] `DATABASE_URL` points to production database.
- [ ] `NEXTAUTH_SECRET` or `AUTH_SECRET` is strong and stable.
- [ ] `NEXTAUTH_URL` matches production URL.
- [ ] Public API endpoint variables match production URL.
- [ ] ImageKit production keys are configured.
- [ ] Upstash Redis is configured.
- [ ] QStash is configured if workflows are enabled.
- [ ] Email sender is verified.
- [ ] `ENABLE_WORKFLOWS` is intentionally set.
- [ ] No production secrets are stored in Git.

### Database

- [ ] Schema migration has been reviewed.
- [ ] Backup or restore point exists.
- [ ] Migration has been applied.
- [ ] Seed test accounts are not present unless intentionally allowed.
- [ ] Admin account exists.
- [ ] Hot query indexes exist for catalog and borrow routes.
- [ ] Copy count invariants are verified.

### Build And Runtime

- [ ] `npm ci` succeeds.
- [ ] `npm run lint` succeeds.
- [ ] `npm run typecheck` succeeds.
- [ ] `npm run test` succeeds.
- [ ] `npm run build` succeeds.
- [ ] Docker build succeeds if Docker deployment is in scope.
- [ ] Production deployment serves the expected commit.
- [ ] App logs are accessible.

### API Performance

- [ ] `/api/books?limit=12&sort=rating&page=1` passes benchmark threshold.
- [ ] `/api/books/genres` passes benchmark threshold.
- [ ] `/api/books/recommendations?limit=10` passes benchmark threshold.
- [ ] `/api/books/[id]` passes benchmark threshold.
- [ ] Query plan report is acceptable for hot routes.
- [ ] Cache invalidation works after catalog and borrow mutations.

### Security

- [ ] CodeQL passes.
- [ ] Secret scan passes.
- [ ] Dependency review passes.
- [ ] npm audit has no untriaged critical production advisories.
- [ ] Container scan has no untriaged critical findings.
- [ ] Admin APIs use server-side guards.
- [ ] User-owned APIs prevent cross-user access.
- [ ] Rate limiting is active in production.
- [ ] Release archives do not contain `.env`, `.env.*`, `.vercel`, or private keys.

## Manual Smoke Test

Run after every production deployment.

### Public Read Smoke

```bash
BASE_URL=https://mundia-library.vercel.app
curl -fsS "$BASE_URL/api/books?limit=1"
curl -fsS "$BASE_URL/api/books/genres"
curl -fsS "$BASE_URL/api/books/recommendations?limit=3"
```

### Browser Smoke

Student:

1. Sign in as approved student.
2. Open homepage.
3. Search catalog.
4. Open book detail page.
5. Open profile.
6. Confirm borrowing tabs and empty states render.

Admin:

1. Sign in as admin.
2. Open `/admin`.
3. Open users.
4. Open books.
5. Open borrow requests.
6. Open renewal requests.
7. Open automation.
8. Confirm no unauthorized redirects.

## Release Gate

A release should not ship if any of these are true:

- Production build fails.
- Auth cannot be verified.
- Admin access cannot be verified.
- Catalog list API returns 500.
- Database migration is unreviewed.
- Any known data-loss risk lacks an explicit mitigation.
- Security scan reports an untriaged high or critical issue.
- Release package contains secrets.

## Known Hardening Work

Track these items as production maturity work:

- Replace salted SHA-256 password hashing with Argon2id or bcrypt.
- Add end-to-end tests for auth, borrowing, returns, and admin approval.
- Consolidate email sending ownership between direct provider service and QStash workflow email.
- Add explicit health endpoint for deployment and uptime monitoring.
- Add structured logging with request IDs.
- Add audit logging coverage for all privileged admin mutations.
- Add database migration policy with reviewed SQL artifacts.
- Add visual regression coverage for main pages.
- Add stricter package artifact generation script.
- Confirm backup and restore drill with actual production provider.

## Definition Of Done For Production Features

A production feature is done when:

- The user workflow is complete.
- Error and empty states are handled.
- Permissions are enforced server-side.
- Data invariants are preserved.
- Tests cover the risky logic.
- Docs describe setup and operational impact.
- CI passes.
- Release notes explain the change in product language.

## Ownership

Every production area should have a named maintainer before institutional rollout:

| Area | Owner needed |
| --- | --- |
| Deployment and environment | Yes |
| Database and backups | Yes |
| Security advisories | Yes |
| Library operations process | Yes |
| Email sender and templates | Yes |
| Admin account lifecycle | Yes |
| Release management | Yes |
| Incident response | Yes |
