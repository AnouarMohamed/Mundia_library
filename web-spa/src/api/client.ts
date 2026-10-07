import type { components } from "./schema";

export type LearningResource = components["schemas"]["LearningResource"];
export type LearningResourcePage = components["schemas"]["LearningResourcePage"];
export type CatalogEdition = components["schemas"]["CatalogEdition"];
export type CatalogSearch = components["schemas"]["CatalogSearch"];
export type CirculationEligibility = components["schemas"]["CirculationEligibility"];
export type LoanCommand = components["schemas"]["LoanCommand"];
export type LoanHistoryItem = components["schemas"]["LoanHistoryItem"];
export type MemberLoanPage = components["schemas"]["MemberLoanPage"];
export type MemberReservationPage = components["schemas"]["MemberReservationPage"];
export type Reservation = components["schemas"]["ReservationCommand"];
export type MemberProfile = components["schemas"]["MemberProfile"];
export type NotificationItem = components["schemas"]["NotificationItem"];
export type NotificationPage = components["schemas"]["NotificationPage"];
export type NotificationPreference = components["schemas"]["NotificationPreference"];
export type UpdateNotificationPreference = components["schemas"]["UpdateNotificationPreference"];
export type EditionDownloadAvailability = components["schemas"]["EditionDownloadAvailability"];
export type DownloadableFormat = components["schemas"]["DownloadableFormat"];
export type DownloadAuthorization = components["schemas"]["DownloadAuthorization"];
export type Session = components["schemas"]["Session"];
export type AdminMemberPage = components["schemas"]["AdminMemberPage"];
export type AdminMember = components["schemas"]["AdminMemberSummary"];
export type AccountStatus = components["schemas"]["AccountStatus"];
export type MembershipCommand = components["schemas"]["MembershipCommand"];
export type CommandResult<T> = { record: T; replayed: boolean };
export type VersionedNotificationPreference = { preference: NotificationPreference; entityTag: string };
export type NotificationReadStatus = "ALL" | "READ" | "UNREAD";

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

export async function getMemberProfile(signal?: AbortSignal): Promise<MemberProfile> {
  const value = await requestJson("/api/v1/membership/profile", { signal });
  if (!isMemberProfile(value)) throw new ApiError(502);
  return value;
}

export async function getAdminMembers(
  status: AccountStatus = "PENDING",
  cursor?: string,
  limit = 25,
  signal?: AbortSignal,
): Promise<AdminMemberPage> {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100 ||
      (cursor !== undefined && !isAdminCursor(cursor))) throw new ApiError(400);
  const query = new URLSearchParams({ status, limit: String(limit) });
  if (cursor) query.set("cursor", cursor);
  const value = await requestJson(`/api/v1/admin/members?${query}`, { signal });
  if (!isAdminMemberPage(value, status, limit)) throw new ApiError(502);
  return value;
}

export async function changeAdminMemberStatus(
  member: AdminMember,
  status: Exclude<AccountStatus, "PENDING">,
  reason: string,
): Promise<MembershipCommand> {
  if (!isAdminMember(member) || !["APPROVED", "REJECTED"].includes(status) ||
      !isSafeMultilineText(reason.trim(), 500) || reason.trim().length < 8) throw new ApiError(400);
  const csrf = await getCsrfToken();
  const response = await fetch(`/api/v1/admin/members/${encodeURIComponent(member.memberId)}/status`, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "If-Match": `"${member.aggregateVersion}"`,
      "Idempotency-Key": `spa:member-status:${crypto.randomUUID()}`,
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify({ status, reason: reason.trim() }),
  });
  const value = await responseJson(response);
  const entityTag = response.headers.get("etag");
  const replayed = response.headers.get("idempotency-replayed");
  if (!isMembershipCommand(value) || value.memberId !== member.memberId || value.status !== status ||
      value.aggregateVersion <= member.aggregateVersion || entityTag !== `"${value.aggregateVersion}"` ||
      (replayed !== "true" && replayed !== "false") || value.replayed !== (replayed === "true")) {
    throw new ApiError(502);
  }
  return value;
}

export async function getNotifications(
  status: NotificationReadStatus,
  cursor?: string,
  signal?: AbortSignal,
): Promise<NotificationPage> {
  if (!["ALL", "READ", "UNREAD"].includes(status) || (cursor !== undefined && !isNotificationCursor(cursor))) {
    throw new ApiError(400);
  }
  const query = new URLSearchParams({ status, limit: String(NOTIFICATION_PAGE_SIZE) });
  if (cursor) query.set("cursor", cursor);
  const value = await requestJson(`/api/v1/notifications?${query}`, { signal });
  if (!isNotificationPage(value, NOTIFICATION_PAGE_SIZE, status)) throw new ApiError(502);
  return value;
}

export async function markNotificationRead(notificationId: string): Promise<NotificationItem> {
  validateCommandIdentifiers(notificationId);
  const csrf = await getCsrfToken();
  const value = await mutationJson(
    `/api/v1/notifications/${encodeURIComponent(notificationId)}/read`,
    { method: "PATCH", headers: { [csrf.headerName]: csrf.token } },
  );
  if (!isNotificationItem(value) || value.notificationId !== notificationId || value.readAt === null) {
    throw new ApiError(502);
  }
  return value;
}

export async function getNotificationPreference(signal?: AbortSignal): Promise<VersionedNotificationPreference> {
  const response = await fetch("/api/v1/notifications/preferences", {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
    signal,
  });
  const value = await responseJson(response);
  if (!isNotificationPreference(value)) throw new ApiError(502);
  const entityTag = response.headers.get("etag");
  if (!entityTag || !isEntityTag(entityTag) || entityTag !== `"${value.version}"`) throw new ApiError(502);
  return { preference: value, entityTag };
}

export async function updateNotificationPreference(
  memberId: string,
  entityTag: string,
  update: UpdateNotificationPreference,
): Promise<VersionedNotificationPreference> {
  validateCommandIdentifiers(memberId);
  if (!isEntityTag(entityTag) || !isNotificationPreferenceUpdate(update)) throw new ApiError(400);
  const csrf = await getCsrfToken();
  const response = await fetch("/api/v1/notifications/preferences", {
    method: "PUT",
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "If-Match": entityTag,
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify(update),
  });
  const value = await responseJson(response);
  if (!isNotificationPreference(value) || value.memberId !== memberId) throw new ApiError(502);
  const nextEntityTag = response.headers.get("etag");
  if (!nextEntityTag || !isEntityTag(nextEntityTag) || nextEntityTag !== `"${value.version}"` ||
      value.version <= Number(entityTag.slice(1, -1))) throw new ApiError(502);
  return { preference: value, entityTag: nextEntityTag };
}

export async function searchCatalog(input: {
  query: string;
  page: number;
  availableOnly?: boolean;
  limit?: number;
  signal?: AbortSignal;
}): Promise<CatalogSearch> {
  const limit = input.limit ?? CATALOG_PAGE_SIZE;
  if (input.query.length > 200 || !Number.isSafeInteger(input.page) || input.page < 0 || input.page > 10_000 ||
      !Number.isSafeInteger(limit) || limit < 1 || limit > 100) throw new ApiError(400);
  const query = new URLSearchParams({ page: String(input.page), limit: String(limit), sortBy: "title" });
  const search = input.query.trim();
  if (search) query.set("query", search);
  if (input.availableOnly) query.set("availableOnly", "true");
  const value = await requestJson(`/api/v1/catalog/search?${query}`, { signal: input.signal });
  if (!isCatalogSearch(value, input.page, limit)) throw new ApiError(502);
  return value;
}

export async function getCirculationEligibility(signal?: AbortSignal): Promise<CirculationEligibility> {
  const value = await requestJson("/api/v1/circulation/eligibility", { signal });
  if (!isEligibility(value)) throw new ApiError(502);
  return value;
}

export async function getEditionDownloadAvailability(
  editionId: string,
  signal?: AbortSignal,
): Promise<EditionDownloadAvailability> {
  validateCommandIdentifiers(editionId);
  const value = await requestJson(
    `/api/v1/digital-content/editions/${encodeURIComponent(editionId)}/availability`,
    { signal },
  );
  if (!isEditionDownloadAvailability(value) || value.editionId !== editionId) throw new ApiError(502);
  return value;
}

export async function authorizeEditionDownload(assetId: string): Promise<DownloadAuthorization> {
  validateCommandIdentifiers(assetId);
  const csrf = await getCsrfToken();
  const value = await mutationJson(
    `/api/v1/digital-content/assets/${encodeURIComponent(assetId)}/authorizations`,
    { method: "POST", headers: { [csrf.headerName]: csrf.token } },
  );
  if (!isRecord(value) || !isUuid(value.authorizationId) || value.assetId !== assetId ||
      typeof value.downloadUrl !== "string" || !isSignedDownloadUrl(value.downloadUrl) ||
      !isIsoInstant(value.expiresAt) || !isShortLivedExpiry(value.expiresAt)) throw new ApiError(502);
  return value as DownloadAuthorization;
}

export async function getLoanPage(cursor?: string, signal?: AbortSignal): Promise<MemberLoanPage> {
  const query = historyQuery(cursor);
  const value = await requestJson(`/api/v1/circulation/loans?${query}`, { signal });
  if (!isLoanPage(value, HISTORY_PAGE_SIZE)) throw new ApiError(502);
  return value;
}

export async function getReservationPage(cursor?: string, signal?: AbortSignal): Promise<MemberReservationPage> {
  const query = historyQuery(cursor);
  const value = await requestJson(`/api/v1/circulation/reservations?${query}`, { signal });
  if (!isReservationPage(value, HISTORY_PAGE_SIZE)) throw new ApiError(502);
  return value;
}

export async function getCatalogEditions(editionIds: string[], signal?: AbortSignal): Promise<CatalogEdition[]> {
  if (editionIds.length === 0) return [];
  if (editionIds.length > MAXIMUM_EDITION_BATCH || editionIds.some((id) => !isUuid(id)) ||
      new Set(editionIds).size !== editionIds.length) throw new ApiError(400);
  const query = new URLSearchParams();
  editionIds.forEach((editionId) => query.append("editionId", editionId));
  const value = await requestJson(`/api/v1/catalog/editions?${query}`, { signal });
  if (!Array.isArray(value) || value.length > editionIds.length || value.some((item) => !isCatalogEdition(item))) {
    throw new ApiError(502);
  }
  const positions = new Map(editionIds.map((id, index) => [id, index]));
  const returnedIds = value.map((edition) => edition.editionId);
  if (new Set(returnedIds).size !== returnedIds.length || returnedIds.some((id) => !positions.has(id)) ||
      returnedIds.some((id, index) => index > 0 && positions.get(id)! <= positions.get(returnedIds[index - 1])!)) {
    throw new ApiError(502);
  }
  return value;
}

export async function getCirculationOverview(signal?: AbortSignal) {
  const [eligibility, loans, reservations] = await Promise.all([
    getCirculationEligibility(signal),
    getLoanPage(undefined, signal),
    getReservationPage(undefined, signal),
  ]);
  if (loans.memberId !== eligibility.memberId || reservations.memberId !== eligibility.memberId) {
    throw new ApiError(502);
  }
  const editionIds = Array.from(new Set([
    ...loans.items.map((loan) => loan.editionId),
    ...reservations.items.map((reservation) => reservation.editionId),
  ]));
  const editions = await getCatalogEditions(editionIds, signal);
  return { eligibility, loans, reservations, editions };
}

export async function requestLoan(
  editionId: string,
  memberId: string,
): Promise<CommandResult<LoanCommand>> {
  validateCommandIdentifiers(editionId, memberId);
  const result = await circulationCommand<LoanCommand>(
    "/api/v1/circulation/loans",
    "request-loan",
    201,
    { editionId },
  );
  if (!isLoanCommand(result.record) || result.record.memberId !== memberId ||
      result.record.editionId !== editionId || result.record.status !== "REQUESTED" || result.record.version !== 0) {
    throw new ApiError(502);
  }
  return result;
}

export async function cancelLoan(
  loanId: string,
  memberId: string,
  editionId: string,
): Promise<CommandResult<LoanCommand>> {
  validateCommandIdentifiers(loanId, memberId, editionId);
  const result = await circulationCommand<LoanCommand>(
    `/api/v1/circulation/loans/${encodeURIComponent(loanId)}/cancel`,
    "cancel-loan",
    200,
  );
  if (!isLoanCommand(result.record) || result.record.loanId !== loanId || result.record.memberId !== memberId ||
      result.record.editionId !== editionId || result.record.status !== "CANCELLED" || result.record.version < 1) {
    throw new ApiError(502);
  }
  return result;
}

export async function renewLoan(
  loanId: string,
  memberId: string,
  editionId: string,
): Promise<CommandResult<LoanCommand>> {
  validateCommandIdentifiers(loanId, memberId, editionId);
  const result = await circulationCommand<LoanCommand>(
    `/api/v1/circulation/loans/${encodeURIComponent(loanId)}/renew`,
    "renew-loan",
    200,
  );
  if (!isLoanCommand(result.record) || result.record.loanId !== loanId || result.record.memberId !== memberId ||
      result.record.editionId !== editionId || result.record.status !== "ACTIVE" ||
      result.record.renewalCount < 1 || result.record.version < 2) throw new ApiError(502);
  return result;
}

export async function placeReservation(
  editionId: string,
  memberId: string,
): Promise<CommandResult<Reservation>> {
  validateCommandIdentifiers(editionId, memberId);
  const result = await circulationCommand<Reservation>(
    "/api/v1/circulation/reservations",
    "place-reservation",
    201,
    { editionId },
  );
  if (!isReservation(result.record) || result.record.memberId !== memberId ||
      result.record.editionId !== editionId || !["WAITING", "READY"].includes(result.record.status)) {
    throw new ApiError(502);
  }
  return result;
}

export async function cancelReservation(
  reservationId: string,
  memberId: string,
  editionId: string,
): Promise<CommandResult<Reservation>> {
  validateCommandIdentifiers(reservationId, memberId, editionId);
  const result = await circulationCommand<Reservation>(
    `/api/v1/circulation/reservations/${encodeURIComponent(reservationId)}/cancel`,
    "cancel-reservation",
    200,
  );
  if (!isReservation(result.record) || result.record.reservationId !== reservationId ||
      result.record.memberId !== memberId || result.record.editionId !== editionId ||
      result.record.status !== "CANCELLED" || result.record.version < 1) throw new ApiError(502);
  return result;
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
  const csrf = await getCsrfToken();
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
  const csrf = await getCsrfToken();
  const response = await fetch("/api/v1/auth/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: { [csrf.headerName]: csrf.token },
  });
  if (!response.ok && response.status !== 204) throw new ApiError(response.status);
}

async function getCsrfToken(): Promise<{ headerName: "X-XSRF-TOKEN"; token: string }> {
  const csrf = await requestJson("/api/v1/auth/csrf");
  if (!isRecord(csrf) || csrf.headerName !== "X-XSRF-TOKEN" || typeof csrf.token !== "string" ||
      csrf.token.length < 16 || csrf.token.length > 1024 || /[\r\n]/u.test(csrf.token)) throw new ApiError(502);
  return { headerName: csrf.headerName, token: csrf.token };
}

async function circulationCommand<T>(
  path: string,
  operation: string,
  expectedStatus: number,
  body?: Record<string, string>,
): Promise<CommandResult<T>> {
  const csrf = await getCsrfToken();
  const idempotencyKey = `spa:${operation}:${crypto.randomUUID()}`;
  const response = await fetch(path, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...(body ? { "Content-Type": "application/json" } : {}),
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": idempotencyKey,
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  if (!response.ok || response.status !== expectedStatus) throw new ApiError(response.status);
  if (!(response.headers.get("content-type") ?? "").toLowerCase().startsWith("application/json")) {
    throw new ApiError(502);
  }
  const replayedHeader = response.headers.get("idempotency-replayed");
  if (replayedHeader !== "true" && replayedHeader !== "false") throw new ApiError(502);
  return { record: await response.json() as T, replayed: replayedHeader === "true" };
}

async function mutationJson(path: string, init: RequestInit): Promise<unknown> {
  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers: { Accept: "application/json", ...init.headers },
  });
  return responseJson(response);
}

async function responseJson(response: Response): Promise<unknown> {
  if (!response.ok) throw new ApiError(response.status);
  if (!(response.headers.get("content-type") ?? "").toLowerCase().startsWith("application/json")) {
    throw new ApiError(502);
  }
  return response.json();
}

function validateCommandIdentifiers(...identifiers: string[]) {
  if (identifiers.some((identifier) => !isUuid(identifier))) throw new ApiError(400);
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

function isMemberProfile(value: unknown): value is MemberProfile {
  if (!isRecord(value)) return false;
  if (!isUuid(value.memberId) || !isEmail(value.email) || !isSafeText(value.fullName, 200) ||
      !Number.isSafeInteger(value.universityId) || Number(value.universityId) < 1 ||
      !["PENDING", "APPROVED", "REJECTED"].includes(String(value.status)) ||
      !["USER", "ADMIN", "SUPER_ADMIN"].includes(String(value.role)) ||
      !isIsoInstant(value.createdAt) || !isIsoInstant(value.updatedAt)) {
    return false;
  }
  return Date.parse(value.updatedAt) >= Date.parse(value.createdAt);
}

function isAdminMemberPage(value: unknown, status: AccountStatus, limit: number): value is AdminMemberPage {
  if (!isRecord(value) || !Array.isArray(value.items) || value.items.length > limit ||
      !value.items.every((item) => isAdminMember(item) && item.status === status) ||
      !isNullableAdminCursor(value.nextCursor) || (value.nextCursor !== null && value.items.length !== limit)) return false;
  const items = value.items as AdminMember[];
  return items.slice(1).every((member, index) => {
    const previous = items[index]!;
    const difference = Date.parse(previous.createdAt) - Date.parse(member.createdAt);
    return difference < 0 || (difference === 0 && previous.memberId < member.memberId);
  });
}

function isAdminMember(value: unknown): value is AdminMember {
  return isRecord(value) && isUuid(value.memberId) && isEmail(value.email) && isSafeText(value.fullName, 200) &&
    Number.isSafeInteger(value.universityId) && Number(value.universityId) > 0 &&
    ["PENDING", "APPROVED", "REJECTED"].includes(String(value.status)) &&
    ["USER", "ADMIN", "SUPER_ADMIN"].includes(String(value.role)) &&
    isNonNegativeInteger(value.aggregateVersion) && isIsoInstant(value.createdAt) && isIsoInstant(value.updatedAt) &&
    Date.parse(value.updatedAt) >= Date.parse(value.createdAt);
}

function isMembershipCommand(value: unknown): value is MembershipCommand {
  return isRecord(value) && isUuid(value.memberId) && isNonNegativeInteger(value.aggregateVersion) &&
    Number(value.aggregateVersion) > 0 && ["APPROVED", "REJECTED"].includes(String(value.status)) &&
    isIsoInstant(value.occurredAt) && typeof value.replayed === "boolean";
}

function isEditionDownloadAvailability(value: unknown): value is EditionDownloadAvailability {
  if (!isRecord(value) || !isUuid(value.editionId) || typeof value.downloadable !== "boolean" ||
      !Array.isArray(value.formats) || value.formats.length > 2 ||
      value.downloadable !== (value.formats.length > 0) || !value.formats.every(isDownloadableFormat)) return false;
  return new Set(value.formats.map((format) => format.assetId)).size === value.formats.length &&
    new Set(value.formats.map((format) => format.format)).size === value.formats.length;
}

function isDownloadableFormat(value: unknown): value is DownloadableFormat {
  if (!isRecord(value) || !isUuid(value.assetId) || !["PDF", "EPUB"].includes(String(value.format)) ||
      !Number.isSafeInteger(value.sizeBytes) || Number(value.sizeBytes) < 1 || Number(value.sizeBytes) > MAXIMUM_ASSET_BYTES ||
      typeof value.sha256 !== "string" || !/^[0-9a-f]{64}$/u.test(value.sha256) ||
      typeof value.licenseExpression !== "string" || !DIGITAL_LICENSES.has(value.licenseExpression) ||
      !isSafeMultilineText(value.attribution, 1_000)) return false;
  return value.mediaType === (value.format === "PDF" ? "application/pdf" : "application/epub+zip");
}

function isSignedDownloadUrl(value: string): boolean {
  if (!isPublicHttpsUrl(value) || value.length > 12_000) return false;
  const url = new URL(value);
  if (!/^\/digital-content\/[0-9a-f]{2}\/[0-9a-f-]{36}\/[0-9a-f]{64}\.(?:pdf|epub)$/u.test(url.pathname)) return false;
  const entries = Array.from(url.searchParams.entries());
  const expected = new Set(["Policy", "Signature", "Key-Pair-Id", "Hash-Algorithm"]);
  return entries.length === expected.size && new Set(entries.map(([key]) => key)).size === expected.size &&
    entries.every(([key, item]) => expected.has(key) && /^[A-Za-z0-9_~-]{1,8192}$/u.test(item)) &&
    url.searchParams.get("Hash-Algorithm") === "SHA256";
}

function isShortLivedExpiry(value: string): boolean {
  const expiresAt = Date.parse(value);
  const now = Date.now();
  return expiresAt > now - 60_000 && expiresAt <= now + 10 * 60_000;
}

function isNotificationPage(value: unknown, limit: number, status: NotificationReadStatus): value is NotificationPage {
  if (!isRecord(value) || !isUuid(value.memberId) || !Array.isArray(value.items) || value.items.length > limit ||
      !value.items.every(isNotificationItem) || !isNullableNotificationCursor(value.nextCursor) ||
      (value.nextCursor !== null && value.items.length !== limit) ||
      new Set(value.items.map((item) => item.notificationId)).size !== value.items.length ||
      (status === "READ" && value.items.some((item) => item.readAt === null)) ||
      (status === "UNREAD" && value.items.some((item) => item.readAt !== null))) return false;
  return isDescending(value.items, (item) => item.createdAt, (item) => item.notificationId);
}

function isNotificationItem(value: unknown): value is NotificationItem {
  return isRecord(value) && isUuid(value.notificationId) &&
    ["DUE_SOON", "OVERDUE", "HOLD_READY", "ACCOUNT_STATUS", "GENERAL"].includes(String(value.category)) &&
    isSafeText(value.subject, 160) && isSafeMultilineText(value.body, 2_000) &&
    isIsoInstant(value.occurredAt) && isIsoInstant(value.createdAt) &&
    Date.parse(value.createdAt) >= Date.parse(value.occurredAt) && isNullableInstant(value.readAt) &&
    (value.readAt === null || Date.parse(value.readAt) >= Date.parse(value.createdAt));
}

function isNotificationPreference(value: unknown): value is NotificationPreference {
  return isRecord(value) && isUuid(value.memberId) &&
    typeof value.emailEnabled === "boolean" && typeof value.dueSoonEnabled === "boolean" &&
    typeof value.overdueEnabled === "boolean" && typeof value.holdReadyEnabled === "boolean" &&
    typeof value.accountStatusEnabled === "boolean" && isNonNegativeInteger(value.version) &&
    isNullableInstant(value.updatedAt) && ((value.version === 0) === (value.updatedAt === null));
}

function isNotificationPreferenceUpdate(value: unknown): value is UpdateNotificationPreference {
  return isRecord(value) && Object.keys(value).length === 5 &&
    typeof value.emailEnabled === "boolean" && typeof value.dueSoonEnabled === "boolean" &&
    typeof value.overdueEnabled === "boolean" && typeof value.holdReadyEnabled === "boolean" &&
    typeof value.accountStatusEnabled === "boolean";
}

function isCatalogSearch(value: unknown, page: number, limit: number): value is CatalogSearch {
  if (!isRecord(value) || !Array.isArray(value.editions) || value.editions.length > limit ||
      !value.editions.every(isCatalogEdition) || new Set(value.editions.map((edition) => edition.editionId)).size !== value.editions.length ||
      !isNonNegativeInteger(value.total) || !isNonNegativeInteger(value.page) || value.page !== page ||
      !isNonNegativeInteger(value.totalPages) || value.totalPages !== (value.total === 0 ? 0 : Math.ceil(value.total / limit)) ||
      value.editions.length > value.total) return false;
  return value.editions.every((edition) => edition.isActive);
}

function historyQuery(cursor?: string): URLSearchParams {
  if (cursor !== undefined && !isCursor(cursor)) throw new ApiError(400);
  const query = new URLSearchParams({ limit: String(HISTORY_PAGE_SIZE) });
  if (cursor) query.set("cursor", cursor);
  return query;
}

function isEligibility(value: unknown): value is CirculationEligibility {
  return isRecord(value) && isUuid(value.memberId) &&
    ["ELIGIBLE", "INELIGIBLE", "SUSPENDED"].includes(String(value.status)) &&
    (value.reasonCode === null || (typeof value.reasonCode === "string" && /^[A-Z][A-Z0-9_]{0,63}$/u.test(value.reasonCode))) &&
    isNonNegativeInteger(value.sourceVersion) && isIsoInstant(value.sourceOccurredAt) &&
    ((value.status === "ELIGIBLE") === (value.reasonCode === null));
}

function isLoanPage(value: unknown, limit: number): value is MemberLoanPage {
  if (!isRecord(value) || !isUuid(value.memberId) || !Array.isArray(value.items) || value.items.length > limit ||
      !isNullableCursor(value.nextCursor) || (value.nextCursor !== null && value.items.length !== limit) ||
      !value.items.every((item) => isLoan(item) && item.memberId === value.memberId)) return false;
  return isDescending(value.items, (item) => item.requestedAt, (item) => item.loanId);
}

function isLoan(value: unknown): value is LoanHistoryItem {
  if (!isRecord(value) || !isUuid(value.loanId) || !isUuid(value.memberId) || !isUuid(value.editionId) ||
      (value.copyId !== null && !isUuid(value.copyId)) ||
      !["REQUESTED", "ACTIVE", "RETURNED", "REJECTED", "CANCELLED"].includes(String(value.status)) ||
      !isIsoInstant(value.requestedAt) || !isNullableInstant(value.checkedOutAt) || !isNullableInstant(value.dueAt) ||
      !isNullableInstant(value.returnedAt) || !isNullableInstant(value.rejectedAt) ||
      !isNonNegativeInteger(value.renewalCount) || !isNonNegativeInteger(value.version)) return false;
  switch (value.status) {
    case "REQUESTED":
    case "CANCELLED":
      return value.copyId === null && value.checkedOutAt === null && value.dueAt === null &&
        value.returnedAt === null && value.rejectedAt === null;
    case "ACTIVE":
      return value.copyId !== null && value.checkedOutAt !== null && value.dueAt !== null &&
        Date.parse(value.dueAt) > Date.parse(value.checkedOutAt) && value.returnedAt === null && value.rejectedAt === null;
    case "RETURNED":
      return value.copyId !== null && value.checkedOutAt !== null && value.dueAt !== null && value.returnedAt !== null &&
        Date.parse(value.returnedAt) >= Date.parse(value.checkedOutAt) && value.rejectedAt === null;
    case "REJECTED":
      return value.copyId === null && value.checkedOutAt === null && value.dueAt === null && value.returnedAt === null &&
        value.rejectedAt !== null && Date.parse(value.rejectedAt) >= Date.parse(value.requestedAt);
    default:
      return false;
  }
}

function isLoanCommand(value: unknown): value is LoanCommand {
  if (!isRecord(value) || !isUuid(value.loanId) || !isUuid(value.memberId) || !isUuid(value.editionId) ||
      (value.copyId !== null && !isUuid(value.copyId)) ||
      !["REQUESTED", "ACTIVE", "RETURNED", "REJECTED", "CANCELLED"].includes(String(value.status)) ||
      !isIsoInstant(value.requestedAt) || !isNullableInstant(value.checkedOutAt) || !isNullableInstant(value.dueAt) ||
      !isNullableInstant(value.returnedAt) || !isNonNegativeInteger(value.renewalCount) || !isNonNegativeInteger(value.version)) return false;
  switch (value.status) {
    case "REQUESTED":
    case "CANCELLED":
    case "REJECTED":
      return value.copyId === null && value.checkedOutAt === null && value.dueAt === null && value.returnedAt === null;
    case "ACTIVE":
      return value.copyId !== null && value.checkedOutAt !== null && value.dueAt !== null && value.returnedAt === null &&
        Date.parse(value.checkedOutAt) >= Date.parse(value.requestedAt) && Date.parse(value.dueAt) > Date.parse(value.checkedOutAt);
    case "RETURNED":
      return value.copyId !== null && value.checkedOutAt !== null && value.dueAt !== null && value.returnedAt !== null &&
        Date.parse(value.returnedAt) >= Date.parse(value.checkedOutAt);
    default:
      return false;
  }
}

function isReservationPage(value: unknown, limit: number): value is MemberReservationPage {
  if (!isRecord(value) || !isUuid(value.memberId) || !Array.isArray(value.items) || value.items.length > limit ||
      !isNullableCursor(value.nextCursor) || (value.nextCursor !== null && value.items.length !== limit) ||
      !value.items.every((item) => isReservation(item) && item.memberId === value.memberId)) return false;
  return isDescending(value.items, (item) => item.placedAt, (item) => item.reservationId);
}

function isReservation(value: unknown): value is Reservation {
  if (!isRecord(value) || !isUuid(value.reservationId) || !isUuid(value.memberId) || !isUuid(value.editionId) ||
      (value.copyId !== null && !isUuid(value.copyId)) ||
      !["WAITING", "READY", "FULFILLED", "CANCELLED", "EXPIRED"].includes(String(value.status)) ||
      !isIsoInstant(value.placedAt) || !isNullableInstant(value.readyAt) || !isNullableInstant(value.expiresAt) ||
      !isNullableInstant(value.fulfilledAt) || !isNullableInstant(value.cancelledAt) || !isNonNegativeInteger(value.version)) return false;
  const readyShape = value.copyId !== null && value.readyAt !== null && value.expiresAt !== null &&
    Date.parse(value.readyAt) >= Date.parse(value.placedAt) && Date.parse(value.expiresAt) > Date.parse(value.readyAt);
  switch (value.status) {
    case "WAITING":
      return value.copyId === null && value.readyAt === null && value.expiresAt === null && value.fulfilledAt === null && value.cancelledAt === null;
    case "READY":
    case "EXPIRED":
      return readyShape && value.fulfilledAt === null && value.cancelledAt === null;
    case "FULFILLED":
      return readyShape && value.fulfilledAt !== null && value.cancelledAt === null &&
        Date.parse(value.fulfilledAt) >= Date.parse(value.readyAt!) && Date.parse(value.fulfilledAt) <= Date.parse(value.expiresAt!);
    case "CANCELLED":
      return value.fulfilledAt === null && value.cancelledAt !== null && Date.parse(value.cancelledAt) >= Date.parse(value.placedAt) &&
        ((value.copyId === null && value.readyAt === null && value.expiresAt === null) || readyShape);
    default:
      return false;
  }
}

function isCatalogEdition(value: unknown): value is CatalogEdition {
  return isRecord(value) && isUuid(value.editionId) && isUuid(value.workId) && isSafeText(value.title, 500) &&
    isSafeText(value.isbn, 32) && isSafeText(value.publisher, 300) &&
    Number.isSafeInteger(value.publicationYear) && Number(value.publicationYear) >= 1000 && Number(value.publicationYear) <= 3000 &&
    isSafeText(value.language, 32) && Number.isSafeInteger(value.pageCount) && Number(value.pageCount) > 0 &&
    (value.coverUrl === null || (typeof value.coverUrl === "string" && isPublicHttpsUrl(value.coverUrl))) &&
    (value.coverColor === null || isSafeText(value.coverColor, 64)) &&
    (value.videoUrl === null || (typeof value.videoUrl === "string" && isPublicHttpsUrl(value.videoUrl))) &&
    isNonNegativeInteger(value.totalCopies) && isNonNegativeInteger(value.availableCopies) &&
    Number(value.availableCopies) <= Number(value.totalCopies) && typeof value.isActive === "boolean";
}

function isDescending<T>(items: T[], time: (item: T) => string, id: (item: T) => string): boolean {
  return items.slice(1).every((item, index) => {
    const previous = items[index]!;
    const timeDifference = Date.parse(time(previous)) - Date.parse(time(item));
    return timeDifference > 0 || (timeDifference === 0 && id(previous) > id(item));
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isSafeText(value: unknown, maximum: number): value is string {
  if (typeof value !== "string" || value.length === 0 || value.length > maximum || value !== value.trim()) {
    return false;
  }
  return Array.from(value).every((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined && codePoint > 31 && codePoint !== 127;
  });
}

function isEmail(value: unknown): value is string {
  if (!isSafeText(value, 320)) return false;
  const separator = value.indexOf("@");
  return separator > 0 && separator === value.lastIndexOf("@") && separator < value.length - 1;
}

function isIsoInstant(value: unknown): value is string {
  return typeof value === "string" && value.length >= 20 && value.length <= 35 &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/u.test(value) &&
    Number.isFinite(Date.parse(value));
}

function isNullableInstant(value: unknown): value is string | null {
  return value === null || isIsoInstant(value);
}

function isCursor(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 160 && /^[A-Za-z0-9_-]+$/u.test(value);
}

function isNullableCursor(value: unknown): value is string | null {
  return value === null || isCursor(value);
}

function isNotificationCursor(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 128 && /^[A-Za-z0-9_-]+$/u.test(value);
}

function isAdminCursor(value: unknown): value is string {
  return typeof value === "string" && value.length >= 16 && value.length <= 200 && /^[A-Za-z0-9_-]+$/u.test(value);
}

function isNullableAdminCursor(value: unknown): value is string | null {
  return value === null || isAdminCursor(value);
}

function isNullableNotificationCursor(value: unknown): value is string | null {
  return value === null || isNotificationCursor(value);
}

function isEntityTag(value: string): boolean {
  return /^"(?:0|[1-9][0-9]{0,18})"$/u.test(value);
}

function isSafeMultilineText(value: unknown, maximum: number): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= maximum && value === value.trim() &&
    !Array.from(value).some((character) => character !== "\n" && character !== "\t" && /\p{Cc}/u.test(character));
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(value);
}

const HISTORY_PAGE_SIZE = 20;
const CATALOG_PAGE_SIZE = 20;
const MAXIMUM_EDITION_BATCH = 50;
const NOTIFICATION_PAGE_SIZE = 20;
const MAXIMUM_ASSET_BYTES = 1024 * 1024 * 1024;
const DIGITAL_LICENSES = new Set(["CC-BY-4.0", "CC-BY-SA-4.0", "CC0-1.0", "PDM-1.0"]);
