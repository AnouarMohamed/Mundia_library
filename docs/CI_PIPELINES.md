# CI Pipelines

This repository uses a multi-workflow GitHub Actions setup designed for reliability, security, and fast feedback.

## Goals

- Block regressions before merge
- Enforce consistent code quality
- Detect vulnerable dependency changes
- Run continuous static security analysis
- Keep dependencies, GitHub Actions, and Docker base images fresh
- Surface supply-chain posture issues before they become production risk
- Keep pull requests organized with labels, size signals, and code ownership
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

Config: `.github/codeql/codeql-config.yml`

Triggers:

- Push to `main`
- Pull requests targeting `main`
- Scheduled weekly scan (Mondays)
- Manual dispatch

Purpose:

- Detect static analysis security issues in JavaScript/TypeScript code
- Run the default CodeQL suites plus `security-extended` and `security-and-quality`

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

### 4) Dependabot

File: `.github/dependabot.yml`

Schedule:

- Weekly npm dependency update PRs
- Weekly GitHub Actions update PRs
- Weekly Docker base image update PRs

Purpose:

- Keep runtime dependencies, development tooling, actions, and container bases current
- Group minor/patch npm updates to reduce PR noise
- Ignore npm semver-major version bumps by default so major upgrades happen deliberately
- Label dependency PRs for easy triage

### 5) PR Labeler

Files:

- `.github/workflows/pr-labeler.yml`
- `.github/labeler.yml`

Triggers:

- Pull request opened, updated, reopened, or marked ready for review

Purpose:

- Add area labels such as `frontend`, `backend`, `database`, `security`, `dependencies`, `performance`, and `docs`
- Keep labels synced as a pull request changes

### 6) PR Size Labeler

File: `.github/workflows/pr-size.yml`

Triggers:

- Pull request opened, updated, reopened, or marked ready for review

Purpose:

- Add `size/XS` through `size/XL` labels based on changed lines
- Help reviewers spot risky large changes before merge

### 7) Stale Issue and PR Bot

File: `.github/workflows/stale.yml`

Triggers:

- Daily schedule
- Manual dispatch

Purpose:

- Mark inactive issues and pull requests as `stale`
- Close stale work after a grace period
- Exempt `security`, `blocked`, and `pinned` work from cleanup

### 8) Repository Label Sync

File: `.github/workflows/repo-labels.yml`

Triggers:

- Manual dispatch
- Push to `main` when label automation changes

Purpose:

- Create and update the standard labels used by the repo bots
- Keep label names, colors, and descriptions consistent

### 9) Code Owners

File: `.github/CODEOWNERS`

Purpose:

- Define default repository ownership
- Allow branch protection to request owner review automatically

### 10) Secret Scan

File: `.github/workflows/secret-scan.yml`

Triggers:

- Pull requests targeting `main`
- Push to `main`

Purpose:

- Detect accidentally committed credentials and tokens

Required checks recommendation:

- `Detect leaked secrets`

### 11) NPM Security Audit

File: `.github/workflows/npm-audit.yml`

Triggers:

- Pull requests changing `package.json` or `package-lock.json`
- Pushes to `main` changing dependency manifests
- Scheduled weekly audit
- Manual dispatch

Purpose:

- Fail on critical production dependency advisories
- Upload a full JSON advisory report for high/moderate findings that need planned remediation

Required checks recommendation:

- `Audit production dependencies`

### 12) Container Security Scan

File: `.github/workflows/container-security.yml`

Triggers:

- Pull requests changing Docker/app dependency inputs
- Pushes to `main` changing Docker/app dependency inputs
- Scheduled weekly image scan
- Manual dispatch

Purpose:

- Build the production Docker image
- Scan OS and library packages with Trivy
- Upload SARIF results to GitHub code scanning
- Block critical fixed image vulnerabilities

Required checks recommendation:

- `Scan Docker image with Trivy`

### 13) OpenSSF Scorecard

File: `.github/workflows/scorecard.yml`

Triggers:

- Pushes to `main`
- Branch protection changes
- Scheduled weekly run
- Manual dispatch

Purpose:

- Track repository supply-chain posture
- Upload SARIF results to GitHub code scanning

### 14) API Performance Benchmarks

File: `.github/workflows/api-benchmarks.yml`

Triggers:

- Pull requests targeting `main`
- Manual dispatch

Purpose:

- Run repeatable latency benchmarks for key API routes
- Fail CI when p95 exceeds configured thresholds

Dependabot note:

- Skipped for Dependabot PRs to avoid running expensive benchmark suites on routine dependency bumps

Measured routes:

- `/api/books`
- `/api/books/genres`
- `/api/books/recommendations`
- `/api/books/[id]`

Required checks recommendation:

- `Benchmark key API routes`

### 15) Nightly API Load Test

File: `.github/workflows/nightly-load-test.yml`

Triggers:

- Scheduled nightly run
- Manual dispatch

Purpose:

- Run higher-concurrency API load checks against seeded data
- Compare p95 latency against baseline + regression tolerance
- Publish JSON and Markdown trend artifacts

Artifacts:

- `artifacts/perf/nightly-load-report.json`
- `artifacts/perf/nightly-load-report.md`

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
- `Audit production dependencies`
- `Scan Docker image with Trivy`
- `Benchmark key API routes`

## Notes

- CI build uses placeholder environment values so build-time validations still run without production secrets.
- Runtime integrations (email providers, external APIs, Upstash) are not called in these workflows.
- `npm-audit.yml` currently blocks only critical advisories so existing high-severity dependency work can be remediated deliberately without freezing all PRs.
- Dependabot should be enabled in the GitHub repository settings for security updates and version update PRs.
- If you later add automated tests, add a dedicated `test` job in `.github/workflows/ci.yml` and make it a required check.
