# Notification service

Kotlin/Spring resource server that owns notification preferences, in-app inbox
items, and provider-delivery state. Its browser-facing operations are
caller-bound through the canonical `membership_id` token claim.

Implemented in this slice:

- PostgreSQL/Flyway ownership and generated jOOQ types;
- bounded, deterministic keyset pages for a member's inbox;
- read/unread filtering and idempotent mark-read behavior;
- strict Protobuf notification-intent ingestion from Kafka with manual,
  post-transaction offset acknowledgement;
- atomic inbox, channel-delivery, and durable receipt creation with exact
  replay deduplication and conflicting-replay rejection;
- bounded broker fetches, `read_committed` isolation, TLS/SASL validation,
  consumer health, and Prometheus processing/replay/failure metrics;
- exact issuer, audience, JWT type, and OAuth scope enforcement;
- OpenAPI/controller parity tests and real PostgreSQL integration tests;
- OCI image, dependency updates, CI build, security scan, and GHCR publication.

The service is not production-routed yet. Circulation/catalog intent producers,
preference commands, provider workers, retry/DLQ behavior, suppression,
callbacks, BFF routing, and Kubernetes values remain Phase 5 work.
