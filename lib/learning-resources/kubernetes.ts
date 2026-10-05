import { createHash } from "crypto";
import { getVerifiedLicenseUrl } from "./license-policy";
import type { LearningResourceCandidate } from "./types";

export const KUBERNETES_SOURCE_NAME = "Kubernetes Documentation";
export const KUBERNETES_LICENSE_EVIDENCE_URL =
  "https://raw.githubusercontent.com/kubernetes/website/main/LICENSE";
const DOCS_ORIGIN = "https://kubernetes.io";
const MAX_RESPONSE_BYTES = 2_000_000;
const ADAPTER_VERSION = "kubernetes-learning-path-v1";

interface Guide {
  key: string;
  title: string;
  description: string;
  category: string;
  path: string;
}

export const KUBERNETES_GUIDES: readonly Guide[] = [
  guide(
    "basics",
    "Learn Kubernetes Basics",
    "A hands-on introduction to deploying, scaling, updating, and debugging containerized applications.",
    "Cloud & DevOps",
    "/docs/tutorials/kubernetes-basics/",
  ),
  guide(
    "components",
    "Kubernetes Components",
    "Understand the control plane, nodes, and core components that make a Kubernetes cluster work.",
    "Cloud & DevOps",
    "/docs/concepts/overview/components/",
  ),
  guide(
    "pods",
    "Pods",
    "Learn the smallest deployable Kubernetes workload and its lifecycle, networking, and storage model.",
    "Cloud & DevOps",
    "/docs/concepts/workloads/pods/",
  ),
  guide(
    "deployments",
    "Deployments",
    "Operate declarative application rollouts, updates, scaling, and rollback using Deployments.",
    "Cloud & DevOps",
    "/docs/concepts/workloads/controllers/deployment/",
  ),
  guide(
    "services",
    "Services, Load Balancing, and Networking",
    "Study service discovery, ingress, network policy, DNS, and traffic routing in clusters.",
    "Cloud & DevOps",
    "/docs/concepts/services-networking/",
  ),
  guide(
    "storage",
    "Kubernetes Storage",
    "Learn volumes, persistent storage, storage classes, snapshots, and stateful workload fundamentals.",
    "Cloud & DevOps",
    "/docs/concepts/storage/",
  ),
  guide(
    "autoscaling",
    "Horizontal Pod Autoscaling",
    "Scale workloads from observed resource and custom metrics while understanding controller behavior.",
    "Site Reliability Engineering",
    "/docs/tasks/run-application/horizontal-pod-autoscale-walkthrough/",
  ),
  guide(
    "large-clusters",
    "Considerations for Large Clusters",
    "Review quotas, control-plane limits, networking, and operational choices for larger clusters.",
    "Site Reliability Engineering",
    "/docs/setup/best-practices/cluster-large/",
  ),
  guide(
    "multi-zone",
    "Running in Multiple Zones",
    "Design cluster availability across failure zones and understand node and storage placement tradeoffs.",
    "Site Reliability Engineering",
    "/docs/setup/best-practices/multiple-zones/",
  ),
  guide(
    "logging",
    "Logging Architecture",
    "Understand node-level logging, log rotation, agents, and cluster-level log pipelines.",
    "Site Reliability Engineering",
    "/docs/concepts/cluster-administration/logging/",
  ),
  guide(
    "security",
    "Cloud Native Security",
    "Apply defense in depth across cloud, cluster, container, and application layers.",
    "Cloud & DevSecOps",
    "/docs/concepts/security/cloud-native-security/",
  ),
  guide(
    "pod-security",
    "Pod Security Standards",
    "Use the privileged, baseline, and restricted policies to define secure workload boundaries.",
    "Cloud & DevSecOps",
    "/docs/concepts/security/pod-security-standards/",
  ),
  guide(
    "rbac",
    "Role Based Access Control Good Practices",
    "Reduce authorization risk with least privilege, careful bindings, and safer account design.",
    "Identity & Access Management",
    "/docs/concepts/security/rbac-good-practices/",
  ),
  guide(
    "secrets",
    "Good Practices for Kubernetes Secrets",
    "Protect confidential data with safer access, storage encryption, rotation, and isolation practices.",
    "Cloud & DevSecOps",
    "/docs/concepts/security/secrets-good-practices/",
  ),
  guide(
    "security-checklist",
    "Kubernetes Security Checklist",
    "Review practical cluster and workload controls across authentication, networking, pods, and supply chain.",
    "Cloud & DevSecOps",
    "/docs/concepts/security/security-checklist/",
  ),
] as const;

export interface KubernetesBatch {
  revision: string;
  candidates: LearningResourceCandidate[];
}

export async function fetchKubernetesGuides(
  input: { fetcher?: typeof fetch } = {},
): Promise<KubernetesBatch> {
  const fetcher = input.fetcher ?? fetch;
  const license = await fetchBounded(
    KUBERNETES_LICENSE_EVIDENCE_URL,
    "text/plain",
    fetcher,
  );
  if (
    !license.includes(
      "Creative Commons Attribution 4.0 International Public License",
    )
  ) {
    throw new Error("KUBERNETES_LICENSE_NOT_VERIFIED");
  }
  await Promise.all(
    KUBERNETES_GUIDES.map((item) =>
      fetchBounded(
        new URL(item.path, DOCS_ORIGIN).toString(),
        "text/html",
        fetcher,
      ),
    ),
  );
  const revision = sha256(
    JSON.stringify({
      adapterVersion: ADAPTER_VERSION,
      guides: KUBERNETES_GUIDES,
    }),
  );
  return {
    revision,
    candidates: KUBERNETES_GUIDES.map((item) => toCandidate(item, revision)),
  };
}

async function fetchBounded(
  url: string,
  expectedType: string,
  fetcher: typeof fetch,
) {
  const response = await fetcher(url, {
    headers: {
      Accept: expectedType,
      "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
    },
    redirect: "error",
    signal: AbortSignal.timeout(20_000),
  });
  const contentType = (
    response.headers.get("content-type") ?? ""
  ).toLowerCase();
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (!response.ok || !contentType.includes(expectedType)) {
    throw new Error(`KUBERNETES_HTTP_${response.status}`);
  }
  if (declaredLength > MAX_RESPONSE_BYTES)
    throw new Error("KUBERNETES_RESPONSE_TOO_LARGE");
  const content = await response.text();
  if (Buffer.byteLength(content, "utf8") > MAX_RESPONSE_BYTES) {
    throw new Error("KUBERNETES_RESPONSE_TOO_LARGE");
  }
  return content;
}

function toCandidate(item: Guide, revision: string): LearningResourceCandidate {
  const sourceUrl = new URL(item.path, DOCS_ORIGIN).toString();
  const base = {
    sourceName: KUBERNETES_SOURCE_NAME,
    sourceRecordKey: `kubernetes-docs:${item.key}`,
    title: item.title,
    author: "The Kubernetes Authors",
    description: item.description,
    coverUrl: null,
    coverAlt: null,
    category: item.category,
    language: "en",
    licenseExpression: "CC-BY" as const,
    licenseUrl: getVerifiedLicenseUrl("CC-BY"),
    sourceUrl,
    downloadUrl: null,
    readUrl: sourceUrl,
    verificationStatus: "VERIFIED" as const,
    verificationReason:
      "Verified against the official Kubernetes website repository CC BY 4.0 licence and a live official documentation page.",
    verificationEvidenceUrl: KUBERNETES_LICENSE_EVIDENCE_URL,
    sourceRevision: revision,
  };
  return { ...base, contentHash: sha256(JSON.stringify(base)) };
}

function guide(
  key: string,
  title: string,
  description: string,
  category: string,
  path: string,
): Guide {
  return { key, title, description, category, path };
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
