# Mundiapolis production platform foundation

This directory is a reviewable starting point for the production platform. It
does **not** represent a deployed environment and it deliberately contains no
credentials, secret values, cloud account identifiers, DNS names, or image
digests.

The foundation targets managed Kubernetes and keeps application contracts
portable:

- Terraform owns cloud networking, the Kubernetes control plane, encryption,
  workload identity, and cloud-to-cluster interfaces.
- Argo CD owns in-cluster state.
- The `mundia-service` Helm chart provides a hardened deployment contract for
  stateless services.
- External Secrets Operator is the only supported path from a cloud secret
  manager to short-lived Kubernetes Secrets.
- Schema migrations run as isolated, digest-identical PreSync Jobs. Runtime
  pods never receive schema-owner credentials, and a least-privilege cleanup
  hook removes the migration ExternalSecret and target Secret before rollout.
- OpenTelemetry is the telemetry boundary. Applications do not depend on a
  vendor-specific agent.
- Kyverno admission policies and Pod Security Admission reject common unsafe
  workload configurations.
- PostgreSQL, Kafka, Redis, object storage, OpenSearch, and OIDC remain managed
  services outside Kubernetes.

## Why the reference implementation uses AWS

No production cloud has been approved yet. AWS is used here to make the
foundation concrete and reviewable, not to silently make that business
decision. EKS has private control-plane networking, managed node groups, KMS
envelope encryption, and EKS Pod Identity. AWS also has managed equivalents for
every required data service (RDS/Aurora PostgreSQL, MSK, ElastiCache, S3,
OpenSearch Service, and Secrets Manager).

Provider coupling is contained in `terraform/modules/aws-*`. The Helm chart,
Kubernetes policies, GitOps layout, telemetry protocol, secret references, and
the dependency contract are cloud-neutral. A different cloud should implement
the same outputs and security invariants rather than changing service
manifests.

## Repository layout

```text
platform/
├── decisions/                 Architecture decisions and open blockers
├── docs/                      Operations, trust boundaries, and promotion
├── gitops/                    Argo CD bootstrap and environment desired state
├── helm/mundia-service/       Secure stateless-service chart
├── policies/kyverno/          Enforced admission policies
├── scripts/                   Dependency-free validation
└── terraform/
    ├── environments/          Isolated dev, staging, and production roots
    └── modules/               AWS implementation and portable contracts
```

## Trust boundaries

```text
Internet
   │ TLS, WAF/rate limits (not yet selected)
   ▼
managed load balancer / ingress
   │ NetworkPolicy + authenticated application protocol
   ▼
stateless workloads ──OTLP──► OTel gateway ──TLS──► telemetry backend
   │
   ├──TLS──► managed PostgreSQL (authoritative data)
   ├──TLS──► managed Kafka (events)
   ├──TLS──► managed Redis (ephemeral cache/rate limits)
   ├──TLS──► managed OpenSearch (derived discovery index)
   ├──TLS──► managed object storage
   └──TLS──► institutional OIDC issuer/JWKS

External Secrets Operator ──workload identity──► cloud secret manager
Argo CD                 ──read-only deploy key──► Git source
```

Kubernetes nodes and workloads live in private subnets. The Kubernetes API is
private by default. Database, broker, cache, and search endpoints must be
private. Internet egress is denied by workload NetworkPolicies except for
explicit TLS destinations; production should route unavoidable internet egress
through an inspected egress gateway.

## Environment isolation

Dev, staging, and production are separate Terraform state roots and should use
separate cloud accounts. They must not share:

- Kubernetes clusters or node IAM roles;
- encryption keys or secret prefixes;
- databases, brokers, caches, indexes, or object buckets;
- OIDC clients;
- DNS zones used for application traffic;
- telemetry credentials.

Production uses three availability zones, a private Kubernetes API, one NAT
gateway per AZ, deletion protection where supported, at least four circulation
replicas, a PodDisruptionBudget, topology spread, and conservative autoscaling.
The example files do not weaken these invariants to make a first deployment
easier.

## Review and validation

Run the local checks (the Python validator uses the pinned tooling dependency in
`requirements-validation.txt`):

```bash
python3 -m unittest discover -s platform/tests -p 'test_*.py'
platform/scripts/validate.sh
```

The script also runs `terraform fmt`, `helm lint/template`, `kustomize build`,
`kubeconform`, and `kyverno test` when those tools are installed. Missing
optional tools are reported, not concealed. For a release-candidate check:

```bash
platform/scripts/validate.sh --release
```

Release mode intentionally fails while `REPLACE_*`, `.invalid`, example
backends, unapproved image digests, or other decision blockers remain.
It also fails closed unless Terraform or OpenTofu, kubeconform, and the Kyverno
CLI are installed; local mode reports those missing checks without blocking
review of the templates.

## Bootstrap order

1. Approve the decisions listed in
   [`decisions/0001-reference-platform.md`](decisions/0001-reference-platform.md).
2. Create an encrypted, locked Terraform state backend in each cloud account.
3. Replace one environment's `*.tfvars.example` with values from an approved
   inventory; never commit the real file.
4. Apply network and EKS in a controlled infrastructure pipeline with a
   reviewed plan and protected apply.
5. Install Argo CD out of band using its verified upstream artifact, then apply
   only the matching root Application.
6. Allow Argo CD to install the approved platform add-ons and policies before
   application workloads.
7. Provision managed data services through separate reviewed modules/pipelines,
   populate Secrets Manager, and pass only ARNs/endpoints through the dependency
   contract.
8. Promote a digest already verified in dev and staging. Do not rebuild between
   environments.

## What is deliberately not claimed

This tree has not been applied to a cloud account. It has not passed a real
disaster-recovery exercise, penetration test, capacity test, or compliance
assessment. Add-on chart versions, regions, domains, trust roots, image
digests, data-service sizing, backup policies, and identity-provider
configuration are explicit blockers. See
[`docs/PRODUCTION_BLOCKERS.md`](docs/PRODUCTION_BLOCKERS.md).
