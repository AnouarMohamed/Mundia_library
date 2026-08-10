# Mundiapolis backend services

This is an additive Gradle multi-project build for the strangler migration. The
existing Next.js application remains the production entry point until a service
passes its migration and cutover gates.

## Requirements

- JDK 25
- Docker, for PostgreSQL integration tests

## Build and test

```bash
cd services
./gradlew clean check
```

`circulation-service` generates jOOQ sources from its Flyway migrations before
compilation. Integration tests start an isolated PostgreSQL container and verify
that Flyway and the persistence adapter work together.

The packaged application defaults `spring.flyway.enabled` to `false`.
`bootRun` explicitly opts into Flyway for the single-role local database; this
local convenience is not part of the container runtime contract.

## Run locally

Start PostgreSQL:

```bash
docker compose up -d circulation-db
```

Run the service with a real development OIDC issuer and JWK set:

```bash
export AUTH_ISSUER_URI=https://identity.example.test/realms/mundia
export AUTH_JWK_SET_URI=https://identity.example.test/realms/mundia/protocol/openid-connect/certs
export AUTH_AUDIENCE=circulation-api
./gradlew :circulation-service:bootRun
```

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

Every authenticated endpoint is also protected by a distributed,
principal-scoped fixed-window admission layer. Read, command, and sensitive
operations have independent budgets. Rejections return 429 with
`RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`, and `Retry-After`;
database admission failures return 503 and never fail open. Expired buckets are
removed in bounded scheduled batches. This service control complements, but
does not replace, the mandatory ingress WAF and network-level DDoS controls.

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
fingerprint and before/after state. Loan/copy/fine state, exact replay result, immutable
fine-ledger entry
when applicable, and one versioned outbox event commit in the same PostgreSQL
transaction.

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
```
