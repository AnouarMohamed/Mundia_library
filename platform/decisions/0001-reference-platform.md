# ADR 0001: managed Kubernetes reference platform

- Status: proposed
- Date: 2026-07-26
- Decision owners: platform, security, SRE, application

## Context

The product needs independently deployable services, strong isolation, safe
horizontal scaling, managed stateful dependencies, and reproducible
environments. The organization has not supplied a cloud, residency region,
institutional OIDC provider, DNS zone, traffic forecast, or compliance scope.

## Proposed decision

Use a managed Kubernetes service as the stateless compute plane and managed
services for all durable data. The concrete reference implementation is:

- Amazon EKS in private subnets across three availability zones;
- private EKS API access in staging and production;
- EKS Pod Identity for AWS API access, never static IAM keys;
- KMS envelope encryption for Kubernetes secret objects;
- separate AWS accounts and Terraform state per environment;
- Argo CD pull-based reconciliation;
- External Secrets Operator backed by Secrets Manager;
- ingress-nginx plus cert-manager as replaceable TLS ingress components;
- OpenTelemetry Collector as the telemetry boundary;
- Kyverno plus Pod Security Admission for preventive controls;
- managed PostgreSQL, MSK, ElastiCache, S3, OpenSearch, and an external OIDC
  provider.

## Consequences

Application manifests and protocols remain portable. AWS-specific code is
isolated to Terraform modules and workload-identity annotations/associations.
The platform requires experienced Kubernetes and AWS operators, an add-on
upgrade process, capacity planning, and a tested regional recovery strategy.

Kubernetes is not used to operate databases, brokers, caches, or search
clusters. Microservice boundaries do not imply a shared database: every service
receives a distinct database identity and migration owner.

## Alternatives considered

### Serverless containers

Operationally simpler, but less suitable for the requested GitOps and
policy-as-code model and more provider-specific across service networking,
autoscaling, identity, and observability.

### Self-managed Kubernetes

Rejected because managing control-plane availability and etcd would add risk
without product differentiation.

### Stateful services inside Kubernetes

Rejected for the initial production design. Managed services reduce the
operational burden for backups, failover, patching, and recovery, though their
configuration still requires explicit verification.

## Approval gates

This ADR remains proposed until owners approve:

1. cloud and primary/recovery regions;
2. data residency and regulatory scope;
3. separate account and state-backend model;
4. ingress/WAF and controlled-egress products;
5. OIDC provider, claims, audiences, and break-glass process;
6. availability, latency, RPO/RTO, and cost targets;
7. data-service products and recovery topology;
8. Git source, artifact registries, signing authority, and SBOM retention.

