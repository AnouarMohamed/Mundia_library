FROM node:25-bookworm-slim AS base
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1

FROM base AS deps
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM base AS prod-deps
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund

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

FROM base AS runner
ENV NODE_ENV=production
COPY package.json ./
COPY next.config.ts ./
COPY --from=prod-deps /app/node_modules ./node_modules
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/public ./public
EXPOSE 3000
CMD ["npm", "run", "start"]
