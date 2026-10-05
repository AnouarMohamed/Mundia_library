import { createHash } from "crypto";
import { getVerifiedLicenseUrl } from "./license-policy";
import type { LearningResourceCandidate } from "./types";

export const OWASP_SOURCE_NAME = "OWASP Cheat Sheet Series";
export const OWASP_INDEX_URL = "https://cheatsheetseries.owasp.org/index.html";
export const OWASP_LICENSE_EVIDENCE_URL =
  "https://owasp.org/projects/cheat-sheet-series";
const PUBLISHED_ORIGIN = "https://cheatsheetseries.owasp.org";
const MAX_RESPONSE_BYTES = 2_000_000;
const ADAPTER_VERSION = "owasp-cheat-sheets-v1";

export interface OwaspBatch {
  revision: string;
  candidates: LearningResourceCandidate[];
}

/**
 * Imports the official published index as metadata. The articles remain on
 * OWASP infrastructure; Mundia neither copies nor proxies their content.
 */
export async function fetchOwaspCheatSheets(
  input: { fetcher?: typeof fetch } = {},
): Promise<OwaspBatch> {
  const fetcher = input.fetcher ?? fetch;
  const [indexHtml, evidenceHtml] = await Promise.all([
    fetchBoundedHtml(OWASP_INDEX_URL, fetcher),
    fetchBoundedHtml(OWASP_LICENSE_EVIDENCE_URL, fetcher),
  ]);
  if (
    !/CC\s+BY-SA\s+4\.0/iu.test(evidenceHtml) &&
    !/Creative Commons Attribution(?:-| )ShareAlike 4\.0/iu.test(evidenceHtml)
  ) {
    throw new Error("OWASP_LICENSE_NOT_VERIFIED");
  }

  const resources = extractCheatSheets(indexHtml);
  if (resources.length < 50 || resources.length > 500) {
    throw new Error("OWASP_UNEXPECTED_CATALOG_SIZE");
  }
  const revision = sha256(
    JSON.stringify({ adapterVersion: ADAPTER_VERSION, resources }),
  );
  return {
    revision,
    candidates: resources.map((resource) => toCandidate(resource, revision)),
  };
}

async function fetchBoundedHtml(url: string, fetcher: typeof fetch) {
  const response = await fetcher(url, {
    headers: {
      Accept: "text/html",
      "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
    },
    redirect: "error",
    signal: AbortSignal.timeout(20_000),
  });
  const contentType = (
    response.headers.get("content-type") ?? ""
  ).toLowerCase();
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (!response.ok || !contentType.includes("text/html")) {
    throw new Error(`OWASP_HTTP_${response.status}`);
  }
  if (declaredLength > MAX_RESPONSE_BYTES) {
    throw new Error("OWASP_RESPONSE_TOO_LARGE");
  }
  const html = await response.text();
  if (Buffer.byteLength(html, "utf8") > MAX_RESPONSE_BYTES) {
    throw new Error("OWASP_RESPONSE_TOO_LARGE");
  }
  return html;
}

function extractCheatSheets(html: string) {
  const results = new Map<
    string,
    { key: string; title: string; url: string }
  >();
  const links = html.matchAll(
    /href=["']([^"']*\/??cheatsheets\/[^"'?#]+_Cheat_Sheet\.html)["']/giu,
  );
  for (const match of links) {
    const rawHref = match[1];
    if (!rawHref) continue;
    const url = new URL(rawHref, `${PUBLISHED_ORIGIN}/`);
    if (
      url.origin !== PUBLISHED_ORIGIN ||
      url.search ||
      url.hash ||
      !/^\/cheatsheets\/[A-Za-z0-9_-]+_Cheat_Sheet\.html$/u.test(url.pathname)
    ) {
      continue;
    }
    const key = url.pathname.slice("/cheatsheets/".length, -".html".length);
    const title = key
      .replace(/_Cheat_Sheet$/u, "")
      .replaceAll("_", " ")
      .replace(/\bCicd\b/gu, "CI/CD")
      .replace(/\bSbom\b/gu, "SBOM")
      .replace(/\bApi\b/gu, "API")
      .replace(/\bOwasp\b/gu, "OWASP");
    results.set(key, {
      key,
      title: `${title} Cheat Sheet`,
      url: url.toString(),
    });
  }
  return [...results.values()].sort((left, right) =>
    left.key.localeCompare(right.key),
  );
}

function toCandidate(
  resource: { key: string; title: string; url: string },
  revision: string,
): LearningResourceCandidate {
  const category = categoryFor(resource.title);
  const base = {
    sourceName: OWASP_SOURCE_NAME,
    sourceRecordKey: `owasp-cheat-sheet:${resource.key.toLowerCase()}`,
    title: resource.title,
    author: "OWASP Cheat Sheet Series Team",
    description: `Practical, community-maintained guidance on ${resource.title.replace(/ Cheat Sheet$/u, "").toLowerCase()} for developers, operators, and security engineers.`,
    coverUrl: null,
    coverAlt: null,
    category,
    language: "en",
    licenseExpression: "CC-BY-SA" as const,
    licenseUrl: getVerifiedLicenseUrl("CC-BY-SA"),
    sourceUrl: resource.url,
    downloadUrl: null,
    readUrl: resource.url,
    verificationStatus: "VERIFIED" as const,
    verificationReason:
      "Verified from the official OWASP published index and project-level CC BY-SA 4.0 licence statement.",
    verificationEvidenceUrl: OWASP_LICENSE_EVIDENCE_URL,
    sourceRevision: revision,
  };
  return { ...base, contentHash: sha256(JSON.stringify(base)) };
}

function categoryFor(title: string) {
  if (
    /Kubernetes|Docker|Cloud|Infrastructure as Code|CI.CD|GitHub Actions|Serverless|Workload Identity/iu.test(
      title,
    )
  ) {
    return "Cloud & DevSecOps";
  }
  if (/API|GraphQL|REST|Web Service|gRPC|Webhook|Microservice/iu.test(title)) {
    return "API & Microservices Security";
  }
  if (
    /Java|JavaScript|TypeScript|Node|Django|FastAPI|Laravel|Rails|Symfony|DotNet|C-Based/iu.test(
      title,
    )
  ) {
    return "Secure Software Engineering";
  }
  if (
    /Authentication|Authorization|Identity|OAuth|SAML|Password|Passkey|Session|Access Control|Multifactor/iu.test(
      title,
    )
  ) {
    return "Identity & Access Management";
  }
  if (/Supply Chain|Dependency|SBOM|NPM|Third Party/iu.test(title)) {
    return "Software Supply Chain Security";
  }
  return "Application Security";
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
