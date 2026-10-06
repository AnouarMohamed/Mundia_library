import type { components } from "./schema";

export type LearningResource = components["schemas"]["LearningResource"];
export type LearningResourcePage = components["schemas"]["LearningResourcePage"];
export type Session = components["schemas"]["Session"];

export class ApiError extends Error {
  constructor(readonly status: number, message = "The library service is unavailable") {
    super(message);
  }
}

async function requestJson(path: string, init?: RequestInit): Promise<unknown> {
  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers: { Accept: "application/json", ...init?.headers },
  });
  if (!response.ok) throw new ApiError(response.status);
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) throw new ApiError(502);
  return response.json();
}

export async function getSession(): Promise<Session> {
  const value = await requestJson("/api/v1/auth/session");
  if (!isRecord(value) || typeof value.authenticated !== "boolean") throw new ApiError(502);
  return {
    authenticated: value.authenticated,
    displayName: typeof value.displayName === "string" ? value.displayName : null,
  };
}

export async function searchLearningResources(input: {
  query: string;
  category: string;
  page: number;
  limit?: number;
}): Promise<LearningResourcePage> {
  const query = new URLSearchParams({ page: String(input.page), limit: String(input.limit ?? 24) });
  if (input.query) query.set("query", input.query);
  if (input.category) query.set("category", input.category);
  const value = await requestJson(`/api/v1/catalog/learning-resources?${query}`);
  if (!isResourcePage(value)) throw new ApiError(502);
  return value;
}

export async function getLearningResource(resourceId: string): Promise<LearningResource> {
  const value = await requestJson(`/api/v1/catalog/learning-resources/${encodeURIComponent(resourceId)}`);
  if (!isResource(value) || value.resourceId !== resourceId) throw new ApiError(502);
  return value;
}

export async function getLearningResourceCategories(): Promise<string[]> {
  const value = await requestJson("/api/v1/catalog/learning-resource-categories");
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) throw new ApiError(502);
  return value;
}

export async function authorizeExternalDownload(resourceId: string): Promise<string> {
  const csrf = await requestJson("/api/v1/auth/csrf");
  if (!isRecord(csrf) || typeof csrf.headerName !== "string" || typeof csrf.token !== "string") {
    throw new ApiError(502);
  }
  const authorization = await requestJson(
    `/api/v1/digital-content/external-resources/${encodeURIComponent(resourceId)}/authorizations`,
    { method: "POST", headers: { [csrf.headerName]: csrf.token } },
  );
  if (!isRecord(authorization) || authorization.resourceId !== resourceId ||
      typeof authorization.downloadUrl !== "string" || !isPublicHttpsUrl(authorization.downloadUrl)) {
    throw new ApiError(502);
  }
  return authorization.downloadUrl;
}

export async function logout(): Promise<void> {
  const csrf = await requestJson("/api/v1/auth/csrf");
  if (!isRecord(csrf) || typeof csrf.headerName !== "string" || typeof csrf.token !== "string") {
    throw new ApiError(502);
  }
  const response = await fetch("/api/v1/auth/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: { [csrf.headerName]: csrf.token },
  });
  if (!response.ok && response.status !== 204) throw new ApiError(response.status);
}

export function isPublicHttpsUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !url.username && !url.password && !url.hash &&
      url.port === "" && url.hostname !== "localhost" && !url.hostname.endsWith(".local") &&
      !url.hostname.endsWith(".internal");
  } catch {
    return false;
  }
}

function isResourcePage(value: unknown): value is LearningResourcePage {
  return isRecord(value) && Array.isArray(value.resources) && value.resources.every(isResource) &&
    isNonNegativeInteger(value.total) && isNonNegativeInteger(value.page) &&
    isNonNegativeInteger(value.totalPages);
}

function isResource(value: unknown): value is LearningResource {
  if (!isRecord(value)) return false;
  return isUuid(value.resourceId) && isSafeText(value.title, 500) &&
    (value.author === null || isSafeText(value.author, 500)) &&
    (value.description === null || isSafeText(value.description, 4_000)) &&
    isSafeText(value.category, 128) && isSafeText(value.language, 16) &&
    (value.coverUrl === null || (typeof value.coverUrl === "string" && isPublicHttpsUrl(value.coverUrl))) &&
    (value.coverAlt === null || isSafeText(value.coverAlt, 300)) &&
    isSafeText(value.sourceName, 100) && typeof value.sourceUrl === "string" && isPublicHttpsUrl(value.sourceUrl) &&
    ["CC-BY", "CC-BY-SA", "CC0", "PUBLIC-DOMAIN"].includes(String(value.licenseExpression)) &&
    typeof value.licenseUrl === "string" && isPublicHttpsUrl(value.licenseUrl) &&
    ((value.accessMode === "DOWNLOAD" && value.readUrl === null) ||
      (value.accessMode === "READ_AT_SOURCE" && typeof value.readUrl === "string" && isPublicHttpsUrl(value.readUrl)));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isSafeText(value: unknown, maximum: number): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= maximum && value === value.trim();
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(value);
}
