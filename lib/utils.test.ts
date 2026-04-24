import { describe, expect, it } from "vitest";

import { cn, getInitials } from "./utils";

describe("getInitials", () => {
  it("returns up to two uppercase initials", () => {
    expect(getInitials("Jane Doe")).toBe("JD");
    expect(getInitials("john")).toBe("J");
    expect(getInitials("mary jane watson")).toBe("MJ");
  });
});

describe("cn", () => {
  it("merges class names and resolves tailwind conflicts", () => {
    expect(cn("p-2", "p-4", "text-sm", false && "hidden")).toBe("p-4 text-sm");
  });
});
