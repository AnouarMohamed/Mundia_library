import { describe, expect, it } from "vitest";
import { decodeDisplayText } from "./display-text";

describe("learning-resource display text", () => {
  it("decodes safe named, decimal, and hexadecimal character references", () => {
    expect(
      decodeDisplayText("&#34;Nothing to See Here&#39;&#39; &amp; R&#xE9;nyi"),
    ).toBe('"Nothing to See Here\'\' & Rényi');
  });

  it("leaves malformed and unsafe references inert", () => {
    expect(decodeDisplayText("<b>safe</b> &#0; &#xD800; &unknown;")).toBe(
      "<b>safe</b> &#0; &#xD800; &unknown;",
    );
  });
});
