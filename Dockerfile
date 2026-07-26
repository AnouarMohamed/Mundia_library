FROM node:24-bookworm-slim@sha256:6f7b03f7c2c8e2e784dcf9295400527b9b1270fd37b7e9a7285cf83b6951452d AS base
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1

FROM base AS deps
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund

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

FROM base AS runner
ENV NODE_ENV=production
ENV APP_ENV=production
ENV HOSTNAME=0.0.0.0
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
RUN rm -rf ./node_modules/next/node_modules/postcss
COPY --from=builder /app/node_modules/postcss ./node_modules/next/node_modules/postcss
USER node
EXPOSE 3000
CMD ["node", "server.js"]
