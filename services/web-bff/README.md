# Web BFF

The Web BFF is the confidential OIDC client and only browser-facing application
API for the future static React SPA. It owns browser sessions and bounded UI
composition, but no domain database or business state.

Start local Redis:

```bash
docker compose -f services/compose.yaml up -d session-redis
```

Then provide a real development OIDC client and run:

```bash
cd services
OIDC_ISSUER=https://identity.example.test/exact-tenant \
OIDC_CLIENT_ID=library-web-bff \
OIDC_CLIENT_SECRET=replace-locally \
CATALOG_CLIENT_ID=library-web-bff-catalog \
CATALOG_CLIENT_SECRET=replace-locally \
CATALOG_SERVICE_URL=http://localhost:8082 \
CATALOG_AUDIENCE=catalog-api \
MEMBERSHIP_CLIENT_ID=library-web-bff-membership \
MEMBERSHIP_CLIENT_SECRET=replace-locally \
MEMBERSHIP_SERVICE_URL=http://localhost:8081 \
CIRCULATION_CLIENT_ID=library-web-bff-circulation \
CIRCULATION_CLIENT_SECRET=replace-locally \
CIRCULATION_SERVICE_URL=http://localhost:8083 \
BFF_PUBLIC_BASE_URL=http://localhost:8080 \
REDIS_URL=redis://localhost:56379 \
./gradlew :web-bff:bootRun
```

The exact redirect URI is
`http://localhost:8080/login/oauth2/code/institutional`. Never use the local
HTTP or insecure-cookie settings outside local development.

Authenticated browser sessions can call `GET /api/v1/catalog/search`, bounded
edition batch reads, the learning-resource search/detail routes, and the
category route. The BFF validates and bounds all inputs, exchanges the
server-side institutional token for a short-lived `catalog-api` token limited
to `catalog.search`, `catalog.read`, and `catalog.learning-resource.read`, and
strictly validates the Catalog response before mapping it into the browser
contract. Neither token is returned to the browser. The static contract is
available at `/openapi/web-bff-v1.json`.

The authorization server must enable RFC 8693 token exchange for the separate
`catalog-service` confidential client and issue the Catalog service's audience
(`catalog-api` by default). The Catalog service independently verifies issuer,
audience, token type, and scope.

Authenticated sessions can also call `GET /api/v1/membership/profile`. For
this user-specific route, the BFF exchanges the current institutional access
token using RFC 8693 and requests exactly the `membership-api` audience and
`membership.profile.read` scope. The Membership service derives the member
identifier from the canonical `membership_id` token claim; neither the browser
nor the BFF supplies a member ID in the URL. The browser never receives either
access token.

The authorization server must enable token exchange for the separate
`membership-service` confidential client and issue a short-lived Bearer access
token with that exact audience, scope, and a canonical UUID `membership_id`
claim. The BFF rejects overlong delegated tokens, bounds token and service
response sizes and timeouts, forbids token-endpoint redirects, and discards a
cached delegated client after a downstream `401` or `403`. Outside local
development, issuer, authorization, token, and service endpoints must use
HTTPS; authorization and token endpoints must share an exact origin. The
issuer may use a different HTTPS origin, as it does with Amazon Cognito hosted
domains.

Administration uses a distinct `membership-admin-service` token-exchange
registration so ordinary profile access never requests privileged scopes. Its
token must be issued only to approved administrators and is limited to the
same `membership-api` audience plus `membership.profile.read`,
`membership.members.read`, and `membership.status.manage`. Every administrative
request re-reads the operator's authoritative profile and requires an approved
`ADMIN` or `SUPER_ADMIN`. The BFF exposes a bounded member queue and forwards
status decisions with CSRF, exact `If-Match`, and idempotency protections.
Circulation administration additionally exchanges a distinct
`circulation-admin-service` token limited to queue reads, loan decisions and
returns, and reservation fulfilment or expiry; self-service Circulation tokens
never receive these scopes. Every browser mutation remains CSRF-protected and
uses an actor-scoped idempotency key.

The Circulation self-service routes cover eligibility, bounded keyset-paginated
loan and reservation history, loan request/cancel/renew, and reservation
place/cancel. They exchange the same server-side user token for a separate
`circulation-api` token limited to the exact self-service read and command
scopes. History accepts only status, limit, and opaque cursor inputs. Commands
accept only edition or target-resource UUIDs and an `Idempotency-Key`, while
Circulation derives the member from the canonical token claim and owns the
actor-scoped idempotency record. Mutations require the BFF's CSRF header, and
all caller-specific responses are non-cacheable.
