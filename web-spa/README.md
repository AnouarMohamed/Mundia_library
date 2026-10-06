# Static web SPA

This is the final browser runtime described by ADR 0003. It is an immutable
Vite/React build and communicates only with the same-origin Kotlin Web BFF.
Next.js remains the production migration shell until each route passes its
backfill, parity, rollback, and edge-routing gates.

```bash
npm run spa:generate
VITE_ENABLE_LEARNING_RESOURCES=true npm run spa:build
VITE_ENABLE_MEMBER_PROFILE=true npm run spa:build
npm run spa:test
npm run spa:release
```

Both slice flags default to disabled. Enable `VITE_ENABLE_LEARNING_RESOURCES`
only after Catalog and Digital Content reconciliation. Enable
`VITE_ENABLE_MEMBER_PROFILE` only after Membership parity and browser checks.
Flags can be enabled independently. The SPA never receives OAuth tokens or
service credentials. Browser authentication uses the BFF's secure Redis-backed
session cookie; mutations obtain the BFF's CSRF token first.

During local development, Vite proxies `/api`, `/oauth2`, and `/login` to the
Web BFF at `http://localhost:8080`. Production must present the static assets
and those BFF paths through one HTTPS origin.

`npm run spa:release` produces `dist/web-spa` plus a deterministic
`dist/web-spa-manifest.json` containing the full source revision, aggregate
SHA-256, per-file hashes, sizes, feature flags, and deployment cache policy.
CI uploads those exact tested files; later environments promote that artifact
without rebuilding it.
