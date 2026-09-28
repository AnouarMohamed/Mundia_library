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
- The BFF obtains a dedicated client-credentials token scoped to
  `catalog.search`; no browser token is forwarded. The authorization server
  must issue the Catalog service audience (`catalog-api` by default). This is
  suitable only for non-user-specific catalog reads.
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
- The browser contract is versioned at
  `services/web-bff/src/main/resources/static/openapi/web-bff-v1.json`.

The deployment is not production-routed. Institutional IdP client
registration with token exchange, Redis failover testing, circulation admin
routes, Kubernetes values, edge routing, and the React shell remain required
before a UI slice moves.
