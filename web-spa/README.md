# Static web SPA

This is the final browser runtime described by ADR 0003. It is an immutable
Vite/React build and communicates only with the same-origin Kotlin Web BFF.
Next.js remains the production migration shell until each route passes its
backfill, parity, rollback, and edge-routing gates.

```bash
npm run spa:generate
VITE_ENABLE_LEARNING_RESOURCES=true npm run spa:build
npm run spa:test
```

`VITE_ENABLE_LEARNING_RESOURCES` defaults to disabled. Enable it only for a
staging build whose Catalog and Digital Content records have reconciled. The
SPA never receives OAuth tokens or service credentials. Browser authentication
uses the BFF's secure Redis-backed session cookie; mutations obtain the BFF's
CSRF token first.

During local development, Vite proxies `/api`, `/oauth2`, and `/login` to the
Web BFF at `http://localhost:8080`. Production must present the static assets
and those BFF paths through one HTTPS origin.
