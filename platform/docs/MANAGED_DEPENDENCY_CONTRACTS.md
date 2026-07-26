# Managed dependency contracts

These are interfaces, not provisioned resources. Product and platform teams can
review service boundaries before a cloud/database module is selected. The
Terraform dependency contract carries resource ARNs and non-secret endpoints;
External Secrets carries runtime credentials.

## PostgreSQL

Each service owns a database (or isolated database/schema during transition), a
runtime role, and a separate migration-owner role. Runtime roles cannot perform
DDL or access another service's tables. Connections require TLS with hostname
and CA verification.

The circulation runtime secret exposes:

| Property | Consumer variable | Rule |
| --- | --- | --- |
| `jdbc_url` | `DATABASE_URL` | private `jdbc:postgresql` endpoint with TLS parameters |
| `username` | `DATABASE_USERNAME` | circulation runtime role only |
| `password` | `DATABASE_PASSWORD` | rotated, never shared with migration owner |

Production selection must include Multi-AZ failover, automatic backups,
point-in-time recovery, encryption with an approved KMS key, audit/error logs,
minor-version maintenance, and a successful timed restore. Pool ceilings are a
global budget: `maximum replicas × per-pod pool + migrations/operations` must
remain below the database connection limit with failover headroom.

## Kafka-compatible event backbone

The Kafka secret contains private TLS bootstrap servers and approved
authentication material. Topics, schemas, partitions, retention, quotas, and
ACLs are versioned separately. Producers use the transactional-outbox path;
the broker is never a substitute for the service database transaction.

Each service identity receives only its topic prefixes and consumer groups.
Production requires TLS hostname/CA validation, broker authentication, schema
compatibility policy, replay/quarantine procedures, per-tenant abuse limits,
and capacity evidence at the 5× burst target.

## Redis

Redis is ephemeral. The secret contains a private TLS URL and credentials. It
may hold caches, rate-limit state, and short-lived coordination data, but never
the sole copy of library, membership, loan, entitlement, or audit state.

Services must remain correct when Redis is unavailable or completely flushed.
Production selection needs Multi-AZ failover, explicit eviction policy, memory
alarms, key namespacing, TLS verification, and a tested cache-loss exercise.

## Object storage

Terraform carries only the bucket ARN. Workload identity policies grant
operation- and prefix-specific access. Buckets are private, KMS encrypted,
versioned, public-access-blocked, access-logged, and lifecycle-managed.

Untrusted uploads enter a quarantine prefix, are size/type bounded and
re-encoded where appropriate, and cannot be served until malware/content
inspection succeeds. Signed access is short-lived and bound to an authorized
object key.

## OpenSearch

The contract carries a private HTTPS endpoint without credentials. OpenSearch
is a derived discovery index, never the system of record. Writes arrive from
versioned events, and a complete rebuild from authoritative sources must be
documented and tested.

Production selection requires workload-scoped authorization, encryption,
Multi-AZ capacity, index templates/mappings, lifecycle policy, query limits,
snapshot/rebuild evidence, and protection against user-controlled expensive
queries.

## OIDC

The issuer and JWKS URLs are exact HTTPS endpoints. Client material, if
confidential, lives in the OIDC secret. Every service validates issuer,
audience, signature algorithm, time claims, and operation-specific scopes.
Administrative access requires institutional MFA and short sessions.

The identity team must approve claims, account lifecycle, key rotation,
revocation expectations, service-to-service credentials, and a fully audited
break-glass path. Network reachability to JWKS does not weaken token
validation.

## Telemetry

The endpoint is a private or controlled-egress HTTPS OTLP endpoint. The
telemetry secret contains the complete authorization header value. The OTel
gateway removes authorization, cookie, end-user, and SQL-statement attributes
before export. Security still needs to approve attribute allowlists, sampling,
retention, residency, and backend access.

