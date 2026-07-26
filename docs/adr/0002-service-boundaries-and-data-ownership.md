# ADR 0002: Service boundaries and data ownership

- Status: Accepted
- Date: 2026-07-26

## Decision

The initial service boundaries are:

- Membership: patron profile, approval, eligibility, and suspension.
- Catalog: works, editions, contributors, subjects, media, and reviews.
- Circulation: physical copies, requests, reservations, loans, renewals,
  circulation policy, and fine ledger.
- Notifications: preferences, templates, in-app notifications, and deliveries.
- Discovery: disposable search, availability, and recommendation projections.

Authentication credentials, MFA, and sessions belong to the managed identity
provider. Audit records are exported to a dedicated append-only archive.

Circulation and inventory remain one consistency boundary. Approval atomically
assigns a physical copy and opens a loan; return atomically closes the loan and
releases the same copy.

Each service owns its database, credentials, migrations, and writes. Services
must not perform cross-service joins or use another service's database. Shared
facts arrive through versioned events or an owning service's API.

## Integration rules

- REST/OpenAPI is used for bounded synchronous commands and queries.
- Versioned events use a transactional outbox and at-least-once delivery.
- Consumers are idempotent and preserve ordering by aggregate identifier.
- Distributed transactions and dual writes are prohibited.
- Read models may be eventually consistent and rebuildable.
- Only one system may write an aggregate during a migration cutover.

## Consequences

- Catalog or membership outages do not need to block circulation when its local
  eligibility and metadata projections are current.
- Cross-service foreign keys are replaced by opaque identifiers and
  reconciliation.
- Circulation cutover requires a controlled single-writer transition rather than
  per-user traffic splitting.
