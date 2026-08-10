# Circulation migration toolkit

This isolated Node.js 24 package converts a frozen legacy circulation snapshot
into copy-level loans, preserved renewal counts, and immutable fine-ledger
state. It writes `circulation_copy`, `circulation_loan`, `circulation_fine`,
and `circulation_fine_ledger_entry`. It is designed for a strangler cutover,
not for continuous synchronization.

The default path is read-only. `snapshot`, `plan`, and `reconcile` cannot write
to a database. `apply` is the only write path and requires multiple independent
operator acknowledgements.

## Safety contract

- Only literal `127.0.0.1` or `[::1]` PostgreSQL URLs are accepted.
  `localhost`, DNS names, URL query parameters, and fragments are rejected.
  The validated URL is decomposed into explicit driver fields rather than
  reparsed by `pg`. Do not use SSH tunnels or local proxies to reach a remote
  database; software cannot distinguish those from a genuinely local server.
- No ambient `DATABASE_URL` is read. Database URLs are read only from explicit,
  operator-owned mode-`0600` secret files so credentials do not appear in npm
  command echo, shell history, or the process argument list. Expected database
  names remain explicit command arguments.
- Snapshot and reconciliation transactions are `SERIALIZABLE`, `READ ONLY`,
  and `DEFERRABLE`, with bounded statement, lock, connection, and idle
  transaction timeouts.
- Snapshot schema v2 records the exact PostgreSQL 18 legacy column/enum
  contract and isolation policy. Earlier v1 snapshots are intentionally
  rejected and must be recaptured.
- The apply process receives a plan and a target URL only. It has no source
  connection and cannot write the legacy database.
- Apply requires PostgreSQL 18 and exactly successful circulation Flyway
  versions 1 through 11. It validates the target column signature, validated
  constraints, and enabled immutable/consistency triggers before touching data.
- Apply uses one serializable transaction, a PostgreSQL advisory lock, bounded
  timeouts, insert-only statements, and exact post-insert reconciliation across
  copies, loans, fines, and ledger entries.
- Existing rows are accepted only when they exactly match the plan. Conflicting
  or unexpected rows for a migrated edition roll the transaction back. No
  existing copy or loan is updated or deleted.
- A plan with any error finding is not applicable. Plan files are
  checksum-verified and apply requires the independently recorded SHA-256 again
  on the command line. A SHA-256 checksum is not an authenticated signature:
  artifact/hash authentication is an external release gate.
- Artifacts are created with mode `0600` and are never overwritten. Reads
  reject symlinks, multiple hard links, foreign ownership, and group/other
  permissions; writes reject symlinked or group/world-writable parent paths.
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

The deterministic `test/fixtures/valid-snapshot.json` is the green-path
fixture. Database integration is enabled only against a newly migrated,
disposable PostgreSQL 18 database:

```bash
CIRCULATION_MIGRATION_TEST_URL=postgresql://...@127.0.0.1:PORT/FRESH_DB \
CIRCULATION_MIGRATION_TEST_DATABASE=FRESH_DB \
npm run test:integration
```

CI must provision that fresh database, run circulation Flyway 1 through 11, and
set both test-only variables; it must not point this test at a shared database.
`test:integration` fails rather than skips when either variable is absent.
The ordinary `npm test` reports the database scenario as skipped when the
variables are absent while still running every unit/security test.

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
| `renewal_count`                      | exact `circulation_loan.renewal_count` and loan version  |
| nonzero `fine_amount`                | MAD minor units + one immutable assessment ledger entry  |

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

Renewal counts must be integers from 0 through 100 and are never truncated.
Legacy fine balances use explicit cutover semantics:

- currency is MAD and conversion to minor units is exact (no floating point or
  rounding);
- `NULL` and exact zero mean no outstanding fine;
- a nonzero value is the current outstanding balance and becomes an initial
  `ASSESSMENT`;
- the legacy row's explicit `updated_at`, which must be after the due timestamp,
  is the assessment time;
- a fixed migration-principal fingerprint is used instead of pretending a
  legacy human actor was authenticated;
- no historical outbox event is emitted.

The finance owner must approve these decisions through the required plan flags.
Invalid precision/range, an ineligible loan, a missing assessment timestamp, or
an unsupported renewal count blocks migration. There is no archive-and-drop
override for supported operational data.

Legacy actor fields, notes, and reminder metadata also remain checksum-bound in
`legacyLoanArchive`, but evidence-only archival is not treated as a migration.
Any non-null unsupported metadata blocks the plan until an authenticated,
queryable audit/records destination is implemented and approved. Treat every
plan as sensitive institutional data.

## Known seeded-data cutover blocker

The redacted, checksum-bound
`evidence/seeded-inventory-cutover-blocker.json` records five seeded editions
whose unavailable counters cannot be explained by active loans. It contains
edition UUIDs and inventory counts only—no member or loan data. This evidence
is intentionally `BLOCKED` and must stay that way until a librarian chooses,
per edition, between:

1. correcting `available_copies` to proven physical inventory; or
2. identifying each unavailable physical copy and assigning an authoritative
   target status such as `LOST` or `DAMAGED`.

The first choice can be followed by a new snapshot. The second requires a
separately reviewed and tested ordinal-to-status mapping extension to this
tool—the current version intentionally has no status override. The migration
must never normalize counters or invent copy identities merely to turn the
plan green.

## Cutover workflow

The `*-url-file` inputs must be regular, single-link, current-operator-owned
files with no group/other permissions. Each contains one line such as
`postgresql://ROLE:PERCENT_ENCODED_PASSWORD@127.0.0.1:PORT/DATABASE`. Provision
them through the deployment secret mechanism; do not construct them in a
logged command.

### 1. Rehearse without writes

Create a least-privilege legacy database role with `SELECT` only. Capture from
a local, restored backup:

```bash
npm run cli -- snapshot \
  --source-url-file /run/secrets/legacy-reader-url \
  --expect-database legacy_library \
  --out artifacts/rehearsal.snapshot.json
```

Create the dry-run plan:

```bash
npm run cli -- plan \
  --snapshot artifacts/rehearsal.snapshot.json \
  --branch-id 99999999-9999-4999-8999-999999999999 \
  --identifier-policy preserve-legacy-uuids \
  --fine-currency MAD \
  --legacy-null-fine-policy no-fine \
  --legacy-fine-balance-policy current-outstanding-as-initial-assessment \
  --fine-assessment-time-policy legacy-updated-at \
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
  --fine-currency MAD \
  --legacy-null-fine-policy no-fine \
  --legacy-fine-balance-policy current-outstanding-as-initial-assessment \
  --fine-assessment-time-policy legacy-updated-at \
  --out artifacts/rehearsal.approved.plan.json
```

### 2. Prepare the target

Apply the circulation service's reviewed Flyway migrations 1 through 11 to a
fresh local PostgreSQL 18 target. Stop the circulation service writer. The
target database role should have only:

- `CONNECT` on the expected database;
- `USAGE` on schema `public`;
- `SELECT` on `flyway_schema_history`;
- catalog visibility needed for the schema/constraint/trigger checks;
- `SELECT` and `INSERT` on `circulation_copy`, `circulation_loan`,
  `circulation_fine`, and `circulation_fine_ledger_entry`;
- permission to call `pg_advisory_lock` and `pg_advisory_unlock`.

Do not grant `UPDATE`, `DELETE`, DDL, or access to legacy tables.

### 3. Freeze and make the final plan

At the approved cutover:

1. stop every legacy circulation writer and background job;
2. prove legacy writes are frozen;
3. capture a new final snapshot;
4. create and peer-review a new plan;
5. independently record the plan SHA-256;
6. authenticate the reviewed plan hash through the change-control/signing
   system outside this package;
7. keep both the legacy and target writers stopped.

Never run change-data capture or dual writes for circulation. If the cutover is
abandoned, route traffic back to the still-frozen legacy system before
re-enabling its writer.

### 4. Apply once, safely replayable

The only database-writing invocation is:

```bash
CIRCULATION_MIGRATION_WRITE_ACK=TARGET_ONLY_NO_DUAL_WRITE \
npm run cli -- apply \
  --plan artifacts/final.plan.json \
  --target-url-file /run/secrets/circulation-migrator-url \
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
  --target-url-file /run/secrets/circulation-reader-url \
  --expect-database circulation \
  --out artifacts/final.independent-reconciliation.json
```

Require `status: "MATCH"`, zero mismatches, a checksum-verified report bound to
the reviewed plan, external artifact/hash authentication, and human sign-off
before routing any traffic. Also reconcile catalog edition IDs and
membership IDs outside this package; the circulation database intentionally
has no cross-service foreign keys.

Verify either artifact offline (no database connection is opened):

```bash
npm run cli -- verify --plan artifacts/final.plan.json
npm run cli -- verify \
  --report artifacts/final.independent-reconciliation.json \
  --plan artifacts/final.plan.json
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
- `LOAN_RENEWAL_COUNT_INVALID`
- `LOAN_FINE_AMOUNT_INVALID`
- `FINE_ASSESSMENT_TIMESTAMP_INVALID`
- `LEGACY_METADATA_DESTINATION_UNRESOLVED`

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
