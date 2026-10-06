import { describe, expect, it } from "vitest";
import {
  normalizeOfficialLearningResourceCover,
  resolveOfficialLearningResourceCover,
} from "./cover-policy";

describe("learning-resource cover policy", () => {
  it("accepts the exact official Gutenberg cover convention", () => {
    expect(
      normalizeOfficialLearningResourceCover(
        "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
      ),
    ).toBe("https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg");
  });

  it("accepts a bounded official DOAB thumbnail path", () => {
    expect(
      normalizeOfficialLearningResourceCover(
        "https://directory.doabooks.org/bitstream/20.500.12854/12345/1/cover.jpg",
      ),
    ).toBe(
      "https://directory.doabooks.org/bitstream/20.500.12854/12345/1/cover.jpg",
    );
  });

  it("accepts an exact NASA report thumbnail path", () => {
    expect(
      normalizeOfficialLearningResourceCover(
        "https://archive.org/services/img/nasa_techdoc_19630001635",
      ),
    ).toBe(
      "https://archive.org/services/img/nasa_techdoc_19630001635",
    );
  });

  it.each([
    "http://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
    "https://gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
    "https://www.gutenberg.org.evil.test/cache/epub/1342/pg1342.cover.medium.jpg",
    "https://www.gutenberg.org/cache/epub/1342/pg7.cover.medium.jpg",
    "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg?x=1",
    "https://user@www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
    "https://directory.doabooks.org/bitstream/20.500.12854/12345/1/cover.jpg?download=1",
    "https://directory.doabooks.org/bitstream/20.500.12854/12345/1/file.pdf",
    "https://archive.org/services/img/community_upload",
    "https://archive.org/services/img/nasa_techdoc_19630001635?download=1",
    "not-a-url",
  ])("rejects an unapproved cover URL: %s", (value) => {
    expect(normalizeOfficialLearningResourceCover(value)).toBeNull();
  });

  it("derives only trusted NASA report thumbnails from exact archive items", () => {
    expect(
      resolveOfficialLearningResourceCover({
        coverUrl: null,
        sourceName: "Internet Archive · NASA Technical Reports",
        sourceUrl:
          "https://archive.org/details/nasa_techdoc_19630001635",
      }),
    ).toBe(
      "https://archive.org/services/img/nasa_techdoc_19630001635",
    );
    expect(
      resolveOfficialLearningResourceCover({
        coverUrl: null,
        sourceName: "Internet Archive · NASA Technical Reports",
        sourceUrl: "https://archive.org/details/community_upload",
      }),
    ).toBeNull();
  });
});
