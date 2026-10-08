import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  changeAdminMemberStatus,
  authorizeEditionDownload,
  cancelLoan,
  cancelReservation,
  getCirculationOverview,
  getAdminMembers,
  getEditionDownloadAvailability,
  getLearningResource,
  getMemberProfile,
  getNotificationPreference,
  getNotifications,
  isPublicHttpsUrl,
  markNotificationRead,
  placeReservation,
  renewLoan,
  requestLoan,
  searchCatalog,
  searchLearningResources,
  updateNotificationPreference,
} from "./client";

afterEach(() => vi.unstubAllGlobals());

describe("learning-resource BFF client", () => {
  it("accepts a bounded contract response", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      resources: [resource()], total: 1, page: 0, totalPages: 1,
    }), { status: 200, headers: { "content-type": "application/json" } })));

    await expect(searchLearningResources({ query: "unix", category: "", page: 0 }))
      .resolves.toMatchObject({ total: 1, resources: [{ title: "Reliable UNIX Systems" }] });
  });

  it("rejects mismatched identities and unsafe URLs", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(resource()), {
      status: 200, headers: { "content-type": "application/json" },
    })));

    await expect(getLearningResource("55555555-5555-4555-8555-555555555555"))
      .rejects.toBeInstanceOf(ApiError);
    expect(isPublicHttpsUrl("http://127.0.0.1/private")).toBe(false);
    expect(isPublicHttpsUrl("https://publisher.example.edu/book")).toBe(true);
  });
});

describe("member-profile BFF client", () => {
  it("accepts a caller-bound profile contract", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(profile())));

    await expect(getMemberProfile()).resolves.toMatchObject({
      fullName: "Houda Benjdida",
      role: "USER",
      status: "APPROVED",
    });
    expect(fetch).toHaveBeenCalledWith(
      "/api/v1/membership/profile",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it.each([
    { fullName: "Unsafe\nName" },
    { universityId: 0 },
    { role: "ROOT" },
    { createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-06T00:00:00Z" },
  ])("rejects malformed profile data: %j", async (override) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({ ...profile(), ...override })));

    await expect(getMemberProfile()).rejects.toBeInstanceOf(ApiError);
  });
});

describe("administration BFF client", () => {
  it("accepts a bounded, ordered account queue", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({ items: [adminMember()], nextCursor: null })));

    await expect(getAdminMembers("PENDING")).resolves.toMatchObject({
      items: [{ fullName: "Pending Student", aggregateVersion: 0 }],
    });
    expect(String(vi.mocked(fetch).mock.calls[0]?.[0])).toContain("status=PENDING");
  });

  it("sends csrf, exact version and unique idempotency for a decision", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(new Response(JSON.stringify({
        memberId: ADMIN_TARGET_ID,
        aggregateVersion: 1,
        status: "APPROVED",
        occurredAt: "2026-10-07T10:00:00Z",
        replayed: false,
      }), { status: 200, headers: {
        "content-type": "application/json",
        etag: '"1"',
        "idempotency-replayed": "false",
      } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(changeAdminMemberStatus(adminMember(), "APPROVED", "Identity evidence verified"))
      .resolves.toMatchObject({ status: "APPROVED", aggregateVersion: 1 });
    const request = fetchMock.mock.calls[1]?.[1];
    const headers = new Headers(request?.headers);
    expect(headers.get("If-Match")).toBe('"0"');
    expect(headers.get("X-XSRF-TOKEN")).toBe(CSRF_TOKEN);
    expect(headers.get("Idempotency-Key")).toMatch(/^spa:member-status:/u);
  });

  it("rejects a queue with the wrong status", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({
      items: [{ ...adminMember(), status: "APPROVED" }], nextCursor: null,
    })));

    await expect(getAdminMembers("PENDING")).rejects.toBeInstanceOf(ApiError);
  });
});

describe("circulation BFF client", () => {
  it("accepts a bounded active catalog page", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({
      editions: [edition(LOAN_EDITION_ID)], total: 1, page: 0, totalPages: 1,
    })));

    await expect(searchCatalog({ query: "distributed", page: 0, availableOnly: true }))
      .resolves.toMatchObject({ editions: [{ title: "Distributed Systems" }] });
    expect(String(vi.mocked(fetch).mock.calls[0]?.[0])).toContain("availableOnly=true");
  });

  it("loads caller-bound history with one bounded catalog batch", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(eligibility()))
      .mockResolvedValueOnce(jsonResponse(loanPage()))
      .mockResolvedValueOnce(jsonResponse(reservationPage()))
      .mockResolvedValueOnce(jsonResponse([edition(LOAN_EDITION_ID), edition(RESERVATION_EDITION_ID)]));
    vi.stubGlobal("fetch", fetchMock);

    const overview = await getCirculationOverview();

    expect(overview.loans.items[0]?.status).toBe("ACTIVE");
    expect(overview.reservations.items[0]?.status).toBe("WAITING");
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(String(fetchMock.mock.calls[3]?.[0])).toContain(`editionId=${LOAN_EDITION_ID}`);
    expect(String(fetchMock.mock.calls[3]?.[0])).toContain(`editionId=${RESERVATION_EDITION_ID}`);
  });

  it("rejects history belonging to a different member before catalog lookup", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(eligibility()))
      .mockResolvedValueOnce(jsonResponse({ ...loanPage(), memberId: "99999999-9999-4999-8999-999999999999" }))
      .mockResolvedValueOnce(jsonResponse(reservationPage()));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getCirculationOverview()).rejects.toBeInstanceOf(ApiError);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("sends csrf and a unique idempotency key for every caller-bound command", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(csrfResponse()).mockResolvedValueOnce(commandResponse(loanCommand("REQUESTED"), 201))
      .mockResolvedValueOnce(csrfResponse()).mockResolvedValueOnce(commandResponse(loanCommand("CANCELLED"), 200))
      .mockResolvedValueOnce(csrfResponse()).mockResolvedValueOnce(commandResponse(loanCommand("ACTIVE"), 200))
      .mockResolvedValueOnce(csrfResponse()).mockResolvedValueOnce(commandResponse(reservationCommand("WAITING"), 201))
      .mockResolvedValueOnce(csrfResponse()).mockResolvedValueOnce(commandResponse(reservationCommand("CANCELLED"), 200));
    vi.stubGlobal("fetch", fetchMock);

    await expect(requestLoan(LOAN_EDITION_ID, MEMBER_ID)).resolves.toMatchObject({ record: { status: "REQUESTED" }, replayed: false });
    await expect(cancelLoan(LOAN_ID, MEMBER_ID, LOAN_EDITION_ID)).resolves.toMatchObject({ record: { status: "CANCELLED" } });
    await expect(renewLoan(LOAN_ID, MEMBER_ID, LOAN_EDITION_ID)).resolves.toMatchObject({ record: { status: "ACTIVE", renewalCount: 1 } });
    await expect(placeReservation(RESERVATION_EDITION_ID, MEMBER_ID)).resolves.toMatchObject({ record: { status: "WAITING" } });
    await expect(cancelReservation(RESERVATION_ID, MEMBER_ID, RESERVATION_EDITION_ID)).resolves.toMatchObject({ record: { status: "CANCELLED" } });

    const commandCalls = [1, 3, 5, 7, 9].map((index) => fetchMock.mock.calls[index]!);
    const keys = commandCalls.map(([, init]) => new Headers(init?.headers).get("Idempotency-Key"));
    expect(new Set(keys).size).toBe(5);
    expect(keys.every((key) => key?.startsWith("spa:") && key.length >= 16)).toBe(true);
    expect(commandCalls.every(([, init]) => new Headers(init?.headers).get("X-XSRF-TOKEN") === CSRF_TOKEN)).toBe(true);
    expect(commandCalls.map(([path]) => path)).toEqual([
      "/api/v1/circulation/loans",
      `/api/v1/circulation/loans/${LOAN_ID}/cancel`,
      `/api/v1/circulation/loans/${LOAN_ID}/renew`,
      "/api/v1/circulation/reservations",
      `/api/v1/circulation/reservations/${RESERVATION_ID}/cancel`,
    ]);
  });

  it("rejects a command response whose caller identity changed", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(commandResponse({ ...loanCommand("REQUESTED"), memberId: "99999999-9999-4999-8999-999999999999" }, 201)));

    await expect(requestLoan(LOAN_EDITION_ID, MEMBER_ID)).rejects.toBeInstanceOf(ApiError);
  });
});

describe("notification BFF client", () => {
  it("accepts an ordered caller-bound unread page", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({
      memberId: MEMBER_ID,
      items: [notification(NOTIFICATION_ID, null)],
      nextCursor: null,
    })));

    await expect(getNotifications("UNREAD")).resolves.toMatchObject({
      memberId: MEMBER_ID,
      items: [{ subject: "Loan due soon", readAt: null }],
    });
    expect(String(vi.mocked(fetch).mock.calls[0]?.[0])).toContain("status=UNREAD");
  });

  it("rejects a read item returned by the unread filter", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({
      memberId: MEMBER_ID,
      items: [notification(NOTIFICATION_ID, "2026-10-07T09:00:00Z")],
      nextCursor: null,
    })));

    await expect(getNotifications("UNREAD")).rejects.toBeInstanceOf(ApiError);
  });

  it("marks owned notifications read with csrf", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(jsonResponse(notification(NOTIFICATION_ID, "2026-10-07T09:00:00Z")));
    vi.stubGlobal("fetch", fetchMock);

    await expect(markNotificationRead(NOTIFICATION_ID)).resolves.toMatchObject({ readAt: "2026-10-07T09:00:00Z" });
    expect(fetchMock.mock.calls[1]?.[0]).toBe(`/api/v1/notifications/${NOTIFICATION_ID}/read`);
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: "PATCH" });
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get("X-XSRF-TOKEN")).toBe(CSRF_TOKEN);
  });

  it("preserves the strong preference version on csrf-protected updates", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(preferenceResponse(notificationPreference(1), "\"1\""))
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(preferenceResponse(notificationPreference(2, false), "\"2\""));
    vi.stubGlobal("fetch", fetchMock);

    const current = await getNotificationPreference();
    await expect(updateNotificationPreference(MEMBER_ID, current.entityTag, {
      emailEnabled: false,
      dueSoonEnabled: true,
      overdueEnabled: true,
      holdReadyEnabled: true,
      accountStatusEnabled: true,
    })).resolves.toMatchObject({ entityTag: "\"2\"", preference: { emailEnabled: false, version: 2 } });

    const headers = new Headers(fetchMock.mock.calls[2]?.[1]?.headers);
    expect(headers.get("If-Match")).toBe("\"1\"");
    expect(headers.get("X-XSRF-TOKEN")).toBe(CSRF_TOKEN);
  });

  it("rejects a preference body whose ETag does not match its version", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(preferenceResponse(notificationPreference(2), "\"1\"")));

    await expect(getNotificationPreference()).rejects.toBeInstanceOf(ApiError);
  });
});

describe("digital-content BFF client", () => {
  it("accepts rights-safe bounded edition formats", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(downloadAvailability())));

    await expect(getEditionDownloadAvailability(LOAN_EDITION_ID)).resolves.toMatchObject({
      downloadable: true,
      formats: [{ format: "PDF", licenseExpression: "CC-BY-4.0" }],
    });
    expect(fetch).toHaveBeenCalledWith(
      `/api/v1/digital-content/editions/${LOAN_EDITION_ID}/availability`,
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("authorizes a signed download only after csrf bootstrap", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(jsonResponse({
        authorizationId: "99999999-9999-4999-8999-999999999999",
        assetId: ASSET_ID,
        downloadUrl: SIGNED_DOWNLOAD_URL,
        expiresAt: shortLivedExpiry(),
      }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(authorizeEditionDownload(ASSET_ID)).resolves.toMatchObject({ downloadUrl: SIGNED_DOWNLOAD_URL });
    expect(fetchMock.mock.calls[1]?.[0]).toBe(`/api/v1/digital-content/assets/${ASSET_ID}/authorizations`);
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: "POST" });
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get("X-XSRF-TOKEN")).toBe(CSRF_TOKEN);
  });

  it("rejects unapproved licences and malformed signed URLs", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(jsonResponse({
      ...downloadAvailability(),
      formats: [{ ...downloadAvailability().formats[0], licenseExpression: "ARR" }],
    })));
    await expect(getEditionDownloadAvailability(LOAN_EDITION_ID)).rejects.toBeInstanceOf(ApiError);

    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(jsonResponse({
        authorizationId: "99999999-9999-4999-8999-999999999999",
        assetId: ASSET_ID,
        downloadUrl: "https://attacker.example/download.pdf?Policy=x",
        expiresAt: shortLivedExpiry(),
      })));
    await expect(authorizeEditionDownload(ASSET_ID)).rejects.toBeInstanceOf(ApiError);
  });
});

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function profile() {
  return {
    memberId: "77777777-7777-4777-8777-777777777777",
    email: "h.benjdida@mundiapolis.ma",
    fullName: "Houda Benjdida",
    universityId: 1,
    status: "APPROVED",
    role: "USER",
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-06T11:00:00Z",
  };
}

const MEMBER_ID = "77777777-7777-4777-8777-777777777777";
const LOAN_EDITION_ID = "11111111-1111-4111-8111-111111111111";
const RESERVATION_EDITION_ID = "22222222-2222-4222-8222-222222222222";
const LOAN_ID = "33333333-3333-4333-8333-333333333333";
const RESERVATION_ID = "55555555-5555-4555-8555-555555555555";
const CSRF_TOKEN = "csrf-token-for-browser-tests";
const NOTIFICATION_ID = "88888888-8888-4888-8888-888888888888";
const ASSET_ID = "12000000-0000-4000-8000-000000000001";
const ADMIN_TARGET_ID = "13000000-0000-4000-8000-000000000001";
const SHA256 = "a".repeat(64);
const SIGNED_DOWNLOAD_URL = `https://downloads.example.test/digital-content/ab/${ASSET_ID}/${SHA256}.pdf?Policy=abc&Signature=def&Key-Pair-Id=ghi&Hash-Algorithm=SHA256`;

function eligibility() {
  return {
    memberId: MEMBER_ID,
    status: "ELIGIBLE",
    reasonCode: null,
    sourceVersion: 4,
    sourceOccurredAt: "2026-10-06T10:00:00Z",
  };
}

function adminMember() {
  return {
    memberId: ADMIN_TARGET_ID,
    email: "pending.student@mundiapolis.ma",
    fullName: "Pending Student",
    universityId: 20260001,
    status: "PENDING" as const,
    role: "USER" as const,
    aggregateVersion: 0,
    createdAt: "2026-10-07T08:00:00Z",
    updatedAt: "2026-10-07T08:00:00Z",
  };
}

function loanPage() {
  return {
    memberId: MEMBER_ID,
    items: [{
      loanId: LOAN_ID,
      memberId: MEMBER_ID,
      editionId: LOAN_EDITION_ID,
      copyId: "44444444-4444-4444-8444-444444444444",
      status: "ACTIVE",
      requestedAt: "2026-10-05T10:00:00Z",
      checkedOutAt: "2026-10-05T11:00:00Z",
      dueAt: "2026-10-20T11:00:00Z",
      returnedAt: null,
      rejectedAt: null,
      renewalCount: 0,
      version: 1,
    }],
    nextCursor: null,
  };
}

function reservationPage() {
  return {
    memberId: MEMBER_ID,
    items: [{
      reservationId: RESERVATION_ID,
      memberId: MEMBER_ID,
      editionId: RESERVATION_EDITION_ID,
      copyId: null,
      status: "WAITING",
      placedAt: "2026-10-04T10:00:00Z",
      readyAt: null,
      expiresAt: null,
      fulfilledAt: null,
      cancelledAt: null,
      version: 0,
    }],
    nextCursor: null,
  };
}

function csrfResponse() {
  return jsonResponse({ headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: CSRF_TOKEN });
}

function commandResponse(value: unknown, status: number) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json", "idempotency-replayed": "false" },
  });
}

function preferenceResponse(value: unknown, entityTag: string) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "content-type": "application/json", etag: entityTag },
  });
}

function notification(notificationId: string, readAt: string | null) {
  return {
    notificationId,
    category: "DUE_SOON",
    subject: "Loan due soon",
    body: "Distributed Systems is due soon.",
    occurredAt: "2026-10-07T08:00:00Z",
    createdAt: "2026-10-07T08:00:01Z",
    readAt,
  };
}

function notificationPreference(version: number, emailEnabled = true) {
  return {
    memberId: MEMBER_ID,
    emailEnabled,
    dueSoonEnabled: true,
    overdueEnabled: true,
    holdReadyEnabled: true,
    accountStatusEnabled: true,
    version,
    updatedAt: "2026-10-07T08:00:00Z",
  };
}

function downloadAvailability() {
  return {
    editionId: LOAN_EDITION_ID,
    downloadable: true,
    formats: [{
      assetId: ASSET_ID,
      format: "PDF",
      mediaType: "application/pdf",
      sizeBytes: 2_097_152,
      sha256: SHA256,
      licenseExpression: "CC-BY-4.0",
      attribution: "Distributed Systems, Open Engineering Faculty.",
    }],
  };
}

function shortLivedExpiry() {
  return new Date(Date.now() + 5 * 60_000).toISOString();
}

function loanCommand(status: "REQUESTED" | "CANCELLED" | "ACTIVE") {
  const active = status === "ACTIVE";
  return {
    loanId: LOAN_ID,
    memberId: MEMBER_ID,
    editionId: LOAN_EDITION_ID,
    copyId: active ? "44444444-4444-4444-8444-444444444444" : null,
    status,
    requestedAt: "2026-10-05T10:00:00Z",
    checkedOutAt: active ? "2026-10-05T11:00:00Z" : null,
    dueAt: active ? "2026-11-20T11:00:00Z" : null,
    returnedAt: null,
    renewalCount: active ? 1 : 0,
    version: status === "REQUESTED" ? 0 : active ? 2 : 1,
  };
}

function reservationCommand(status: "WAITING" | "CANCELLED") {
  return {
    reservationId: RESERVATION_ID,
    memberId: MEMBER_ID,
    editionId: RESERVATION_EDITION_ID,
    copyId: null,
    status,
    placedAt: "2026-10-04T10:00:00Z",
    readyAt: null,
    expiresAt: null,
    fulfilledAt: null,
    cancelledAt: status === "CANCELLED" ? "2026-10-06T10:00:00Z" : null,
    version: status === "CANCELLED" ? 1 : 0,
  };
}

function edition(editionId: string) {
  return {
    editionId,
    workId: "66666666-6666-4666-8666-666666666666",
    title: editionId === LOAN_EDITION_ID ? "Distributed Systems" : "Reliable Cloud Operations",
    isbn: "9780000000001",
    publisher: "Mundia Press",
    publicationYear: 2026,
    language: "en",
    pageCount: 320,
    coverUrl: null,
    coverColor: null,
    videoUrl: null,
    totalCopies: 4,
    availableCopies: 2,
    isActive: true,
    aggregateVersion: 0,
  };
}

function resource() {
  return {
    resourceId: "44444444-4444-4444-8444-444444444444",
    title: "Reliable UNIX Systems",
    author: "Open Faculty",
    description: "Operational design for reliable UNIX services.",
    category: "Software Engineering",
    language: "en",
    coverUrl: null,
    coverAlt: null,
    sourceName: "University Open Press",
    sourceUrl: "https://publisher.example.edu/books/unix",
    licenseExpression: "CC-BY",
    licenseUrl: "https://creativecommons.org/licenses/by/4.0/",
    accessMode: "DOWNLOAD",
    readUrl: null,
  };
}
