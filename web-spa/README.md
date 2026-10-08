# Static web SPA

This is the final browser runtime described by ADR 0003. It is an immutable
Vite/React build and communicates only with the same-origin Kotlin Web BFF.
Next.js remains the production migration shell until each route passes its
backfill, parity, rollback, and edge-routing gates.

```bash
npm run spa:generate
VITE_ENABLE_LEARNING_RESOURCES=true npm run spa:build
VITE_ENABLE_MEMBER_PROFILE=true npm run spa:build
VITE_ENABLE_CIRCULATION_SELF_SERVICE=true npm run spa:build
VITE_ENABLE_NOTIFICATIONS=true npm run spa:build
VITE_ENABLE_ADMINISTRATION=true npm run spa:build
npm run spa:test
npm run spa:release
```

All slice flags default to disabled. Enable `VITE_ENABLE_LEARNING_RESOURCES`
only after Catalog and Digital Content reconciliation. Enable
`VITE_ENABLE_MEMBER_PROFILE` only after Membership parity and browser checks.
Enable `VITE_ENABLE_CIRCULATION_SELF_SERVICE` only after Catalog and Circulation
projections reconcile and failure drills pass. This switch enables physical
catalog search, edition details, licensed first-party downloads, borrow and
reservation commands, and caller-bound history. Digital files are authorized
only after a user chooses PDF or EPUB; signed URLs are never prefetched.
Flags can be enabled independently. The SPA never receives OAuth tokens or
service credentials. Browser authentication uses the BFF's secure Redis-backed
session cookie; mutations obtain the BFF's CSRF token first and use a unique,
actor-scoped idempotency key.

Enable `VITE_ENABLE_NOTIFICATIONS` only after inbox projections and email
delivery have reconciled. The inbox is caller-bound and keyset paginated;
mark-read uses CSRF protection, while preference updates additionally require
the latest strong ETag to prevent lost updates.

Enable `VITE_ENABLE_ADMINISTRATION` only after the identity provider grants
approved administrators the `membership.members.read` and
`membership.status.manage` delegated scopes and the separate Circulation admin
client is limited to `circulation.admin.read`, loan approve/reject/return, and
reservation fulfil/expire scopes. The routes fail closed for other users and
re-check the operator's authoritative approved admin role on every request.
Account decisions require CSRF, a fresh aggregate ETag, an actor-scoped
idempotency key, and a written audit reason. Circulation commands require CSRF,
explicit confirmation, and a unique actor-scoped idempotency key; queue reads
are bounded and keyset paginated. Catalog administration additionally requires
the separate `catalog.read,catalog.manage` client and exposes bounded search,
work/edition creation, edition correction, and publication activation. Retries
reuse the same idempotency key, updates carry the displayed aggregate version,
and inventory is never editable from the Catalog workspace.

During local development, Vite proxies `/api`, `/oauth2`, and `/login` to the
Web BFF at `http://localhost:8080`. Production must present the static assets
and those BFF paths through one HTTPS origin.

`npm run spa:release` produces `dist/web-spa` plus a deterministic
`dist/web-spa-manifest.json` containing the full source revision, aggregate
SHA-256, per-file hashes, sizes, feature flags, and deployment cache policy.
CI uploads those exact tested files; later environments promote that artifact
without rebuilding it.
