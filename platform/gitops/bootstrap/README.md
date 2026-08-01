# GitOps bootstrap boundary

Argo CD itself is installed once from a verified, pinned upstream release by a
protected bootstrap pipeline. Terraform does not manage Argo resources.

After the cluster is reachable from the private deployment runner:

1. create the add-on namespaces;
2. apply the two AppProjects;
3. replace and review every add-on chart version;
4. apply add-on Applications and wait for healthy admission/secret/metrics
   controllers;
5. apply the Kyverno policy Application;
6. apply only the root Application belonging to that cluster;
7. sync the environment platform layer, then its protected migration layer,
   then the workload layer, all at the same reviewed commit and image digest.

The three environment Applications are examples for separate clusters. Do not
apply all three to one cluster. Production has automated self-healing but
disables automated pruning so destructive removal needs an explicit approved
sync.

Each environment has three deliberately separate Applications:

- `mundia-<environment>-platform` owns the runtime namespace, SecretStore,
  limits, and telemetry boundary;
- `mundia-<environment>-migrations` owns a separate migration namespace, its
  schema-owner ExternalSecret, one-shot migration, cleanup RBAC, and cleanup
  Job;
- `mundia-<environment>` owns only runtime workloads and cannot create Jobs,
  RBAC, SecretStores, or cluster-scoped resources.

The migration credential must be revoked or rotated in the database after the
cleanup hook deletes its Kubernetes objects. Promotion is blocked until both
the cluster deletion and database-side revocation are evidenced.

The chart repository versions are intentionally unresolved. Before approval,
record the chart archive digest, rendered manifests, upstream provenance,
container digests, CRD upgrade notes, and compatibility with the approved
Kubernetes version.
