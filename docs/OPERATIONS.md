# Operations Runbook

This runbook is for maintainers handling production or production-like environments.

## Operational Priorities

1. Protect patron data and account security.
2. Keep catalog discovery and book availability accurate.
3. Keep borrow, return, renewal, and fine workflows consistent.
4. Prefer clear manual intervention over silent automated damage.
5. Document every production incident and follow-up action.

## Routine Checks

Daily:

- Confirm production app loads.
- Confirm `/api/books?limit=1` returns data.
- Check error logs for new 5xx patterns.
- Check admin dashboard loads for an admin account.
- Check pending account and borrow request queues.

Weekly:

- Review dependency alerts and security workflows.
- Review failed email sends and workflow errors.
- Review overdue records and fine update output.
- Confirm database backups or provider restore points.
- Run or inspect API benchmark and load-test artifacts.

Before term start or high-traffic periods:

- Run `npm run benchmark:api` against a production-like deployment.
- Confirm database indexes for hot routes.
- Confirm Upstash Redis quota.
- Confirm email provider quota and sender reputation.
- Confirm admin staffing for account approvals and circulation queues.

## Incident Severity

| Severity | Examples | Response |
| --- | --- | --- |
| S1 | Auth unavailable, data leak, app down, database unavailable | Stop risky automation, notify owner, preserve logs, restore service first. |
| S2 | Borrow approvals broken, admin cannot process returns, widespread API 500s | Triage within the hour, identify release or service dependency, patch or rollback. |
| S3 | Email reminders failing, one admin page broken, slow catalog route | Triage same day, mitigate manually if needed. |
| S4 | Cosmetic issue, docs issue, isolated stale cache | Fix in normal PR flow. |

## First Response Checklist

1. Identify affected user path: student, admin, API, auth, automation, upload, email, database.
2. Check most recent deployment and commit.
3. Check logs for stack traces and repeated messages.
4. Check provider dashboards: Vercel, database, Upstash, ImageKit, Brevo, Resend.
5. If automation may be causing damage, set `ENABLE_WORKFLOWS=false` and redeploy.
6. If a release caused the issue and rollback is safe, rollback code.
7. If schema is involved, avoid destructive changes until data impact is understood.
8. Record timeline, root cause, mitigation, and follow-up issue.

## Common Failures

### App Down Or Returning 500s

Check:

```bash
curl -i "$BASE_URL/api/books?limit=1"
```

Likely causes:

- Missing or invalid `DATABASE_URL`.
- Database provider outage.
- Invalid production environment variable.
- Runtime exception in a route handler or server component.
- Recent schema drift.

Actions:

- Inspect deployment logs.
- Confirm `DATABASE_URL` target.
- Confirm the latest migration was applied.
- Redeploy last known good version if code-only rollback is safe.

### Auth Redirect Loop

Symptoms:

- User signs in and returns to sign-in page.
- Admin is redirected unexpectedly.
- Session exists but role is missing or stale.

Check:

- `NEXTAUTH_URL` matches public URL.
- `NEXTAUTH_SECRET` or `AUTH_SECRET` is stable across deployments.
- Browser cookies are scoped to the correct domain.
- User row has expected `role` and `status`.

Actions:

- Fix env mismatch and redeploy.
- Ask affected user to clear cookies only after confirming server config.
- For role changes, have user sign out and sign in again.

### Admin Cannot Access `/admin`

Check:

- User has `role = ADMIN`.
- Session token includes role claim.
- `middleware.ts` can read the auth secret.
- `app/admin/layout.tsx` fallback DB lookup succeeds.

Actions:

- Verify user in database.
- Re-sign in after role promotion.
- Check database connectivity.

### Catalog Is Slow

Hot routes:

- `GET /api/books`
- `GET /api/books/genres`
- `GET /api/books/recommendations`
- `GET /api/books/[id]`

Actions:

```bash
npm run explain:hot-queries
npm run benchmark:api
```

Check:

- Missing indexes.
- Query filters causing broad scans.
- Redis unavailable.
- Cache invalidation loop.
- Too high `limit` values from clients. The API clamps limits, but logs can still show abuse patterns.

### Borrow Counts Are Wrong

Symptoms:

- Book shows available copies but admin records disagree.
- Borrow approval decremented copies incorrectly.
- Return did not increment copies.

Actions:

- Inspect `books.availableCopies`.
- Inspect active `borrow_records` for the book.
- Run existing verification scripts where appropriate:

```bash
npm run verify-borrow
npm run fix-borrow-sync
```

Use repair scripts only after reading what they do and confirming a backup exists.

### Email Reminders Fail

Check:

- `BREVO_API_KEY`
- `BREVO_SENDER_EMAIL`
- `RESEND_TOKEN`
- Provider dashboard logs.
- Sender domain verification.
- Quotas and suppression lists.

Actions:

- Keep overdue and due reminders manual until provider is restored.
- Do not repeatedly trigger bulk reminders during provider incidents.

### QStash Or Workflow Fails

Check:

- `ENABLE_WORKFLOWS`
- `QSTASH_URL`
- `QSTASH_TOKEN`
- Upstash dashboard
- Route logs for workflow endpoint errors

Actions:

- Disable workflows if retries are amplifying the issue.
- Process urgent circulation tasks manually in admin.
- Re-enable after one successful test.

### Image Uploads Fail

Check:

```bash
curl -i "$BASE_URL/api/auth/imagekit"
```

Likely causes:

- Missing ImageKit keys.
- Invalid ImageKit URL endpoint.
- Rate limit failure.
- ImageKit service issue.

Actions:

- Confirm service credentials.
- Confirm upload client receives auth parameters.
- Check provider dashboard.

## Data Repair Guidelines

Do:

- Take a backup first.
- Prefer read-only diagnostics before mutation.
- Run repair scripts locally against a copy when possible.
- Log exact commands and timestamps.
- Verify before and after counts.

Do not:

- Run destructive SQL from memory.
- Use repair scripts without reading them.
- Change production data during an active incident unless it mitigates user impact.
- Reset production database state to solve a code problem.

## Backup And Restore Expectations

Production database provider should support:

- Automated backups.
- Point-in-time recovery or recent restore points.
- Manual snapshot before schema changes.

Before any schema migration:

1. Confirm the latest backup timestamp.
2. Confirm restore process is known.
3. Confirm migration is additive or reversible.

## Admin Operating Procedures

### Account Approval

1. Open admin account requests.
2. Review submitted identity information.
3. Approve only valid university users.
4. Reject invalid requests with a clear reason.
5. Avoid promoting users to admin unless there is an explicit operational need.

### Borrow Request Approval

1. Confirm the requested book has available copies.
2. Approve the request.
3. Confirm due date is set.
4. Confirm available copy count changed.

### Return Processing

1. Find the active borrow record.
2. Mark returned.
3. Confirm return date and fine amount.
4. Confirm available copy count changed.

### Overdue Fine Updates

1. Review fine configuration.
2. Run overdue update from admin automation.
3. Inspect result summary.
4. Do not run repeatedly unless the action is confirmed idempotent for the target date.

### Exports

Exports may include sensitive operational data. Store them carefully and remove local copies when finished.

## Logs To Preserve For Incidents

- Deployment ID and commit SHA.
- App logs around first failure.
- Database error messages.
- Provider request IDs from Upstash, ImageKit, Brevo, Resend, or Vercel.
- User-facing route and timestamp.
- Exact admin action attempted.

## Post-Incident Review Template

```md
## Summary

## Impact

## Timeline

## Root Cause

## Resolution

## What Worked

## What Failed

## Follow-Up Tasks
```
