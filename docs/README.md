# Documentation Index

This directory is the operating manual for Mundiapolis Library. It is written for maintainers who need to run, change, ship, and support the product in production.

## Start Here

| Document | Use it when |
| --- | --- |
| [Architecture](ARCHITECTURE.md) | You need to understand the system boundaries, request flows, auth model, and runtime decisions. |
| [Configuration](CONFIGURATION.md) | You need to configure local, preview, staging, or production environments. |
| [Development](DEVELOPMENT.md) | You are setting up a workstation or making code changes. |
| [Deployment](DEPLOYMENT.md) | You are deploying to Vercel, Docker, or a standalone Next.js server bundle. |
| [Operations Runbook](OPERATIONS.md) | You are responding to a production issue or performing routine admin operations. |
| [API Reference](API_REFERENCE.md) | You are integrating with or debugging route handlers. |
| [Data Model](DATA_MODEL.md) | You are changing schema, migrations, borrow lifecycle, or admin data. |
| [Testing and CI](TESTING_AND_CI.md) | You are validating a branch, fixing checks, or tuning benchmarks. |
| [Release Process](RELEASE_PROCESS.md) | You are cutting a tag, publishing artifacts, and deploying a version. |
| [Production Readiness](PRODUCTION_READINESS.md) | You are deciding whether the product is ready to ship. |
| [Security Policy](../SECURITY.md) | You are reporting or handling a vulnerability. |
| [Contributing](../CONTRIBUTING.md) | You are opening a PR or maintaining repository standards. |

## Existing Deep-Dive Notes

These documents capture implementation history and performance hardening work. Keep them when they remain accurate, but use the documents above as the main product handbook.

| Document | Subject |
| --- | --- |
| [API Benchmark CI Plan](API_BENCHMARK_CI_PLAN.md) | PR benchmark hardening and artifact strategy. |
| [CI Governance](CI_GOVERNANCE.md) | Branch protection and required checks. |
| [CI Pipelines](CI_PIPELINES.md) | GitHub Actions workflows and security automation. |
| [Backend Migration Feasibility](BACKEND_MIGRATION_FEASIBILITY.md) | Rationale for hardening the Next.js backend before a rewrite. |
| [Phase 2 Query Tuning](PHASE2_QUERY_TUNING.md) | Query-level API performance improvements. |
| [Phase 3 Load and Cache](PHASE3_LOAD_AND_CACHE.md) | Cache invalidation, load testing, and benchmark artifacts. |
| [DB Admin Options](DB_ADMIN_OPTIONS.md) | Local database administration tooling choices. |

## Documentation Standards

- Prefer exact commands that can be copied and run.
- Name the environment where a command is safe: local, CI, staging, or production.
- Document required environment variables before optional integrations.
- Keep security-sensitive values out of docs and examples.
- When behavior changes, update docs in the same PR as the code.
- If a doc describes an endpoint, script, or workflow, include the file path that owns it.

## Product Boundaries

Mundiapolis Library currently owns:

- Student and faculty catalog discovery.
- Borrow request submission and history.
- Renewal requests.
- Book reviews.
- Admin circulation desk.
- Catalog management.
- User and account approvals.
- Fine configuration and overdue processing.
- Reminder automation.
- Admin analytics and exports.

It does not currently own:

- Payment processing for fines.
- External university identity provider integration.
- Barcode inventory reconciliation against a physical shelf system.
- Multi-tenant institution management.
- Public anonymous borrowing.
