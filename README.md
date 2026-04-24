# Mundiapolis Library Management System

<p align="center">
  <strong>Full-stack university library platform with student and admin portals</strong><br/>
  Next.js 15 - React 19 - TypeScript - Drizzle ORM - MySQL - NextAuth - Upstash
</p>

<p align="center">
  <img alt="Next.js" src="https://img.shields.io/badge/Next.js-15-black?logo=next.js" />
  <img alt="React" src="https://img.shields.io/badge/React-19-149ECA?logo=react" />
  <img alt="TypeScript" src="https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white" />
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-Database-336791?logo=MySQL&logoColor=white" />
  <img alt="Drizzle" src="https://img.shields.io/badge/Drizzle-ORM-C5F74F" />
  <img alt="Auth" src="https://img.shields.io/badge/Auth-NextAuth_v5-4B5563" />
</p>

![Student Library Landing](<screenshots/Screenshot 2026-02-16 134555.png>)

## Table of Contents

- [Project Overview](#project-overview)
- [Key Capabilities](#key-capabilities)
- [UI Preview](#ui-preview)
- [Architecture](#architecture)
- [Route Map](#route-map)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Docker Setup](#docker-setup)
- [Environment Variables](#environment-variables)
- [Scripts](#scripts)
- [CI Pipelines](#ci-pipelines)
- [API Surface](#api-surface)
- [Security and Reliability Notes](#security-and-reliability-notes)
- [Troubleshooting](#troubleshooting)

## Project Overview

Mundiapolis Library Management System is a modern full-stack library application designed for university use cases. It includes:

- Student workflows: discover books, borrow, return, review, and track borrowing history
- Admin workflows: user approvals, borrow approvals, catalog control, analytics, reminders, fines, recommendations, and exports
- Operational automation: overdue processing, email reminders, recommendation refresh, and workflow triggers

The project is implemented with Next.js App Router and combines Server Components, Server Actions, and API Route Handlers.

## Key Capabilities

| Domain | Implemented Capabilities |
| --- | --- |
| Authentication | NextAuth credentials login, JWT sessions, role-aware access (`USER`, `ADMIN`) |
| Student Experience | Featured library page, full catalog filters/sorting/pagination, book details, borrow requests, profile tabs |
| Borrow Flow | Request (`PENDING`) -> admin approval (`BORROWED`) -> return (`RETURNED`) with due dates and fine calculation |
| Reviews | Eligibility checks (borrow history), create/edit/delete review APIs |
| Admin Dashboard | KPI cards, circulation trends, borrow mix, genre performance, top-rated books, activity feeds |
| Admin Operations | Borrow request approvals, account/admin requests, user management, catalog management |
| Automation | Fine config, due-soon and overdue reminders, overdue fine updates, recommendation generation + cache refresh |
| Integrations | MySQL + Drizzle, Upstash Redis/QStash, ImageKit, Brevo + Resend fallback |
| Reliability | DB retry helper for transient failures, API rate limits (dev bypass), admin route guards |

## UI Preview

### Auth Pages

| Sign In Experience | Sign Up Experience |
| --- | --- |
| ![Sign in page](<screenshots/Screenshot 2026-02-16 150350.png>) | ![Sign up page](<screenshots/Screenshot 2026-02-16 150448.png>) |

### Student Portal

| Library Home + Featured Book | Book Collection + Filters |
| --- | --- |
| ![Library home](<screenshots/Screenshot 2026-02-16 134555.png>) | ![Book collection filters](<screenshots/Screenshot 2026-02-16 134743.png>) |

| Featured Book Detail | Recommendations Shelf |
| --- | --- |
| ![Featured book detail](<screenshots/Screenshot 2026-02-16 134704.png>) | ![Recommendations shelf](<screenshots/Screenshot 2026-02-16 134638.png>) |

| Borrow Insights and Actions |
| --- |
| ![Borrow insights panel](<screenshots/Screenshot 2026-02-16 134623.png>) |

| Borrowing History / Profile |
| --- |
| ![My profile borrowing tabs](<screenshots/Screenshot 2026-02-16 134941.png>) |

### Admin Portal

| Admin Dashboard Overview | Admin Analytics Panels |
| --- | --- |
| ![Admin dashboard overview](<screenshots/Screenshot 2026-02-16 100006.png>) | ![Admin charts](<screenshots/Screenshot 2026-02-16 100025.png>) |

| Genre and Top Rated Insights | Recent Borrow/User Activity |
| --- | --- |
| ![Genre and top rated](<screenshots/Screenshot 2026-02-16 100103.png>) | ![Recent activity](<screenshots/Screenshot 2026-02-16 100147.png>) |

| Automation Controls | Recommendation + Export Actions |
| --- | --- |
| ![Automation page](<screenshots/Screenshot 2026-02-16 100417.png>) | ![Recommendation and export actions](<screenshots/Screenshot 2026-02-16 100433.png>) |

| User Management and Admin Requests |
| --- |
| ![All users page](<screenshots/Screenshot 2026-02-16 100640.png>) |

## Architecture

### 1. System Diagram

```mermaid
flowchart TB
  subgraph Clients
    STUDENT[Student User]
    ADMIN[Admin User]
  end

  subgraph Web[Next.js 15 App Router]
    UI[Pages and Layouts]
    SA[Server Actions]
    API[API Route Handlers]
    AUTH[NextAuth Credentials]
    MW[Middleware /admin/*]
  end

  DB[(MySQL + Drizzle)]
  REDIS[(Upstash Redis)]
  QSTASH[(Upstash QStash and Workflow)]
  IMG[ImageKit]
  MAIL[Brevo and Resend]

  STUDENT --> UI
  ADMIN --> UI

  UI --> AUTH
  UI --> SA
  UI --> API
  ADMIN --> MW
  MW --> AUTH

  SA --> DB
  API --> DB
  API --> REDIS
  API --> IMG
  SA --> QSTASH
  API --> QSTASH
  QSTASH --> MAIL
  API --> MAIL
```

### 2. Auth and Access Control

```mermaid
sequenceDiagram
  participant U as User
  participant UI as Next.js UI
  participant NA as NextAuth
  participant DB as MySQL
  participant MW as Middleware

  U->>UI: Sign in with email/password
  UI->>NA: Credentials auth
  NA->>DB: Lookup user + verify salted hash
  DB-->>NA: User with role
  NA-->>UI: JWT session

  U->>UI: Request /admin/*
  UI->>MW: Middleware check
  MW->>NA: auth()
  NA-->>MW: Session
  MW-->>UI: Allow admin or redirect
```

### 3. Borrow Lifecycle

```mermaid
stateDiagram-v2
  [*] --> PENDING: Student requests borrow
  PENDING --> BORROWED: Admin approves and sets dueDate
  PENDING --> CANCELLED: Admin rejects request
  BORROWED --> RETURNED: Return processed and fine calculated
  RETURNED --> [*]
  CANCELLED --> [*]
```

### 4. Borrow Processing Sequence

```mermaid
sequenceDiagram
  participant S as Student
  participant SA as borrowBook action
  participant DB as MySQL
  participant A as Admin
  participant ASA as Admin borrow actions

  S->>SA: borrowBook(userId, bookId)
  SA->>DB: Insert borrow record status=PENDING
  DB-->>S: Request created

  A->>ASA: approveBorrowRequest(recordId)
  ASA->>DB: status=BORROWED, set dueDate, decrement availableCopies

  A->>ASA: returnBook(recordId)
  ASA->>DB: status=RETURNED, set returnDate, compute fine, increment availableCopies
```

### 5. Admin Automation Flow

```mermaid
sequenceDiagram
  participant A as Admin
  participant UI as Automation UI
  participant API as Admin API Routes
  participant DB as MySQL
  participant M as Email Provider

  A->>UI: Trigger due/overdue reminder action
  UI->>API: POST /api/admin/send-due-reminders or send-overdue-reminders
  API->>DB: Query overdue or due-soon borrow records
  API->>M: Send reminder emails
  API-->>UI: Success and result summary

  A->>UI: Trigger overdue fine update
  UI->>API: POST /api/admin/update-overdue-fines
  API->>DB: Compute and persist fineAmount
  API-->>UI: Updated fine records
```

### 6. Database ERD (Core Tables)

```mermaid
erDiagram
  USERS ||--o{ BORROW_RECORDS : creates
  BOOKS ||--o{ BORROW_RECORDS : requested_for
  USERS ||--o{ BOOK_REVIEWS : writes
  BOOKS ||--o{ BOOK_REVIEWS : receives
  USERS ||--o{ ADMIN_REQUESTS : submits
  USERS ||--o{ ADMIN_REQUESTS : reviews

  USERS {
    uuid id PK
    string full_name
    string email UK
    int university_id UK
    string password
    enum role
    enum status
    timestamp last_login
  }

  BOOKS {
    uuid id PK
    string title
    string author
    string genre
    int rating
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
    int renewal_count
  }

  BOOK_REVIEWS {
    uuid id PK
    uuid user_id FK
    uuid book_id FK
    int rating
    string comment
  }

  ADMIN_REQUESTS {
    uuid id PK
    uuid user_id FK
    enum status
    string request_reason
    uuid reviewed_by FK
  }

  SYSTEM_CONFIG {
    uuid id PK
    string key UK
    string value
    string description
  }
```

## Route Map

### App Routes

| Route | Access | Purpose |
| --- | --- | --- |
| `/sign-in` | Public | Credential login |
| `/sign-up` | Public | Student registration |
| `/library` | Authenticated | Featured book + recommendations |
| `/all-books` | Authenticated | Filtered catalog view |
| `/books/[id]` | Authenticated | Book details + reviews + borrow actions |
| `/my-profile` | Authenticated | Active borrows, pending requests, history |
| `/admin` | Admin | Dashboard and analytics |
| `/admin/users` | Admin | User list + role/status actions |
| `/admin/books` | Admin | Catalog management |
| `/admin/book-requests` | Admin | Borrow request operations |
| `/admin/account-requests` | Admin | Admin access request review |
| `/admin/automation` | Admin | Reminders, fines, exports, recommendation jobs |

### API Categories

| Category | Representative Endpoints |
| --- | --- |
| Books | `/api/books`, `/api/books/[id]`, `/api/books/genres`, `/api/books/recommendations`, `/api/books/[id]/borrow-stats` |
| Borrows | `/api/borrow-records`, `/api/admin/borrow-requests` |
| Reviews | `/api/reviews/[bookId]`, `/api/reviews/edit/[reviewId]`, `/api/reviews/delete/[reviewId]`, `/api/reviews/eligibility/[bookId]` |
| Admin Ops | `/api/admin/stats`, `/api/admin/fine-config`, `/api/admin/reminder-stats`, `/api/admin/admin-requests` |
| Admin Automation | `/api/admin/send-due-reminders`, `/api/admin/send-overdue-reminders`, `/api/admin/update-overdue-fines`, `/api/admin/generate-recommendations`, `/api/admin/update-trending-books`, `/api/admin/refresh-recommendation-cache`, `/api/admin/export-stats`, `/api/admin/export/[type]` |
| Auth + Upload | `/api/auth/[...nextauth]`, `/api/auth/imagekit` |
| Workflow | `/api/workflows/onboarding` |

## Tech Stack

| Layer | Technology |
| --- | --- |
| Frontend | Next.js 15 App Router, React 19, Tailwind CSS, Radix UI |
| Data Fetching | TanStack Query |
| Backend | Next.js Route Handlers + Server Actions |
| Auth | NextAuth v5 (Credentials provider, JWT sessions) |
| Database | MySQL + Drizzle ORM |
| Caching and Rate Limits | Upstash Redis + `@upstash/ratelimit` |
| Background Jobs | Upstash Workflow / QStash |
| Media | ImageKit |
| Email | Brevo (primary) + Resend (fallback) |

## Project Structure

```text
app/
  (auth)/                 # Sign in / sign up
  (root)/                 # Student portal pages
  admin/                  # Admin portal pages
  api/                    # Route handlers
components/               # UI and feature components
constants/                # App constants
database/
  drizzle.ts              # Database connection
  schema.ts               # Drizzle schema
  seed.ts                 # Seed script
hooks/                    # Query/mutation hooks
lib/
  actions/                # Student-facing server actions
  admin/actions/          # Admin server actions
  services/               # API service wrappers
  db/retry.ts             # DB retry helper
migrations/               # Drizzle migrations
scripts/                  # Maintenance and diagnostics scripts
screenshots/              # Project UI screenshots used in this README
```

## Getting Started

### Prerequisites

- Node.js 20+
- npm
- MySQL database
- Upstash Redis + QStash credentials
- ImageKit credentials
- Brevo and/or Resend email credentials

### 1. Install dependencies

```bash
npm install
```

### 2. Configure environment

Create `.env.local` in the project root:

```env
DATABASE_URL=mysql://user:password@host:3306/dbname
NEXTAUTH_SECRET=replace_with_secure_secret
NEXTAUTH_URL=http://localhost:3000

NEXT_PUBLIC_API_ENDPOINT=http://localhost:3000
NEXT_PUBLIC_PROD_API_ENDPOINT=http://localhost:3000

NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT=https://ik.imagekit.io/your_id
NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY=your_public_key
IMAGEKIT_PRIVATE_KEY=your_private_key

UPSTASH_REDIS_URL=https://your-redis.upstash.io
UPSTASH_REDIS_TOKEN=your_redis_token
QSTASH_URL=https://qstash.upstash.io
QSTASH_TOKEN=your_qstash_token

BREVO_API_KEY=your_brevo_api_key
BREVO_SENDER_EMAIL=noreply@example.com
BREVO_SENDER_NAME=Mundiapolis Library
RESEND_TOKEN=your_resend_token

ENABLE_WORKFLOWS=false
```

Important:

- Some standalone scripts load `.env` directly (not `.env.local`).
- If you run CLI scripts like `npm run seed`, mirror required keys in `.env`.

### 3. Apply database schema

```bash
npm run db:migrate
```

### 4. Optional seed

```bash
npm run seed
```

### 5. Start development server

```bash
npm run dev
```

Open `http://localhost:3000`.

## Docker Setup

### Prerequisites

- Docker Desktop (or Docker Engine + Compose v2)

### 1. Keep `.env.local` configured

- Docker uses your existing `.env.local` for app secrets and integration keys.
- `DATABASE_URL` is overridden in Compose to point to the MySQL container (`db`).

### 2. Build and run app + MySQL

```bash
docker compose up --build
```

What this does:

- Starts `db` (MySQL 8) with a persistent volume.
- Starts `app` (Next.js production server) on `http://localhost:3000`.
- Starts `adminer` (lightweight browser DB admin) on `http://localhost:8080`.
- Runs `npm run db:migrate` automatically before the app starts.

### 3. Optional seed after containers are up

```bash
docker compose exec app npm run seed
```

### 4. Useful commands

```bash
docker compose logs -f app
docker compose down
docker compose down -v
```

Optional overrides in `.env` (Compose-level):

```env
MYSQL_DATABASE=library_management
MYSQL_ROOT_PASSWORD=rootpassword
ADMINER_PORT=8080
```

For standalone DB tooling recommendations (including DBeaver and Drizzle Studio), see `docs/DB_ADMIN_OPTIONS.md`.

## Environment Variables

| Variable | Required | Purpose |
| --- | --- | --- |
| `DATABASE_URL` | Yes | MySQL connection string |
| `NEXTAUTH_SECRET` | Yes | NextAuth secret for JWT/cookies |
| `NEXTAUTH_URL` | Yes | Base URL for auth callbacks |
| `NEXT_PUBLIC_API_ENDPOINT` | Yes | Public API base URL |
| `NEXT_PUBLIC_PROD_API_ENDPOINT` | Yes | Production-style endpoint used by workflows |
| `NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT` | Yes | ImageKit URL endpoint |
| `NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY` | Yes | ImageKit public key |
| `IMAGEKIT_PRIVATE_KEY` | Yes | ImageKit private key (server-side) |
| `UPSTASH_REDIS_URL` | Yes | Upstash Redis URL |
| `UPSTASH_REDIS_TOKEN` | Yes | Upstash Redis token |
| `QSTASH_URL` | Yes | Upstash QStash URL |
| `QSTASH_TOKEN` | Yes | Upstash QStash token |
| `BREVO_API_KEY` | Yes | Brevo API key |
| `BREVO_SENDER_EMAIL` | Yes | Sender email for Brevo |
| `BREVO_SENDER_NAME` | No | Sender display name |
| `RESEND_TOKEN` | Yes | Resend API token fallback |
| `ENABLE_WORKFLOWS` | No | Force-enable onboarding workflow outside production |

## Scripts

| Command | Description |
| --- | --- |
| `npm run dev` | Start local development server |
| `npm run dev:turbo` | Start dev server with Turbopack |
| `npm run build` | Create production build |
| `npm run start` | Start production server |
| `npm run lint` | Run ESLint checks |
| `npm run typecheck` | Run strict TypeScript checks |
| `npm run ci:quality` | Run lint + typecheck + build locally (CI parity) |
| `npm run benchmark:api` | Benchmark key API routes and enforce p95 thresholds |
| `npm run explain:hot-queries` | Run EXPLAIN plans for the hottest API query shapes |
| `npm run db:generate` | Generate Drizzle migration files |
| `npm run db:migrate` | Push schema to MySQL |
| `npm run db:studio` | Open Drizzle Studio |
| `npm run seed` | Seed books from `dummybooks.json` |
| `npm run db:migrate-csv` | Run CSV migration helper |
| `npm run db:perf-indexes` | Add performance indexes |
| `npm run verify-stats` | Validate admin stats consistency |
| `npm run verify-borrow` | Validate borrow details consistency |
| `npm run fix-borrow-sync` | Repair borrow synchronization issues |
| `npm run fix-overcorrection` | Correct over-updated records |
| `npm run find-missing-borrow` | Find missing borrow relationships |
| `npm run check-all-books` | Validate book records |
| `npm run fix-coding-interview` | Specialized maintenance fix script |

## CI Pipelines

This repository now includes a multi-workflow GitHub Actions pipeline:

- `CI` (`.github/workflows/ci.yml`): lint, typecheck, production build, and Docker image build validation
- `CodeQL Security Scan` (`.github/workflows/codeql.yml`): static security analysis on PRs, pushes, and weekly schedule
- `Dependency Review` (`.github/workflows/dependency-review.yml`): blocks high-severity vulnerable dependency additions on PRs to `main`
- `Secret Scan` (`.github/workflows/secret-scan.yml`): detects leaked credentials and tokens on pull requests and protected branch pushes
- `API Performance Benchmarks` (`.github/workflows/api-benchmarks.yml`): validates latency for key API routes with p95 threshold gates

Full operational documentation is available in `docs/CI_PIPELINES.md`.
PR quality gates are standardized via `.github/pull_request_template.md`.
Phase-2 query optimization notes are documented in `docs/PHASE2_QUERY_TUNING.md`.

## API Surface

### Core Endpoints

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/books` | List books with search/filter/sort/pagination |
| `GET` | `/api/books/[id]` | Get one book by ID |
| `GET` | `/api/books/genres` | Get distinct genres |
| `GET` | `/api/books/recommendations` | Get personalized/fallback recommendations |
| `GET` | `/api/books/[id]/borrow-stats` | Get borrow metrics for a book |
| `GET` | `/api/borrow-records` | Get borrow records with filters |
| `GET` | `/api/reviews/[bookId]` | List reviews for a book |
| `POST` | `/api/reviews/[bookId]` | Create review |
| `GET` | `/api/reviews/eligibility/[bookId]` | Check if current user can review |
| `PUT` | `/api/reviews/edit/[reviewId]` | Update review |
| `DELETE` | `/api/reviews/edit/[reviewId]` | Delete review (alternate route implementation) |
| `DELETE` | `/api/reviews/delete/[reviewId]` | Delete review |
| `GET` | `/api/auth/imagekit` | Get ImageKit upload auth params |

### Admin Endpoints

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/admin/stats` | Dashboard metrics |
| `GET` | `/api/users` | User listing for admin |
| `GET` | `/api/admin/borrow-requests` | Borrow request list |
| `GET` | `/api/admin/admin-requests` | Admin access requests |
| `GET` | `/api/admin/fine-config` | Get daily fine amount |
| `POST` | `/api/admin/fine-config` | Update daily fine amount |
| `GET` | `/api/admin/reminder-stats` | Reminder summary metrics |
| `POST` | `/api/admin/send-due-reminders` | Send due-soon reminders |
| `POST` | `/api/admin/send-overdue-reminders` | Send overdue reminders |
| `POST` | `/api/admin/update-overdue-fines` | Calculate and persist overdue fines |
| `POST` | `/api/admin/generate-recommendations` | Generate recommendation data |
| `POST` | `/api/admin/update-trending-books` | Refresh trending books |
| `POST` | `/api/admin/refresh-recommendation-cache` | Invalidate/rebuild recommendation cache |
| `GET` | `/api/admin/export-stats` | Export counters |
| `POST` | `/api/admin/export/[type]` | Export books/users/borrows/analytics |

## Security and Reliability Notes

- Auth uses NextAuth credentials with salted SHA-256 verification.
- `/admin/*` routes are protected by middleware and role checks.
- Admin APIs enforce server-side access guards (`requireAdminRouteAccess`).
- API rate limiting uses Upstash Redis; local development bypass is enabled for speed.
- Database operations include retry logic for transient timeout/network failures.
- `next.config.ts` currently sets `typescript.ignoreBuildErrors` and `eslint.ignoreDuringBuilds` to `true`. Tighten this before strict production CI.

## Troubleshooting

- `No database connection string was provided`
  - Check `DATABASE_URL` in `.env.local` (and `.env` for CLI scripts).
- Login fails or redirects unexpectedly
  - Verify `NEXTAUTH_SECRET` and `NEXTAUTH_URL`.
- Upload auth errors
  - Validate ImageKit keys and URL endpoint variables.
- Reminder/export/recommendation automation fails
  - Validate Upstash, Brevo, and Resend environment variables.
- CLI script works differently from app runtime
  - Some scripts explicitly load `.env`; ensure values are mirrored from `.env.local`.

