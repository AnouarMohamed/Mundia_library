# Mundiapolis backend services

This Gradle multi-project build contains six Kotlin/Spring applications: the
browser-facing Web BFF plus Membership, Catalog, Circulation, Digital Content,
and Notification domain services. The existing Next.js application remains the
production entry point until each route passes its migration and cutover gates.

## Requirements

- JDK 25
- Docker, for PostgreSQL integration tests

## Build and test

```bash
cd services
./gradlew clean check
```

All five domain services generate jOOQ sources from their Flyway migrations before
compilation. Integration tests start isolated
PostgreSQL containers and verify that Flyway, the persistence adapters, HTTP
authorization, and published contracts work together.

The packaged application defaults `spring.flyway.enabled` to `false`.
`bootRun` explicitly opts into Flyway for the single-role local database; this
local convenience is not part of the container runtime contract.

## Run locally

Start PostgreSQL:

```bash
docker compose up -d circulation-db membership-db catalog-db notification-db digital-content-db
```

Run the service with a real development OIDC issuer and JWK set:

```bash
export AUTH_ISSUER_URI=https://identity.example.test/realms/mundia
export AUTH_JWK_SET_URI=https://identity.example.test/realms/mundia/protocol/openid-connect/certs
export AUTH_AUDIENCE=circulation-api
./gradlew :circulation-service:bootRun
```

Run Membership against its local database (Flyway is enabled only for
`bootRun`):

```bash
export AUTH_ISSUER_URI=https://identity.example.test/realms/mundia
export AUTH_JWK_SET_URI=https://identity.example.test/realms/mundia/protocol/openid-connect/certs
export AUTH_AUDIENCE=membership-api
./gradlew :membership-service:bootRun
```

Run Catalog against its local database:

```bash
export AUTH_ISSUER_URI=https://identity.example.test/realms/mundia
export AUTH_JWK_SET_URI=https://identity.example.test/realms/mundia/protocol/openid-connect/certs
export AUTH_AUDIENCE=catalog-api
./gradlew :catalog-service:bootRun
```

Run Notification against its local database:

```bash
export AUTH_ISSUER_URI=https://identity.example.test/realms/mundia
export AUTH_JWK_SET_URI=https://identity.example.test/realms/mundia/protocol/openid-connect/certs
export AUTH_AUDIENCE=notification-api
./gradlew :notification-service:bootRun
```

Run Digital Content against its local database:

```bash
export AUTH_ISSUER_URI=https://identity.example.test/realms/mundia
export AUTH_JWK_SET_URI=https://identity.example.test/realms/mundia/protocol/openid-connect/certs
export AUTH_AUDIENCE=digital-content-api
./gradlew :digital-content-service:bootRun
```

## Learning-resource catalog boundary

Catalog owns searchable learning-resource metadata; Digital Content remains
the sole authority for licence evidence and download authorization. Verified
legacy records are moved in deterministic batches of at most 250 through
`PUT /api/v1/catalog/learning-resource-imports/{importId}` using the dedicated
`catalog.learning-resource.import` machine scope. Import IDs are bound to both
the caller and a canonical manifest, and every completed batch has immutable
reconciliation evidence at the same URI via `GET`.

Application reads use `catalog.learning-resource.read` with indexed, stable
paging at `GET /api/v1/catalog/learning-resources`; detail and category routes
share that scope. The browser-facing equivalents now run through the Web BFF
with session-bound token exchange and strict downstream validation. These
responses include bounded licence and access-mode display metadata but never a
download target or authorization decision. Digital Content remains the
authority that rechecks rights and issues an audited download URL. The
legacy-to-Kotlin backfill is dry-run by default:

```bash
npm run backfill:learning-resources:kotlin -- --offset 0 --limit 250
```

Applying it additionally requires `CATALOG_SERVICE_URL` and a short-lived
`CATALOG_IMPORT_BEARER_TOKEN`. The script performs a read-after-write evidence
check for every batch and never logs the token. Keep the current production
read path until the full verified dataset is reconciled, then cut the frontend
over separately.

## Physical catalog migration boundary

Historical works, editions, contributors, and published reviews enter Catalog
through `PUT /api/v1/catalog/legacy-imports/{importId}` with the machine-only
`catalog.import` scope. Batches contain at most ten works and 1,000 reviews and
remain below the command-body limit. Each batch is atomic, actor-bound, and
exactly replayable; `GET` on the same URI returns its immutable receipt.
Historical imports do not emit live outbox events. Ratings are rebuilt from the
imported reviews so aggregate state cannot claim reviews that are absent.

The operator runner requires an isolated loopback PostgreSQL 18 restore and a
private source-URL file; it is dry-run-first and blocks the entire plan on any
preflight finding. Production cutover remains gated on reviewed reconciliation,
so the presence of this endpoint does not make Catalog the production writer.

## Digital content availability API

Digital Content owns downloadable-file rights, immutable digests, private
object manifests, malware-scan state, and publication state. Its first slice is
`GET /api/v1/digital-content/editions/{editionId}/availability`, requiring
`digital-content.availability.read`. It returns formats only when rights are
verified and current, territory is global, the object scan is clean, and the
asset is published. Missing or ineligible content returns a uniform empty
availability result; private object keys and provenance URLs never leave the
service. The immutable contract is available at
`GET /openapi/digital-content-v1.json`.

`POST /api/v1/digital-content/assets/{assetId}/authorizations` requires the
dedicated `digital-content.download.authorize` scope, locks and rechecks the
asset, records a privacy-minimized authorization audit, and returns a one-minute
CloudFront URL signed with RSA/SHA-256. The Kotlin BFF exposes the same
caller-bound operation with CSRF protection and validates the exact download
origin, path, query shape, asset identity, and expiry before returning it. The
service never proxies file bytes and neither signed URLs nor member identifiers
are persisted.

Rights-reviewed files that remain at an official third-party source use the
separate external-resource boundary. A machine caller with
`digital-content.external-resource.manage` registers an immutable manifest at
`PUT /api/v1/digital-content/external-resources/{resourceId}`. Registration is
bound to the caller and exact manifest digest, accepts only canonical CC BY
4.0, CC BY-SA 4.0, CC0 1.0, or Public Domain Mark 1.0 evidence, and rejects
credentials, non-HTTPS URLs, explicit ports, fragments, IP literals, local
names, traversal, and encoded path separators. Availability never exposes the
download URL. Authenticated downloads require a fresh, audited authorization
through the Kotlin BFF; neither service fetches or proxies third-party bytes.

The quarantined ingestion worker and S3 malware-scan event integration are
implemented. Safe promotion, a trusted territory signal, and SPA cutover to the
Kotlin authorization routes are the next slices. Keep signing
disabled until the private S3 origin, CloudFront trusted key group, and mounted
PKCS#8 key are provisioned.

The local database defaults are defined in `compose.yaml`. Production must
provide all database and identity settings through its secret/configuration
manager.

Production schema changes use the same immutable application image in a
one-shot mode that starts neither Spring nor HTTP:

```bash
APP_MIGRATION_ONLY=true \
DATABASE_MIGRATION_URL=jdbc:postgresql://database.example/circulation \
DATABASE_MIGRATION_USERNAME=circulation_migrator \
DATABASE_MIGRATION_PASSWORD='from-a-secret-manager' \
java -jar circulation-service.jar
```

All three dedicated migration variables are mandatory. Runtime
`DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` are never used as
fallbacks. A JDBC URL containing user or password parameters is rejected so a
driver cannot echo credentials. Invalid maintenance-mode values, conflicting
maintenance modes, validation failures, or migration failures exit non-zero
without printing connection exceptions.

Health probes:

- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

The immutable OpenAPI document is public at
`GET /openapi/circulation-v1.json`. All other non-health HTTP endpoints require
a valid bearer token, and domain endpoints enforce explicit OAuth scopes.

Authenticated operational reads also expose the effective policy revision at
`GET /api/v1/circulation/policy` and the privacy-preserving eligibility
projection at `GET /api/v1/circulation/members/{memberId}/eligibility`.
Self-service eligibility reads are bound to the token's `membership_id`; the
separate `circulation.eligibility.read.any` scope is required for staff reads.
Policy reads return an ETag. Administrators install immutable revisions with
`PUT /api/v1/circulation/policy`, an exact `If-Match` revision, an
`Idempotency-Key`, and `circulation.policy.manage`.

The circulation desk read model exposes bounded, status-filtered keyset queues
at `GET /api/v1/circulation/admin/loans` and
`GET /api/v1/circulation/admin/reservations`, plus live counts at
`GET /api/v1/circulation/admin/overview`. These endpoints require the separate
`circulation.admin.read` scope and use dedicated status/time indexes; command
execution continues through the existing idempotent aggregate workflows.

Every authenticated endpoint is also protected by a distributed,
principal-scoped fixed-window admission layer. Read, command, and sensitive
operations have independent budgets. Rejections return 429 with
`RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`, and `Retry-After`;
database admission failures return 503 and never fail open. Expired buckets are
removed in bounded scheduled batches. This service control complements, but
does not replace, the mandatory ingress WAF and network-level DDoS controls.

## Membership read API

The first authoritative Membership slice owns member profile, eligibility, and
identity-evidence metadata in its own PostgreSQL schema. Its immutable OpenAPI
document is public at `GET /openapi/membership-v1.json`; health probes use the
same public actuator paths as Circulation.

| Read | Endpoint | Required scope |
|---|---|---|
| Own profile | `GET /api/v1/members/{memberId}/profile` | `membership.profile.read` |
| Delegated profile | `GET /api/v1/members/{memberId}/profile` | `membership.profile.read.any` |
| Own eligibility | `GET /api/v1/members/{memberId}/eligibility` | `membership.eligibility.read` |
| Delegated eligibility | `GET /api/v1/members/{memberId}/eligibility` | `membership.eligibility.read.any` |
| Identity-evidence metadata | `GET /api/v1/members/{memberId}/identity-evidence` | `membership.identity-evidence.read` |
| Verified legacy-evidence transfer | `PUT|GET /api/v1/members/identity-evidence-transfers/{transferId}` | `membership.identity-evidence.transfer` |
| Administrative member queue | `GET /api/v1/members` | `membership.members.read` |

Self-service profile and eligibility reads require a canonical UUID
`membership_id` token claim equal to the path member. Eligibility is derived
from authoritative account status, active-loan limits, and overdue-fine state;
unknown or incomplete member state fails closed. Identity-evidence responses
contain only MIME type, size, checksum, and timestamps. The private object key
is database-only, and this API publishes neither durable nor signed URLs.

The schema is installed from
`membership-service/src/main/resources/db/migration`. Runtime Flyway remains
disabled by default, so production deployment must apply the reviewed migration
with its dedicated migration role before starting this service. Membership
status writes and crash-recoverable outbox publication are implemented. The
legacy import boundary is `PUT|GET
/api/v1/members/legacy-imports/{importId}` with `membership.import`; batches are
limited to 100 members and exactly replayable by the same actor and manifest.
The importer stores no password and quarantines only the SHA-256 digest of each
unverified legacy evidence reference. Historical import emits neither audit nor
live eligibility events. The separate evidence-transfer boundary binds that
digest to normalized private-object metadata, a scanner-attestation digest,
retention, and an immutable actor-bound receipt without exposing storage keys.
The external storage/scanner pipeline, Circulation projection bootstrap, shadow
comparison, and BFF cutover remain Phase 4 gates; the Next.js application is
still authoritative until those gates pass.

## Catalog read API

The first Catalog slice owns works, editions, contributors, media references,
and review storage in its PostgreSQL schema. Circulation remains authoritative
for physical copies: `totalCopies` and `availableCopies` are a disposable,
versioned Catalog projection and default to zero until Circulation publishes a
known state. The immutable contract is public at
`GET /openapi/catalog-v1.json`.

| Read | Endpoint | Required scope |
|---|---|---|
| Work metadata and ordered authors | `GET /api/v1/catalog/works/{workId}` | `catalog.read` |
| Edition metadata and projected availability | `GET /api/v1/catalog/editions/{editionId}` | `catalog.read` |
| Bounded edition metadata batch (maximum 50) | `GET /api/v1/catalog/editions?editionId=…` | `catalog.read` |
| Filtered, sorted catalog page | `GET /api/v1/catalog/search` | `catalog.search` |
| Active-edition genres | `GET /api/v1/catalog/genres` | `catalog.search` |
| Administrative edition search, including inactive records | `GET /api/v1/catalog/admin/editions` | `catalog.manage` |
| Privacy-safe published reviews | `GET /api/v1/catalog/works/{workId}/reviews` | `catalog.read` |
| Create a work and ordered authors | `POST /api/v1/catalog/works` | `catalog.manage` |
| Create an edition under a work | `POST /api/v1/catalog/works/{workId}/editions` | `catalog.manage` |
| Replace work metadata and ordered authors | `PUT /api/v1/catalog/works/{workId}` | `catalog.manage` |
| Replace edition metadata | `PUT /api/v1/catalog/editions/{editionId}` | `catalog.manage` |
| Activate or deactivate an edition | `POST /api/v1/catalog/editions/{editionId}/activation` | `catalog.manage` |
| Create an eligible member review | `POST /api/v1/catalog/works/{workId}/reviews` | `catalog.review.write` |
| Replace the authenticated member's review | `PUT /api/v1/catalog/reviews/{reviewId}` | `catalog.review.write` |
| Delete the authenticated member's review | `DELETE /api/v1/catalog/reviews/{reviewId}` | `catalog.review.write` |

Search filtering, totals, and pagination execute in PostgreSQL and use a stable
edition-ID tie breaker. Review reads exclude hidden content and never expose
member identifiers. Commands require an actor-bound `Idempotency-Key`; the
aggregate, exact replay snapshot, append-only audit, and versioned outbox event
commit atomically. Updates also require an exact aggregate-version ETag in
`If-Match`, reject stale versions, and return the new version in `ETag`.
Review commands bind ownership to the canonical `membership_id` JWT claim and
authorize creation only from the local, ordered returned-loan projection. A
member can publish at most one review per work. Review deletion physically
removes the text; audit and broker events intentionally retain neither member
identity nor review content. Catalog commands never accept copy counts or copy state. The schema is installed
from `catalog-service/src/main/resources/db/migration`; runtime Flyway remains
disabled by default. The Kotlin BFF and SPA now expose the Catalog administration
slice through a dedicated `catalog-admin-service` token-exchange registration.
Each browser request first revalidates the operator against Membership, and
mutations preserve exact ETags, actor-bound idempotency, and audit reasons.
Legacy backfill/reconciliation and production routing remain later Phase 4
gates, so Next.js is still the production authority.

When `CIRCULATION_CONSUMER_ENABLED=true`, Catalog consumes Circulation's shared
v1 event topic with an independent consumer group and broker credentials. It
ignores unrelated aggregates, strictly validates copy and loan event headers
and Protobuf state combinations, and applies each aggregate version to a local
projection and atomic inbox before manually committing the Kafka offset.
Edition totals are derived transactionally from those rows: withdrawn copies
are excluded from total inventory and only `AVAILABLE` copies are available.
The ordered loan projection retains the minimal member/edition relationship and
returned timestamp required to authorize review creation; it contains no member
profile data.
Exact redelivery replays safely; gaps, conflicting event IDs/versions,
future-skewed events, malformed contracts, and fatal broker failures stop the
consumer and make readiness unhealthy. The disposable edition-level cache is
cleared by migration V5 and must be rebuilt from retained version-zero copy
history before production routing.

Catalog outbox delivery is disabled by default and uses the same operational
contract as Circulation when enabled: aggregate-ordered `SKIP LOCKED` leases,
bounded exponential retries, poison-event blocking, synchronous Kafka
acknowledgements, retention cleanup, health/metrics, and the immutable
`mundia.catalog.v1.CatalogEvent` Protobuf envelope. Production must supply the
broker TLS/SASL settings and enable `OUTBOX_DELIVERY_ENABLED=true` only after
the topic, ACLs, and consumer contract are provisioned.

## Notification inbox API

The Notification service owns member preferences, in-app notifications, and
provider-delivery state. The first slice exposes only caller-bound inbox
operations; it never accepts a member identifier from the request.

| Operation | Endpoint | Required scope |
|---|---|---|
| List own inbox | `GET /api/v1/notifications/me` | `notification.inbox.read` |
| Mark own item read | `PATCH /api/v1/notifications/{notificationId}/read` | `notification.inbox.write` |

Inbox pages use a maximum of 100 items and an opaque canonical keyset cursor,
with deterministic timestamp and UUID ordering. Read-state updates include the
token's canonical `membership_id` in the SQL predicate, return the same 404 for
missing and cross-member records, and preserve the first read timestamp on
replay. Responses are non-cacheable. The immutable contract is public at
`GET /openapi/notification-v1.json`.

Kafka intent consumption, preference writes, provider delivery, suppression,
retry/DLQ controls, and caller-bound Kotlin BFF routing are implemented and
covered by their service suites. Catalog-triggered intents, production broker/
provider provisioning, and Kubernetes/Terraform values remain Phase 5 gates;
the service is not production-routed yet.

## Circulation command API

The first authoritative slice exposes:

| Command | Endpoint | Required scope |
|---|---|---|
| Request own loan | `POST /api/v1/circulation/loans` | `circulation.loan.request` |
| Request for another member | `POST /api/v1/circulation/loans` | `circulation.loan.request.on-behalf` |
| Approve and allocate a copy | `POST /api/v1/circulation/loans/{loanId}/approve` | `circulation.loan.approve` |
| Reject a pending request | `POST /api/v1/circulation/loans/{loanId}/reject` | `circulation.loan.reject` |
| Cancel an own pending request | `POST /api/v1/circulation/loans/{loanId}/cancel` | `circulation.loan.cancel` |
| Cancel for another member | `POST /api/v1/circulation/loans/{loanId}/cancel` | `circulation.loan.cancel.on-behalf` |
| Renew an eligible own loan | `POST /api/v1/circulation/loans/{loanId}/renew` | `circulation.loan.renew` |
| Renew for another member | `POST /api/v1/circulation/loans/{loanId}/renew` | `circulation.loan.renew.on-behalf` |
| Return a loan and release its copy | `POST /api/v1/circulation/loans/{loanId}/return` | `circulation.loan.return` |
| Place an own reservation | `POST /api/v1/circulation/reservations` | `circulation.reservation.place` |
| Place for another member | `POST /api/v1/circulation/reservations` | `circulation.reservation.place.on-behalf` |
| Cancel an own reservation | `POST /api/v1/circulation/reservations/{reservationId}/cancel` | `circulation.reservation.cancel` |
| Cancel for another member | `POST /api/v1/circulation/reservations/{reservationId}/cancel` | `circulation.reservation.cancel.on-behalf` |
| Fulfil a ready reservation | `POST /api/v1/circulation/reservations/{reservationId}/fulfill` | `circulation.reservation.fulfill` |
| Expire a due reservation | `POST /api/v1/circulation/reservations/{reservationId}/expire` | `circulation.reservation.expire` |
| Update circulation policy | `PUT /api/v1/circulation/policy` | `circulation.policy.manage` |
| Register a physical copy | `POST /api/v1/circulation/copies` | `circulation.inventory.register` |
| Change an eligible copy condition | `POST /api/v1/circulation/copies/{copyId}/condition` | `circulation.inventory.condition.update` |
| Relocate an available copy | `POST /api/v1/circulation/copies/{copyId}/relocations` | `circulation.inventory.relocate` |
| Assess a fine | `POST /api/v1/circulation/fines` | `circulation.fine.assess` |
| Record an external fine payment | `POST /api/v1/circulation/fines/{fineId}/payments` | `circulation.fine.payment.record` |
| Apply an audited fine adjustment | `POST /api/v1/circulation/fines/{fineId}/adjustments` | `circulation.fine.adjust` |

Every command requires an `Idempotency-Key` header containing 16–128 visible
ASCII characters. A successful replay returns the original response snapshot
and sets `Idempotency-Replayed: true`. Reusing the key for different input is a
conflict. Keys are namespaced by a SHA-256 fingerprint of the validated token
issuer, subject, authorized party, and client identifier; one actor can never
collide with or replay another actor's key.

Self-service request tokens must contain a canonical UUID `membership_id`
claim matching the request body. Missing, malformed, or cross-member claims
fail closed with HTTP 403 before any command transaction begins. Staff service
tokens may omit that claim only when granted the separate
`circulation.loan.request.on-behalf` scope.

Copy allocation is stable by barcode and identifier. Copy registration,
condition changes, and relocation use a separate actor-bound idempotency store
and an explicit state machine: staff cannot manually create `ON_LOAN` or
`RESERVED`, mutate loaned/reserved copies, resurrect a withdrawn copy, or
relocate anything except available inventory. Each command requires an audit
reason and writes an append-only inventory audit entry with the actor
fingerprint and before/after state. Loan/copy/fine state, exact replay result,
immutable fine-ledger entry when applicable, and the corresponding versioned
outbox events commit in the same PostgreSQL transaction. Copy registration
emits version zero before any immediate hold assignment; every later
availability transition (`AVAILABLE`, `RESERVED`, or `ON_LOAN`) emits
`circulation.copy.status-changed` at the exact copy aggregate version. Consumers
can rebuild inventory without inferring copy state from independently ordered
loan or reservation events.

Reservation placement is serialized per edition with a PostgreSQL advisory
transaction lock. Available copies become time-bounded holds immediately;
otherwise members join a deterministic FIFO queue. Returns, newly available
inventory, cancellations, and expiries promote the oldest waiter atomically.
Renewal is denied when another member is waiting. Fulfilment converts the held
copy and new active loan in one transaction. A bounded scheduler expires due
holds, and every transition emits the versioned Protobuf/outbox contract.

Loan requests, approvals, and renewals consult the local Membership-owned
eligibility projection while holding the same per-member transaction lock used
by the event consumer. Missing projection state fails closed with HTTP 503;
ineligible or suspended state returns HTTP 422. Rejection, cancellation, fine
settlement, and returns remain available. In particular, suspension never
prevents a member from returning a book.

## Membership eligibility consumer

When `MEMBERSHIP_CONSUMER_ENABLED=true`, Circulation consumes the minimal
`mundia.membership.v1.MemberEligibilityChanged` Protobuf contract from the
configured Membership topic. The consumer requires exactly one matching set of
content type, event, and schema headers; a canonical UUID key equal to the
member ID; a supported schema version; bounded payload size; and a valid state
and reason combination. Profile and identity-document data are not part of the
contract.

The projection update and immutable inbox row commit in one PostgreSQL
transaction. Kafka offsets are committed manually only after that transaction
succeeds. A crash between those operations is safe because redelivery resolves
through the inbox. Exact duplicates replay safely, approved older backfill
events are recorded as stale, and aggregate versions must otherwise be
contiguous from version zero. Transient broker poll and commit failures retry
with bounded backoff; a prolonged outage makes readiness unhealthy when the
last successful poll exceeds the configured silence limit.

Malformed, conflicting, future-skewed, or gapped events are never skipped or
sent through a best-effort path. The consumer stops, leaves the offset
uncommitted, increments a failure metric, and makes readiness unhealthy for
operator repair and controlled replay. Its group, topic, TLS/SASL credential,
and ACL are independent from the Circulation outbox producer.

The source topic must retain uncompacted, ordered aggregate history long enough
to rebuild the projection from version zero. Circulation deliberately cannot
skip an eligibility version gap because doing so could authorize borrowing from
an incomplete state. Production enablement therefore requires a tested
Membership snapshot/full-replay procedure and broker-retention evidence.

For cutover from a reconciled Membership snapshot, Circulation exposes
`PUT|GET /api/v1/circulation/membership-eligibility-bootstrap/{bootstrapId}`
only to a machine identity carrying `circulation.eligibility.bootstrap`. Each
PUT is bounded to 100 integrity-hashed records and either installs every exact
source version plus an immutable actor-bound receipt or rolls back completely.
It uses the consumer's per-member advisory lock, creates no synthetic event or
inbox record, rejects conflicting projection state, and makes the next real
Kafka event at version `N+1` valid after bootstrap version `N`. The endpoint is
the receiver, not a completed cutover. Membership provides the matching source
at `PUT|GET /api/v1/members/eligibility-snapshots/{snapshotId}` plus bounded
item pages at
`GET /api/v1/members/eligibility-snapshots/{snapshotId}/items`, isolated by
`membership.eligibility.snapshot`. It captures one table-consistent revision,
stores immutable privacy-minimal facts, and binds creation and reads to the
same actor. Production execution, soak-window shadow parity, and rollback
evidence remain required.

The operator is implemented in `eligibility-bootstrap-operator` and packaged as
a non-deployable Kotlin CLI. It verifies the entire Membership manifest before
any target mutation, derives stable batch IDs, checks every Circulation receipt,
then reads every target projection through a separate least-privilege token and
requires exact source parity. It writes `0600` evidence containing no bearer
token or member identifier. Run
it through `make eligibility-bootstrap`; see `docs/OPERATIONS.md` for the
dry-run/apply sequence. Its presence is not production cutover evidence.

## Scheduled loan reminders

When `LOAN_REMINDER_ENABLED=true`, Circulation scans bounded batches of active
loans for due-soon and overdue deadlines with `FOR UPDATE SKIP LOCKED` and writes
notification intents through the same transactional outbox used by hold
readiness. Each intent is paired in
the transaction with a durable receipt keyed by loan ID, observed due date, and
reminder type. Multiple service replicas can therefore race safely without
creating duplicate intents, while a changed due date after renewal remains a
new, eligible reminder cycle. Returned loans and stale candidate snapshots are
rechecked under a row lock and skipped.

The scheduler is disabled by default. Configure its lead time, interval, and
batch bounds with `LOAN_REMINDER_DUE_SOON_LEAD_TIME`,
`LOAN_REMINDER_POLL_INTERVAL`, and `LOAN_REMINDER_BATCH_SIZE`; enable it only
when the outbox producer and Notification consumer are also operational.

## Outbox delivery

When `OUTBOX_DELIVERY_ENABLED=true`, the service leases unpublished rows with
`FOR UPDATE SKIP LOCKED`, validates their JSON payload against the v1 event
contract, encodes Protobuf, and waits for a Kafka acknowledgement before
marking the row published. Leases expire after a process crash, retries are
bounded with backoff, irrecoverable contract violations are blocked for
operator action, and published rows are retained before cleanup. Producer
idempotence is enabled, but the database-to-broker boundary is intentionally
documented as **at least once**: every consumer must use `event_id` as its inbox
deduplication key.

The broker topics and ACLs must already exist; neither producer nor consumer is
authorized to administer topics. The platform supplies private TLS/SASL
connectivity and approved schema subjects. Broker selection, schema-registry
deployment, replay drills, and production retention/partition sizing remain
release evidence, not application defaults.

## Container build

Use `services` as the build context:

```bash
docker build -f circulation-service/Dockerfile -t mundia/circulation-service:dev .
docker build -f membership-service/Dockerfile -t mundia/membership-service:dev .
docker build -f catalog-service/Dockerfile -t mundia/catalog-service:dev .
docker build -f notification-service/Dockerfile -t mundia/notification-service:dev .
docker build -f digital-content-service/Dockerfile -t mundia/digital-content-service:dev .
docker build -f web-bff/Dockerfile -t mundia/web-bff:dev .
```
