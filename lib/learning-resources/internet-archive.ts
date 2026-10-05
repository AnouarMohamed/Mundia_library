import { createHash } from "crypto";
import { classifyResourceLicense } from "./license-policy";
import type { LearningResourceCandidate } from "./types";

const SOURCE_NAME = "Internet Archive · NASA Technical Reports";
const SEARCH_ENDPOINT = "https://archive.org/advancedsearch.php";
const METADATA_ENDPOINT = "https://archive.org/metadata/";
const TRUSTED_COLLECTION = "nasa_techdocs";
const LEGACY_PUBLIC_DOMAIN_URL =
  "http://creativecommons.org/licenses/publicdomain/";
const MAX_BATCH_SIZE = 50;
const MAX_RESPONSE_BYTES = 2_000_000;
const ADAPTER_VERSION = "internet-archive-nasa-v1";
const USER_AGENT =
  "Mundia-Library/0.2 (gpt-6.1-sol; +https://mundialibrary.tech)";

const ENGINEERING_QUERY = [
  "AERODYNAMICS",
  '"AIRCRAFT DESIGN"',
  "PROPULSION",
  "AVIONICS",
  "ROBOTICS",
  '"CONTROL SYSTEMS"',
  '"SPACECRAFT MATERIALS"',
  '"STRUCTURAL DESIGN"',
  "TELECOMMUNICATIONS",
  '"SOFTWARE ENGINEERING"',
  "COMPUTING",
  '"FLIGHT DYNAMICS"',
  "ROCKET",
].join(" OR ");

interface SearchDocument {
  identifier?: unknown;
  title?: unknown;
}

interface SearchResponse {
  response?: {
    numFound?: unknown;
    docs?: unknown;
  };
}

interface ItemMetadataResponse {
  item_last_updated?: unknown;
  metadata?: unknown;
  files?: unknown;
}

type JsonRecord = Record<string, unknown>;

export interface InternetArchiveBatch {
  revision: string;
  candidates: LearningResourceCandidate[];
  matches: number;
}

export async function fetchInternetArchiveBatch(
  input: {
    limit?: number;
    fetcher?: typeof fetch;
    pause?: (milliseconds: number) => Promise<void>;
  } = {},
): Promise<InternetArchiveBatch> {
  const limit = input.limit ?? MAX_BATCH_SIZE;
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_BATCH_SIZE) {
    throw new Error("IA_INVALID_LIMIT");
  }
  const fetcher = input.fetcher ?? fetch;
  const searchUrl = buildSearchUrl(limit);
  const search = await fetchJson<SearchResponse>(searchUrl, fetcher);
  const docs = Array.isArray(search.response?.docs)
    ? (search.response.docs as SearchDocument[])
    : [];
  const identifiers: string[] = [];
  const seenTitles = new Set<string>();
  for (const document of docs) {
    const identifier = normalizeIdentifier(document.identifier);
    const titleKey = firstString(document.title).toLocaleLowerCase("en");
    if (!identifier || !titleKey || seenTitles.has(titleKey)) continue;
    seenTitles.add(titleKey);
    identifiers.push(identifier);
    if (identifiers.length === limit) break;
  }

  const candidates: LearningResourceCandidate[] = [];
  const versions: Array<Record<string, unknown>> = [];
  for (let offset = 0; offset < identifiers.length; offset += 4) {
    const group = identifiers.slice(offset, offset + 4);
    const items = await Promise.all(
      group.map(async (identifier) => {
        const endpoint = new URL(
          encodeURIComponent(identifier),
          METADATA_ENDPOINT,
        );
        endpoint.searchParams.set("extended_err", "1");
        const payload = await fetchJson<ItemMetadataResponse>(
          endpoint,
          fetcher,
        );
        return { identifier, payload };
      }),
    );
    for (const { identifier, payload } of items) {
      const parsed = parseInternetArchiveItem(identifier, payload);
      if (!parsed) continue;
      versions.push(parsed.version);
      candidates.push(parsed.candidate);
    }
    if (offset + 4 < identifiers.length) {
      await (input.pause ?? defaultPause)(1_000);
    }
  }

  const revision = sha256(
    JSON.stringify({ adapterVersion: ADAPTER_VERSION, versions }),
  );
  return {
    revision,
    matches: toNonNegativeInteger(search.response?.numFound),
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

export function parseInternetArchiveItem(
  expectedIdentifier: string,
  payload: ItemMetadataResponse,
): {
  candidate: LearningResourceCandidate;
  version: Record<string, unknown>;
} | null {
  const metadata = asRecord(payload.metadata);
  if (!metadata) return null;
  const identifier = normalizeIdentifier(metadata.identifier);
  if (!identifier || identifier !== expectedIdentifier) return null;
  const collection = stringArray(metadata.collection);
  if (
    metadata.mediatype !== "texts" ||
    !collection.includes(TRUSTED_COLLECTION)
  ) {
    return null;
  }

  const title = cleanText(firstString(metadata.title), 500);
  if (!title) return null;
  const licenceEvidence = firstString(metadata.licenseurl);
  const licence = classifyResourceLicense(licenceEvidence);
  const verified =
    licence.verified &&
    licence.expression === "PUBLIC-DOMAIN" &&
    normalizeLegacyPublicDomainUrl(licenceEvidence) !== null;
  const file = chooseOriginalPdf(payload.files);
  const sourceUrl = `https://archive.org/details/${identifier}`;
  const downloadUrl =
    verified && file
      ? `https://archive.org/download/${identifier}/${encodeURIComponent(file.name)}`
      : null;
  const subjects = stringArray(metadata.subject);
  const base = {
    sourceName: SOURCE_NAME,
    sourceRecordKey: `internet-archive:${identifier}`,
    title,
    author: cleanText(stringArray(metadata.creator).join(", "), 500),
    description: cleanText(firstString(metadata.description), 4_000),
    coverUrl: null,
    coverAlt: null,
    category: classifyCategory(`${title} ${subjects.join(" ")}`),
    language: normalizeLanguage(firstString(metadata.language)),
    licenseExpression: verified ? licence.expression : null,
    licenseUrl: verified ? licence.licenseUrl : null,
    sourceUrl,
    downloadUrl,
    readUrl: downloadUrl ? null : sourceUrl,
    verificationStatus:
      verified && file ? ("VERIFIED" as const) : ("QUARANTINED" as const),
    verificationReason:
      verified && file
        ? "Verified from the item-level public-domain declaration and original PDF in the trusted NASA Technical Reports collection."
        : "The item did not satisfy the trusted collection, per-item public-domain, and original-PDF requirements.",
    verificationEvidenceUrl: verified
      ? "https://creativecommons.org/publicdomain/mark/1.0/"
      : null,
    sourceRevision: "0".repeat(64),
  };
  return {
    candidate: { ...base, contentHash: sha256(JSON.stringify(base)) },
    version: {
      identifier,
      updated: toNonNegativeInteger(payload.item_last_updated),
      file: file?.name ?? null,
      sha1: file?.sha1 ?? null,
      license: licenceEvidence,
    },
  };
}

function buildSearchUrl(limit: number) {
  const endpoint = new URL(SEARCH_ENDPOINT);
  endpoint.searchParams.set(
    "q",
    `collection:${TRUSTED_COLLECTION} AND mediatype:texts AND licenseurl:"${LEGACY_PUBLIC_DOMAIN_URL}" AND title:(${ENGINEERING_QUERY})`,
  );
  endpoint.searchParams.append("fl[]", "identifier");
  endpoint.searchParams.append("fl[]", "title");
  endpoint.searchParams.append("sort[]", "downloads desc");
  endpoint.searchParams.set("rows", String(limit * 2));
  endpoint.searchParams.set("page", "1");
  endpoint.searchParams.set("output", "json");
  return endpoint;
}

async function fetchJson<T>(endpoint: URL, fetcher: typeof fetch): Promise<T> {
  const response = await fetcher(endpoint, {
    headers: { Accept: "application/json", "User-Agent": USER_AGENT },
    redirect: "error",
    signal: AbortSignal.timeout(20_000),
  });
  if (response.status === 429) throw new Error("IA_RATE_LIMITED");
  if (!response.ok) throw new Error(`IA_HTTP_${response.status}`);
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("json")) {
    throw new Error("IA_INVALID_CONTENT_TYPE");
  }
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (declaredLength > MAX_RESPONSE_BYTES)
    throw new Error("IA_RESPONSE_TOO_LARGE");
  const content = await response.text();
  if (Buffer.byteLength(content, "utf8") > MAX_RESPONSE_BYTES) {
    throw new Error("IA_RESPONSE_TOO_LARGE");
  }
  try {
    return JSON.parse(content) as T;
  } catch {
    throw new Error("IA_INVALID_JSON");
  }
}

function chooseOriginalPdf(value: unknown) {
  if (!Array.isArray(value)) return null;
  for (const entry of value) {
    const file = asRecord(entry);
    const name = firstString(file?.name);
    if (
      file?.source === "original" &&
      (file.format === "Text PDF" || file.format === "PDF") &&
      /^[A-Za-z0-9][A-Za-z0-9._ -]{0,200}\.pdf$/iu.test(name)
    ) {
      return { name, sha1: firstString(file.sha1) || null };
    }
  }
  return null;
}

function normalizeIdentifier(value: unknown) {
  const identifier = firstString(value);
  return /^nasa_techdoc_\d{11}$/u.test(identifier) ? identifier : null;
}

function normalizeLegacyPublicDomainUrl(value: string) {
  try {
    const url = new URL(value);
    if (url.protocol === "https:") url.protocol = "http:";
    return url.toString() === LEGACY_PUBLIC_DOMAIN_URL ? url.toString() : null;
  } catch {
    return null;
  }
}

function classifyCategory(value: string) {
  if (/(software|comput|algorithm|data system)/iu.test(value)) {
    return "Computer Science";
  }
  if (/(telecommunication|communication system|network)/iu.test(value)) {
    return "Networking & Telecommunications";
  }
  if (/(material|structure|stress|fatigue)/iu.test(value)) {
    return "Materials & Structural Engineering";
  }
  if (/(propulsion|engine|turbine|thermodynamic)/iu.test(value)) {
    return "Propulsion Engineering";
  }
  if (/(robot|control system|automation)/iu.test(value)) {
    return "Control & Robotics";
  }
  return "Aerospace Engineering";
}

function normalizeLanguage(value: string) {
  const normalized = value.trim().toLowerCase();
  return normalized === "english" || normalized === "eng"
    ? "en"
    : normalized.slice(0, 16) || "en";
}

function firstString(value: unknown) {
  if (typeof value === "string" || typeof value === "number") {
    return String(value).replace(/\s+/gu, " ").trim();
  }
  if (Array.isArray(value)) return firstString(value[0]);
  return "";
}

function stringArray(value: unknown) {
  const values = Array.isArray(value) ? value : [value];
  return values.map(firstString).filter(Boolean);
}

function cleanText(value: string, max: number) {
  return value.slice(0, max) || null;
}

function asRecord(value: unknown): JsonRecord | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as JsonRecord)
    : null;
}

function toNonNegativeInteger(value: unknown) {
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 0 ? number : 0;
}

function defaultPause(milliseconds: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, milliseconds));
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
