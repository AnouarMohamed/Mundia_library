# Admission policy

Pod Security Admission enforces the upstream `restricted` profile in product
namespaces. Kyverno adds product-specific controls for resources/health probes,
immutable signed images, direct service exposure, and TLS ingress.

Policies match only namespaces carrying the `mundiapolis.io/environment` label
so cluster add-ons can follow their own reviewed security profiles. There are
no committed policy exceptions. A required exception must be:

1. limited to a named workload, namespace, rule, and short expiry;
2. approved by platform and security;
3. tracked as a risk with a remediation owner;
4. applied from a protected incident repository and removed automatically.

The image-verification policy is intentionally blocked on the production
registry and signing identity. It must never be deleted to get an initial
deployment through.

