# ADR 0003: Static React SPA and Kotlin BFF

- Status: Accepted
- Date: 2026-09-27
- Supersedes: the final web-runtime target in ADR 0001

## Context

Next.js currently combines the browser UI, authentication/session handling,
request composition, domain logic, and direct legacy database access. Keeping
that runtime after the domain-service cutovers would retain a second backend
stack and make it too easy to recreate a monolith behind the UI.

The authenticated library and administration product does not require server
components for correctness. Public catalog indexing can be addressed with
static pre-rendering if product evidence later requires it.

## Decision

The final web tier consists of two independently deployable artifacts:

- a React/Vite single-page application built as immutable static assets and
  served from object storage through a CDN; and
- a Kotlin/Spring `web-bff` that is the only browser-facing application API.

The SPA and BFF are presented through one public origin. The edge routes static
asset requests to object storage and authentication/API paths to the BFF. This
avoids browser CORS and cross-site cookie dependencies.

The BFF is a confidential OIDC client. It uses authorization code with PKCE,
stores sessions in managed Redis, issues only secure `HttpOnly`, `SameSite=Lax`
session cookies, and requires a separate CSRF token for mutations. OAuth tokens
remain server-side. The BFF may authenticate, authorize UI capabilities,
compose bounded responses, and call service APIs. It must not own domain data,
query a domain database, run cross-service transactions, or contain domain
state transitions.

Membership, Catalog, and Circulation remain private resource servers and must
re-authorize every request using short-lived audience-scoped credentials and
the end-user identity required by the operation.

Next.js remains only as a temporary migration shell. No new business logic or
domain persistence may be added to it. Feature slices move behind explicit
cutover controls; deletion occurs only after parity, rollback, and retention
gates pass.

## Consequences

- The finished system has one Kotlin backend stack and a static TypeScript UI.
- The frontend can be deployed and cached independently from backend services.
- The BFF becomes a security-critical service and requires Redis availability,
  session revocation tests, OIDC tenant tests, and edge-origin controls.
- Existing React components may be reused, but Next.js server actions, route
  handlers, server components, and NextAuth bindings must be replaced.
- A big-bang frontend rewrite and direct browser-to-microservice access are
  explicitly rejected.
