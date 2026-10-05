const GUTENBERG_COVER_PATH =
  /^\/cache\/epub\/(\d+)\/pg\1\.cover\.medium\.jpg$/u;
const DOAB_COVER_PATH =
  /^\/bitstream\/20\.500\.12854\/\d+\/\d+\/[A-Za-z0-9._%~-]+\.(?:jpe?g|png|webp)$/iu;

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
        DOAB_COVER_PATH.test(url.pathname));
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
