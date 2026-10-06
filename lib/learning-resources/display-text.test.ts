import { describe, expect, it } from "vitest";
import {
  decodeDisplayText,
  formatLearningResourceText,
} from "./display-text";

describe("learning-resource display text", () => {
  it("decodes safe named, decimal, and hexadecimal character references", () => {
    expect(
      decodeDisplayText("&#34;Nothing to See Here&#39;&#39; &amp; R&#xE9;nyi"),
    ).toBe('"Nothing to See Here\'\' & Rényi');
  });

  it("fully decodes bounded nested references from XML feeds", () => {
    expect(decodeDisplayText("H&amp;#246;lder&amp;#39;s inequality")).toBe(
      "Hölder's inequality",
    );
    expect(decodeDisplayText("line one&#xd;line two")).toBe(
      "line one\nline two",
    );
  });

  it("leaves malformed and unsafe references inert", () => {
    expect(decodeDisplayText("<b>safe</b> &#0; &#xD800; &unknown;")).toBe(
      "<b>safe</b> &#0; &#xD800; &unknown;",
    );
  });

  it("removes simple scholarly inline math delimiters", () => {
    expect(
      formatLearningResourceText(
        "$α$-Wasserstein Mechanism for R&amp;D",
      ),
    ).toBe("α-Wasserstein Mechanism for R&D");
  });
});
