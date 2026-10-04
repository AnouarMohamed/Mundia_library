import { createHash } from "crypto";
import { classifyResourceLicense } from "./license-policy";
import type {
  LearningResourceCandidate,
  LearningResourceSourceSnapshot,
} from "./types";

const SOURCE_NAME = "EbookFoundation/free-programming-books";
const CONTENTS_ENDPOINT =
  "https://api.github.com/repos/EbookFoundation/free-programming-books/contents/books/free-programming-books-subjects.md";
const MAX_SOURCE_BYTES = 1_000_000;

interface GitHubContentsResponse {
  content?: unknown;
  encoding?: unknown;
  sha?: unknown;
  size?: unknown;
  type?: unknown;
}

export async function fetchEbookFoundationSnapshot(
  options: { revision?: string; fetcher?: typeof fetch } = {},
): Promise<LearningResourceSourceSnapshot> {
  const revision = options.revision ?? "main";
  if (revision !== "main" && !/^[0-9a-f]{40}$/.test(revision)) {
    throw new Error("EBOOK_FOUNDATION_INVALID_REVISION");
  }
  const endpoint = new URL(CONTENTS_ENDPOINT);
  endpoint.searchParams.set("ref", revision);
  const response = await (options.fetcher ?? fetch)(endpoint, {
    headers: {
      Accept: "application/vnd.github+json",
      "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
      "X-GitHub-Api-Version": "2022-11-28",
    },
    redirect: "error",
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) {
    throw new Error(`EBOOK_FOUNDATION_HTTP_${response.status}`);
  }
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (declaredLength > MAX_SOURCE_BYTES * 2) {
    throw new Error("EBOOK_FOUNDATION_RESPONSE_TOO_LARGE");
  }

  const payload = (await response.json()) as GitHubContentsResponse;
  if (
    payload.type !== "file" ||
    payload.encoding !== "base64" ||
    typeof payload.content !== "string" ||
    typeof payload.sha !== "string" ||
    !/^[0-9a-f]{40}$/.test(payload.sha) ||
    typeof payload.size !== "number" ||
    payload.size < 1 ||
    payload.size > MAX_SOURCE_BYTES
  ) {
    throw new Error("EBOOK_FOUNDATION_INVALID_RESPONSE");
  }

  const content = Buffer.from(
    payload.content.replaceAll("\n", ""),
    "base64",
  ).toString("utf8");
  if (Buffer.byteLength(content, "utf8") !== payload.size) {
    throw new Error("EBOOK_FOUNDATION_SIZE_MISMATCH");
  }
  return { revision: payload.sha, content };
}

export function parseEbookFoundationCatalog(
  snapshot: LearningResourceSourceSnapshot,
): LearningResourceCandidate[] {
  if (!/^[0-9a-f]{40}$/.test(snapshot.revision)) {
    throw new Error("EBOOK_FOUNDATION_INVALID_REVISION");
  }

  const candidates: LearningResourceCandidate[] = [];
  let category = "General";

  for (const rawLine of snapshot.content.split(/\r?\n/u)) {
    const heading = /^(#{3,4})\s+(.+?)\s*$/u.exec(rawLine);
    if (heading) {
      category = normalizeText(heading[2]).slice(0, 128) || "General";
      continue;
    }
    const parsed = parseBookLine(rawLine);
    if (!parsed) continue;

    const licence = classifyResourceLicense(rawLine);
    const directDownload = isDirectDownloadUrl(parsed.url)
      ? parsed.url
      : null;
    const sourceRecordKey = sha256(canonicalizeSourceUrl(parsed.url));
    const base = {
      sourceName: SOURCE_NAME,
      sourceRecordKey,
      title: parsed.title,
      author: parsed.author,
      category,
      language: "en",
      licenseExpression: licence.expression,
      licenseUrl: licence.licenseUrl,
      sourceUrl: parsed.url,
      downloadUrl: directDownload,
      readUrl: directDownload ? null : parsed.url,
      verificationStatus: licence.verified
        ? ("VERIFIED" as const)
        : ("QUARANTINED" as const),
      verificationReason: licence.reason,
      verificationEvidenceUrl: licence.verified
        ? `https://github.com/EbookFoundation/free-programming-books/blob/${snapshot.revision}/books/free-programming-books-subjects.md`
        : null,
      sourceRevision: snapshot.revision,
    };
    candidates.push({
      ...base,
      contentHash: sha256(JSON.stringify(base)),
    });
  }

  return deduplicate(candidates);
}

function parseBookLine(line: string): {
  title: string;
  author: string | null;
  url: string;
} | null {
  const prefix = /^\s*\*\s+\[/u.exec(line);
  if (!prefix) return null;
  const titleEnd = line.indexOf("](", prefix[0].length);
  if (titleEnd < 0) return null;
  const title = normalizeText(line.slice(prefix[0].length, titleEnd));
  const urlStart = titleEnd + 2;
  const urlEnd = findMarkdownUrlEnd(line, urlStart);
  if (!title || urlEnd < 0) return null;

  const rawUrl = line.slice(urlStart, urlEnd).trim();
  const url = normalizeHttpsUrl(rawUrl);
  if (!url) return null;

  const suffix = line.slice(urlEnd + 1).replace(/^\s*-\s*/u, "").trim();
  const author = normalizeAuthor(suffix);
  return { title: title.slice(0, 500), author, url };
}

function findMarkdownUrlEnd(line: string, start: number) {
  let depth = 0;
  for (let index = start; index < line.length; index += 1) {
    const character = line[index];
    if (character === "(") depth += 1;
    if (character === ")") {
      if (depth === 0) return index;
      depth -= 1;
    }
  }
  return -1;
}

function normalizeAuthor(value: string): string | null {
  if (!value) return null;
  const withoutAnnotations = value
    .replace(/\s*\([^()]*(?:PDF|EPUB|HTML|CC\s|CC0|PDM|PUBLIC DOMAIN|ARCHIVED|IN PROCESS)[^()]*\)\s*/giu, " ")
    .replace(/\s*\*\([^)]*\)\*\s*/gu, " ");
  const normalized = normalizeText(withoutAnnotations).replace(/^[-–—]\s*/u, "");
  return normalized ? normalized.slice(0, 500) : null;
}

function normalizeText(value: string) {
  return value
    .replace(/[*_`]/gu, "")
    .replace(/\s+/gu, " ")
    .trim();
}

function normalizeHttpsUrl(value: string): string | null {
  try {
    const url = new URL(value);
    if (url.protocol === "http:") url.protocol = "https:";
    if (
      url.protocol !== "https:" ||
      url.username ||
      url.password ||
      url.port ||
      !url.hostname
    ) {
      return null;
    }
    url.hash = "";
    return url.toString();
  } catch {
    return null;
  }
}

function canonicalizeSourceUrl(value: string) {
  const url = new URL(value);
  url.hostname = url.hostname.toLowerCase();
  url.searchParams.sort();
  return url.toString();
}

function isDirectDownloadUrl(value: string) {
  const path = new URL(value).pathname.toLowerCase();
  return path.endsWith(".pdf") || path.endsWith(".epub");
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}

function deduplicate(candidates: LearningResourceCandidate[]) {
  const unique = new Map<string, LearningResourceCandidate>();
  for (const candidate of candidates) {
    const existing = unique.get(candidate.sourceRecordKey);
    if (!existing || candidate.verificationStatus === "VERIFIED") {
      unique.set(candidate.sourceRecordKey, candidate);
    }
  }
  return [...unique.values()];
}
