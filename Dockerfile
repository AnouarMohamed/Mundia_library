FROM node:26-bookworm-slim@sha256:662933cf47f013bc8e4beb31a6116448427a82057ba7c42c97e4c5ba766504c2 AS base
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1

FROM base AS deps
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund
RUN npm run deps:build-native

FROM base AS builder
ARG NEXT_PUBLIC_API_ENDPOINT
ARG NEXT_PUBLIC_PROD_API_ENDPOINT
ARG NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT
ENV NEXT_PUBLIC_API_ENDPOINT=$NEXT_PUBLIC_API_ENDPOINT
ENV NEXT_PUBLIC_PROD_API_ENDPOINT=$NEXT_PUBLIC_PROD_API_ENDPOINT
ENV NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT=$NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT
RUN test -n "$NEXT_PUBLIC_API_ENDPOINT" \
    && test -n "$NEXT_PUBLIC_PROD_API_ENDPOINT"
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN DATABASE_URL=postgresql://build:build@localhost:5432/builddb \
    APP_ENV=development \
    NEXTAUTH_SECRET=build-only-not-a-production-secret \
    AUTH_SECRET=build-only-not-a-production-secret \
    NEXTAUTH_URL=http://localhost:3000 \
    ENABLE_WORKFLOWS=false \
    npm run build

FROM deps AS db-tools
COPY drizzle.config.ts tsconfig.json ./
COPY database ./database
COPY migrations ./migrations
COPY dummybooks.json ./dummybooks.json

FROM base AS runner-files
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
RUN rm -rf ./node_modules/next/node_modules/postcss
COPY --from=builder /app/node_modules/postcss ./node_modules/next/node_modules/postcss

FROM gcr.io/distroless/nodejs24-debian13:nonroot@sha256:9eeb7f5887d0e239e78264b06f7f11d2e14be534050481803a9e4728fcdd278e AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV APP_ENV=production
ENV HOSTNAME=0.0.0.0
COPY --from=runner-files --chown=65532:65532 /app /app
USER 65532:65532
EXPOSE 3000
CMD ["server.js"]
