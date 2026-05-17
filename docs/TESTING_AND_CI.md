# Testing And CI

This project uses local scripts and GitHub Actions to prevent regressions before deployment.

## Local Quality Gate

Run the full local gate before opening or merging a production-bound PR:

```bash
npm run ci:quality
```

This runs:

1. `npm run lint`
2. `npm run typecheck`
3. `npm run test`
4. `npm run test:e2e`
5. `npm run build`

## Individual Commands

| Command | Purpose |
| --- | --- |
| `npm run lint` | ESLint with zero-warning policy. |
| `npm run typecheck` | TypeScript compile check without emitted files. |
| `npm run test` | Vitest test suite. |
| `npm run test:e2e` | Playwright smoke and security E2E suite. |
| `npm run build` | Production Next.js build. |
| `npm run benchmark:api` | Key API route benchmark. |
| `npm run loadtest:nightly` | Heavier load test and baseline comparison. |
| `npm run explain:hot-queries` | Query plan diagnostics. |

## Current Test Locations

Tests are colocated near the code they validate.

Examples:

- `lib/utils.test.ts`
- `lib/cache/redis-cache.test.ts`
- `lib/services/apiError.fuzz.test.ts`
- `lib/actions/book.test.ts`
- `lib/actions/renewal.test.ts`
- `lib/admin/actions/borrow.test.ts`
- `lib/admin/actions/recommendations.test.ts`
- `tests/e2e/security.spec.ts`

## What To Test

Prioritize tests for:

- Permission checks.
- Borrow lifecycle transitions.
- Copy count changes.
- Renewal request behavior.
- Fine calculations.
- Cache invalidation helpers.
- API error formatting.
- Input validation.
- Utility functions used by multiple pages or actions.

UI-only layout changes do not always need unit tests, but they should still be manually verified on desktop and mobile.

## GitHub Actions Workflows

| Workflow | File | Purpose |
| --- | --- | --- |
| CI | `.github/workflows/ci.yml` | Lint, typecheck, tests, Playwright E2E, production build, Docker image build on push. |
| API Performance Benchmarks | `.github/workflows/api-benchmarks.yml` | PR and main benchmark gate for key API routes. |
| Nightly API Load Test | `.github/workflows/nightly-load-test.yml` | Scheduled load and regression trend checks. |
| CodeQL | `.github/workflows/codeql.yml` | Static analysis for JavaScript/TypeScript security. |
| Container Security | `.github/workflows/container-security.yml` | Docker image scanning. |
| Dependency Review | `.github/workflows/dependency-review.yml` | Blocks risky dependency changes on PRs. |
| npm Audit | `.github/workflows/npm-audit.yml` | Audits production npm dependencies. |
| Secret Scan | `.github/workflows/secret-scan.yml` | Detects committed secrets. |
| Scorecard | `.github/workflows/scorecard.yml` | Supply-chain posture checks. |
| PR Labeler | `.github/workflows/pr-labeler.yml` | Labels PRs by changed paths. |
| PR Size | `.github/workflows/pr-size.yml` | Adds PR size signal. |
| Stale | `.github/workflows/stale.yml` | Marks inactive issues and PRs. |

More detail:

- [CI Pipelines](CI_PIPELINES.md)
- [CI Governance](CI_GOVERNANCE.md)

## CI Required Checks

Recommended required checks for `main`:

- `CI / Quality (Lint, Typecheck, Test, E2E, Build)`
- `CI / Docker Image Build`
- `API Performance Benchmarks / Benchmark key API routes`

Branch protection helper:

```bash
chmod +x scripts/apply-branch-protection.sh
./scripts/apply-branch-protection.sh
```

Requires repository admin permission.

## API Benchmarking

Script:

```bash
npm run benchmark:api
```

Measured routes:

- `/api/books?limit=12&sort=rating&page=1`
- `/api/books/genres`
- `/api/books/recommendations?limit=10`
- `/api/books/[id]`

Default thresholds:

| Metric | Default |
| --- | ---: |
| Books list P95 | 500 ms |
| Genres P95 | 350 ms |
| Recommendations P95 | 550 ms |
| Book details P95 | 450 ms |

CI uses a PostgreSQL service, applies schema, seeds data, builds the app, starts `next start`, waits for readiness, then runs the benchmark script.

### Running Benchmarks Locally

Terminal 1:

```bash
npm run build
npm run start
```

Terminal 2:

```bash
BENCH_BASE_URL=http://127.0.0.1:3000 npm run benchmark:api
```

Increase stability:

```bash
BENCH_WARMUP=5 BENCH_ITERATIONS=20 BENCH_ROUNDS=3 npm run benchmark:api
```

## Nightly Load Test

Script:

```bash
npm run loadtest:nightly
```

Baseline:

- `perf-baselines/nightly-load-baseline.json`

Artifacts:

- `artifacts/perf/nightly-load-report.json`
- `artifacts/perf/nightly-load-report.md`

The nightly script compares route P95 values against absolute thresholds and baseline regression allowances.

## Query Plan Diagnostics

Run:

```bash
npm run explain:hot-queries
```

Artifacts:

- `artifacts/perf/query-plan-index-report.json`
- `artifacts/perf/query-plan-index-report.md`

Use this when:

- `/api/books` slows down.
- Filters or sort options change.
- Indexes are added or removed.
- Database cardinality changes significantly.

## Docker Build Validation

CI builds the production image on push. Local validation:

```bash
docker build -t mundia-library:ci .
```

For full Compose stack:

```bash
docker compose up --build
```

## Security Checks

Security automation includes:

- CodeQL
- Gitleaks or secret scan workflow
- Dependency Review
- Dependabot
- npm audit
- Trivy container scan
- OpenSSF Scorecard

Do not bypass these checks for production-bound changes without documenting the risk and follow-up owner.

The current moderate production audit finding is the nested
`next/node_modules/postcss <8.5.10` advisory. The top-level PostCSS package is
kept patched, but Next.js still vendors its own PostCSS copy. The npm audit
workflow blocks critical advisories and reports moderate advisories until the
upstream Next.js dependency can be upgraded safely or overridden without
breaking installs.

## Fixing A Failing CI Run

1. Identify the failing workflow and job name.
2. Read the first real error, not only the final exit code.
3. Reproduce locally with the same command when possible.
4. Check whether docs-only changes skipped expected workflows.
5. For benchmark failures, inspect server logs and benchmark artifacts.
6. For Docker failures, build locally with `docker build`.
7. For dependency review, inspect the advisory and decide patch, pin, or documented exception.

## Benchmark Failure Triage

If `API Performance Benchmarks / Benchmark key API routes` fails:

1. Open the benchmark artifact.
2. Identify which route exceeded P95 threshold.
3. Check server log artifact for startup or runtime errors.
4. Confirm seed data was applied.
5. Run the benchmark locally with the same environment values.
6. Run query-plan diagnostics for affected routes.
7. Decide whether the regression is real, threshold noise, or environment failure.

Real regressions should be fixed before merge. Noisy thresholds should be adjusted only with evidence.

## Release Validation

Minimum release gate:

```bash
npm run ci:quality
```

For API, database, cache, or performance-sensitive releases:

```bash
npm run benchmark:api
```

For operationally risky releases:

```bash
npm run loadtest:nightly
```

For database-heavy releases:

```bash
npm run explain:hot-queries
```

## Test Coverage Gaps To Close

Important gaps to prioritize:

- End-to-end auth flow.
- Admin approval and return flows.
- Borrow copy-count invariants.
- Review eligibility.
- Renewal duplicate prevention.
- Fine calculation idempotency.
- API route authorization.
- Email workflow dry-run behavior.
- Visual regression screenshots for the main student and admin pages.
