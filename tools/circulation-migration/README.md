# Circulation migration toolkit

This isolated Node.js 24 package converts a frozen legacy circulation snapshot
into copy-level `circulation_copy` and `circulation_loan` rows. It is designed
for a strangler cutover, not for continuous synchronization.

The default path is read-only. `snapshot`, `plan`, and `reconcile` cannot write
to a database. `apply` is the only write path and requires multiple independent
operator acknowledgements.

## Safety contract

- Only direct `localhost`, `127.0.0.1`, or `::1` PostgreSQL URLs are accepted.
  URL query parameters are rejected because they can override connection
  routing. Do not use SSH tunnels or local proxies to reach a remote database.
- No ambient `DATABASE_URL` is read. Every database and expected database name
  must be passed explicitly.
- The snapshot transaction is `REPEATABLE READ`, `READ ONLY`, and `DEFERRABLE`.
- The apply process receives a plan and a target URL only. It has no source
  connection and cannot write the legacy database.
- Apply uses one serializable transaction, a PostgreSQL advisory lock, bounded
  timeouts, insert-only statements, and exact post-insert reconciliation.
- Existing rows are accepted only when they exactly match the plan. Conflicting
  or unexpected rows for a migrated edition roll the transaction back. No
  existing copy or loan is updated or deleted.
- A plan with any error finding is not applicable. Plan files are integrity
  checked and apply requires the expected SHA-256 again on the command line.
- Artifacts are created with mode `0600` and are never overwritten.
- Apply reserves its evidence path before connecting. A crash leaves a
  machine-readable `PENDING` artifact that instructs operators to reconcile;
  a caught failure is finalized as `UNKNOWN_REQUIRES_RECONCILIATION`.
- Historical rows do not emit outbox events. Replaying old domain events would
  incorrectly trigger current notifications and downstream workflows.

This tooling reduces migration risk; it cannot prove that an external source,
catalog, membership system, or operator decision is correct. Production
cutover still requires peer review, backups, restore rehearsal, and an approved
rollback decision.

## Install and verify

Use the repository production baseline, Node.js 24.17 or newer:

```bash
cd tools/circulation-migration
npm ci
npm run check
npm audit --audit-level=low
```

The package has its own lockfile and does not modify the application package.

## Data mapping

| Legacy field/state                   | Circulation target                                       |
| ------------------------------------ | -------------------------------------------------------- |
| `books.id`                           | `edition_id`                                             |
| `users.id`                           | `member_id`                                              |
| `books.total_copies`                 | deterministic physical copy rows                         |
| `PENDING`                            | `REQUESTED`, with no copy                                |
| `BORROWED`                           | `ACTIVE`, with an assigned `ON_LOAN` copy                |
| `RETURNED`                           | `RETURNED`, with a timeline-feasible copy                |
| `borrow_date`                        | `requested_at` and, for physical loans, `checked_out_at` |
| date-only `due_date` / `return_date` | end of that UTC day                                      |

Legacy UUID preservation must be confirmed against the catalog and membership
backfills. The plan requires
`--identifier-policy preserve-legacy-uuids` so this cross-service assumption is
never implicit.

Copy IDs are UUIDv5 values derived from the recorded namespace, branch, edition,
and one-based copy ordinal. Barcodes use the same inputs. Re-running a plan
produces the same copy IDs and barcodes.

The legacy schema has no physical copy identifier. When an edition has multiple
copies, the planner therefore blocks by default. After a librarian approves the
policy, `--allow-synthetic-historical-copy-assignment` enables deterministic
interval allocation:

1. loans are ordered by checkout timestamp and loan UUID;
2. each loan gets the lowest ordinal copy whose prior loan has ended;
3. current active loans hold a copy indefinitely;
4. an over-capacity timeline is an error, never silently overbooked.

Fine balances and renewal counts are operational data absent from the current
target schema, so they also block by default. Only an explicit
`--allow-archived-unsupported-operational-fields` decision permits migration.
All non-target legacy fields and a checksum of every source loan remain in the
plan's `legacyLoanArchive`. Treat that plan as sensitive institutional data and
retain it under the records policy.

## Cutover workflow

### 1. Rehearse without writes

Create a least-privilege legacy database role with `SELECT` only. Capture from
a local, restored backup:

```bash
npm run cli -- snapshot \
  --source-url postgresql://legacy_reader:PASS@127.0.0.1:55439/legacy_library \
  --expect-database legacy_library \
  --out artifacts/rehearsal.snapshot.json
```

Create the dry-run plan:

```bash
npm run cli -- plan \
  --snapshot artifacts/rehearsal.snapshot.json \
  --branch-id 99999999-9999-4999-8999-999999999999 \
  --identifier-policy preserve-legacy-uuids \
  --out artifacts/rehearsal.plan.json
```

The command exits `2` and writes complete machine-readable evidence when the
plan is blocked. Review every finding. Do not use the policy overrides merely
to make the status green.

If approved decisions are needed, create a new artifact rather than replacing
the first one:

```bash
npm run cli -- plan \
  --snapshot artifacts/rehearsal.snapshot.json \
  --branch-id 99999999-9999-4999-8999-999999999999 \
  --identifier-policy preserve-legacy-uuids \
  --allow-synthetic-historical-copy-assignment \
  --allow-archived-unsupported-operational-fields \
  --out artifacts/rehearsal.approved.plan.json
```

### 2. Prepare the target

Apply the circulation service's reviewed Flyway migrations to a local
PostgreSQL 18 target. Stop the circulation service writer. The target database
role should have only:

- `CONNECT` on the expected database;
- `USAGE` on schema `public`;
- `SELECT` and `INSERT` on `circulation_copy` and `circulation_loan`;
- permission to call `pg_advisory_xact_lock`.

Do not grant `UPDATE`, `DELETE`, DDL, or access to legacy tables.

### 3. Freeze and make the final plan

At the approved cutover:

1. stop every legacy circulation writer and background job;
2. prove legacy writes are frozen;
3. capture a new final snapshot;
4. create and peer-review a new plan;
5. independently record the plan SHA-256;
6. keep both the legacy and target writers stopped.

Never run change-data capture or dual writes for circulation. If the cutover is
abandoned, route traffic back to the still-frozen legacy system before
re-enabling its writer.

### 4. Apply once, safely replayable

The only database-writing invocation is:

```bash
CIRCULATION_MIGRATION_WRITE_ACK=TARGET_ONLY_NO_DUAL_WRITE \
npm run cli -- apply \
  --plan artifacts/final.plan.json \
  --target-url postgresql://circulation_migrator:PASS@127.0.0.1:55440/circulation \
  --expect-database circulation \
  --expect-plan-sha256 COPY_THE_REVIEWED_64_CHARACTER_HASH \
  --cutover-state legacy-writes-frozen \
  --target-writer-state stopped \
  --allow-target-writes \
  --evidence artifacts/final.application-reconciliation.json
```

A process or database failure rolls back the transaction. Re-running the same
integrity-checked plan is safe: exact rows are no-ops and any divergence blocks
commit. If the database committed but writing the local evidence file failed,
run read-only reconciliation to regenerate evidence.

### 5. Reconcile independently

Use a read-only target role:

```bash
npm run cli -- reconcile \
  --plan artifacts/final.plan.json \
  --target-url postgresql://circulation_reader:PASS@127.0.0.1:55440/circulation \
  --expect-database circulation \
  --out artifacts/final.independent-reconciliation.json
```

Require `status: "MATCH"`, zero mismatches, a verified report SHA-256, and human
sign-off before routing any traffic. Also reconcile catalog edition IDs and
membership IDs outside this package; the circulation database intentionally
has no cross-service foreign keys.

Verify either artifact offline (no database connection is opened):

```bash
npm run cli -- verify --plan artifacts/final.plan.json
npm run cli -- verify --report artifacts/final.independent-reconciliation.json
```

## Blocking findings

Important error codes include:

- `IDENTIFIER_MAPPING_NOT_ACKNOWLEDGED`
- `BOOK_INVENTORY_INVALID`
- `AVAILABLE_COUNTER_DRIFT`
- `OPEN_LOAN_DUPLICATE`
- `HISTORICAL_COPY_IDENTITY_AMBIGUOUS`
- `COPY_TIMELINE_OVER_CAPACITY`
- `LOAN_WITHOUT_PHYSICAL_COPY`
- `LOAN_DUE_DATE_INVALID`
- `RETURN_TIMESTAMP_INVALID`
- `OPERATIONAL_FIELDS_NOT_IN_TARGET`

The plan records findings, target rows, per-edition counts, source row hashes,
mapping policy, and deterministic reconciliation checks in one reviewable JSON
artifact.

## Rollback

Before traffic moves, rollback is simply: keep the target writer stopped,
retain the evidence, and re-enable the frozen legacy writer.

After traffic moves, never delete or overwrite target loans to force a rollback.
Stop new writes, preserve both databases, reconcile the target delta, and run an
approved forward migration or compensating business workflow. This package
deliberately contains no delete, truncate, or reverse-write command.
