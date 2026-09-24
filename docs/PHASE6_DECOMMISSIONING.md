# Phase 6 — Monolith Backend Decommissioning & Governance

Status: **Phase 6 Blueprint; Execution Pending Phase 3/4 Soak Period Sign-Off**

This document specifies the authoritative rules, safety checks, and step-by-step procedure for retiring legacy monolith domain writes, obsolete PostgreSQL tables, direct database access routes, and credentials authentication once the strangler migration cutover retention window has elapsed.

---

## Decommissioning Scope

| Component | Current State | Phase 6 Target State | Verification Method |
|---|---|---|---|
| **Circulation Writes** | Next.js Drizzle ORM writes to `borrow_records` & `books.availableCopies` | All circulation mutations routed exclusively to `services/circulation-service` | Code & AST dependency scan for `borrowRecords` mutations |
| **Catalog Metadata** | Next.js remains authoritative for `books`; Catalog has secured PostgreSQL reads plus non-routed idempotent work/edition create commands, while update/review commands, outbox delivery, availability events, backfill, BFF routing, and cutover remain pending | Serviced exclusively by `Catalog Service` via [contracts/catalog.ts](../lib/services/contracts/catalog.ts) | Grep scan for direct `books` table queries |
| **Membership & Identity** | Next.js Drizzle ORM remains authoritative for `users`; Membership has a PostgreSQL-backed secured read slice, but writes, backfill, BFF routing, retention automation, and cutover remain pending | Serviced exclusively by `Membership Service` via [contracts/membership.ts](../lib/services/contracts/membership.ts) | AST scan for direct `users` table queries |
| **Authentication** | Dual NextAuth Credentials + OIDC PKCE | Managed OIDC PKCE exclusively; legacy SHA-256 / bcrypt password verification retired | NextAuth config audit (`auth.ts`) |
| **Notification Delivery** | Direct Brevo / QStash email sending from Next.js | Outbox-driven `Notification Service` (implementation in progress) via [contracts/notification.ts](../lib/services/contracts/notification.ts) | Outbox event delivery audit |
| **Search & Recommendations** | PostgreSQL ILIKE queries & local DB joins | OpenSearch `Discovery Service` (implementation in progress) via [contracts/discovery.ts](../lib/services/contracts/discovery.ts) | Read latency SLO monitoring |

---

## Decommissioning Execution Steps

### 1. Freeze & Retention Gate
* Confirm that the **Phase 3 cutover soak period** (minimum 30 days at zero parity errors) has passed.
* Verify that no emergency kill-switch rollbacks to legacy writers were triggered during the soak window.
* Archive cutover reconciliation JSON artifacts in a secure, immutable storage bucket.

### 2. Lock Out Legacy Write Paths
* In Next.js App Router BFF, set `CIRCULATION_PRIMARY_WRITER=KOTLIN_SERVICE` permanently and remove legacy Drizzle fallback branches in [lib/actions/book.ts](../lib/actions/book.ts) and [lib/admin/actions/borrow.ts](../lib/admin/actions/borrow.ts).
* Remove legacy table DDL mutations for `borrow_records`.

### 3. Archive Legacy Tables
* Execute canonical migration to rename legacy tables to `archived_legacy_borrow_records` before final dropping.
* Revoke write (`INSERT`, `UPDATE`, `DELETE`) privileges from application database roles on legacy tables.

### 4. Dependency & AST Audit
Run static code analysis to verify zero remaining direct table access:

```bash
# Check for direct borrowRecords mutations in Next.js codebase
grep -rn "borrowRecords" app/ lib/ database/

# Check for legacy password verification code
grep -rn "verifyPassword" app/ lib/
```

---

## Definition of Done for Monolith Retirement

1. **Zero Legacy Writers**: No application code or background job executes SQL `INSERT`/`UPDATE`/`DELETE` against legacy circulation or user tables.
2. **OIDC Only**: Managed OIDC handles 100% of user authentication; credentials sign-in route handlers are decommissioned.
3. **Clean Security & Dependency Scans**: AST and dependency scans find zero references to obsolete domain write paths.
4. **SLO Sign-Off**: Read latencies (p95 < 250ms) and circulation command latencies (p95 < 500ms) meet [PRODUCTION_OVERHAUL.md](PRODUCTION_OVERHAUL.md) objectives.
