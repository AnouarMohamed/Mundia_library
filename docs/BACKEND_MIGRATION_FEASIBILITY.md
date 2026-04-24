# Backend Migration Feasibility: Next.js API Hardening vs Spring Boot Rewrite

Date: 2026-04-24

## Executive recommendation

Do not start with a full Spring Boot rewrite. Prioritize targeted hardening and performance work in the existing Next.js API layer for 6-8 weeks, then re-evaluate with measured latency and throughput data.

The current performance profile is more likely constrained by query/index design, cache coverage, and route-level work amplification than by Node.js runtime throughput.

## Current architecture constraints

- Next.js App Router with Node.js runtime for API routes
- Drizzle ORM on MySQL
- Significant query load in these route families:
  - Books listing/filter/search and recommendations
  - Borrow records filtering and history joins
  - Admin analytics and export aggregations

## Options compared

### Option A: Next.js API hardening (recommended first)

Scope:

- Index optimization for hot predicates and sort paths
- Query tuning and payload minimization
- Cache strategy expansion for stable read paths
- Benchmark gate in CI for key routes
- SLO tracking and regression alerts

Estimated effort:

- 2-4 engineer weeks initial pass
- +1-2 weeks validation and follow-up tuning

Risk:

- Low to medium
- Minimal product disruption
- Keeps deployment and auth model stable

Expected performance impact:

- p95 API latency improvement: 25-55% on hot read routes
- DB CPU reduction: 20-40% for optimized query families
- Time-to-first-byte improvements on catalog pages through better API response times

### Option B: Spring Boot rewrite (full backend split)

Scope:

- New service architecture, authentication integration, API contract migration
- Data-access layer rewrite and parallel operation
- Gradual client migration to external backend service

Estimated effort:

- 10-18 engineer weeks for parity + stabilization
- Additional 4-8 weeks for production hardening and migration edge cases

Risk:

- High
- Contract drift and feature parity risk
- Increased operational complexity (two stacks, two deploy paths)

Expected performance impact:

- p95 API latency improvement: 10-35% if query patterns remain unchanged
- Higher upside only if rewrite also includes aggressive DB/query redesign
- Better JVM throughput under sustained heavy concurrency, but not a guaranteed fix for current bottlenecks

## Projection summary

| Dimension | Next.js Hardening | Spring Boot Rewrite |
| --- | --- | --- |
| Delivery speed | Fast | Slow |
| Implementation risk | Low/Medium | High |
| Operational complexity | Low | High |
| Near-term latency gains | High | Medium |
| Team/context switching cost | Low | High |
| Rollback complexity | Low | High |

## Decision gate after hardening

Re-evaluate migration only if all conditions remain true after hardening:

- p95 for key routes still violates SLO targets under realistic load
- DB plans are already optimized and indexed
- Cache hit rates are acceptable but latency remains high
- Throughput bottleneck is clearly at application runtime concurrency (not DB)

## Suggested roadmap

1. Week 1-2: implement index pass and benchmark CI gate.
2. Week 2-4: tune top 5 slowest queries (EXPLAIN ANALYZE driven).
3. Week 4-6: add route-level caching and payload trimming for heavy reads.
4. Week 6-8: run load tests and compare against explicit SLOs.
5. Week 8: make go/no-go decision on Spring split based on measured data.

## Exit criteria for considering Spring Boot

Proceed with Spring Boot only if measured data shows runtime-layer constraints after DB and caching optimization are exhausted. If not, continue with incremental hardening in the current architecture.
