const GUTENBERG_COVER_PATH =
  /^\/cache\/epub\/(\d+)\/pg\1\.cover\.medium\.jpg$/u;
const DOAB_COVER_PATH =
  /^\/bitstream\/20\.500\.12854\/\d+\/\d+\/[A-Za-z0-9._%~-]+\.(?:jpe?g|png|webp)$/iu;
const NASA_ITEM_PATH = /^\/details\/(nasa_techdoc_[0-9]{11})$/u;
const NASA_COVER_PATH = /^\/services\/img\/nasa_techdoc_[0-9]{11}$/u;
const NASA_SOURCE_NAME = "Internet Archive · NASA Technical Reports";

/**
 * Only render cover assets whose ownership and URL shape were verified by an
 * importer. Rejecting query strings also prevents the allowlist from becoming
 * an open-ended remote image proxy when another source is added later.
 */
export function normalizeOfficialLearningResourceCover(
  value: string | null,
): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    const approvedPath =
      (url.hostname === "www.gutenberg.org" &&
        GUTENBERG_COVER_PATH.test(url.pathname)) ||
      (url.hostname === "directory.doabooks.org" &&
        DOAB_COVER_PATH.test(url.pathname)) ||
      (url.hostname === "archive.org" &&
        NASA_COVER_PATH.test(url.pathname));
    if (
      url.protocol !== "https:" ||
      url.username ||
      url.password ||
      url.port ||
      url.search ||
      url.hash ||
      !approvedPath
    ) {
      return null;
    }
    return url.toString();
  } catch {
    return null;
  }
}

export function resolveOfficialLearningResourceCover(input: {
  coverUrl: string | null;
  sourceName: string;
  sourceUrl: string;
}): string | null {
  const storedCover = normalizeOfficialLearningResourceCover(input.coverUrl);
  if (storedCover) return storedCover;
  if (input.sourceName !== NASA_SOURCE_NAME) return null;
  try {
    const source = new URL(input.sourceUrl);
    const match = NASA_ITEM_PATH.exec(source.pathname);
    if (
      source.protocol !== "https:" ||
      source.hostname !== "archive.org" ||
      source.username ||
      source.password ||
      source.port ||
      source.search ||
      source.hash ||
      !match
    ) {
      return null;
    }
    return `https://archive.org/services/img/${match[1]}`;
  } catch {
    return null;
  }
}
