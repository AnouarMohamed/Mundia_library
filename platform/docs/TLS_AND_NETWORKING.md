# TLS, ingress, and egress

The circulation service is internal by default. The public web/BFF tier should
be the only caller exposed through ingress; enabling an ingress for any service
requires a hostname, TLS secret, certificate issuer, and HTTPS redirect.
Kyverno rejects plaintext Ingress resources and direct LoadBalancer/NodePort
Services in product namespaces.

The reference add-on defines ingress-nginx with TLS 1.2/1.3 and ModSecurity CRS,
but the edge decision remains open. Before use, validate the exact chart and
controller image, choose an AWS load-balancer/WAF topology, constrain trusted
proxy headers, tune request/body/time limits, and run false-positive tests
against real product traffic.

## Certificate contract

cert-manager is installed without a `ClusterIssuer`. Security must approve one
of:

- DNS-01 with an organization-owned public CA and a narrowly scoped DNS
  workload identity; or
- an institutional private CA for internal-only names.

Issuer credentials use workload identity or External Secrets, never literal
Git secrets. Renewal, alerting, and revocation must be exercised before launch.

## Egress contract

NetworkPolicies default each workload to only:

- cluster DNS;
- the namespace-local OTel gateway;
- explicitly configured private CIDRs and dependency ports.

CIDR policies are not identity. Database/broker/cache/search security groups
must admit only the approved workload/node security groups, and service
credentials remain least privilege. A production internet dependency such as a
public institutional JWKS URL requires an approved egress gateway/proxy,
destination allowlisting, TLS verification, observability, and a documented
failure mode. Do not solve it by adding `0.0.0.0/0`.

