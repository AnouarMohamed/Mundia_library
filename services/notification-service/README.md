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
  terminal dead-letter state, stable delivery correlation IDs, call timeouts, backlog
  metrics, and lease-fenced acknowledgements;
- a bounded OAuth 2.0 client-credentials recipient resolver that requests only
  `membership.profile.read.any`, caches short-lived service tokens, verifies the
  returned member identity, and keeps addresses out of broker events;
- an AWS SES v2 adapter with bounded SDK timeouts, explicit error
  classification, configuration-set event routing, delivery correlation headers
  and tags, and the AWS default credential chain for EKS Pod Identity or IRSA;
- a horizontally scalable SES feedback consumer using SNS-to-SQS fanout,
  mandatory SNS Signature Version 2 verification, SSRF-safe bounded certificate
  retrieval, transactional replay receipts, and monotonic delivery outcomes;
- privacy-preserving automatic email suppression for complaints and permanent
  bounces, with transactional evidence, queued-work cancellation, claimed-work
  fencing, and mandatory in-app delivery retained;
- a least-privilege `notification.suppression.write` removal operation with a
  mandatory operator justification, idempotency key, member-scoped race lock,
  and durable before-state audit; removal affects only future email work;
- exact issuer, audience, JWT type, and OAuth scope enforcement;
- OpenAPI/controller parity tests and real PostgreSQL integration tests;
- OCI image, dependency updates, CI build, security scan, and GHCR publication.

The worker is disabled by default and intentionally has no cross-service
database access. Enabling it requires the Membership service URL, audience,
token endpoint, client ID and client secret, plus a verified SES sender and
configuration set. Placeholder SES values fail startup validation. Production
uses pod workload identity; never inject static AWS access keys into the pod.

SES v2 `SendEmail` has no idempotency token. The durable delivery UUID is
attached as `X-Mundia-Delivery-Id` and the `delivery_id` SES message tag for
correlation, but an ambiguous network failure can still result in a duplicate
send. Signed feedback reconciliation makes provider outcomes observable and
idempotent, while send initiation itself remains honestly at least once.
The service is not production-routed yet. Scheduled due/overdue and legitimate
catalog-triggered intent producers, dead-letter replay tooling, BFF routing,
and Kubernetes values remain Phase 5 work.
