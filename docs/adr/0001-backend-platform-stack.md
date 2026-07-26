# ADR 0001: Backend platform stack

- Status: Accepted
- Date: 2026-07-26

## Context

The existing Next.js application mixes presentation, transport, business rules,
and persistence. The product requires independently deployable services with
strong circulation invariants, explicit security boundaries, and sustained
operational support.

## Decision

New backend services use:

- Kotlin on JDK 25 with Spring Boot 4.1.
- Spring MVC with virtual threads for synchronous request handling.
- PostgreSQL as the authoritative store for each service.
- Flyway for reviewed, forward-compatible migrations.
- jOOQ-generated schema types for explicit SQL and compile-time drift detection.
- OAuth 2.0 resource-server validation against a managed OIDC provider.
- OpenTelemetry/Micrometer-compatible instrumentation and Actuator probes.
- Testcontainers for PostgreSQL integration tests.
- OCI containers deployed to managed Kubernetes; stateful infrastructure remains
  in managed services outside the cluster.

The existing Next.js application is retained as the web frontend/BFF during the
strangler migration. It will stop accessing domain databases as APIs move to
their owning services.

## Consequences

- The team operates two languages during migration.
- Database schema and generated jOOQ sources become one reviewed contract.
- Services gain independent scaling and release boundaries.
- Kubernetes and event infrastructure require a platform ownership function.
- A rewrite-in-place or synchronized big-bang cutover is explicitly rejected.
