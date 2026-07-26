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

### Circulation schema migrations

Circulation uses two PostgreSQL login roles and two secret-manager records:

- `circulation_runtime` has `CONNECT`, schema `USAGE`, and only the required
  DML/sequence privileges. It does not own the schema, cannot create/alter/drop
  objects, and cannot write Flyway history.
- `circulation_migrator` owns the circulation schema and is the only login used
  for reviewed DDL. Its secret-manager record contains a credential-free JDBC
  URL plus a distinct username and password. It must not share a remote record
  or password with the runtime role.

The exact role names are database-inventory decisions, but the privilege split
is a release invariant. Database provisioning must audit grants before every
production promotion; Helm and Argo CD cannot prove server-side PostgreSQL
grants.

The migration sequence is fail-closed:

1. A PreSync ExternalSecret materializes only
   `DATABASE_MIGRATION_URL`, `DATABASE_MIGRATION_USERNAME`, and
   `DATABASE_MIGRATION_PASSWORD`.
2. A single-flight PreSync Job runs the exact application image digest with
   `APP_MIGRATION_ONLY=true`. Its ServiceAccount has token automount disabled,
   its egress is DNS plus PostgreSQL only, and it starts no Spring context,
   OIDC client, business bean, probe, port, or HTTP listener.
3. The application validates migration names, applies the bundled Flyway
   migrations, validates the resulting history, emits no credentials, and
   exits. Any missing variable or migration failure blocks the rollout.
4. A later PreSync cleanup Job runs only after migration success. It gets one
   explicitly projected, audience-bound token for at most 15 minutes. RBAC
   permits only `delete` on the named migration ExternalSecret and its named
   target Secret; it cannot read either. It deletes the source first and then
   the target, before runtime resources sync.
5. Runtime Deployments mount only `circulation-runtime` and set
   `SPRING_FLYWAY_ENABLED=false`. The application artifact also defaults
   in-process Flyway to disabled.

The schema-owner secret is therefore present only during the migration hook,
not for the Deployment lifetime. Successful hook Jobs are removed immediately;
failed Jobs have a bounded TTL for diagnosis. If migration fails, Argo/Helm
does not reach the cleanup wave. The incident owner must revoke or rotate the
migrator credential in the database and secret manager before retrying; leaving
a failed migration credential active is a release blocker. Never recover by
mounting the migration secret in the Deployment or enabling runtime Flyway.

The cleanup NetworkPolicy needs the concrete Kubernetes API Service `/32` for
each cluster. `REPLACE_*_KUBERNETES_API_SERVICE_IP/32` is intentionally a
release-blocking value until cluster networking is allocated; broad API-server
or `0.0.0.0/0` egress is forbidden.

Argo CD and Helm limitations are explicit:

- The namespace, External Secrets CRD/controller, ClusterSecretStore, DNS, and
  migration/cleanup egress routes must exist before the first PreSync run.
- ExternalSecret reconciliation is asynchronous. Hook ordering creates the
  ExternalSecret first, while the Job remains pending until its target Secret
  exists; `activeDeadlineSeconds` bounds that wait.
- Argo CD honors the `PreSync` waves. Helm honors the matching
  `pre-install,pre-upgrade` weights. A renderer that strips either annotation
  family is unsupported.
- The cleanup API Service IP cannot be discovered safely by Helm. It must come
  from the reviewed cluster inventory and is checked by the release gate.
- Migrations must use expand/contract changes. Rollback changes the image
  digest; it does not and must not attempt an automatic down-migration.

## Recovery

Infrastructure reconstruction and data recovery are separate exercises.
Terraform/Argo CD should reconstruct a clean cluster. Managed-service backups
must restore to isolated recovery instances, then application invariants and
event positions must be verified before cutover. Production is not ready until
RPO/RTO are demonstrated under a timed game day.
