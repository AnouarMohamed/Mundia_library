import { createHash } from "crypto";
import { getVerifiedLicenseUrl } from "./license-policy";
import type { LearningResourceCandidate } from "./types";

export const NIST_SOURCE_NAME = "NIST SP 800 Series";
export const NIST_RIGHTS_URL =
  "https://www.nist.gov/nist-research-library/nist-publications";
const CSRC_HOST = "csrc.nist.gov";
const PDF_HOST = "nvlpubs.nist.gov";
const MAX_HTML_BYTES = 1_000_000;
export const MAX_PROXIED_NIST_BYTES = 4_000_000;

interface NistPublication {
  id: string;
  title: string;
  category: string;
  sourceUrl: string;
}

export const NIST_PUBLICATIONS: readonly NistPublication[] = [
  publication(
    "800-228-upd1",
    "Guidelines for API Protection for Cloud-Native Systems",
    "Cloud & DevSecOps",
    "/pubs/sp/800/228/upd1/final",
  ),
  publication(
    "800-204A",
    "Building Secure Microservices-based Applications Using Service-Mesh Architecture",
    "Cloud & DevSecOps",
    "/pubs/sp/800/204/a/final",
  ),
  publication(
    "800-204B",
    "Attribute-based Access Control for Microservices-based Applications Using a Service Mesh",
    "Cloud & DevSecOps",
    "/pubs/sp/800/204/b/final",
  ),
  publication(
    "800-204C",
    "Implementation of DevSecOps for a Microservices-based Application with Service Mesh",
    "Cloud & DevSecOps",
    "/pubs/sp/800/204/c/final",
  ),
  publication(
    "800-210",
    "General Access Control Guidance for Cloud Systems",
    "Cloud & DevSecOps",
    "/pubs/sp/800/210/final",
  ),
  publication(
    "800-207",
    "Zero Trust Architecture",
    "Network Security",
    "/pubs/sp/800/207/final",
  ),
  publication(
    "800-190",
    "Application Container Security Guide",
    "Cloud & DevSecOps",
    "/pubs/sp/800/190/final",
  ),
  publication(
    "800-218",
    "Secure Software Development Framework (SSDF) Version 1.1",
    "Secure Software Engineering",
    "/pubs/sp/800/218/final",
  ),
  publication(
    "800-61r3",
    "Incident Response Recommendations and Considerations for Cybersecurity Risk Management",
    "Cybersecurity Operations",
    "/pubs/sp/800/61/r3/final",
  ),
  publication(
    "800-63-4",
    "Digital Identity Guidelines",
    "Identity & Access Management",
    "/pubs/sp/800/63/4/final",
  ),
  publication(
    "800-53r5-upd1",
    "Security and Privacy Controls for Information Systems and Organizations",
    "Security Engineering",
    "/pubs/sp/800/53/r5/upd1/final",
  ),
  publication(
    "800-115",
    "Technical Guide to Information Security Testing and Assessment",
    "Cybersecurity Operations",
    "/pubs/sp/800/115/final",
  ),
  publication(
    "800-41r1",
    "Guidelines on Firewalls and Firewall Policy",
    "Network Security",
    "/pubs/sp/800/41/r1/final",
  ),
  publication(
    "800-77r1",
    "Guide to IPsec VPNs",
    "Network Security",
    "/pubs/sp/800/77/r1/final",
  ),
  publication(
    "800-137",
    "Information Security Continuous Monitoring for Federal Information Systems and Organizations",
    "Cybersecurity Operations",
    "/pubs/sp/800/137/final",
  ),
  publication(
    "800-161r1-upd1",
    "Cybersecurity Supply Chain Risk Management Practices for Systems and Organizations",
    "Security Engineering",
    "/pubs/sp/800/161/r1/upd1/final",
  ),
  publication(
    "800-125A",
    "Security Recommendations for Server-based Hypervisor Platforms",
    "Cloud & DevSecOps",
    "/pubs/sp/800/125/a/final",
  ),
  publication(
    "800-125B",
    "Secure Virtual Network Configuration for Virtual Machine Protection",
    "Network Security",
    "/pubs/sp/800/125/b/final",
  ),
  publication(
    "800-46r2",
    "Guide to Enterprise Telework, Remote Access, and Bring Your Own Device Security",
    "Network Security",
    "/pubs/sp/800/46/r2/final",
  ),
  publication(
    "800-171r3",
    "Protecting Controlled Unclassified Information in Nonfederal Systems and Organizations",
    "Security Engineering",
    "/pubs/sp/800/171/r3/final",
  ),
  publication(
    "800-215",
    "Guide to a Secure Enterprise Network Landscape",
    "Network Security",
    "/pubs/sp/800/215/final",
  ),
  publication(
    "800-223",
    "High-Performance Computing Security: Architecture, Threat Analysis, and Security Posture",
    "Infrastructure Security",
    "/pubs/sp/800/223/final",
  ),
  publication(
    "800-233",
    "Service Mesh Proxy Models for Cloud-Native Applications",
    "Cloud & DevSecOps",
    "/pubs/sp/800/233/final",
  ),
] as const;

export interface NistBatch {
  revision: string;
  candidates: LearningResourceCandidate[];
}

export async function fetchNistPublications(
  input: {
    fetcher?: typeof fetch;
  } = {},
): Promise<NistBatch> {
  const fetcher = input.fetcher ?? fetch;
  const candidates: LearningResourceCandidate[] = [];
  for (const item of NIST_PUBLICATIONS) {
    candidates.push(await fetchPublication(item, fetcher));
  }
  const revision = sha256(JSON.stringify(candidates));
  return {
    revision,
    candidates: candidates.map((candidate) => {
      const revised = {
        ...candidate,
        sourceRevision: revision,
        contentHash: "",
      };
      return { ...revised, contentHash: sha256(JSON.stringify(revised)) };
    }),
  };
}

async function fetchPublication(
  item: NistPublication,
  fetcher: typeof fetch,
): Promise<LearningResourceCandidate> {
  let downloadUrl: string | null = null;
  let failure = "The official publication page or PDF could not be verified.";
  try {
    const page = await fetcher(item.sourceUrl, {
      headers: { Accept: "text/html", "User-Agent": userAgent() },
      redirect: "error",
      signal: AbortSignal.timeout(20_000),
    });
    const type = (page.headers.get("content-type") ?? "").toLowerCase();
    const length = Number(page.headers.get("content-length") ?? 0);
    if (!page.ok || !type.includes("text/html") || length > MAX_HTML_BYTES) {
      throw new Error("NIST_INVALID_PUBLICATION_PAGE");
    }
    const html = await page.text();
    if (Buffer.byteLength(html, "utf8") > MAX_HTML_BYTES) {
      throw new Error("NIST_PUBLICATION_PAGE_TOO_LARGE");
    }
    const extracted = extractOfficialPdfUrl(html);
    if (!extracted) throw new Error("NIST_PDF_LINK_MISSING");
    const probe = await fetcher(extracted, {
      headers: {
        Accept: "application/pdf",
        Range: "bytes=0-3",
        "User-Agent": userAgent(),
      },
      redirect: "error",
      signal: AbortSignal.timeout(20_000),
    });
    const pdfType = (probe.headers.get("content-type") ?? "").toLowerCase();
    const totalMatch = (probe.headers.get("content-range") ?? "").match(
      /^bytes 0-3\/(\d+)$/u,
    );
    const pdfLength = Number(totalMatch?.[1] ?? 0);
    if (
      probe.status !== 206 ||
      !pdfType.startsWith("application/pdf") ||
      !Number.isSafeInteger(pdfLength) ||
      pdfLength < 1 ||
      pdfLength > MAX_PROXIED_NIST_BYTES
    ) {
      failure =
        "The official PDF did not pass the type and four-megabyte free-tier download limit checks.";
    } else {
      downloadUrl = extracted;
    }
    await probe.body?.cancel();
  } catch {
    downloadUrl = null;
  }

  const verified = Boolean(downloadUrl);
  const base = {
    sourceName: NIST_SOURCE_NAME,
    sourceRecordKey: `nist-sp:${item.id.toLowerCase()}`,
    title: item.title,
    author: "National Institute of Standards and Technology",
    category: item.category,
    language: "en",
    licenseExpression: verified ? ("PUBLIC-DOMAIN" as const) : null,
    licenseUrl: verified ? getVerifiedLicenseUrl("PUBLIC-DOMAIN") : null,
    sourceUrl: item.sourceUrl,
    downloadUrl,
    readUrl: item.sourceUrl,
    verificationStatus: verified
      ? ("VERIFIED" as const)
      : ("QUARANTINED" as const),
    verificationReason: verified
      ? "Verified against the official CSRC record and NIST PDF; these works are not copyrighted in the United States and NIST grants worldwide royalty-free reprint rights."
      : failure,
    verificationEvidenceUrl: verified ? NIST_RIGHTS_URL : null,
    sourceRevision: "pending",
  };
  return { ...base, contentHash: sha256(JSON.stringify(base)) };
}

export function extractOfficialPdfUrl(html: string) {
  const match = html.match(
    /https:\/\/nvlpubs\.nist\.gov\/nistpubs\/[A-Za-z0-9._~!$&'()*+,;=:@%/-]+\.pdf/iu,
  );
  if (!match) return null;
  try {
    const url = new URL(match[0]);
    if (
      url.protocol !== "https:" ||
      url.hostname !== PDF_HOST ||
      url.username ||
      url.password ||
      url.port ||
      !url.pathname.toLowerCase().endsWith(".pdf")
    )
      return null;
    return url.toString();
  } catch {
    return null;
  }
}

export function isProxiableNistDownload(resource: {
  sourceName?: string | null;
  licenseExpression?: string | null;
  downloadUrl?: string | null;
}) {
  if (
    resource.sourceName !== NIST_SOURCE_NAME ||
    resource.licenseExpression !== "PUBLIC-DOMAIN" ||
    !resource.downloadUrl
  )
    return false;
  try {
    const url = new URL(resource.downloadUrl);
    return (
      url.protocol === "https:" &&
      url.hostname === PDF_HOST &&
      !url.username &&
      !url.password &&
      !url.port &&
      url.pathname.toLowerCase().endsWith(".pdf")
    );
  } catch {
    return false;
  }
}

function publication(
  id: string,
  title: string,
  category: string,
  path: string,
) {
  const url = new URL(path, "https://csrc.nist.gov");
  if (url.hostname !== CSRC_HOST) throw new Error("NIST_INVALID_MANIFEST_URL");
  return { id, title, category, sourceUrl: url.toString() };
}

function userAgent() {
  return "Mundia-Library/0.2 (+https://mundialibrary.tech)";
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
