# CI Pipelines

This repository uses a multi-workflow GitHub Actions setup designed for reliability, security, and fast feedback.

## Goals

- Block regressions before merge
- Enforce consistent code quality
- Detect vulnerable dependency changes
- Run continuous static security analysis
- Validate Docker build health continuously

## Workflows

### 1) Continuous Integration

File: `.github/workflows/ci.yml`

Triggers:

- Push to `main`, `develop`, and `release/**`
- Every pull request

Jobs:

- `quality`
  - Installs dependencies with lockfile
  - Runs lint
  - Runs strict TypeScript typecheck
  - Runs production build with CI-safe environment values
- `docker`
  - Builds the production Docker image
  - Uses Buildx and GitHub Actions cache for speed

Required checks recommendation:

- `Quality (Lint, Typecheck, Build)`
- `Docker Image Build`

### 2) CodeQL Security Scan

File: `.github/workflows/codeql.yml`

Triggers:

- Push to `main`
- Pull requests targeting `main`
- Scheduled weekly scan (Mondays)

Purpose:

- Detect static analysis security issues in JavaScript/TypeScript code

Required checks recommendation:

- `Analyze (JavaScript/TypeScript)`

### 3) Dependency Review

File: `.github/workflows/dependency-review.yml`

Triggers:

- Pull requests targeting `main`

Purpose:

- Block pull requests that introduce high-severity vulnerable dependencies
- Block disallowed strong-copyleft licenses in new dependency changes

Required checks recommendation:

- `Check dependency changes`

### 4) Secret Scan

File: `.github/workflows/secret-scan.yml`

Triggers:

- Pull requests targeting `main`
- Push to `main`

Purpose:

- Detect accidentally committed credentials and tokens

Required checks recommendation:

- `Detect leaked secrets`

### 5) API Performance Benchmarks

File: `.github/workflows/api-benchmarks.yml`

Triggers:

- Pull requests targeting `main`
- Manual dispatch

Purpose:

- Run repeatable latency benchmarks for key API routes
- Fail CI when p95 exceeds configured thresholds

Measured routes:

- `/api/books`
- `/api/books/genres`
- `/api/books/recommendations`
- `/api/books/[id]`

Required checks recommendation:

- `Benchmark key API routes`

## Pull Request Governance

File: `.github/pull_request_template.md`

Purpose:

- Enforce consistent PR quality
- Require validation steps and performance impact notes
- Improve deployment safety with migration/rollback checklist

## Local parity commands

Run these commands before pushing if you want parity with CI quality checks:

```bash
npm ci
npm run lint
npm run typecheck
npm run build
```

## Branch protection baseline

For a strong production policy on `main`:

- Require pull request before merge
- Require status checks to pass before merge
- Require up-to-date branches before merge
- Require at least one approval
- Require conversation resolution before merge
- Include administrators in restrictions

Suggested required checks:

- `Quality (Lint, Typecheck, Build)`
- `Docker Image Build`
- `Analyze (JavaScript/TypeScript)`
- `Check dependency changes`
- `Detect leaked secrets`
- `Benchmark key API routes`

## Notes

- CI build uses placeholder environment values so build-time validations still run without production secrets.
- Runtime integrations (email providers, external APIs, Upstash) are not called in these workflows.
- If you later add automated tests, add a dedicated `test` job in `.github/workflows/ci.yml` and make it a required check.
