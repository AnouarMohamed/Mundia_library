import { afterEach, describe, expect, it, vi } from "vitest";

import { logError, logInfo } from "@/lib/security/logger";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("security logger", () => {
  it("redacts nested secrets without redacting ordinary aggregate fields", () => {
    const info = vi.spyOn(console, "info").mockImplementation(() => undefined);

    logInfo("test.event", {
      totalCopies: 5,
      nested: {
        accessToken: "do-not-log",
        profile: {
          email: "student@example.test",
        },
      },
      list: [{ password: "do-not-log" }],
    });

    const payload = JSON.parse(String(info.mock.calls[0]?.[0]));
    expect(payload).toMatchObject({
      event: "test.event",
      totalCopies: 5,
      nested: {
        accessToken: "[redacted]",
        profile: {
          email: "[redacted]",
        },
      },
      list: [{ password: "[redacted]" }],
    });
  });

  it("serializes circular context safely", () => {
    const error = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    const circular: Record<string, unknown> = {};
    circular.self = circular;

    logError("test.failure", new Error("expected"), { circular });

    const payload = JSON.parse(String(error.mock.calls[0]?.[0]));
    expect(payload.circular.self).toBe("[circular]");
    expect(payload.error).toBe("Error: expected");
  });
});
