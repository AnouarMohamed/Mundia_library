# Mundiapolis Library Management System

Full-stack university library platform built with Next.js 15, TypeScript, Drizzle ORM, and PostgreSQL.

It includes:
- Student portal (discover books, request borrows, return books, write reviews)
- Admin portal (catalog, users, approvals, fines, reminders, exports, recommendations)
- Background workflow and email integrations (Upstash + Brevo/Resend)

## Table of Contents
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Scripts](#scripts)
- [API Overview](#api-overview)
- [Security Notes](#security-notes)
- [Troubleshooting](#troubleshooting)

## Features
- Credential-based auth with NextAuth (JWT session strategy)
- Role-aware access (`USER`, `ADMIN`) with admin route protection
- Book catalog with search, filtering, sorting, pagination
- Borrow request lifecycle: `PENDING -> BORROWED -> RETURNED`
- Fine handling for overdue borrows
- Review system with eligibility checks (must have returned the book)
- Admin automation:
  - due-soon and overdue reminder emails
  - fine configuration
  - recommendation generation
  - trending update and cache refresh
  - data export (CSV/JSON)
- Image uploads via ImageKit auth endpoint
- Rate limiting via Upstash Redis (bypassed in development mode)

## Tech Stack
| Layer | Stack |
| --- | --- |
| Frontend | Next.js 15 (App Router), React 19, Tailwind CSS, Radix UI |
| State/Data | TanStack Query |
| Backend | Next.js Route Handlers + Server Actions (Node.js runtime) |
| Database | PostgreSQL + Drizzle ORM |
| Auth | NextAuth v5 (Credentials provider) |
| Caching/Queue | Upstash Redis + Upstash Workflow/QStash |
| Media | ImageKit |
| Email | Brevo (primary), Resend (fallback) |

## Architecture

### High-Level System
```mermaid
flowchart LR
  U[Student User] --> WEB[Next.js Web App]
  A[Admin User] --> WEB

  WEB --> AUTH[NextAuth Credentials]
  WEB --> SA[Server Actions]
  WEB --> API[API Route Handlers]
  WEB --> RQ[TanStack Query Cache]
  WEB --> MW[Middleware /admin/*]

  AUTH --> DB[(PostgreSQL)]
  SA --> DB
  API --> DB

  API --> IK[ImageKit]
  API --> RL[Upstash Redis Rate Limit]
  API --> WF[Upstash Workflow / QStash]
  WF --> EMAIL[Brevo or Resend]
```

### Borrow Request Sequence
```mermaid
sequenceDiagram
  participant S as Student
  participant UI as App UI
  participant SA as borrowBook Server Action
  participant DB as PostgreSQL
  participant AD as Admin
  participant ASA as Admin Borrow Actions

  S->>UI: Request borrow
  UI->>SA: borrowBook(userId, bookId)
  SA->>DB: Insert borrow record (PENDING)
  DB-->>UI: Pending request created

  AD->>UI: Approve request
  UI->>ASA: approveBorrowRequest(recordId)
  ASA->>DB: Set BORROWED + dueDate and decrement availableCopies

  S->>UI: Return book
  UI->>ASA: returnBook(recordId)
  ASA->>DB: Set RETURNED + fine and increment availableCopies
```

### Borrow Lifecycle
```mermaid
stateDiagram-v2
  [*] --> PENDING
  PENDING --> BORROWED: Admin approves
  PENDING --> [*]: Admin rejects request
  BORROWED --> RETURNED: Book returned
```

### Database ERD
```mermaid
erDiagram
  USERS ||--o{ BORROW_RECORDS : creates
  BOOKS ||--o{ BORROW_RECORDS : referenced_by
  USERS ||--o{ BOOK_REVIEWS : writes
  BOOKS ||--o{ BOOK_REVIEWS : receives
  USERS ||--o{ ADMIN_REQUESTS : submits
  USERS ||--o{ ADMIN_REQUESTS : reviews

  USERS {
    uuid id PK
    text email UK
    int university_id UK
    text password
    enum role
    enum status
  }

  BOOKS {
    uuid id PK
    varchar title
    varchar author
    text genre
    int total_copies
    int available_copies
    bool is_active
  }

  BORROW_RECORDS {
    uuid id PK
    uuid user_id FK
    uuid book_id FK
    enum status
    date due_date
    date return_date
    decimal fine_amount
  }

  BOOK_REVIEWS {
    uuid id PK
    uuid user_id FK
    uuid book_id FK
    int rating
    text comment
  }

  ADMIN_REQUESTS {
    uuid id PK
    uuid user_id FK
    enum status
    text request_reason
  }

  SYSTEM_CONFIG {
    uuid id PK
    varchar key UK
    text value
  }
```

## Project Structure
```text
app/
  (auth)/                 # Sign in / Sign up routes
  (root)/                 # User-facing authenticated pages
  admin/                  # Admin dashboard pages
  api/                    # Route handlers (books, borrows, admin, reviews, auth)
components/               # UI and feature components
database/
  drizzle.ts              # DB connection
  schema.ts               # Drizzle schema
  seed.ts                 # Seed script
hooks/                    # React Query hooks
lib/
  actions/                # User server actions
  admin/actions/          # Admin server actions
  services/               # API wrapper services
migrations/               # SQL migrations
scripts/                  # Diagnostics and maintenance scripts
```

## Getting Started

### Prerequisites
- Node.js 20+
- npm
- PostgreSQL database
- Upstash (Redis + QStash), ImageKit, and email provider credentials

### 1. Install dependencies
```bash
npm install
```

### 2. Configure environment
Create `.env.local` in the project root:

```env
DATABASE_URL=postgresql://user:password@host:5432/dbname
NEXTAUTH_SECRET=replace_me
NEXTAUTH_URL=http://localhost:3000

NEXT_PUBLIC_API_ENDPOINT=http://localhost:3000
NEXT_PUBLIC_PROD_API_ENDPOINT=http://localhost:3000

NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT=https://ik.imagekit.io/your_id
NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY=public_key
IMAGEKIT_PRIVATE_KEY=private_key

UPSTASH_REDIS_URL=https://your-redis.upstash.io
UPSTASH_REDIS_TOKEN=your_redis_token
QSTASH_URL=https://qstash.upstash.io
QSTASH_TOKEN=your_qstash_token

BREVO_API_KEY=your_brevo_key
BREVO_SENDER_EMAIL=noreply@example.com
BREVO_SENDER_NAME=Mundiapolis Library
RESEND_TOKEN=your_resend_key

ENABLE_WORKFLOWS=false
```

Note: Some standalone scripts read from `.env` (not `.env.local`). If you use those scripts, duplicate the required keys into `.env`.

### 3. Apply database schema
```bash
npm run db:migrate
```

### 4. (Optional) Seed sample books
```bash
npm run seed
```

### 5. Start development server
```bash
npm run dev
```

Open `http://localhost:3000`.

## Environment Variables
| Variable | Required | Purpose |
| --- | --- | --- |
| `DATABASE_URL` | Yes | PostgreSQL connection string |
| `NEXTAUTH_SECRET` | Yes | NextAuth JWT/cookie secret |
| `NEXTAUTH_URL` | Yes | Base app URL for auth callbacks |
| `NEXT_PUBLIC_API_ENDPOINT` | Yes | Public API base URL |
| `NEXT_PUBLIC_PROD_API_ENDPOINT` | Yes | Metadata/workflow endpoint base URL |
| `NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT` | Yes | ImageKit URL endpoint |
| `NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY` | Yes | ImageKit public key |
| `IMAGEKIT_PRIVATE_KEY` | Yes | ImageKit private key |
| `UPSTASH_REDIS_URL` | Yes | Upstash Redis URL |
| `UPSTASH_REDIS_TOKEN` | Yes | Upstash Redis token |
| `QSTASH_URL` | Yes | Upstash QStash URL |
| `QSTASH_TOKEN` | Yes | Upstash QStash token |
| `BREVO_API_KEY` | Yes | Brevo API key |
| `BREVO_SENDER_EMAIL` | Yes | Brevo sender email |
| `BREVO_SENDER_NAME` | No | Brevo sender name |
| `RESEND_TOKEN` | Yes | Resend API token fallback |
| `ENABLE_WORKFLOWS` | No | Force-enable onboarding workflow outside production |

## Scripts
| Command | Description |
| --- | --- |
| `npm run dev` | Start local dev server |
| `npm run dev:turbo` | Start dev server with Turbopack |
| `npm run build` | Production build |
| `npm run start` | Start production server |
| `npm run lint` | Run Next.js lint |
| `npm run db:generate` | Generate Drizzle migration files |
| `npm run db:migrate` | Push schema to database |
| `npm run db:studio` | Open Drizzle Studio |
| `npm run seed` | Seed books from `dummybooks.json` |
| `npm run db:migrate-csv` | Import data from CSV migration script |
| `npm run db:perf-indexes` | Add performance indexes |
| `npm run verify-stats` | Verify admin stats consistency |
| `npm run verify-borrow` | Verify borrow detail consistency |
| `npm run fix-borrow-sync` | Repair borrow synchronization issues |
| `npm run fix-overcorrection` | Correct over-updated records |
| `npm run find-missing-borrow` | Find missing borrow relationships |
| `npm run check-all-books` | Validate book records |
| `npm run fix-coding-interview` | Specialized maintenance script |

## API Overview

Core endpoints:
- `GET /api/books` - list books (search/filter/sort/pagination)
- `GET /api/books/[id]` - single book details
- `GET /api/books/genres` - distinct genres
- `GET /api/books/recommendations` - personalized or fallback recommendations
- `GET /api/books/[id]/borrow-stats` - per-book borrow metrics
- `GET /api/borrow-records` - user/admin borrow records
- `GET|POST /api/reviews/[bookId]` - list/create reviews
- `GET /api/reviews/eligibility/[bookId]` - review eligibility
- `PUT /api/reviews/edit/[reviewId]` - update own review
- `DELETE /api/reviews/delete/[reviewId]` - delete own review
- `GET /api/auth/imagekit` - ImageKit upload auth params

Admin endpoints:
- `GET /api/admin/stats`
- `GET /api/users` (admin-only user listing)
- `GET /api/admin/borrow-requests`
- `GET /api/admin/admin-requests`
- `GET|POST /api/admin/fine-config`
- `POST /api/admin/send-due-reminders`
- `POST /api/admin/send-overdue-reminders`
- `POST /api/admin/update-overdue-fines`
- `POST /api/admin/generate-recommendations`
- `POST /api/admin/update-trending-books`
- `POST /api/admin/refresh-recommendation-cache`
- `GET /api/admin/export-stats`
- `POST /api/admin/export/[type]`

## Security Notes
- Auth is NextAuth credentials-based with salted SHA-256 password hashing.
- `/admin/*` is protected by middleware and role checks.
- Admin APIs use `requireAdminRouteAccess()` guard.
- Most API routes are rate-limited by IP using Upstash.
- In development, rate-limit checks are bypassed for speed.

## Troubleshooting
- `No database connection string was provided`:
  - Ensure `DATABASE_URL` is set correctly.
- Auth issues in local:
  - Set both `NEXTAUTH_SECRET` and `NEXTAUTH_URL`.
- Upload/email/automation failures:
  - Verify ImageKit, Upstash, Brevo, and Resend credentials.
- Script works in app but fails in CLI:
  - Some scripts load `.env` explicitly; mirror values from `.env.local`.
- Build passes despite type/lint issues:
  - `next.config.ts` currently ignores TypeScript and ESLint errors during build.
