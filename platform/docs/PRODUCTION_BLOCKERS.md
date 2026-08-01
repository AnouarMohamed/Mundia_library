# Production decision and evidence blockers

Nothing in this file should be replaced with an optimistic assumption. Each
item needs an owner, evidence, and approval before a production apply.

| Blocker | Required evidence | Owner |
| --- | --- | --- |
| Cloud accounts and regions | approved account inventory, residency review, SCP/baseline controls | security/platform |
| Terraform state | encrypted versioned bucket, lock table, restricted plan/apply roles, recovery test | platform |
| Network ranges | non-overlapping CIDRs, IPAM allocation, private DNS and egress design | network/platform |
| Public edge | selected WAF/load balancer/ingress, DDoS controls, trusted proxy chain, rate limits | security/platform |
| DNS and certificates | owned zones, certificate issuers, renewal and revocation exercise | platform |
| OIDC | issuer/JWKS, client registrations, exact audiences/scopes, MFA/admin policy, break glass | identity/security |
| Container provenance | immutable registry, approved digests, signatures, SBOMs, admission trust roots | release/security |
| PostgreSQL | product/tier, Multi-AZ, TLS trust, per-service roles, PITR, restore test, connection budget | data/SRE |
| Kafka | product/tier, private TLS/auth, partitions, retention, quotas, schema registry, replay exercise | data/SRE |
| Redis | product/tier, TLS/auth, failover, eviction policy, cache-loss behavior | data/SRE |
| Object storage | buckets, KMS keys, versioning, lifecycle, malware/quarantine workflow | security/platform |
| OpenSearch | private domain, TLS/auth, capacity, index lifecycle, rebuild procedure | search/SRE |
| External Secrets | approved chart and digest, exact secret path/target admission policy, namespace-isolated migration credential, cleanup plus database-side rotation/revocation test | security/platform/data |
| Telemetry | backend, TLS/auth, PII filtering, retention, sampling, alert ownership | SRE/security |
| Add-ons | verified versions/digests for Argo CD, CNI, DNS, CSI, ingress, cert-manager, OTel, Kyverno | platform |
| Capacity | measured workload model, pod requests/limits, DB pools, broker partitions, 2×/5× tests | performance/SRE |
| Recovery | cross-region design, RPO/RTO runbooks, successful restore/failover game day | SRE/data |
| Security | ASVS verification, threat-model signoff, external penetration test and remediation | security |
| Operations | 24/7 ownership, alerts, escalation, change windows, rollback and incident exercises | SRE/product |

## Non-negotiable release evidence

- All images are referenced by approved `sha256` digests and have verified
  signatures and SBOM attestations.
- `validate.sh --release`, Terraform plan policy checks, Helm tests, admission
  policy tests, and Kubernetes schema validation pass.
- A canary rollout and rollback have been exercised using the exact artifact.
- No workload runs privileged, as root, with writable root filesystems, with
  host namespaces, or with unbounded CPU/memory.
- No committed Kubernetes Secret contains `data` or `stringData`.
- Production has tested backups and a timed restore, not merely enabled
  backups.
- SLO alerts and dashboards page an accountable team before public traffic.
