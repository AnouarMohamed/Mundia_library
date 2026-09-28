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
BFF_PUBLIC_BASE_URL=http://localhost:8080 \
REDIS_URL=redis://localhost:56379 \
./gradlew :web-bff:bootRun
```

The exact redirect URI is
`http://localhost:8080/login/oauth2/code/institutional`. Never use the local
HTTP or insecure-cookie settings outside local development.

Authenticated browser sessions can call `GET /api/v1/catalog/search`. The BFF
validates and bounds all query parameters, obtains its own client-credentials
token with only `catalog.search`, and maps the Catalog
service response into the browser contract. It never forwards an OIDC browser
token. The static contract is available at `/openapi/web-bff-v1.json`.

The authorization-server client registration must issue this token with the
Catalog service's configured audience (`catalog-api` by default). The Catalog
service independently verifies issuer, audience, token type, and scope.

This application credential is intentionally limited to non-user-specific
catalog reads. Profile, circulation, reviews, and administrative operations
must preserve end-user identity through the approved delegated-token design;
do not reuse the catalog credential for those routes.
