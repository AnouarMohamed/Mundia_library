import { createHash } from "crypto";
import { XMLParser, XMLValidator } from "fast-xml-parser";
import { classifyResourceLicense } from "./license-policy";
import { decodeDisplayText } from "./display-text";
import type { LearningResourceCandidate } from "./types";

const SOURCE_NAME = "arXiv";
const OAI_ENDPOINT = "https://oaipmh.arxiv.org/oai";
const MAX_RESPONSE_BYTES = 5_000_000;
const MAX_RECORDS = 250;
const ADAPTER_VERSION = "arxiv-v2";

const ALLOWED_SETS = new Set([
  "cs:cs:AR",
  "cs:cs:CR",
  "cs:cs:DC",
  "cs:cs:NI",
  "cs:cs:OS",
  "cs:cs:PF",
  "cs:cs:RO",
  "cs:cs:SE",
]);

type XmlNode = Record<string, unknown>;

export interface ArxivPage {
  revision: string;
  candidates: LearningResourceCandidate[];
  nextToken: string | null;
  sourceRecords: number;
  selectedRecords: number;
}

export async function fetchArxivPage(input: {
  set: string;
  from?: string;
  limit?: number;
  fetcher?: typeof fetch;
}): Promise<ArxivPage> {
  if (!ALLOWED_SETS.has(input.set)) throw new Error("ARXIV_INVALID_SET");
  if (input.from && !/^\d{4}-\d{2}-\d{2}$/.test(input.from)) {
    throw new Error("ARXIV_INVALID_FROM_DATE");
  }
  const limit = input.limit ?? MAX_RECORDS;
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_RECORDS) {
    throw new Error("ARXIV_INVALID_LIMIT");
  }

  const endpoint = new URL(OAI_ENDPOINT);
  endpoint.searchParams.set("verb", "ListRecords");
  endpoint.searchParams.set("metadataPrefix", "arXiv");
  endpoint.searchParams.set("set", input.set);
  if (input.from) endpoint.searchParams.set("from", input.from);

  const response = await (input.fetcher ?? fetch)(endpoint, {
    headers: {
      Accept: "application/xml, text/xml;q=0.9",
      "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
    },
    redirect: "error",
    signal: AbortSignal.timeout(30_000),
  });
  if (!response.ok) throw new Error(`ARXIV_HTTP_${response.status}`);
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("xml")) {
    throw new Error("ARXIV_INVALID_CONTENT_TYPE");
  }
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (declaredLength > MAX_RESPONSE_BYTES) {
    throw new Error("ARXIV_RESPONSE_TOO_LARGE");
  }
  const content = await response.text();
  if (Buffer.byteLength(content, "utf8") > MAX_RESPONSE_BYTES) {
    throw new Error("ARXIV_RESPONSE_TOO_LARGE");
  }
  return parseArxivPage(content, limit);
}

export function parseArxivPage(
  content: string,
  limit = MAX_RECORDS,
): ArxivPage {
  if (!content || /<!DOCTYPE|<!ENTITY/iu.test(content)) {
    throw new Error("ARXIV_UNSAFE_XML");
  }
  if (XMLValidator.validate(content) !== true) {
    throw new Error("ARXIV_INVALID_XML");
  }
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_RECORDS) {
    throw new Error("ARXIV_INVALID_LIMIT");
  }
  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: "@_",
    parseTagValue: false,
    processEntities: false,
    trimValues: true,
  });
  const parsed = parser.parse(content) as XmlNode;
  const root = asNode(parsed["OAI-PMH"]);
  const list = asNode(root?.ListRecords);
  if (!root || !list) throw new Error("ARXIV_INVALID_OAI_RESPONSE");

  const allRecords = asArray(list.record).map(asNode).filter(isNode);
  const records = allRecords.slice(0, limit);
  const nextToken = nodeText(list.resumptionToken) || null;
  const recordVersions = records.map((record) => {
    const header = asNode(record.header);
    return {
      identifier: normalizeText(String(header?.identifier ?? "")),
      datestamp: normalizeText(String(header?.datestamp ?? "")),
      status: normalizeText(String(header?.["@_status"] ?? "")),
    };
  });
  const revision = sha256(
    JSON.stringify({ adapterVersion: ADAPTER_VERSION, recordVersions }),
  );
  const candidates = records
    .map((record) => parseRecord(record, revision))
    .filter((candidate): candidate is LearningResourceCandidate =>
      Boolean(candidate),
    );
  return {
    revision,
    candidates,
    nextToken,
    sourceRecords: allRecords.length,
    selectedRecords: records.length,
  };
}

function parseRecord(
  record: XmlNode,
  revision: string,
): LearningResourceCandidate | null {
  const header = asNode(record.header);
  if (header?.["@_status"] === "deleted") return null;
  const metadata = asNode(asNode(record.metadata)?.arXiv);
  const id = normalizeArxivId(String(metadata?.id ?? ""));
  const title = cleanText(nodeText(metadata?.title), 500);
  if (!id || !title) return null;

  const categories = normalizeText(nodeText(metadata?.categories)).split(" ");
  const licenceEvidence = nodeText(metadata?.license);
  const licence = classifyResourceLicense(licenceEvidence);
  const evidenceUrl = normalizeLicenseUrl(licenceEvidence);
  const verified = licence.verified && Boolean(evidenceUrl);
  const authors = asArray(asNode(metadata?.authors)?.author)
    .map(asNode)
    .filter(isNode)
    .map(formatAuthor)
    .filter(Boolean)
    .join(", ")
    .slice(0, 500);
  const sourceUrl = `https://arxiv.org/abs/${id}`;
  const base = {
    sourceName: SOURCE_NAME,
    sourceRecordKey: `arxiv:${id}`,
    title,
    author: authors || null,
    description: cleanText(nodeText(metadata?.abstract), 4_000),
    coverUrl: null,
    coverAlt: null,
    category: classifyCategory(categories),
    language: "en",
    licenseExpression: verified ? licence.expression : null,
    licenseUrl: verified ? licence.licenseUrl : null,
    sourceUrl,
    downloadUrl: verified ? `https://arxiv.org/pdf/${id}` : null,
    readUrl: verified ? sourceUrl : null,
    verificationStatus: verified
      ? ("VERIFIED" as const)
      : ("QUARANTINED" as const),
    verificationReason: verified
      ? "Verified from the per-paper licence URI in the official arXiv OAI record."
      : licence.reason,
    verificationEvidenceUrl: verified ? evidenceUrl : null,
    sourceRevision: revision,
  };
  return { ...base, contentHash: sha256(JSON.stringify(base)) };
}

function formatAuthor(author: XmlNode) {
  const forenames = cleanText(nodeText(author.forenames), 150);
  const keyname = cleanText(nodeText(author.keyname), 150);
  return [forenames, keyname].filter(Boolean).join(" ");
}

function classifyCategory(categories: string[]) {
  if (categories.includes("cs.CR")) return "Security";
  if (categories.includes("cs.SE")) return "Software Engineering";
  if (categories.includes("cs.DC")) return "Cloud & Distributed Systems";
  if (categories.includes("cs.NI")) return "Networking & Telecommunications";
  if (categories.includes("cs.OS")) return "Operating Systems";
  if (categories.includes("cs.AR")) return "Computer Architecture";
  if (categories.includes("cs.RO")) return "Robotics";
  if (categories.includes("cs.PF")) return "Systems Performance";
  return "Computer Science";
}

function normalizeArxivId(value: string) {
  const normalized = normalizeText(value).replace(/v\d+$/iu, "");
  return /^(?:[a-z-]+(?:\.[A-Z]{2})?\/\d{7}|\d{4}\.\d{4,5})$/iu.test(normalized)
    ? normalized
    : null;
}

function normalizeLicenseUrl(value: string) {
  try {
    const url = new URL(normalizeText(value));
    if (url.protocol === "http:") url.protocol = "https:";
    return url.protocol === "https:" && url.hostname === "creativecommons.org"
      ? url.toString()
      : null;
  } catch {
    return null;
  }
}

function cleanText(value: string, max: number) {
  return normalizeText(value).slice(0, max) || null;
}

function nodeText(value: unknown) {
  if (typeof value === "string" || typeof value === "number") {
    return normalizeText(String(value));
  }
  return normalizeText(String(asNode(value)?.["#text"] ?? ""));
}

function normalizeText(value: string) {
  return decodeDisplayText(value).replace(/\s+/gu, " ").trim();
}

function asNode(value: unknown): XmlNode | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as XmlNode)
    : null;
}

function isNode(value: XmlNode | null): value is XmlNode {
  return value !== null;
}

function asArray(value: unknown): unknown[] {
  if (value === undefined || value === null) return [];
  return Array.isArray(value) ? value : [value];
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
