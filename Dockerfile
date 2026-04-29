FROM node:25-bookworm-slim@sha256:e49fd70491eb042270f974167c874d6245287263ffc16422fcf93b3c150409d8 AS base
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1

FROM base AS deps
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM base AS builder
ENV DATABASE_URL=mysql://build:build@localhost:3306/builddb
ENV NEXTAUTH_SECRET=build-secret
ENV NEXTAUTH_URL=http://localhost:3000
ENV NEXT_PUBLIC_API_ENDPOINT=http://localhost:3000
ENV NEXT_PUBLIC_PROD_API_ENDPOINT=http://localhost:3000
ENV NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT=https://example.com
ENV NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY=build-public-key
ENV IMAGEKIT_PRIVATE_KEY=build-private-key
ENV UPSTASH_REDIS_URL=https://example.upstash.io
ENV UPSTASH_REDIS_TOKEN=build-token
ENV QSTASH_URL=https://qstash.upstash.io
ENV QSTASH_TOKEN=build-token
ENV BREVO_API_KEY=build-token
ENV BREVO_SENDER_EMAIL=noreply@example.com
ENV RESEND_TOKEN=build-token
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM deps AS db-tools
COPY drizzle.config.ts tsconfig.json ./
COPY database ./database
COPY migrations ./migrations
COPY dummybooks.json ./dummybooks.json

FROM base AS runner
ENV NODE_ENV=production
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
RUN rm -rf ./node_modules/next/node_modules/postcss
COPY --from=builder /app/node_modules/postcss ./node_modules/next/node_modules/postcss
EXPOSE 3000
CMD ["node", "server.js"]
