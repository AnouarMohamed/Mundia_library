# Platform operating model

## Ownership boundaries

Terraform owns cloud resources and IAM. Argo CD owns Kubernetes resources.
Neither tool may manage the same object. Application teams own chart values,
health endpoints, resource profiles, and service SLOs. Platform owns the chart
contract, clusters, add-ons, admission policies, and ingress. SRE owns alerts,
capacity evidence, recovery exercises, and incident coordination. Security
approves identity, secret access, image trust, network boundaries, and policy
exceptions.

## Change flow

1. Build once, scan, generate an SBOM, sign, and publish an immutable image.
2. Promote only its digest by pull request.
3. Static policy, schema, Terraform plan, and chart-render checks run before
   approval.
4. Argo CD reconciles dev, then staging, then production after environment
   evidence is attached.
5. Production rolls out gradually with readiness, availability, error-rate,
   latency, and saturation checks.
6. Roll back by reverting the digest. Database changes must be expand/contract
   and remain backward compatible throughout the rollback window.

Direct `kubectl apply`, mutable image tags, manual secret edits, and console IAM
changes are incident-only actions. Every break-glass action needs an expiring
identity, audit trail, peer notification, and follow-up reconciliation.

## Availability controls

- Three availability zones in production.
- Topology-spread constraints and anti-affinity distribute replicas.
- PodDisruptionBudgets preserve quorum during voluntary disruptions.
- HPA scales on CPU and memory; production should add request/latency metrics
  only after those metrics are proven stable.
- Node groups span zones and autoscale separately from pods.
- Readiness includes required dependencies; liveness remains process-only.
- Graceful termination is longer than the ingress drain interval.

HPA does not fix database or broker bottlenecks. Per-pod connection pools,
cluster replica ceilings, PostgreSQL connection limits, and Kafka partitions
must be capacity-tested as one system.

## Secret lifecycle

Secret values originate in the approved cloud secret manager. Git contains
only remote keys. External Secrets Operator authenticates with workload
identity and materializes namespace-scoped Secrets. Applications consume them
through `envFrom` or projected volumes and never call the secret manager unless
that access is explicitly justified.

Rotation requires overlapping credentials when the dependency supports it,
automatic workload restart/reload, verification, old-credential revocation,
and an audit event. Kubernetes etcd encryption is defense in depth; it does not
turn Kubernetes Secrets into the source of truth.

## Recovery

Infrastructure reconstruction and data recovery are separate exercises.
Terraform/Argo CD should reconstruct a clean cluster. Managed-service backups
must restore to isolated recovery instances, then application invariants and
event positions must be verified before cutover. Production is not ready until
RPO/RTO are demonstrated under a timed game day.

