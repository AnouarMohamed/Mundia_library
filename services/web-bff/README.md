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
BFF_PUBLIC_BASE_URL=http://localhost:8080 \
REDIS_URL=redis://localhost:56379 \
./gradlew :web-bff:bootRun
```

The exact redirect URI is
`http://localhost:8080/login/oauth2/code/institutional`. Never use the local
HTTP or insecure-cookie settings outside local development.
