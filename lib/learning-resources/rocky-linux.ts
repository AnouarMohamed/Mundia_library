import { createHash } from "crypto";
import { getVerifiedLicenseUrl } from "./license-policy";
import type { LearningResourceCandidate } from "./types";

export const ROCKY_LINUX_SOURCE_NAME = "Rocky Linux Documentation";
export const ROCKY_LINUX_LICENSE_URL =
  "https://raw.githubusercontent.com/rocky-linux/documentation/main/LICENSE.md";
const PDF_ORIGIN = "https://rocky-linux.github.io";
const MAX_LICENSE_BYTES = 100_000;

interface RockyLinuxBook {
  key: string;
  title: string;
  description: string;
  category: string;
  pdfUrl: string;
}

export const ROCKY_LINUX_BOOKS: readonly RockyLinuxBook[] = [
  {
    key: "administration-guide",
    title: "Rocky Linux Administration Guide",
    description:
      "A 332-page Linux administration course covering the shell, files, users, permissions, processes, services, storage, networking, security, and system operations.",
    category: "Linux & UNIX Administration",
    pdfUrl:
      "https://rocky-linux.github.io/documentation/RockyLinuxAdminGuide.pdf",
  },
  {
    key: "web-services-guide",
    title: "Rocky Linux Web Services Guide",
    description:
      "A 145-page operations guide to HTTP, Apache, Nginx, application servers, proxies, certificates, logging, and production web-service administration.",
    category: "Linux Networking & Services",
    pdfUrl:
      "https://rocky-linux.github.io/documentation/RockyLinuxWebServicesGuide.pdf",
  },
] as const;

export interface RockyLinuxBatch {
  revision: string;
  candidates: LearningResourceCandidate[];
}

export async function fetchRockyLinuxBooks(
  input: { fetcher?: typeof fetch } = {},
): Promise<RockyLinuxBatch> {
  const fetcher = input.fetcher ?? fetch;
  const license = await fetchLicense(fetcher);
  if (
    !license.includes(
      "Creative Commons Attribution-ShareAlike 4.0 International",
    )
  ) {
    throw new Error("ROCKY_LINUX_LICENSE_NOT_VERIFIED");
  }
  const versions = await Promise.all(
    ROCKY_LINUX_BOOKS.map(async (book) => ({
      key: book.key,
      version: await verifyPdf(book.pdfUrl, fetcher),
    })),
  );
  const revision = sha256(
    JSON.stringify({ books: ROCKY_LINUX_BOOKS, versions }),
  );
  return {
    revision,
    candidates: ROCKY_LINUX_BOOKS.map((book) => toCandidate(book, revision)),
  };
}

async function fetchLicense(fetcher: typeof fetch) {
  const response = await fetcher(ROCKY_LINUX_LICENSE_URL, {
    headers: {
      Accept: "text/plain",
      "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
    },
    redirect: "error",
    signal: AbortSignal.timeout(20_000),
  });
  const type = (response.headers.get("content-type") ?? "").toLowerCase();
  const declared = Number(response.headers.get("content-length") ?? 0);
  if (!response.ok || !type.includes("text/plain")) {
    throw new Error(`ROCKY_LINUX_LICENSE_HTTP_${response.status}`);
  }
  if (declared > MAX_LICENSE_BYTES)
    throw new Error("ROCKY_LINUX_LICENSE_TOO_LARGE");
  const text = await response.text();
  if (Buffer.byteLength(text, "utf8") > MAX_LICENSE_BYTES) {
    throw new Error("ROCKY_LINUX_LICENSE_TOO_LARGE");
  }
  return text;
}

async function verifyPdf(value: string, fetcher: typeof fetch) {
  const url = new URL(value);
  if (
    url.origin !== PDF_ORIGIN ||
    url.search ||
    url.hash ||
    !/^\/documentation\/[A-Za-z0-9._-]+\.pdf$/u.test(url.pathname)
  ) {
    throw new Error("ROCKY_LINUX_INVALID_PDF_URL");
  }
  const response = await fetcher(url, {
    method: "HEAD",
    headers: {
      Accept: "application/pdf",
      "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
    },
    redirect: "error",
    signal: AbortSignal.timeout(20_000),
  });
  const type = (response.headers.get("content-type") ?? "").toLowerCase();
  if (!response.ok || !type.startsWith("application/pdf")) {
    throw new Error(`ROCKY_LINUX_PDF_HTTP_${response.status}`);
  }
  await response.body?.cancel();
  return {
    etag: response.headers.get("etag"),
    lastModified: response.headers.get("last-modified"),
    contentLength: response.headers.get("content-length"),
  };
}

function toCandidate(
  book: RockyLinuxBook,
  revision: string,
): LearningResourceCandidate {
  const base = {
    sourceName: ROCKY_LINUX_SOURCE_NAME,
    sourceRecordKey: `rocky-linux:${book.key}`,
    title: book.title,
    author: "Rocky Linux Documentation Team",
    description: book.description,
    coverUrl: null,
    coverAlt: null,
    category: book.category,
    language: "en",
    licenseExpression: "CC-BY-SA" as const,
    licenseUrl: getVerifiedLicenseUrl("CC-BY-SA"),
    sourceUrl: book.pdfUrl,
    downloadUrl: book.pdfUrl,
    readUrl: "https://docs.rockylinux.org/",
    verificationStatus: "VERIFIED" as const,
    verificationReason:
      "Verified against the official Rocky Linux documentation CC BY-SA 4.0 licence and live official PDF endpoint.",
    verificationEvidenceUrl: ROCKY_LINUX_LICENSE_URL,
    sourceRevision: revision,
  };
  return { ...base, contentHash: sha256(JSON.stringify(base)) };
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
