# Phase 3 Load Testing and Cache Invalidation

Date: 2026-04-24

This phase adds three capabilities:

- Route-level caching for high-read API routes
- Explicit cache invalidation hooks on mutation paths
- Nightly load testing with regression threshold artifacts

## Route-level caching

Updated routes:

- `app/api/books/route.ts`
- `app/api/books/recommendations/route.ts`

Behavior:

- `/api/books` now uses `unstable_cache` with 60s revalidation and `books` tag.
- `/api/books/recommendations` now caches:
  - personalized recommendation computation (`books`, `recommendations` tags)
  - fallback top-rated recommendations (`books`, `recommendations` tags)

## Invalidation points

New helper:

- `lib/cache/revalidate.ts`

Integrated into mutation flows:

- `lib/admin/actions/book.ts`
- `lib/admin/actions/borrow.ts`
- `lib/actions/book.ts`
- `app/api/admin/refresh-recommendation-cache/route.ts`

Strategy:

- Revalidate `books` tag for catalog updates.
- Revalidate `recommendations` tag for recommendation-affecting operations.

## Query-plan index hit report

Enhanced script:

- `scripts/explain-hot-queries.ts`

Command:

```bash
npm run explain:hot-queries
```

Artifacts generated:

- `artifacts/perf/query-plan-index-report.json`
- `artifacts/perf/query-plan-index-report.md`

## Nightly load testing

Workflow:

- `.github/workflows/nightly-load-test.yml`

Command (local/manual):

```bash
npm run loadtest:nightly
```

Baseline config:

- `perf-baselines/nightly-load-baseline.json`

Artifacts generated:

- `artifacts/perf/nightly-load-report.json`
- `artifacts/perf/nightly-load-report.md`

Regression policy:

- Fails when route p95 exceeds:
  - absolute route threshold, or
  - baseline p95 + configured regression tolerance (default 25%)