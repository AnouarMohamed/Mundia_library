/**
 * Database Resilience Utilities
 * 
 * Provides mechanisms to handle transient database connectivity issues, 
 * common in serverless or distributed database environments (e.g., Neon).
 */

type RetryOptions = {
  /** Maximum number of retry attempts (default: 2). */
  retries?: number;
  /** Base delay between retries in milliseconds (default: 250). */
  delayMs?: number;
};

/**
 * Known PostgreSQL and Node.js error codes that represent transient failures.
 * - ETIMEDOUT: Network connection timed out.
 * - ECONNRESET: Connection reset by peer.
 * - 57P01: Database shutdown.
 * - 57P02: Crash shutdown.
 */
const TRANSIENT_CODES = new Set([
  "ETIMEDOUT",
  "ECONNRESET",
  "ENETUNREACH",
  "ECONNREFUSED",
  "57P01",
  "57P02",
]);

/**
 * Simple utility to pause execution for a given duration.
 * @param ms - Milliseconds to sleep.
 */
const sleep = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Analyzes an error to determine if it's a transient "blip" that can be 
 * safely retried without human intervention.
 * 
 * It checks both explicit error codes and common timeout-related keywords 
 * in the error message.
 * 
 * @param error - The caught error object.
 * @returns True if the error is considered transient.
 */
export function isTransientDbError(error: unknown): boolean {
  const code = (error as { code?: string })?.code;
  if (code && TRANSIENT_CODES.has(code)) return true;

  const message = error instanceof Error ? error.message : String(error);
  return /connection timeout|connection terminated|terminat(ed|ion)|timeout/i.test(
    message
  );
}

/**
 * Wraps a database operation with automatic retry logic and linear backoff.
 * 
 * Strategy:
 * 1. Attempt the operation.
 * 2. If it fails, check if the error is transient.
 * 3. If transient and attempts remain, wait (delay * attempt_count) and try again.
 * 4. If non-transient or exhausted, re-throw the last error.
 * 
 * @param operation - An async function representing the DB call.
 * @param options - Retry configuration (retries, delay).
 * @returns The result of the successful operation.
 */
export async function withDbRetry<T>(
  operation: () => Promise<T>,
  { retries = 2, delayMs = 250 }: RetryOptions = {}
): Promise<T> {
  let lastError: unknown;

  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      return await operation();
    } catch (error) {
      lastError = error;
      const canRetry = attempt < retries && isTransientDbError(error);
      if (!canRetry) throw error;
      
      // Wait before retrying (backoff increases with each attempt)
      await sleep(delayMs * (attempt + 1));
    }
  }

  throw lastError;
}

