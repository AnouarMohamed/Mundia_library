type RetryOptions = {
  retries?: number;
  delayMs?: number;
};

const TRANSIENT_CODES = new Set([
  "ETIMEDOUT",
  "ECONNRESET",
  "ENETUNREACH",
  "ECONNREFUSED",
  "57P01",
  "57P02",
]);

const sleep = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms));

export function isTransientDbError(error: unknown): boolean {
  const code = (error as { code?: string })?.code;
  if (code && TRANSIENT_CODES.has(code)) return true;

  const message = error instanceof Error ? error.message : String(error);
  return /connection timeout|connection terminated|terminat(ed|ion)|timeout/i.test(
    message
  );
}

/**
 * Retry transient database failures (timeouts/network blips) with backoff.
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
      await sleep(delayMs * (attempt + 1));
    }
  }

  throw lastError;
}

