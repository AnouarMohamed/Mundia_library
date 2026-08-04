# Mundiapolis Library

> Production-grade, secure, and full-featured university library platform built for students, faculty, and library staff at Mundiapolis University.

[![Node.js Version](https://img.shields.io/badge/node-%3E%3D24.17.0-brightgreen.svg)](https://nodejs.org/)
[![Next.js](https://img.shields.io/badge/Next.js-15.5-black.svg)](https://nextjs.org/)
[![React](https://img.shields.io/badge/React-19-blue.svg)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.x-blue.svg)](https://www.typescriptlang.org/)
[![Drizzle ORM](https://img.shields.io/badge/Drizzle%20ORM-0.45-green.svg)](https://orm.drizzle.team/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Mundiapolis Library provides an end-to-end digital library experience. It supports authenticated catalog discovery, borrow request lifecycle management, renewal workflows, book reviews, administrative approvals, circulation operations, automated fine calculations, overdue reminders, real-time analytics, secure data exports, and production-ready packaging.

---

## Current Release & Live Demo

- **Version**: `0.2.1`
- **Node Baseline**: Node.js 24 LTS (`>=24.17.0 <25`)
- **Production URL**: [https://mundia-library.vercel.app](https://mundia-library.vercel.app)
- **Release Assets**: [GitHub Release v0.2.1](https://github.com/AnouarMohamed/Mundia_library/releases/tag/v0.2.1)

---

## Table of Contents

- [Product Scope & Key Features](#product-scope--key-features)
- [Architecture & Tech Stack](#architecture--tech-stack)
- [System Architecture Flowchart](#system-architecture-flowchart)
- [Data Model & Domain Invariants](#data-model--domain-invariants)
- [Quick Start Guide](#quick-start-guide)
- [Command & Script Reference](#command--script-reference)
- [Environment Configuration](#environment-configuration)
- [Testing & Quality Assurance](#testing--quality-assurance)
- [Deployment Options](#deployment-options)
- [Security & Governance](#security--governance)
- [Documentation Directory](#documentation-directory)

---

## Product Scope & Key Features

### Student & Faculty Portal
- **Catalog Discovery & Search**: Fast full-text search across titles, authors, genres, and summaries.
- **Advanced Filtering & Sorting**: Filter by genre, availability status, publication year, and average rating; sort by rating, title, or publication date.
- **Book Detail & Multimedia**: Detailed metadata including ISBN, page count, publisher, language, edition, and video trailers/previews.
- **Low-Friction Borrow Requests**: Request book loans directly from catalog pages with automatic copy availability verification.
- **Circulation History & Active Loans**: Personal account dashboard tracking active loans, due dates, renewal statuses, and loan history.
- **Renewal Requests**: Submit renewal requests for active loans directly to library staff.
- **Book Reviews & Ratings**: Submit star ratings and written reviews for books previously borrowed.
- **Notification Inbox**: In-app alerts for request approvals, due date warnings, overdue notices, and account status updates.

### Library Admin & Circulation Desk
- **Operational Dashboard**: Real-time stats on total books, active borrows, pending approvals, overdue items, and circulation velocity.
- **User Account Lifecycle Management**: Review student registrations, verify university ID card uploads, and approve/reject membership applications.
- **Circulation Desk Operations**: One-click approval for pending borrow requests and seamless return processing.
- **Inventory & Copy Sync**: Automatic, transaction-safe copy count management (`availableCopies` sync with active loans).
- **Catalog Metadata Maintenance**: Add new books, edit metadata, upload cover images via ImageKit integration, and soft-delete/deactivate catalog items.
- **Fine Policy & Automated Processing**: Configure daily fine rates, calculate overdue penalties dynamically, and track unpaid balances.
- **Automated Reminder Workflows**: Trigger email reminders for upcoming due dates and overdue loans via Upstash QStash/Workflow and Brevo/Resend.
- **Granular RBAC & Admin Capabilities**: Multi-layered permission system enforcing fine-grained administrative capability assignments (`admin_capability_assignments`) alongside audit logging.
- **Data Exports & Reporting**: Export circulation history, user lists, and catalog records to CSV/JSON format.

---

## Architecture & Tech Stack

| Layer | Technology / Tool | Description |
| :--- | :--- | :--- |
| **Framework** | [Next.js 15](https://nextjs.org/) (App Router) | React server components, server actions, route handlers, standalone output |
| **Frontend UI** | [React 19](https://react.dev/), [Tailwind CSS](https://tailwindcss.com/) | Radix UI primitives, Lucide React icons, customized Mundiapolis theme |
| **Language** | [TypeScript 5](https://www.typescriptlang.org/) | Strict type checking without emit (`tsc --noEmit`) |
| **Authentication** | [NextAuth.js v5](https://authjs.dev/) | Credentials provider, salted password hashes, JWT sessions, Edge-safe lazy DB import |
| **Database** | [PostgreSQL 16](https://www.postgresql.org/) | Production relational engine with constraint checks and foreign key enforcement |
| **ORM** | [Drizzle ORM](https://orm.drizzle.team/) & `drizzle-kit` | Type-safe query builder, declarative schema, canonical SQL migration runner |
| **DB Driver** | `pg` Pool | Bounded, transaction-capable PostgreSQL pool across all environments |
| **Cache & Rate Limit** | [Upstash Redis](https://upstash.com/) | Distributed caching, rate limiting with PostgreSQL bucket fallback |
| **Background Workflows** | [Upstash QStash & Workflow](https://upstash.com/) | Asynchronous background tasks, scheduled reminders, and workflow state engines |
| **Media Delivery** | [ImageKit](https://imagekit.io/) | Optimized book cover images and university ID card uploads |
| **Email Service** | [Brevo](https://www.brevo.com/) & [Resend](https://resend.com/) | Primary transactional email delivery with Resend fallback |
| **Testing** | [Vitest](https://vitest.dev/) & [Playwright](https://playwright.dev/) | Unit/integration testing and end-to-end browser automation |
| **Linter** | [Oxlint](https://oxc.rs/docs/guide/usage/linter.html) | Fast JavaScript/TypeScript linting with zero-warning enforcement |

---

## System Architecture Flowchart

```mermaid
flowchart TD
  subgraph Client ["Client Layer"]
    Browser["Student & Admin Web Browsers"]
  end

  subgraph Application ["Next.js 15 App Router Layer"]
    Auth["NextAuth v5 (JWT Sessions & Middleware)"]
    ServerActions["Server Actions (lib/actions & lib/admin/actions)"]
    API["API Route Handlers (app/api)"]
  end

  subgraph Storage ["Persistence & Cache Layer"]
    DB[("PostgreSQL Database\n(Drizzle ORM + pg Pool)")]
    Redis[("Upstash Redis\n(Cache & Rate Limiting)")]
    DBRateLimit[("Rate Limit Buckets\n(Fallback Rate Limiter)")]
  end

  subgraph Integrations ["External Services & Automation"]
    QStash["Upstash QStash / Workflows"]
    ImageKit["ImageKit Storage"]
    Email["Brevo / Resend Email API"]
  end

  Browser -->|"HTTPS Requests"| Auth
  Auth -->|"Session Verification"| ServerActions
  Auth -->|"Route Protection"| API
  ServerActions -->|"Transactions"| DB
  API -->|"Queries / Mutations"| DB
  API -->|"Cached Reads / Rate Limit"| Redis
  API -.->"Fallback Rate Limit"| DBRateLimit
  ServerActions -->|"Upload Media"| ImageKit
  ServerActions -->|"Schedule Reminder Jobs"| QStash
  QStash -->|"Send Due / Overdue Alerts"| Email
  API -->|"Send Transactional Emails"| Email
```

---

## Data Model & Domain Invariants

The database schema ([database/schema.ts](file:///home/anouar/Mundia_library/database/schema.ts)) is managed via Drizzle ORM and PostgreSQL. Key entities include:

- **`users`**: Stores student and admin credentials, status (`PENDING`, `APPROVED`, `REJECTED`), role (`USER`, `ADMIN`), and university ID card references.
- **`federated_identities`**: Provisioned institutional identities for OIDC provider session bindings.
- **`admin_capability_assignments`**: Fine-grained administrative capabilities (`fines.manage_policy`, `users.manage_status`, `bulk.execute`, etc.) with append-only grant/revocation tracking.
- **`books`**: Book metadata, ISBN, total copies, available copies, ratings, and soft-delete toggle (`isActive`).
- **`borrow_records`**: Tracks book loan lifecycle (`PENDING` → `BORROWED` → `RETURNED`), due dates, return dates, fines, and renewal counts.
- **`renewal_requests`**: Extension requests submitted by students for active loans.
- **`book_reviews`**: Verified user reviews and ratings (1–5 stars).
- **`admin_requests`**: Role escalation requests submitted by users.
- **`audit_logs`**: Append-only log recording high-risk administrative operations.
- **`system_config`**: Application settings (e.g., daily fine rates).
- **`notifications`**: In-app user notifications.
- **`rate_limit_buckets`**: SHA-256 hashed fallback rate limit window tracking for DB-only deployments.

### Crucial Domain Invariants
1. **Copy Balance Integrity**: `books.availableCopies` must always equal `totalCopies - count(active BORROWED records)`.
2. **Borrow Lifecycle Transition**: Borrow records progress strictly `PENDING` → `BORROWED` → `RETURNED`.
3. **Single Active Loan Rule**: A user cannot submit multiple active borrow requests for the same book simultaneously.

---

## Quick Start Guide

### Prerequisites

- **Node.js**: `24.17.0` or newer (LTS 24 line)
- **Package Manager**: `npm`
- **Database**: PostgreSQL 16 (local or Docker)
- **Docker & Docker Compose**: Optional, for running local PostgreSQL and Adminer

### Installation & Local Setup

1. **Clone the repository and install dependencies**:
   ```bash
   git clone https://github.com/AnouarMohamed/Mundia_library.git
   cd Mundia_library
   npm ci
   ```

2. **Rebuild native binary dependencies** (if platform binary warnings occur):
   ```bash
   npm run deps:build-native
   ```

3. **Configure Environment Variables**:
   ```bash
   cp .env.example .env.local
   ```
   *(Edit `.env.local` to fit your local setup if defaults need tweaking)*

4. **Start PostgreSQL with Docker Compose**:
   ```bash
   docker compose up -d db
   ```

5. **Apply Database Migrations & Seed Initial Data**:
   ```bash
   npm run db:migrate
   npm run seed
   ```

6. **Launch the Development Server**:
   ```bash
   npm run dev
   # or with Turbopack for faster iteration:
   npm run dev:turbo
   ```

7. **Access the Application**:
   - **Student & Public Interface**: [http://localhost:3000](http://localhost:3000)
   - **Adminer DB Tool**: [http://localhost:8080](http://localhost:8080) (when using Docker)

### Default Local Credentials

| Role | Email | Password | Access Level |
| :--- | :--- | :--- | :--- |
| **Student** | `test@user.com` | `12345678` | Catalog browse, request borrows, view history, request renewals, post reviews |
| **Admin** | `test@admin.com` | `12345678` | Full administrative control, circulation desk, account approvals, fine setup, analytics |

---

## Command & Script Reference

| Command | Purpose |
| :--- | :--- |
| **Development & Build** | |
| `npm run dev` | Start Next.js development server on port 3000 |
| `npm run dev:turbo` | Start Next.js dev server with Turbopack |
| `npm run build` | Build standalone production bundle |
| `npm run start` | Start production server after `npm run build` |
| `npm run deps:build-native` | Rebuild native node modules (`esbuild`, `sharp`, etc.) |
| **Quality & Security** | |
| `npm run lint` | Run Oxlint with zero-warning policy (`--deny-warnings`) |
| `npm run typecheck` | Run TypeScript compiler check without emitting JS (`tsc --noEmit`) |
| `npm run test` | Run unit and integration tests with Vitest |
| `npm run test:e2e` | Run end-to-end browser tests with Playwright |
| `npm run security:audit` | Execute npm vulnerability audit (low threshold) |
| `npm run ci:quality` | Full CI quality check: audit → lint → typecheck → test → test:e2e → build |
| **Database & Schema Management** | |
| `npm run db:migrate` | Apply canonical PostgreSQL migration scripts |
| `npm run db:generate` | Generate new migration files from Drizzle schema updates |
| `npm run db:push` | Push schema changes directly to DB (disposable local environments only) |
| `npm run db:studio` | Launch interactive Drizzle Studio interface |
| `npm run seed` | Seed database with sample books and local test accounts |
| `npm run db:migrate-csv` | Migrate book catalog data from CSV source |
| `npm run db:verify-schema` | Verify production database schema integrity and constraints |
| `npm run db:verify-concurrency` | Run concurrency invariant tests against borrow/copy balance logic |
| `npm run db:perf-indexes` | Apply performance tuning indexes to PostgreSQL |
| **Benchmarking & Operational Utility** | |
| `npm run benchmark:api` | Benchmark critical API routes (`/api/books`, `/api/books/genres`, etc.) |
| `npm run explain:hot-queries` | Analyze PostgreSQL EXPLAIN execution plans for hot queries |
| `npm run loadtest:nightly` | Run stress and load testing script against API routes |
| `npm run auth:capability` | CLI script to manage admin capability assignments |
| `npm run auth:identity` | CLI script to manage federated OIDC identities |
| `npm run verify-borrow` | Verify detailed borrow record consistency |
| `npm run fix-borrow-sync` | Correct book copy counts against active borrow records |

---

## Environment Configuration

Refer to [.env.example](.env.example) for a comprehensive list of configuration parameters. Key environment variables include:

```env
# Mandatory Database & Auth Settings
DATABASE_URL="postgres://postgres:postgres@localhost:5432/university_library"
NEXTAUTH_SECRET="your-super-secret-key-at-least-32-chars-long"
NEXTAUTH_URL="http://localhost:3000"

# Optional Upstash Redis & Rate Limiting (Bypassed in dev if missing)
UPSTASH_REDIS_REST_URL=""
UPSTASH_REDIS_REST_TOKEN=""
DISABLE_RATE_LIMIT="false"

# Optional Upstash QStash & Workflows
QSTASH_URL=""
QSTASH_TOKEN=""
QSTASH_CURRENT_SIGNING_KEY=""
QSTASH_NEXT_SIGNING_KEY=""
ENABLE_WORKFLOWS="true"

# Optional Media Storage (ImageKit)
NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY=""
NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT=""
IMAGEKIT_PRIVATE_KEY=""

# Optional Transactional Email (Brevo & Resend)
BREVO_API_KEY=""
RESEND_API_KEY=""
NODEMAILER_SENDER="Mundiapolis Library <noreply@mundiapolis.ma>"
```

Detailed configuration guidelines are available in [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

---

## Testing & Quality Assurance

Mundiapolis Library enforces strict automated quality gates prior to any code merging or production deployment:

- **Static Analysis & Linting**: Oxlint strictly enforces code quality with a zero-warning policy.
- **Type Safety**: TypeScript strictly validates type signatures across the entire App Router codebase.
- **Unit & Integration Tests**: Vitest runs colocated `*.test.ts` files testing server actions, caches, API error handling, and authorization rules.
- **End-to-End E2E Testing**: Playwright runs full browser automation tests verifying login, catalog search, borrowing, and admin workflows.
- **Security Audits**: Automated `npm audit` checks dependencies against known CVE databases.

To run the complete quality suite locally:
```bash
npm run ci:quality
```

---

## Deployment Options

The application supports three production deployment targets:

1. **Vercel** *(Recommended)*: Zero-config deployment with native App Router support and automated preview deployments.
2. **Docker Container**: Build and launch using the production multi-stage [Dockerfile](file:///home/anouar/Mundia_library/Dockerfile):
   ```bash
   docker build -t mundia-library:latest .
   docker run -p 3000:3000 -e DATABASE_URL="..." -e NEXTAUTH_SECRET="..." mundia-library:latest
   ```
3. **Standalone Server Bundle**: Next.js is configured with `output: "standalone"`, producing an optimized production bundle suitable for custom Node.js servers.

Refer to [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for full deployment instructions and environment verification.

---

## Security & Governance

- **Authentication & Password Protection**: Passwords are saved with salted bcrypt hashes. NextAuth v5 JWT sessions govern authenticated routes.
- **Authoritative Authorization**: `/admin` routes and actions undergo strict multi-tier server-side validation evaluating authentication status, account approval state (`APPROVED`), role (`ADMIN`), and required granular capabilities (`AdminCapability`).
- **Distributed Rate Limiting**: Upstash Redis rate limiting protects public and sensitive API endpoints. Deployments without Redis automatically fall back to PostgreSQL `rate_limit_buckets`.
- **Audit Logging**: Sensitive administrative actions are appended to `audit_logs` with actor details, timestamps, and target identifiers.
- **Security Automation**: CI workflows run GitHub CodeQL analysis, secret scanning, dependency review, and OpenSSF Scorecard evaluations.

For security reports or vulnerabilities, see [SECURITY.md](SECURITY.md).

---

## Documentation Directory

Complete architectural, operational, and development documentation is located in the [`docs/`](docs/) directory:

- **[Documentation Index](docs/README.md)**: Main landing hub for all repository documentation.
- **[System Architecture](docs/ARCHITECTURE.md)**: Deep dive into App Router patterns, auth flow, and database design.
- **[Environment & Configuration](docs/CONFIGURATION.md)**: Detailed configuration matrix and environment setup.
- **[Development Guide](docs/DEVELOPMENT.md)**: Workspace setup, coding conventions, and workflow tips.
- **[Deployment Manual](docs/DEPLOYMENT.md)**: Step-by-step production deployment guide.
- **[Operations Runbook](docs/OPERATIONS.md)**: Admin procedures, manual interventions, and troubleshooting.
- **[API Reference](docs/API_REFERENCE.md)**: Comprehensive documentation of REST routes and Server Actions.
- **[Data Model Specification](docs/DATA_MODEL.md)**: Full PostgreSQL table schemas, indexes, and triggers.
- **[Testing & CI Guide](docs/TESTING_AND_CI.md)**: Vitest, Playwright, and GitHub Actions configuration.
- **[Release Process](docs/RELEASE_PROCESS.md)**: Versioning, changelogs, and release checklist.
- **[Production Readiness Checklist](docs/PRODUCTION_READINESS.md)**: Final verification criteria for production releases.
- **[Contributing Guidelines](CONTRIBUTING.md)**: Workflow rules for contributors.
- **[Security Policy](SECURITY.md)**: Vulnerability disclosure policy.

---

## License & Repository Status

This repository contains private application code for Mundiapolis University Library (`"private": true` in `package.json`). Licensed under the [MIT License](LICENSE).

