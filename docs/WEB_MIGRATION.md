# Web migration: Next.js to React SPA and Kotlin BFF

The accepted target is documented in
[ADR 0003](./adr/0003-static-spa-and-kotlin-bff.md). Until the final cutover,
Next.js is a legacy migration shell rather than the destination architecture.

## Runtime target

```text
Browser -> CDN/WAF -> static React SPA
                   -> Kotlin web-bff -> Membership
                                     -> Catalog
                                     -> Circulation
```

The edge presents one HTTPS origin. Domain services have no public ingress.
The BFF owns browser authentication and sessions but owns no domain database.

## Migration sequence

1. Establish the BFF identity boundary: OIDC code flow with PKCE, Redis-backed
   sessions, secure cookies, CSRF, safe session projection, probes, metrics,
   container publication, and Kubernetes deployment.
2. Add typed, bounded BFF clients and routes for Membership, Catalog, and
   Circulation. Each route has an explicit timeout, response-size limit,
   authorization policy, failure mapping, and OpenAPI contract.
3. Create the Vite/React shell with generated API types, accessible routing,
   CSP-compatible assets, error boundaries, and browser E2E coverage.
4. Migrate vertical slices in this order: sign-in/session, catalog browsing,
   member profile, circulation self-service, and administration.
5. Backfill and reconcile service-owned data before switching each slice.
   Route one writer only; do not dual-write.
6. Prove parity, rollback, session revocation, accessibility, browser support,
   load, and failure behavior in staging.
7. Delete Next.js route handlers, server actions, direct database access, and
   credentials authentication. Remove its image only after rollback retention
   expires.

## Current checkpoint

The `services/web-bff` deployable completes the code-level identity boundary
in step 1 and now has typed Catalog, Membership, and initial Circulation slices
in step 2:

- `GET /api/v1/catalog/search` requires a valid browser session, validates and
  bounds its inputs, enforces connect/read/response-size limits, and maps
  downstream failures to stable problem codes.
- Catalog search plus learning-resource search, detail, and category reads use
  RFC 8693 token exchange from the signed-in user's server-side token. The
  short-lived downstream token is restricted to the `catalog-api` audience and
  `catalog.search,catalog.read,catalog.learning-resource.read`; neither token reaches the
  browser. Responses are bounded, strictly validated, and `no-store`.
- Bounded catalog-edition batch reads resolve up to 50 unique edition UUIDs in
  request order with one database query and one browser-to-BFF request. This
  prevents circulation history from creating per-item Catalog fan-out while
  preserving Catalog ownership of titles, covers, and edition metadata.
- `GET /api/v1/membership/profile` performs RFC 8693 token exchange from the
  signed-in user's server-side OIDC access token into a short-lived token with
  only the `membership-api` audience and `membership.profile.read` scope. The
  Membership service binds the request to the canonical UUID
  `membership_id` claim through its fixed `/api/v1/members/me/profile` route;
  caller-controlled member identifiers and machine credentials are not used.
- OAuth and Membership calls have bounded connect/read timeouts and response
  sizes, no redirects, strict endpoint validation, stable non-sensitive error
  responses, and delegated-client eviction on downstream authorization
  rejection. Tokens remain server-side and profile responses are `no-store`.
- `GET /api/v1/circulation/eligibility`, caller-bound keyset-paginated loan and
  reservation history, loan request/cancellation/renewal, and reservation
  placement/cancellation use a separate delegated token limited to the
  `circulation-api` audience and their eight required self-service scopes.
  Circulation's fixed `/me` routes derive the member from a canonical token
  claim. The browser supplies only an edition, loan, or reservation UUID, CSRF
  token, and bounded actor-scoped idempotency key; it cannot select a member
  identity.
- The static Vite/React shell now provides session bootstrap, accessible
  learning-resource search/detail, read-only member-profile, and caller-bound
  circulation eligibility/history routes,
  generated OpenAPI types, strict runtime response checks, error boundaries,
  responsive loading/empty/error states, and CSRF-protected external-download
  authorization. Learning resources and member profile have independent,
  disabled-by-default `VITE_ENABLE_LEARNING_RESOURCES`,
  `VITE_ENABLE_MEMBER_PROFILE`, and `VITE_ENABLE_CIRCULATION_SELF_SERVICE`
  release switches; the current Vercel routes remain authoritative until
  reconciliation and edge cutover. Circulation history enriches each bounded
  keyset page through one Catalog batch rather than an N+1 request pattern.
- The browser contract is versioned at
  `services/web-bff/src/main/resources/static/openapi/web-bff-v1.json`.
- Dev, staging, and production GitOps overlays now deploy the Web BFF with
  digest-pinned images, External Secrets, autoscaling, disruption budgets,
  TLS-only origin ingress, bounded ingress requests, and allowlisted service
  egress. The shell-free service images use Kubernetes' native pre-stop sleep
  lifecycle action rather than assuming `/bin/sh` exists.
- `platform/edge/same-origin-routing.yaml` is the provider-neutral edge
  contract: private object storage serves the SPA, BFF/auth paths are
  uncacheable, `www` permanently redirects to the apex, and the BFF origin must
  reject direct internet traffic. It is not permission to provision AWS and
  must be translated into reviewed CDN/WAF infrastructure before cutover.
- CI now packages the already-tested SPA as an immutable artifact with its
  source revision, compiled feature flags, aggregate SHA-256, per-file hashes,
  sizes, and cache directives. The dormant `aws-web-edge` Terraform module
  translates the routing contract into private S3 Origin Access Control and
  CloudFront behavior definitions, but no environment calls the module yet.
  Applying it would create billable AWS resources and remains an explicit
  approval gate.

The deployment is not production-routed. Institutional IdP client
registration with token exchange, Redis failover testing, circulation admin
routes, a selected and applied CDN/WAF implementation, service TLS endpoints,
and production browser parity remain required before a UI slice moves. A Kotlin Notification
service boundary now exists for caller-bound inbox reads and mark-read state,
with production-shaped Kafka ingestion and a lease/retry/dead-letter email
worker core. Its service-authenticated, audience-scoped Membership recipient
resolver is implemented; the provider-specific sender, signed callbacks, and
browser routing are still Phase 5 work.
