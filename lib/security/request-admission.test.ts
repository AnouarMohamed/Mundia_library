import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import {
  classifyRequestBudget,
  requiresRequestAdmission,
} from "@/lib/security/request-admission";

describe("request admission classification", () => {
  it.each(["/api/health", "/api/health/live"])(
    "keeps the %s probe outside admission dependencies",
    (pathname) => {
      expect(
        requiresRequestAdmission(
          new NextRequest(`https://library.example.edu${pathname}`),
        ),
      ).toBe(false);
    },
  );

  it("admits reads, commands, sensitive operations, and Server Actions", () => {
    const read = new NextRequest("https://library.example.edu/api/books");
    const command = new NextRequest(
      "https://library.example.edu/api/reviews/book-id",
      { method: "POST" },
    );
    const sensitive = new NextRequest(
      "https://library.example.edu/api/admin/send-due-reminders",
      { method: "POST" },
    );
    const serverAction = new NextRequest(
      "https://library.example.edu/my-profile",
      { method: "POST" },
    );

    expect(classifyRequestBudget(read)).toEqual({
      scope: "request:read",
      limit: 300,
    });
    expect(classifyRequestBudget(command)).toEqual({
      scope: "request:command",
      limit: 120,
    });
    expect(classifyRequestBudget(sensitive)).toEqual({
      scope: "request:sensitive",
      limit: 30,
    });
    expect(requiresRequestAdmission(serverAction)).toBe(true);
  });

  it("does not spend budgets on document reads or CORS preflight", () => {
    expect(
      requiresRequestAdmission(
        new NextRequest("https://library.example.edu/library"),
      ),
    ).toBe(false);
    expect(
      requiresRequestAdmission(
        new NextRequest("https://library.example.edu/api/books", {
          method: "OPTIONS",
        }),
      ),
    ).toBe(false);
  });
});
