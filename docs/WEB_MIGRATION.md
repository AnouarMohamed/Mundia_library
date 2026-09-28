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

The `services/web-bff` deployable establishes the first part of step 1. It does
not yet proxy domain APIs and is not production-routed. Institutional IdP
integration, Redis failover behavior, service-token exchange, Kubernetes
values, and edge routing remain required before the first UI slice moves.
