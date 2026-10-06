const NAMED_ENTITIES: Readonly<Record<string, string>> = {
  amp: "&",
  apos: "'",
  gt: ">",
  lt: "<",
  quot: '"',
};

/**
 * Decodes XML/HTML character references into plain display text. The result is
 * still rendered by React as text, never interpreted as markup.
 */
export function decodeDisplayText(value: string): string {
  let decoded = value;
  for (let pass = 0; pass < MAX_DECODE_PASSES; pass += 1) {
    const next = decoded.replace(
      /&(?:#(\d{1,7})|#x([0-9a-f]{1,6})|([a-z]{2,8}));/giu,
      (entity, decimal: string, hexadecimal: string, named: string) => {
        const codePoint = decimal
          ? Number.parseInt(decimal, 10)
          : hexadecimal
            ? Number.parseInt(hexadecimal, 16)
            : null;
        if (codePoint !== null) {
          return isSafeCodePoint(codePoint)
            ? String.fromCodePoint(codePoint)
            : entity;
        }
        return NAMED_ENTITIES[named.toLowerCase()] ?? entity;
      },
    );
    if (next === decoded) break;
    decoded = next;
  }
  return decoded.replace(/\r\n?/gu, "\n");
}

/** Removes simple inline TeX delimiters that scholarly feeds expose as text. */
export function formatLearningResourceText(value: string): string {
  return decodeDisplayText(value).replace(/\$([^$\n]{1,40})\$/gu, "$1");
}

function isSafeCodePoint(value: number) {
  return (
    Number.isInteger(value) &&
    value > 0 &&
    value <= 0x10ffff &&
    !(value >= 0xd800 && value <= 0xdfff)
  );
}

const MAX_DECODE_PASSES = 4;
