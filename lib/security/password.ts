/**
 * Password Security Module
 *
 * This module manages password hashing and verification strategies. 
 * It supports a hybrid model to facilitate secure migrations from legacy hashing to 
 * modern standards:
 *
 * 1. Legacy (SHA-256): Uses SHA-256 with a unique salt. While better than plain text, 
 *    it is susceptible to GPU-accelerated brute force.
 * 2. Modern (Bcrypt): Uses Bcrypt with a work factor (ROUNDS=12). Bcrypt is 
 *    computationally expensive and resistant to hardware acceleration.
 *
 * Strategy:
 * - New passwords and re-hashes always use Bcrypt.
 * - verifyPassword() transparently handles both formats.
 * - shouldRehashPassword() identifies accounts that need an upgrade to Bcrypt.
 */

import bcrypt from "bcryptjs";
import { sha256 } from "@noble/hashes/sha256";
import { randomBytes } from "@noble/hashes/utils";

/** Prefix used to identify passwords hashed with Bcrypt. */
const BCRYPT_PREFIX = "bcrypt:";
/** Number of cost rounds for Bcrypt. High enough for security, low enough for user experience. */
const BCRYPT_ROUNDS = 12;

/**
 * Combines two byte arrays into one.
 * Helper for SHA-256 password salting.
 */
const concatUint8Arrays = (a: Uint8Array, b: Uint8Array): Uint8Array => {
  const c = new Uint8Array(a.length + b.length);
  c.set(a, 0);
  c.set(b, a.length);
  return c;
};

/**
 * Verifies a password against a legacy SHA-256 hash.
 * @param password - The raw password provided by the user.
 * @param storedPassword - The salt:hash string from the database.
 * @returns True if the password matches the legacy hash.
 */
const verifyLegacySha256Password = (
  password: string,
  storedPassword: string,
) => {
  const [saltB64, hashB64] = storedPassword.split(":");

  if (!saltB64 || !hashB64) {
    return false;
  }

  const salt = Uint8Array.from(Buffer.from(saltB64, "base64"));
  const expectedHash = Buffer.from(hashB64, "base64");
  const passwordBytes = new TextEncoder().encode(password);
  const hashBuffer = sha256(concatUint8Arrays(passwordBytes, salt));

  return Buffer.from(hashBuffer).equals(expectedHash);
};

/**
 * Hashes a password using modern Bcrypt.
 * @param password - The raw password.
 * @returns A Bcrypt-prefixed hash string.
 */
export const hashPassword = async (password: string) => {
  const hash = await bcrypt.hash(password, BCRYPT_ROUNDS);
  return `${BCRYPT_PREFIX}${hash}`;
};

/**
 * Hashes a password using legacy SHA-256. 
 * Only used for testing or specific backward-compatibility migrations.
 * @param password - The raw password.
 * @returns A salt:hash base64 string.
 */
export const hashLegacySha256Password = (password: string) => {
  const salt = randomBytes(16);
  const passwordBytes = new TextEncoder().encode(password);
  const hashBuffer = sha256(concatUint8Arrays(passwordBytes, salt));

  return `${Buffer.from(salt).toString("base64")}:${Buffer.from(hashBuffer).toString("base64")}`;
};

/**
 * Checks if a stored hash string uses the Bcrypt format.
 */
export const isBcryptPasswordHash = (storedPassword: string) =>
  storedPassword.startsWith(BCRYPT_PREFIX);

/**
 * Verifies a raw password against a stored hash (either Bcrypt or SHA-256).
 * 
 * @param password - Raw password input.
 * @param storedPassword - Hash from the database.
 * @returns Promise resolving to true if valid.
 */
export async function verifyPassword(
  password: string,
  storedPassword: string,
) {
  if (isBcryptPasswordHash(storedPassword)) {
    return bcrypt.compare(password, storedPassword.slice(BCRYPT_PREFIX.length));
  }

  return verifyLegacySha256Password(password, storedPassword);
}

/**
 * Determines if a password needs to be re-hashed (upgraded to Bcrypt).
 * Used during successful login to transparently upgrade security.
 */
export const shouldRehashPassword = (storedPassword: string) =>
  !isBcryptPasswordHash(storedPassword);
