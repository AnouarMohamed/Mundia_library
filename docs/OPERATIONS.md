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
- Check Circulation pending-loan, active-loan, overdue, waiting-reservation, and
  ready-reservation counts for unexplained changes.

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

### Catalog Administration Rejects a Valid Operator

Check the operator's current Membership status and role first; cached browser
claims do not grant administration. Then verify the BFF's
`catalog-admin-service` token exchange has the `catalog-api` audience and only
`catalog.read,catalog.manage`. A `409` means the submitted ETag is stale or the
idempotency key was reused with different input: reload the record and retry
with the same key only when the command body is unchanged.

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

### Notification Email Is Automatically Suppressed

The Kotlin Notification service suppresses email after a signed SES complaint
or permanent bounce. In-app delivery remains enabled.

Check:

- `notification_email_suppression` for the member-scoped reason and source
  correlation identifiers.
- `notification_email_feedback_receipt` for the immutable signed-event receipt.
- `notification_delivery.provider_outcome` and the SES configuration-set event
  destination.

Do not remove suppression because a user asks through an unauthenticated
channel, and do not delete provider feedback receipts. Confirm the Membership
address was corrected and verify the requester through the approved support
process. A trusted operator holding only the required
`notification.suppression.write` scope may then call:

```bash
curl --fail-with-body \
  -X POST "$NOTIFICATION_SERVICE_URL/api/v1/notifications/email-suppressions/$MEMBER_ID/removal" \
  -H "Authorization: Bearer $OPERATOR_ACCESS_TOKEN" \
  -H "Idempotency-Key: $REQUEST_ID" \
  -H "Content-Type: application/json" \
  --data '{"justification":"Verified address correction under support ticket SEC-2041"}'
```

Use a new UUID for `REQUEST_ID`; an exact retry returns the original result,
while reuse for different request content returns `409`. Confirm the row in
`notification_email_suppression_removal_audit`. The operation enables only
future eligible email intents and never requeues previously suppressed work.

### Notification Email Is Dead-Lettered

Investigate and correct the recorded `last_error_code` and provider incident
before replay. Never replay an ambiguous delivery that may have reached the
provider, and never bulk-requeue the table. For one confirmed-safe delivery, a
trusted operator holding `notification.dead-letter.replay` may call:

```bash
curl --fail-with-body \
  -X POST "$NOTIFICATION_SERVICE_URL/api/v1/notifications/email-deliveries/$DELIVERY_ID/dead-letter-replay" \
  -H "Authorization: Bearer $OPERATOR_ACCESS_TOKEN" \
  -H "Idempotency-Key: $REQUEST_ID" \
  -H "Content-Type: application/json" \
  --data '{"justification":"Provider configuration repaired under incident INC-2042"}'
```

Confirm the immutable snapshot in
`notification_email_dead_letter_replay_audit`, the incremented delivery
`replay_count`, and a single subsequent worker claim. Exact retries with the
same request return the original result. Reuse for different content, active
recipient suppression, and non-dead-lettered states return `409`.

### Image Uploads Fail

Check:

```bash
curl -i "$BASE_URL/api/auth/imagekit"
```

The expected response is `410 Gone`; direct client signing is intentionally
retired. Uploads must use same-origin `POST /api/uploads`.

Likely causes of server-mediated upload failures:

- Missing ImageKit keys.
- Invalid ImageKit URL endpoint.
- Rate limit failure.
- ImageKit service issue.
- Production video scanning is not yet available.

Actions:

- Confirm service credentials.
- Confirm the caller is authorized for the requested upload intent.
- Confirm the request meets byte, media, and image-dimension limits.
- Check provider dashboard.

## Data Repair Guidelines

### Legacy physical catalog backfill

The Catalog migration importer moves works, editions, contributors, and
published legacy reviews as one bounded historical unit. It derives each work's
rating from those reviews, writes no live outbox event, and records an immutable,
actor-bound receipt for exact replay and reconciliation.

Run it only against an isolated PostgreSQL 18 restore on literal loopback. Put
the source URL in a current-user-owned `0600` file; ambient `DATABASE_URL` is
deliberately ignored. Dry-run first:

```bash
npm run backfill:catalog:kotlin -- \
  --source-url-file /secure/legacy-catalog-url \
  --expect-database legacy_library \
  --evidence-file /secure/catalog-plan.json
```

Resolve every reported finding before applying. For apply, obtain a temporary
machine token whose only application scope is `catalog.import`, set
`CATALOG_SERVICE_URL` and `CATALOG_IMPORT_BEARER_TOKEN` in the operator process,
then add `--apply`. Store the new evidence in a current-user-owned `0700`
directory under a fresh path: artifact creation is exclusive and will not
overwrite prior evidence. Dry-run evidence contains counts and findings, never
review text or member IDs. A successful run requires
every PUT receipt to match the subsequent GET receipt exactly. Re-running the
same batches is safe; changing the actor or payload for an existing import ID is
rejected. Revoke the temporary client/token after sign-off.

The planner blocks empty snapshots, invalid UUIDs and metadata, duplicate ISBNs
or reviews, private media URLs, oversized records, and mismatched review counts
before any target batch is sent. The 128 KiB service boundary is respected with
a 120 KiB client-side ceiling.

### Legacy membership backfill

The Membership importer moves profile, role, account status, and an eligibility
snapshot in deterministic batches of at most 100. It explicitly selects only
the required legacy columns, so password hashes and login history never enter
the migration process. Legacy university-card locators are reduced to SHA-256
digests and quarantined as `UNVERIFIED_LEGACY_REFERENCE`; they are not copied
into verified evidence storage.

Run against an isolated PostgreSQL 18 restore on literal loopback. Dry-run
first, using fresh paths in a current-user-owned `0700` evidence directory:

```bash
npm run backfill:membership:kotlin -- \
  --source-url-file /secure/legacy-membership-url \
  --expect-database legacy_library \
  --evidence-file /secure/membership-plan.json
```

The dry-run evidence contains only counts, digests, import IDs, and field names;
it contains no member IDs, email addresses, document references, or credentials.
Resolve every finding before apply. The planner blocks empty data, invalid or
duplicate identity fields, duplicate evidence references, missing approved
administrators, invalid eligibility counts, and oversized requests.

For apply, set `MEMBERSHIP_SERVICE_URL` and a short-lived
`MEMBERSHIP_IMPORT_BEARER_TOKEN` with only `membership.import`, then append
`--apply` and write to a new evidence path. Every PUT is followed by a GET of
the immutable receipt; any mismatch fails the run. The legacy schema has no fine
payment ledger, so any positive historical loan fine is conservatively imported
as unpaid and must be reviewed before cutover. Revoke the token after sign-off.

This backfill deliberately emits no live eligibility event. Before Membership
becomes authoritative, separately bootstrap the Circulation eligibility
projection, compare exact member/version/count evidence, run shadow parity, and
retain a tested rollback path. Do not infer that a successful import is a
production cutover.

The Circulation-side atomic boundary is available at
`PUT /api/v1/circulation/membership-eligibility-bootstrap/{bootstrapId}`. Each
bounded request carries the source revision and at most 100 snapshots with the
exact Membership aggregate version, occurrence time, and canonical item digest.
The service takes the same per-member advisory lock as the Kafka consumer,
reconciles existing rows exactly, and commits the whole batch plus its immutable
actor-bound receipt or nothing. It deliberately writes no consumer-inbox row
and fabricates no domain event. Repeating the exact PUT with the same actor is a
safe replay; changing the actor or manifest for a used bootstrap ID is a
conflict. Read the receipt back with GET and compare it exactly before signing
off.

Do not invoke this endpoint ad hoc in production. Create the source snapshot
with `PUT /api/v1/members/eligibility-snapshots/{snapshotId}`, then read its
actor-bound receipt and bounded keyset pages from the corresponding GET routes.
Membership takes a short share lock while persisting the immutable snapshot, so
pages cannot mix aggregate versions. The export intentionally contains no
name, email, identity evidence, or raw loan/fine counts.

Use the Kotlin operator to verify every item digest and source manifest,
convert pages into deterministic bounded Circulation batches, and record
secret-free evidence. Tokens are read only from the environment. Prepare a new
owner-only evidence directory, then run dry-run first:

```bash
install -d -m 0700 /secure/mundia-cutover
export MEMBERSHIP_SERVICE_URL=https://membership.internal.example
export MEMBERSHIP_SNAPSHOT_BEARER_TOKEN='<short-lived-token>'
snapshot_id="$(uuidgen)"
make eligibility-bootstrap ELIGIBILITY_BOOTSTRAP_ARGS="--snapshot-id ${snapshot_id} --evidence-file /secure/mundia-cutover/eligibility-dry-run.json"
```

Resolve and review every finding. For apply, use a fresh evidence path, set the
private Circulation origin and its separately audience-bound token, and append
`--apply`. Provide a second short-lived token with only
`circulation.eligibility.read.any` as `CIRCULATION_PARITY_BEARER_TOKEN`; the
operator reads every resulting projection and requires exact status, reason,
source version, timestamp, count, and revision parity before it writes evidence.
The tool refuses existing evidence files, redirects, non-HTTPS
origins, oversized responses, invalid pages/digests/manifests, and mismatched
PUT/GET receipts. `--allow-loopback-http` exists only for isolated local drills.
If an apply run is interrupted between batches, rerun it with the same snapshot
ID: deterministic batch IDs safely reconcile completed receipts before the
operator continues. Use a new evidence path because evidence is immutable.

During the soak window, run target-read-only parity checks with fresh snapshot
IDs and evidence paths. Each run persists an immutable Membership source
snapshot, but `--verify-parity` never constructs a Circulation bootstrap client
and therefore neither reads nor requires the bootstrap credential. It performs
bounded concurrent target reads (default 8; configurable from 1 through 16):

```bash
snapshot_id="$(uuidgen)"
make eligibility-bootstrap ELIGIBILITY_BOOTSTRAP_ARGS="--snapshot-id ${snapshot_id} --verify-parity --parity-concurrency 8 --evidence-file /secure/mundia-cutover/parity-${snapshot_id}.json"
```

Run this from the controlled operations environment at the approved interval,
alert on any non-zero exit, and retain every immutable evidence file. A single
successful parity run is not a soak window and does not authorize cutover.

At production cutover, freeze Membership
eligibility writes or pause the consumer at a recorded offset, create one
snapshot, apply and verify every receipt, resume consumption at the recorded
boundary, and require the first later event for each changed member to be
version `N+1` after snapshot version `N`. The operator's immediate exact parity
check does not replace soak-window shadow parity or the rollback drill. Endpoint
presence is not evidence these steps occurred.

### Legacy identity-evidence transfer

Never copy a legacy card reference directly into Membership. Resolve it only
inside the isolated transfer worker, enforce the pre-buffer byte limit, decode
and normalize supported media, strip metadata, run the approved malware scanner
fail-closed, and write the result to the private identity bucket under the exact
opaque key `identity-evidence/{memberId}/{evidenceId}`. Public access, listing,
and cross-purpose encryption keys remain prohibited.

After the object and scanner evidence are independently durable, use a
short-lived token carrying only `membership.identity-evidence.transfer` to PUT
the transfer manifest to
`/api/v1/members/identity-evidence-transfers/{transferId}`. Read the same URI
back and require an exact receipt match. A transfer succeeds only when its
source-reference digest matches that member's quarantine record. Reuse by a
different actor or with changed input fails. Receipts reveal neither the raw
legacy locator, private object key, nor scanner attestation. Revoke the token
after reconciliation.

This API records verified transfer evidence; it does not fetch, decode, scan,
or upload files itself. Production execution remains blocked until the private
bucket, KMS key, isolated scanner, lifecycle deletion, access audit, and restore
deletion behavior have been provisioned and tested.

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

In the Kotlin administration slice, use the Circulation desk route and confirm
the edition title, member reference, request time, and intended operation in the
confirmation panel. The confirmation intent retains one idempotency key across
retries until it succeeds or the operator cancels it.

### Return Processing

1. Find the active borrow record.
2. Mark returned.
3. Confirm return date and fine amount.
4. Confirm available copy count changed.

### Reservation Processing

1. Process only `READY` reservations for fulfilment or expiry.
2. Confirm the edition and member before submitting the operation.
3. Verify the reservation leaves the ready queue and the overview count changes.
4. If the command result is uncertain, retry with the original idempotency key;
   do not create a second command manually.

If all Circulation admin routes return 403, verify the operator remains
`APPROVED` with `ADMIN` or `SUPER_ADMIN` role in Membership before changing
OAuth scopes. If reads return 502/503, inspect Membership, the BFF token
exchange, and Circulation readiness in that order. Never bypass the fresh role
check to restore the desk.

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
