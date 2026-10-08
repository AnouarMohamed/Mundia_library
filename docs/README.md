# Documentation

This directory is the operating manual for Mundiapolis Library. Read it as a
description of two coexisting states: the current Next.js/Vercel production
shell and the Kotlin microservice platform being cut over route by route.

## Choose your path

| Goal | Start here | Then read |
| --- | --- | --- |
| Understand the system | [Architecture](ARCHITECTURE.md) | [Data Model](DATA_MODEL.md), [ADRs](adr/) |
| Make a code change | [Development](DEVELOPMENT.md) | [Testing and CI](TESTING_AND_CI.md), [API Reference](API_REFERENCE.md) |
| Configure an environment | [Configuration](CONFIGURATION.md) | [OIDC Identity](OIDC_IDENTITY.md) |
| Deploy or release | [Deployment](DEPLOYMENT.md) | [Release Process](RELEASE_PROCESS.md), [Platform](../platform/README.md) |
| Operate production | [Operations](OPERATIONS.md) | [Production Readiness](PRODUCTION_READINESS.md) |
| Review security | [Threat Model](THREAT_MODEL.md) | [Security Verification](SECURITY_VERIFICATION.md), [Security Policy](../SECURITY.md) |
| Change the mobile UI | [Mobile Product Gate](MOBILE_PRODUCT_GATE.md) | [Web SPA](../web-spa/README.md) |
| Continue the migration | [Production Overhaul](PRODUCTION_OVERHAUL.md) | [Web Migration](WEB_MIGRATION.md), [Phase 6 Decommissioning](PHASE6_DECOMMISSIONING.md) |

## Canonical references

| Document | Scope |
| --- | --- |
| [Architecture](ARCHITECTURE.md) | Runtime boundaries, trust model, service ownership, events, and cutover rules |
| [API Reference](API_REFERENCE.md) | Legacy route map plus Kotlin OpenAPI and browser-BFF entry points |
| [Configuration](CONFIGURATION.md) | Required and optional environment contracts; no secret values |
| [Data Model](DATA_MODEL.md) | Legacy schema and domain invariants |
| [Development](DEVELOPMENT.md) | Toolchains, local setup, and change workflow |
| [Testing and CI](TESTING_AND_CI.md) | Make targets, tests, hosted gates, and failure diagnosis |
| [Deployment](DEPLOYMENT.md) | Vercel, OCI images, GHCR, Kubernetes, and promotion |
| [Operations](OPERATIONS.md) | Health checks, incidents, backups, and administrative operations |
| [Production Readiness](PRODUCTION_READINESS.md) | Evidence required before a production claim |
| [Release Process](RELEASE_PROCESS.md) | Versioning, artifacts, and release controls |

Service-specific contracts live beside their code:

- [Kotlin services](../services/README.md)
- [Web BFF](../services/web-bff/README.md)
- [Static SPA](../web-spa/README.md)
- [AWS/Kubernetes platform](../platform/README.md)
- Each service's immutable OpenAPI JSON under
  `services/*/src/main/resources/static/openapi/`

## Supporting design and history

These remain useful but do not override the canonical documents above:

- [CI Governance](CI_GOVERNANCE.md) and [CI Pipelines](CI_PIPELINES.md)
- [API Benchmark CI Plan](API_BENCHMARK_CI_PLAN.md)
- [Backend Migration Feasibility](BACKEND_MIGRATION_FEASIBILITY.md)
- [Query Tuning](PHASE2_QUERY_TUNING.md) and [Load/Cache](PHASE3_LOAD_AND_CACHE.md)
- [Database Administration Options](DB_ADMIN_OPTIONS.md)

## Documentation rules

- State whether a capability is live, implemented behind a gate, or planned.
- Prefer exact commands and name the environment where they are safe.
- Keep secrets, personal data, account identifiers, and real tokens out of
  examples.
- Link endpoints to their owning OpenAPI contract or source file.
- Update architecture, configuration, API, operations, and testing guidance in
  the same change when a boundary moves.
- Do not claim AWS, Kubernetes, OIDC, disaster recovery, or penetration testing
  is production-ready until the corresponding evidence exists.

## Product boundary

The product owns catalog discovery, learning-resource provenance, physical
circulation, member lifecycle, reviews, notifications, and staff operations. It
does not currently own payment processing, the university identity provider,
physical shelf/barcode reconciliation, or multi-tenant institution management.
