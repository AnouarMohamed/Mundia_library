import { describe, expect, it } from "vitest";
import {
  hashLegacySha256Password,
  hashPassword,
  isBcryptPasswordHash,
  shouldRehashPassword,
  verifyPassword,
} from "./password";

describe("password utilities", () => {
  it("verifies legacy SHA-256 hashes and marks them for rehash", async () => {
    const stored = hashLegacySha256Password("correct-password");

    await expect(verifyPassword("correct-password", stored)).resolves.toBe(true);
    await expect(verifyPassword("wrong-password", stored)).resolves.toBe(false);
    expect(shouldRehashPassword(stored)).toBe(true);
  });

  it("stores new hashes in the bcrypt-prefixed format", async () => {
    const stored = await hashPassword("correct-password");

    expect(isBcryptPasswordHash(stored)).toBe(true);
    expect(stored).toMatch(/^bcrypt:\$2[aby]\$/);
    await expect(verifyPassword("correct-password", stored)).resolves.toBe(true);
    expect(shouldRehashPassword(stored)).toBe(false);
  });
});
