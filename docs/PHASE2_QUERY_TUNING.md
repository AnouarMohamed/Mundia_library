# Phase 2 Query Tuning

Date: 2026-04-24

This phase applies practical query-level optimizations to the hottest API endpoints and provides a repeatable EXPLAIN workflow.

## Tuned endpoints

- `GET /api/books`
- `GET /api/books/genres`
- `GET /api/books/recommendations`
- `GET /api/borrow-records`
- `GET /api/books/[id]`

## Changes implemented

### 1) Public catalog filtering and safer pagination

File: `app/api/books/route.ts`

- Enforced active-only catalog reads (`is_active = true`)
- Added `limit` clamp to reduce accidental full-table scans (`1..50`)
- Added `page` sanitization to avoid negative offsets

### 2) Cached genres endpoint

File: `app/api/books/genres/route.ts`

- Added `unstable_cache` for genre list reads
- Added active-only filter for genre extraction
- 5-minute revalidation and `books` tag invalidation support

### 3) Recommendation query tightening

File: `app/api/books/recommendations/route.ts`

- Added `limit` clamp (`1..20`)
- Added deterministic ordering for user reading history (`borrow_date DESC`)
- Reduced secondary fetch size with `remainingSlots` instead of full `limit`

### 4) Borrow records pagination protection

File: `app/api/borrow-records/route.ts`

- Added `limit` clamp (`1..100`)
- Added `page` sanitization

### 5) Active-only single book read

File: `app/api/books/[id]/route.ts`

- Added active-only filter for public detail fetches

## EXPLAIN diagnostics command

Run:

```bash
npm run explain:hot-queries
```

Script:

- `scripts/explain-hot-queries.ts`

What it does:

- Runs `EXPLAIN FORMAT=JSON` for key API query shapes
- Prints parsed query plans and estimated query cost
- Helps validate index usage after schema/index changes

## Complementary index work

Existing index pass (already shipped):

- `migrations/mysql/0003_hot_query_indexes.sql`
- `scripts/add-performance-indexes.ts`

These are designed to support the query predicates and sort patterns used in this phase.
