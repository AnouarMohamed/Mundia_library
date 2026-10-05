import { createHash } from "crypto";
import { getVerifiedLicenseUrl } from "./license-policy";
import type { LearningResourceCandidate } from "./types";

const SOURCE_NAME = "Wikibooks";
const API_ORIGIN = "https://en.wikibooks.org";
const RIGHTS_URL = "https://creativecommons.org/licenses/by-sa/4.0/deed.en";
const MAX_RESPONSE_BYTES = 5_000_000;
const BOOK_ROOT_CATEGORY = "Category:Book:Wikibooks Stacks/Books";
const RIGHTS_RISK_CATEGORIES = [
  "Category:Copyright violations",
  "Category:Fair use electronic games screenshots",
] as const;

export const WIKIBOOK_COLLECTIONS = {
  unix: {
    category: "Category:Shelf:UNIX/all books",
    publicCategory: "UNIX",
  },
  linux: {
    category: "Category:Shelf:Linux/all books",
    publicCategory: "Linux",
  },
  "software-engineering": {
    category: "Category:Shelf:Software engineering/all books",
    publicCategory: "Software Engineering",
  },
  security: {
    category: "Category:Shelf:Information security/all books",
    publicCategory: "Information Security",
  },
  "system-administration": {
    category: "Category:Shelf:System administration/all books",
    publicCategory: "System Administration",
  },
  "version-control": {
    category: "Category:Shelf:Revision control software/all books",
    publicCategory: "Version Control",
  },
} as const;

export type WikibookCollection = keyof typeof WIKIBOOK_COLLECTIONS;

interface WikibooksPayload {
  continue?: unknown;
  query?: unknown;
}

interface WikibooksPage {
  pageid?: unknown;
  ns?: unknown;
  title?: unknown;
  fullurl?: unknown;
  revisions?: unknown;
  categories?: unknown;
}

export interface WikibooksBatch {
  revision: string;
  candidates: LearningResourceCandidate[];
  collection: WikibookCollection;
}

export async function fetchWikibooksCollection(input: {
  collection: WikibookCollection;
  fetcher?: typeof fetch;
}): Promise<WikibooksBatch> {
  const config = WIKIBOOK_COLLECTIONS[input.collection];
  if (!config) throw new Error("WIKIBOOKS_INVALID_COLLECTION");

  const endpoint = new URL("/w/api.php", API_ORIGIN);
  endpoint.searchParams.set("action", "query");
  endpoint.searchParams.set("generator", "categorymembers");
  endpoint.searchParams.set("gcmtitle", config.category);
  endpoint.searchParams.set("gcmnamespace", "0");
  endpoint.searchParams.set("gcmlimit", "100");
  endpoint.searchParams.set("prop", "info|revisions|categories");
  endpoint.searchParams.set("inprop", "url");
  endpoint.searchParams.set("rvprop", "ids|timestamp");
  endpoint.searchParams.set("cllimit", "max");
  endpoint.searchParams.set(
    "clcategories",
    [BOOK_ROOT_CATEGORY, config.category, ...RIGHTS_RISK_CATEGORIES].join("|"),
  );
  endpoint.searchParams.set("meta", "siteinfo");
  endpoint.searchParams.set("siprop", "rightsinfo");
  endpoint.searchParams.set("format", "json");
  endpoint.searchParams.set("formatversion", "2");

  const response = await (input.fetcher ?? fetch)(endpoint, {
    headers: {
      Accept: "application/json",
      "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
    },
    redirect: "error",
    signal: AbortSignal.timeout(20_000),
  });
  if (!response.ok) throw new Error(`WIKIBOOKS_HTTP_${response.status}`);
  if (!(response.headers.get("content-type") ?? "").toLowerCase().includes("json")) {
    throw new Error("WIKIBOOKS_INVALID_CONTENT_TYPE");
  }
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (declaredLength > MAX_RESPONSE_BYTES) {
    throw new Error("WIKIBOOKS_RESPONSE_TOO_LARGE");
  }
  const content = await response.text();
  if (Buffer.byteLength(content, "utf8") > MAX_RESPONSE_BYTES) {
    throw new Error("WIKIBOOKS_RESPONSE_TOO_LARGE");
  }
  const batch = parseWikibooksCollection(content, input.collection);
  const checkedCandidates = await verifyOfficialDownloads(
    batch.candidates,
    input.fetcher ?? fetch,
  );
  const revision = sha256(
    JSON.stringify({
      metadataRevision: batch.revision,
      downloads: checkedCandidates.map((candidate) => ({
        sourceRecordKey: candidate.sourceRecordKey,
        downloadUrl: candidate.downloadUrl,
        verificationStatus: candidate.verificationStatus,
      })),
    }),
  );
  return {
    ...batch,
    revision,
    candidates: checkedCandidates.map((candidate) => {
      const revised = { ...candidate, sourceRevision: revision, contentHash: "" };
      return { ...revised, contentHash: sha256(JSON.stringify(revised)) };
    }),
  };
}

export function parseWikibooksCollection(
  content: string,
  collection: WikibookCollection,
): WikibooksBatch {
  const config = WIKIBOOK_COLLECTIONS[collection];
  if (!config) throw new Error("WIKIBOOKS_INVALID_COLLECTION");
  let payload: WikibooksPayload;
  try {
    payload = JSON.parse(content) as WikibooksPayload;
  } catch {
    throw new Error("WIKIBOOKS_INVALID_JSON");
  }
  if (payload.continue) throw new Error("WIKIBOOKS_COLLECTION_TOO_LARGE");
  if (!isObject(payload.query)) throw new Error("WIKIBOOKS_INVALID_RESPONSE");
  const rights = isObject(payload.query.rightsinfo)
    ? payload.query.rightsinfo
    : null;
  if (rights?.url !== RIGHTS_URL || !Array.isArray(payload.query.pages)) {
    throw new Error("WIKIBOOKS_UNVERIFIED_RIGHTS");
  }

  const pages = payload.query.pages.filter(isObject) as WikibooksPage[];
  const revision = sha256(
    JSON.stringify({
      collection,
      rightsUrl: rights.url,
      pages: pages.map((page) => ({
        pageId: page.pageid,
        title: page.title,
        revisions: page.revisions,
        categories: page.categories,
      })),
    }),
  );
  const candidates = pages
    .map((page) => parsePage(page, config, revision))
    .filter((candidate): candidate is LearningResourceCandidate => Boolean(candidate));
  return { revision, candidates, collection };
}

function parsePage(
  page: WikibooksPage,
  config: (typeof WIKIBOOK_COLLECTIONS)[WikibookCollection],
  revision: string,
): LearningResourceCandidate | null {
  if (!Number.isSafeInteger(page.pageid) || Number(page.pageid) < 1 || page.ns !== 0) {
    return null;
  }
  const title = cleanText(page.title, 500);
  const sourceUrl = normalizeOfficialUrl(page.fullurl);
  const categories = Array.isArray(page.categories)
    ? page.categories
        .filter(isObject)
        .map((category) => cleanText(category.title, 300))
        .filter((value): value is string => Boolean(value))
    : [];
  const revisions = Array.isArray(page.revisions)
    ? page.revisions.filter(isObject)
    : [];
  const revisionId = revisions[0]?.revid;
  if (
    !title ||
    !sourceUrl ||
    !Number.isSafeInteger(revisionId) ||
    !categories.includes(BOOK_ROOT_CATEGORY) ||
    !categories.includes(config.category)
  ) {
    return null;
  }

  const rightsFlagged = categories.some((category) =>
    RIGHTS_RISK_CATEGORIES.includes(
      category as (typeof RIGHTS_RISK_CATEGORIES)[number],
    ),
  );
  const encodedTitle = encodeURIComponent(title.replaceAll(" ", "_"));
  const verified = !rightsFlagged;
  const base = {
    sourceName: SOURCE_NAME,
    sourceRecordKey: `wikibooks:${String(page.pageid)}`,
    title,
    author: "Wikibooks contributors",
    category: config.publicCategory,
    language: "en",
    licenseExpression: verified ? ("CC-BY-SA" as const) : null,
    licenseUrl: verified ? getVerifiedLicenseUrl("CC-BY-SA") : null,
    sourceUrl,
    downloadUrl: verified
      ? `${API_ORIGIN}/api/rest_v1/page/pdf/${encodedTitle}`
      : null,
    readUrl: sourceUrl,
    verificationStatus: verified
      ? ("VERIFIED" as const)
      : ("QUARANTINED" as const),
    verificationReason: verified
      ? "Verified as a Wikibooks book root under the official CC BY-SA 4.0 site licence; the download is generated by Wikimedia's PDF service."
      : "The book has a copyright-violation or fair-use category and requires manual rights review.",
    verificationEvidenceUrl: verified
      ? "https://en.wikibooks.org/wiki/Wikibooks:Copyrights"
      : null,
    sourceRevision: revision,
  };
  return { ...base, contentHash: sha256(JSON.stringify(base)) };
}

async function verifyOfficialDownloads(
  candidates: LearningResourceCandidate[],
  fetcher: typeof fetch,
) {
  const verified = [...candidates];
  let cursor = 0;
  const workers = Array.from(
    { length: Math.min(4, candidates.length) },
    async () => {
      while (cursor < candidates.length) {
        const index = cursor;
        cursor += 1;
        const candidate = candidates[index]!;
        if (!candidate.downloadUrl) continue;
        let valid = false;
        try {
          const response = await fetcher(candidate.downloadUrl, {
            method: "HEAD",
            headers: {
              Accept: "application/pdf",
              "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
            },
            redirect: "error",
            signal: AbortSignal.timeout(20_000),
          });
          const contentType = response.headers.get("content-type") ?? "";
          const disposition = response.headers.get("content-disposition") ?? "";
          valid =
            response.ok &&
            contentType.toLowerCase().startsWith("application/pdf") &&
            /\battachment\b/iu.test(disposition);
        } catch {
          valid = false;
        }
        if (!valid) {
          const quarantined = {
            ...candidate,
            licenseExpression: null,
            licenseUrl: null,
            downloadUrl: null,
            verificationStatus: "QUARANTINED" as const,
            verificationReason:
              "The official Wikimedia PDF endpoint did not return a verified attachment and requires manual review.",
            verificationEvidenceUrl: null,
            contentHash: "",
          };
          verified[index] = {
            ...quarantined,
            contentHash: sha256(JSON.stringify(quarantined)),
          };
        }
      }
    },
  );
  await Promise.all(workers);
  return verified;
}

function normalizeOfficialUrl(value: unknown) {
  if (typeof value !== "string") return null;
  try {
    const url = new URL(value);
    if (
      url.protocol !== "https:" ||
      url.hostname !== "en.wikibooks.org" ||
      url.username ||
      url.password ||
      url.port
    ) {
      return null;
    }
    url.hash = "";
    return url.toString();
  } catch {
    return null;
  }
}

function cleanText(value: unknown, max: number) {
  if (typeof value !== "string") return null;
  const normalized = value.replace(/\s+/gu, " ").trim();
  return normalized ? normalized.slice(0, max) : null;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
