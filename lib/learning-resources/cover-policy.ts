const GUTENBERG_COVER_PATH =
  /^\/cache\/epub\/(\d+)\/pg\1\.cover\.medium\.jpg$/u;

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
    if (
      url.protocol !== "https:" ||
      url.hostname !== "www.gutenberg.org" ||
      url.username ||
      url.password ||
      url.port ||
      url.search ||
      url.hash ||
      !GUTENBERG_COVER_PATH.test(url.pathname)
    ) {
      return null;
    }
    return url.toString();
  } catch {
    return null;
  }
}
