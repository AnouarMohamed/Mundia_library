import { describe, expect, it } from "vitest";
import {
  classifyResourceLicense,
  isVerifiedLicenseExpression,
} from "./license-policy";

describe("learning resource licence policy", () => {
  it.each([
    ["Book (CC BY 4.0)", "CC-BY"],
    ["Book (CC-BY-SA)", "CC-BY-SA"],
    ["Book (CC0 1.0)", "CC0"],
    ["Book (Public Domain)", "PUBLIC-DOMAIN"],
  ])("allows explicit redistribution licence %s", (value, expected) => {
    expect(classifyResourceLicense(value)).toMatchObject({
      verified: true,
      expression: expected,
    });
  });

  it.each(["CC BY-NC", "CC BY-ND", "All rights reserved", "Copyrighted"])(
    "quarantines disallowed licence %s",
    (value) => {
      expect(classifyResourceLicense(value)).toMatchObject({
        verified: false,
        expression: null,
      });
    },
  );

  it("quarantines missing per-book evidence", () => {
    expect(classifyResourceLicense("Free PDF")).toMatchObject({
      verified: false,
      expression: null,
    });
  });

  it("preserves an older allowlisted Creative Commons version", () => {
    expect(
      classifyResourceLicense(
        "CC-BY https://creativecommons.org/licenses/by/3.0/",
      ),
    ).toMatchObject({
      verified: true,
      expression: "CC-BY",
      licenseUrl: "https://creativecommons.org/licenses/by/3.0/",
    });
  });

  it("recognizes only normalized allowlist values", () => {
    expect(isVerifiedLicenseExpression("CC-BY-SA")).toBe(true);
    expect(isVerifiedLicenseExpression("CC-BY-NC")).toBe(false);
  });
});
