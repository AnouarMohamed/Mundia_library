import { createHash } from "crypto";
import { getVerifiedLicenseUrl } from "./license-policy";
import type {
  LearningResourceCandidate,
  VerifiedLicenseExpression,
} from "./types";

const SOURCE_NAME = "Open Textbook Library";
const API_ORIGIN = "https://open.umn.edu";
const MAX_RESPONSE_BYTES = 5_000_000;
const ADAPTER_VERSION = "otl-v2";

export const OTL_SUBJECTS = {
  3: "Computer Science",
  7: "Mathematics",
  13: "Engineering & Technology",
  21: "Management",
  23: "Information Systems",
  35: "Applied Mathematics",
  39: "Physics",
  53: "Civil Engineering",
  54: "Electrical Engineering",
  56: "Mechanical Engineering",
  59: "Programming Languages",
  61: "Databases",
  82: "Statistics",
} as const;

const LICENSES: Record<string, VerifiedLicenseExpression> = {
  Attribution: "CC-BY",
  "Attribution-ShareAlike": "CC-BY-SA",
  "No Rights Reserved": "CC0",
};

interface OtlRecord {
  id?: unknown;
  title?: unknown;
  license?: unknown;
  language?: unknown;
  contributors?: unknown;
  subjects?: unknown;
  formats?: unknown;
  url?: unknown;
  updated_at?: unknown;
}

interface OtlPageResponse {
  data?: unknown;
  links?: unknown;
}

export interface OtlPage {
  revision: string;
  candidates: LearningResourceCandidate[];
  page: number;
  totalPages: number;
  totalCount: number;
}

export async function fetchOpenTextbookPage(input: {
  subjectId: number;
  page: number;
  fetcher?: typeof fetch;
}): Promise<OtlPage> {
  if (!Object.hasOwn(OTL_SUBJECTS, input.subjectId)) {
    throw new Error("OTL_INVALID_SUBJECT");
  }
  if (
    !Number.isSafeInteger(input.page) ||
    input.page < 1 ||
    input.page > 1_000
  ) {
    throw new Error("OTL_INVALID_PAGE");
  }
  const endpoint = new URL(
    `/opentextbooks/subjects/${input.subjectId}.json`,
    API_ORIGIN,
  );
  for (const license of Object.keys(LICENSES)) {
    endpoint.searchParams.append("licenses[]", license);
  }
  endpoint.searchParams.append("formats[]", "PDF");
  endpoint.searchParams.set("page", String(input.page));

  const response = await (input.fetcher ?? fetch)(endpoint, {
    headers: {
      Accept: "application/json",
      "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
    },
    redirect: "error",
    signal: AbortSignal.timeout(20_000),
  });
  if (!response.ok) throw new Error(`OTL_HTTP_${response.status}`);
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("json")) {
    throw new Error("OTL_INVALID_CONTENT_TYPE");
  }
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (declaredLength > MAX_RESPONSE_BYTES)
    throw new Error("OTL_RESPONSE_TOO_LARGE");
  const content = await response.text();
  if (Buffer.byteLength(content, "utf8") > MAX_RESPONSE_BYTES) {
    throw new Error("OTL_RESPONSE_TOO_LARGE");
  }
  return parseOpenTextbookPage(content, input.subjectId, input.page);
}

export function parseOpenTextbookPage(
  content: string,
  subjectId: number,
  page: number,
): OtlPage {
  if (!Object.hasOwn(OTL_SUBJECTS, subjectId))
    throw new Error("OTL_INVALID_SUBJECT");
  let payload: OtlPageResponse;
  try {
    payload = JSON.parse(content) as OtlPageResponse;
  } catch {
    throw new Error("OTL_INVALID_JSON");
  }
  if (!Array.isArray(payload.data) || !isObject(payload.links)) {
    throw new Error("OTL_INVALID_RESPONSE");
  }
  const totalPages = toBoundedInteger(payload.links.total_pages, 0, 1_000);
  const totalCount = toBoundedInteger(payload.links.total_count, 0, 100_000);
  if (totalPages === null || totalCount === null)
    throw new Error("OTL_INVALID_RESPONSE");

  const records = payload.data.filter(isObject) as OtlRecord[];
  const revision = sha256(
    JSON.stringify({
      adapterVersion: ADAPTER_VERSION,
      records: records.map((record) => ({
        id: record.id,
        updatedAt: record.updated_at,
        title: record.title,
        license: record.license,
        formats: record.formats,
        subjects: record.subjects,
      })),
    }),
  );
  const candidates = records
    .map((record) => parseRecord(record, subjectId, revision))
    .filter((candidate): candidate is LearningResourceCandidate =>
      Boolean(candidate),
    );
  return { revision, candidates, page, totalPages, totalCount };
}

function parseRecord(
  record: OtlRecord,
  subjectId: number,
  revision: string,
): LearningResourceCandidate | null {
  if (!Number.isSafeInteger(record.id) || Number(record.id) < 1) return null;
  const id = Number(record.id);
  const title = cleanText(record.title, 500);
  const sourceUrl = normalizeHttpsUrl(record.url);
  if (!title || !sourceUrl) return null;

  const expression =
    typeof record.license === "string" ? LICENSES[record.license] : undefined;
  const formats = Array.isArray(record.formats)
    ? record.formats.filter(isObject)
    : [];
  const pdf = formats.find(
    (format) =>
      String(format.type ?? format.format ?? "").toUpperCase() === "PDF" &&
      normalizeHttpsUrl(format.url),
  );
  const formatUrl = normalizeHttpsUrl(pdf?.url);
  const downloadUrl =
    formatUrl && isDirectDownloadUrl(formatUrl) ? formatUrl : null;
  const authors = Array.isArray(record.contributors)
    ? record.contributors
        .filter(isObject)
        .filter((contributor) => contributor.contribution === "Author")
        .map((contributor) =>
          cleanText(
            contributor.corporate
              ? contributor.last_name
              : [
                  contributor.first_name,
                  contributor.middle_name,
                  contributor.last_name,
                ]
                  .filter(Boolean)
                  .join(" "),
            200,
          ),
        )
        .filter((value): value is string => Boolean(value))
    : [];
  const verified = Boolean(expression && formatUrl);
  const base = {
    sourceName: SOURCE_NAME,
    sourceRecordKey: `otl:${id}`,
    title,
    author: authors.join(", ").slice(0, 500) || null,
    category: resolveCategory(record, subjectId, title),
    language: normalizeLanguage(record.language),
    licenseExpression: verified ? expression! : null,
    licenseUrl: verified ? getVerifiedLicenseUrl(expression!) : null,
    sourceUrl,
    downloadUrl,
    readUrl: downloadUrl ? null : (formatUrl ?? sourceUrl),
    verificationStatus: verified
      ? ("VERIFIED" as const)
      : ("QUARANTINED" as const),
    verificationReason: verified
      ? `Verified from the per-book ${record.license as string} licence and PDF format in the official Open Textbook Library record.`
      : "The record did not contain both an allowlisted licence and an official PDF-format link.",
    verificationEvidenceUrl: verified ? sourceUrl : null,
    sourceRevision: revision,
  };
  return { ...base, contentHash: sha256(JSON.stringify(base)) };
}

function isDirectDownloadUrl(value: string) {
  const url = new URL(value);
  const path = url.pathname.toLowerCase();
  const requestedType = url.searchParams.get("type")?.toLowerCase();
  return (
    path.endsWith(".pdf") ||
    path.endsWith(".epub") ||
    ((requestedType === "pdf" || requestedType === "epub") &&
      path.endsWith("/open/download"))
  );
}

function resolveCategory(record: OtlRecord, subjectId: number, title: string) {
  const normalizedTitle = title.toLowerCase();
  if (
    /aerodynamics|aircraft|aviation|airplane|flight\b/u.test(normalizedTitle)
  ) {
    return "Aerospace Engineering";
  }
  if (
    /industrial engineering|manufacturing|operations research|process improvement|production systems|lean operations/u.test(
      normalizedTitle,
    )
  ) {
    return "Industrial Engineering";
  }
  if (
    /cybersecurity|cyber security|information security|cryptograph/u.test(
      normalizedTitle,
    )
  ) {
    return "Cybersecurity";
  }
  if (
    /cloud computing|cloud-native|devops|kubernetes|containers?\b/u.test(
      normalizedTitle,
    )
  ) {
    return "Cloud & DevOps";
  }
  const subjectNames = Array.isArray(record.subjects)
    ? record.subjects
        .filter(isObject)
        .map((subject) => cleanText(subject.name, 100))
        .filter((value): value is string => Boolean(value))
    : [];
  const priority: ReadonlyArray<readonly [string, string]> = [
    ["Databases", "Databases"],
    ["Information Systems", "Information Systems"],
    ["Programming Languages", "Programming Languages"],
    ["Electrical Engineering", "Electrical Engineering"],
    ["Mechanical Engineering", "Mechanical Engineering"],
    ["Civil Engineering", "Civil Engineering"],
    ["Statistics", "Statistics"],
    ["Applied", "Applied Mathematics"],
    ["Physics", "Physics"],
    ["Management", "Management"],
  ];
  return (
    priority.find(([source]) => subjectNames.includes(source))?.[1] ??
    OTL_SUBJECTS[subjectId as keyof typeof OTL_SUBJECTS]
  );
}

function normalizeLanguage(value: unknown) {
  const language = cleanText(value, 16)?.toLowerCase() ?? "und";
  const aliases: Record<string, string> = {
    eng: "en",
    fra: "fr",
    fre: "fr",
    deu: "de",
    ger: "de",
    spa: "es",
  };
  return aliases[language] ?? language;
}

function normalizeHttpsUrl(value: unknown): string | null {
  if (typeof value !== "string") return null;
  try {
    const url = new URL(value);
    if (url.protocol === "http:") url.protocol = "https:";
    if (
      url.protocol !== "https:" ||
      url.username ||
      url.password ||
      url.port ||
      !url.hostname
    )
      return null;
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

function toBoundedInteger(value: unknown, min: number, max: number) {
  return Number.isSafeInteger(value) &&
    Number(value) >= min &&
    Number(value) <= max
    ? Number(value)
    : null;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
