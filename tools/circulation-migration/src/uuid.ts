import { createHash } from "node:crypto";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

export function normalizeUuid(value: string): string {
  if (!isUuid(value)) throw new TypeError(`Invalid UUID: ${value}`);
  return value.toLowerCase();
}

function uuidBytes(uuid: string): Buffer {
  return Buffer.from(normalizeUuid(uuid).replaceAll("-", ""), "hex");
}

export function uuidV5(namespace: string, name: string): string {
  const digest = createHash("sha1")
    .update(uuidBytes(namespace))
    .update(Buffer.from(name, "utf8"))
    .digest()
    .subarray(0, 16);

  digest[6] = (digest[6]! & 0x0f) | 0x50;
  digest[8] = (digest[8]! & 0x3f) | 0x80;

  const hex = digest.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(
    12,
    16,
  )}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
