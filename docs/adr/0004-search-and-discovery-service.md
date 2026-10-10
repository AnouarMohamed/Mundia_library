# ADR 0004: Search and Discovery service boundary

- Status: Accepted; implementation delegated and not started
- Date: 2026-10-10
- Decision owners: product, application, platform, security, SRE

## Context

Catalog currently provides bounded PostgreSQL search during the strangler
migration. The target architecture calls for an OpenSearch read model, but the
repository did not define one authoritative implementation boundary. Keeping
final search inside Catalog would couple expensive relevance reads and
reindexing to authoritative catalog transactions.

## Decision

Reserve a separate Kotlin/Spring Boot **Search and Discovery service**, named
`discovery-service`. It owns disposable search projections, OpenSearch
mappings, query behavior, relevance, facets, suggestions, reindex control, and
search telemetry.

Catalog remains authoritative for works, editions, contributors, reviews, and
learning-resource metadata. Circulation remains authoritative for physical
availability. Digital Content remains authoritative for rights and delivery
eligibility. Discovery receives privacy-minimized versioned events or bounded
verified snapshots. It never reads another service's database and never owns a
source domain aggregate.

Version one is non-personalized. It must not ingest member identifiers, raw
loan history, identity evidence, email addresses, private object locations, or
search history tied to a person. Recommendations require a later privacy and
design review.

The delegated build contract is
[Search and Discovery service handoff](../SEARCH_DISCOVERY_SERVICE.md).

## Consequences

- Search can scale and fail independently of authoritative writes.
- OpenSearch can be destroyed and rebuilt without losing business data.
- Catalog keeps its current search until measured shadow and rollback gates
  pass; there is no big-bang replacement.
- Producer contracts need privacy-minimized discovery events and snapshot
  exports before this service can become authoritative.
- The boundary adds real operational ownership: OpenSearch cost/capacity,
  consumer lag, index lifecycle, reindexing, and incident response.

## Rejected alternatives

### Keep final search in Catalog

Rejected because search and reindexing have a different scale and failure
profile from catalog writes.

### Query service databases

Rejected because it breaks ownership and turns a disposable view into a hidden
integration database.

### Consume the mixed Circulation topic directly

Rejected because that topic contains member-linked loan events. Discovery
needs only aggregate availability and must receive a separate privacy-minimal
event rather than gaining access to unrelated personal data.

### Personalize recommendations in version one

Rejected because behavioral profiling requires consent, deletion propagation,
explainability, sensitive-interest analysis, and a separate approval.
