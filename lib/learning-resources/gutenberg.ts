import { createHash } from "crypto";
import { XMLParser, XMLValidator } from "fast-xml-parser";
import { getVerifiedLicenseUrl } from "./license-policy";
import type { LearningResourceCandidate } from "./types";

const SOURCE_NAME = "Project Gutenberg";
const ORIGIN = "https://www.gutenberg.org";
const MAX_RESPONSE_BYTES = 5_000_000;
const PAGE_SIZE = 25;

export const GUTENBERG_COLLECTIONS = {
  engineering: { bookshelfId: 671 },
  mathematics: { bookshelfId: 102 },
  physics: { bookshelfId: 103 },
} as const;

export type GutenbergCollection = keyof typeof GUTENBERG_COLLECTIONS;
type XmlNode = Record<string, unknown>;

interface GutenbergDetail {
  ebookId: number;
  title: string;
  authors: string[];
  language: string;
  rights: string[];
  subjects: string[];
}

export interface GutenbergPage {
  revision: string;
  candidates: LearningResourceCandidate[];
  collection: GutenbergCollection;
  page: number;
  hasNextPage: boolean;
}

export async function fetchGutenbergPage(input: {
  collection: GutenbergCollection;
  page: number;
  fetcher?: typeof fetch;
}): Promise<GutenbergPage> {
  const config = GUTENBERG_COLLECTIONS[input.collection];
  if (!config) throw new Error("GUTENBERG_INVALID_COLLECTION");
  if (!Number.isSafeInteger(input.page) || input.page < 1 || input.page > 100) {
    throw new Error("GUTENBERG_INVALID_PAGE");
  }
  const fetcher = input.fetcher ?? fetch;
  const endpoint = new URL(`/ebooks/bookshelf/${config.bookshelfId}.opds`, ORIGIN);
  endpoint.searchParams.set("sort_order", "downloads");
  endpoint.searchParams.set("start_index", String((input.page - 1) * PAGE_SIZE + 1));
  const listXml = await fetchXml(endpoint, fetcher);
  const list = parseListPage(listXml);
  const details = await mapConcurrent(list.ebookIds, 4, async (ebookId) => {
    const detailUrl = new URL(`/ebooks/${ebookId}.opds`, ORIGIN);
    return parseDetailPage(await fetchXml(detailUrl, fetcher), ebookId);
  });
  const revision = sha256(
    JSON.stringify({
      collection: input.collection,
      page: input.page,
      details,
    }),
  );
  const candidates = details.map((detail) => toCandidate(detail, revision));
  return {
    revision,
    candidates,
    collection: input.collection,
    page: input.page,
    hasNextPage: list.hasNextPage,
  };
}

export function parseListPage(content: string) {
  const feed = parseFeed(content);
  const entries = asArray(feed.entry).map(asNode).filter(isNode);
  const ebookIds = [
    ...new Set(
      entries
        .map((entry) => normalizeText(String(entry.id ?? "")))
        .map((id) => /^https:\/\/www\.gutenberg\.org\/ebooks\/(\d+)\.opds$/u.exec(id))
        .map((match) => (match ? Number(match[1]) : null))
        .filter(
          (id): id is number =>
            typeof id === "number" && Number.isSafeInteger(id) && id > 0,
        ),
    ),
  ];
  if (ebookIds.length > PAGE_SIZE) throw new Error("GUTENBERG_INVALID_LIST");
  const links = asArray(feed.link).map(asNode).filter(isNode);
  const hasNextPage = links.some(
    (link) => link["@_rel"] === "next" && normalizeOfficialPath(link["@_href"]),
  );
  return { ebookIds, hasNextPage };
}

export function parseDetailPage(content: string, expectedEbookId: number) {
  const feed = parseFeed(content);
  const entries = asArray(feed.entry).map(asNode).filter(isNode);
  if (entries.length < 1) throw new Error("GUTENBERG_EMPTY_DETAIL");

  const matching = entries.filter((entry) => {
    const value = normalizeText(String(entry.id ?? ""));
    return new RegExp(`^urn:gutenberg:${expectedEbookId}:\\d+$`, "u").test(value);
  });
  if (matching.length < 1) throw new Error("GUTENBERG_DETAIL_ID_MISMATCH");
  const first = matching[0]!;
  const title = cleanText(first.title, 500);
  if (!title) throw new Error("GUTENBERG_DETAIL_WITHOUT_TITLE");
  const authors = [
    ...new Set(
      matching
        .flatMap((entry) => asArray(entry.author))
        .map(asNode)
        .filter(isNode)
        .map((author) => cleanText(author.name, 200))
        .filter((author): author is string => Boolean(author)),
    ),
  ];
  const rights = [
    ...new Set(
      matching
        .map((entry) => cleanText(entry.rights, 500))
        .filter((value): value is string => Boolean(value)),
    ),
  ];
  const subjects = [
    ...new Set(
      matching
        .flatMap((entry) => asArray(entry.category))
        .map(asNode)
        .filter(isNode)
        .filter((category) =>
          String(category["@_scheme"] ?? "").includes("/LCSH"),
        )
        .map((category) => cleanText(category["@_term"], 300))
        .filter((value): value is string => Boolean(value)),
    ),
  ];
  const language = normalizeLanguage(
    matching
      .map((entry) => cleanText(entry["dcterms:language"], 16))
      .find(Boolean) ?? null,
  );
  return { ebookId: expectedEbookId, title, authors, language, rights, subjects };
}

function toCandidate(
  detail: GutenbergDetail,
  revision: string,
): LearningResourceCandidate {
  const publicDomain =
    detail.rights.length > 0 &&
    detail.rights.every((right) => /^Public domain in the USA\.?$/iu.test(right));
  const sourceUrl = `${ORIGIN}/ebooks/${detail.ebookId}`;
  const evidenceUrl = `${ORIGIN}/ebooks/${detail.ebookId}.opds`;
  const base = {
    sourceName: SOURCE_NAME,
    sourceRecordKey: `gutenberg:${detail.ebookId}`,
    title: detail.title,
    author: detail.authors.join(", ").slice(0, 500) || null,
    category: classifyCategory(detail),
    language: detail.language,
    licenseExpression: publicDomain ? ("PUBLIC-DOMAIN" as const) : null,
    licenseUrl: publicDomain ? getVerifiedLicenseUrl("PUBLIC-DOMAIN") : null,
    sourceUrl,
    downloadUrl: null,
    readUrl: publicDomain ? sourceUrl : null,
    verificationStatus: publicDomain
      ? ("VERIFIED" as const)
      : ("QUARANTINED" as const),
    verificationReason: publicDomain
      ? "The official per-book OPDS record declares public-domain status in the USA. Mundia links to the canonical book page as required by Project Gutenberg's linking policy."
      : "The official per-book OPDS rights statement is absent, inconsistent, or identifies a copyrighted permission title.",
    verificationEvidenceUrl: publicDomain ? evidenceUrl : null,
    sourceRevision: revision,
  };
  return { ...base, contentHash: sha256(JSON.stringify(base)) };
}

function classifyCategory(detail: GutenbergDetail) {
  const value = `${detail.title} ${detail.subjects.join(" ")}`;
  if (/(physics|relativit|thermodynamic|quantum|optic|electromagnet|electricity)/iu.test(value)) {
    return "Physics";
  }
  if (/(mathemat|algebra|geometry|calculus|number theory|probability|statistic|logic)/iu.test(value)) {
    return "Mathematics";
  }
  if (/(engineering|mechanic|construction|manufactur|machine|technology|invention)/iu.test(value)) {
    return "Engineering";
  }
  return "Engineering & Science";
}

async function fetchXml(url: URL, fetcher: typeof fetch) {
  if (url.origin !== ORIGIN) throw new Error("GUTENBERG_INVALID_ORIGIN");
  const response = await fetcher(url, {
    headers: {
      Accept: "application/atom+xml, application/xml;q=0.9",
      "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
    },
    redirect: "error",
    signal: AbortSignal.timeout(20_000),
  });
  if (!response.ok) throw new Error(`GUTENBERG_HTTP_${response.status}`);
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("xml")) {
    throw new Error("GUTENBERG_INVALID_CONTENT_TYPE");
  }
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (declaredLength > MAX_RESPONSE_BYTES) {
    throw new Error("GUTENBERG_RESPONSE_TOO_LARGE");
  }
  const content = await response.text();
  if (Buffer.byteLength(content, "utf8") > MAX_RESPONSE_BYTES) {
    throw new Error("GUTENBERG_RESPONSE_TOO_LARGE");
  }
  return content;
}

function parseFeed(content: string): XmlNode {
  if (!content || /<!DOCTYPE|<!ENTITY/iu.test(content)) {
    throw new Error("GUTENBERG_UNSAFE_XML");
  }
  if (XMLValidator.validate(content) !== true) {
    throw new Error("GUTENBERG_INVALID_XML");
  }
  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: "@_",
    parseTagValue: false,
    processEntities: false,
    trimValues: true,
  });
  const feed = asNode((parser.parse(content) as XmlNode).feed);
  if (!feed) throw new Error("GUTENBERG_INVALID_FEED");
  return feed;
}

function normalizeOfficialPath(value: unknown) {
  if (typeof value !== "string") return null;
  try {
    const url = new URL(value, ORIGIN);
    if (url.origin !== ORIGIN || url.username || url.password || url.port) return null;
    return `${url.pathname}${url.search}`;
  } catch {
    return null;
  }
}

function normalizeLanguage(value: string | null) {
  const language = normalizeText(value ?? "").toLowerCase();
  const aliases: Record<string, string> = { eng: "en", fra: "fr", fre: "fr", deu: "de", ger: "de", spa: "es" };
  return aliases[language] ?? (language.slice(0, 16) || "und");
}

function cleanText(value: unknown, max: number) {
  if (typeof value !== "string") return null;
  const normalized = normalizeText(value);
  return normalized ? normalized.slice(0, max) : null;
}

function normalizeText(value: string) {
  return value.replace(/\s+/gu, " ").trim();
}

async function mapConcurrent<T, R>(
  values: T[],
  concurrency: number,
  mapper: (value: T) => Promise<R>,
) {
  const output: R[] = [];
  let cursor = 0;
  const workers = Array.from(
    { length: Math.min(concurrency, values.length) },
    async () => {
      while (cursor < values.length) {
        const index = cursor;
        cursor += 1;
        output[index] = await mapper(values[index]!);
      }
    },
  );
  await Promise.all(workers);
  return output;
}

function asArray(value: unknown): unknown[] {
  if (value === undefined || value === null) return [];
  return Array.isArray(value) ? value : [value];
}

function asNode(value: unknown): XmlNode | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as XmlNode)
    : null;
}

function isNode(value: XmlNode | null): value is XmlNode {
  return value !== null;
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
