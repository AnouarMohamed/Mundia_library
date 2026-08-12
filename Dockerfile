FROM node:24-bookworm-slim@sha256:6f7b03f7c2c8e2e784dcf9295400527b9b1270fd37b7e9a7285cf83b6951452d AS base
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

FROM gcr.io/distroless/nodejs24-debian13:nonroot@sha256:fbbdda866ea71aef98c4abece17e3d61fbf820cc2ef3961522caa2478716171a AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV APP_ENV=production
ENV HOSTNAME=0.0.0.0
COPY --from=runner-files --chown=65532:65532 /app /app
USER 65532:65532
EXPOSE 3000
CMD ["server.js"]
