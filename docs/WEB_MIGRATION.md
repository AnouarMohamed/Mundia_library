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
in step 1 and starts step 2 with the first typed Catalog read slice:

- `GET /api/v1/catalog/search` requires a valid browser session, validates and
  bounds its inputs, enforces connect/read/response-size limits, and maps
  downstream failures to stable problem codes.
- The BFF obtains a dedicated client-credentials token scoped to
  `catalog.search`; no browser token is forwarded. The authorization server
  must issue the Catalog service audience (`catalog-api` by default). This is
  suitable only for non-user-specific catalog reads.
- The browser contract is versioned at
  `services/web-bff/src/main/resources/static/openapi/web-bff-v1.json`.

The deployment is not production-routed. Institutional IdP client
registration, Redis failover testing, delegated end-user token design for
profile/circulation/admin operations, Kubernetes values, edge routing, and the
React shell remain required before a UI slice moves.
