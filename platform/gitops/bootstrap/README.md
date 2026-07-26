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
6. apply only the root Application belonging to that cluster.

The three environment Applications are examples for separate clusters. Do not
apply all three to one cluster. Production has automated self-healing but
disables automated pruning so destructive removal needs an explicit approved
sync.

The chart repository versions are intentionally unresolved. Before approval,
record the chart archive digest, rendered manifests, upstream provenance,
container digests, CRD upgrade notes, and compatibility with the approved
Kubernetes version.

