# API Benchmark CI Hardening Plan

## Goal
Make the API benchmark pipeline reliable, secure, observable, and maintainable while keeping it fast enough for pull requests.

## Standards Checklist

1. Reliability
- [x] Keep server startup, readiness checks, and benchmark execution in the same step.
- [x] Ensure server process cleanup with shell `trap` on exit.
- [x] Add strict shell mode (`set -euo pipefail`) to fail fast and avoid silent errors.
- [x] Skip draft pull requests to avoid noisy CI while the PR is still in progress.

2. Scope and Cost Control
- [x] Trigger on changed paths relevant to API performance.
- [x] Keep manual trigger (`workflow_dispatch`) for on-demand checks.
- [x] Add `push` coverage for `main` so baseline signal exists after merges.

3. Observability
- [x] Keep benchmark markdown in job summary.
- [x] Upload benchmark markdown and JSON artifacts for later analysis.
- [x] Upload server logs on failure for startup/debug diagnostics.

4. Performance Gate Quality
- [x] Enforce route-specific p95 thresholds.
- [x] Increase warmup and iteration defaults to reduce single-run noise.

5. Security and Maintenance
- [ ] Pin workflow actions to immutable commit SHAs.
- [ ] Replace placeholder env values with repository/environment secrets where applicable.
- [ ] Add periodic action-version review cadence.

## Implemented in This Change
- Combined app startup and benchmark run into one step.
- Added cleanup trap for background server process.
- Added strict shell mode for safer execution.
- Added benchmark path filters on pull requests and push to `main`.
- Added draft PR skip condition.
- Added benchmark artifact generation (`/tmp/api-benchmark-summary.md`, `/tmp/api-benchmark-results.json`).
- Added benchmark artifact upload step.
- Kept failure server log upload.

## Follow-up Work
1. Pin `actions/checkout`, `actions/setup-node`, and `actions/upload-artifact` to SHAs.
2. Add a nightly benchmark workflow for long-run trend collection.
3. Store historical benchmark JSON in an external store (artifact retention, object store, or metrics backend).
4. Add optional retry-and-median mode for flaky routes.
