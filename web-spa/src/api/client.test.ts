import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  cancelLoan,
  cancelReservation,
  getCirculationOverview,
  getLearningResource,
  getMemberProfile,
  isPublicHttpsUrl,
  placeReservation,
  renewLoan,
  requestLoan,
  searchCatalog,
  searchLearningResources,
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

function eligibility() {
  return {
    memberId: MEMBER_ID,
    status: "ELIGIBLE",
    reasonCode: null,
    sourceVersion: 4,
    sourceOccurredAt: "2026-10-06T10:00:00Z",
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
