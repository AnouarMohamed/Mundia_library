# Notification service

Kotlin/Spring resource server that owns notification preferences, in-app inbox
items, and provider-delivery state. Its browser-facing operations are
caller-bound through the canonical `membership_id` token claim.

Implemented in this slice:

- PostgreSQL/Flyway ownership and generated jOOQ types;
- bounded, deterministic keyset pages for a member's inbox;
- read/unread filtering and idempotent mark-read behavior;
- caller-bound, strongly versioned email/category preferences with mandatory
  in-app delivery, exact-retry convergence, and auditable suppression;
- strict Protobuf notification-intent ingestion from Kafka with manual,
  post-transaction offset acknowledgement;
- atomic inbox, channel-delivery, and durable receipt creation with exact
  replay deduplication and conflicting-replay rejection;
- bounded broker fetches, `read_committed` isolation, TLS/SASL validation,
  consumer health, and Prometheus processing/replay/failure metrics;
- a provider-neutral email worker core with PostgreSQL `SKIP LOCKED` leases,
  bounded exponential backoff and deterministic jitter, attempt budgets,
  terminal dead-letter state, provider idempotency keys, call timeouts, backlog
  metrics, and lease-fenced acknowledgements;
- a bounded OAuth 2.0 client-credentials recipient resolver that requests only
  `membership.profile.read.any`, caches short-lived service tokens, verifies the
  returned member identity, and keeps addresses out of broker events;
- exact issuer, audience, JWT type, and OAuth scope enforcement;
- OpenAPI/controller parity tests and real PostgreSQL integration tests;
- OCI image, dependency updates, CI build, security scan, and GHCR publication.

The worker is disabled by default and intentionally has no cross-service
database access. Enabling it requires the Membership service URL, audience,
token endpoint, client ID, and client secret, and still fails startup until an
email-provider adapter is installed.
The service is not production-routed yet. Scheduled due/overdue and legitimate
catalog-triggered intent producers, the provider adapter, signed provider
callbacks, dead-letter replay tooling, BFF routing, and Kubernetes values remain
Phase 5 work.
