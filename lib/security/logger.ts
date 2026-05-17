/**
 * Logger Module
 *
 * Provides a structured JSON logging system for the University Library application.
 * Structured logging (JSON format) is essential for production observability, allowing
 * easy parsing by log aggregators like Datadog, ELK, or CloudWatch.
 *
 * Features:
 * - Redaction: Automatically masks sensitive information (passwords, tokens, cookies) 
 *   to prevent accidental leakage of PII or credentials in logs.
 * - Severity Levels: Supports 'info', 'warn', and 'error' levels.
 * - Contextual Data: Allows attaching arbitrary key-value pairs to every log entry.
 */

/** Valid log severity levels. */
type LogLevel = "info" | "warn" | "error";

/** Contextual metadata attached to a log entry. */
type LogContext = Record<string, string | number | boolean | null | undefined>;

/**
 * Deeply traverses an object to redact sensitive keys.
 * Matches keys using a case-insensitive regex for password, token, secret, etc.
 * 
 * @param value - The object or value to sanitize.
 * @returns A new object with sensitive fields masked as "[redacted]".
 */
const redact = (value: unknown): unknown => {
  if (!value || typeof value !== "object") {
    return value;
  }

  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>).map(([key, item]) => {
      if (/password|token|secret|key|authorization|cookie/i.test(key)) {
        return [key, "[redacted]"];
      }

      return [key, item];
    }),
  );
};

/**
 * Internal write method that formats and outputs the log entry.
 * 
 * @param level - Log severity level.
 * @param event - A short, descriptive string identifying the event (e.g., "auth.login_success").
 * @param context - Additional structured data for the event.
 */
const write = (level: LogLevel, event: string, context: LogContext = {}) => {
  const redactedContext = redact(context) as LogContext;
  const payload = {
    level,
    event,
    timestamp: new Date().toISOString(),
    ...redactedContext,
  };

  const line = JSON.stringify(payload);

  if (level === "error") {
    console.error(line);
  } else if (level === "warn") {
    console.warn(line);
  } else {
    console.info(line);
  }
};

/**
 * Logs an informational event.
 * @param event - Event identifier.
 * @param context - Metadata.
 */
export const logInfo = (event: string, context?: LogContext) =>
  write("info", event, context);

/**
 * Logs a warning event.
 * @param event - Event identifier.
 * @param context - Metadata.
 */
export const logWarn = (event: string, context?: LogContext) =>
  write("warn", event, context);

/**
 * Logs an error event with error details.
 * 
 * @param event - Event identifier.
 * @param error - The caught error object or message.
 * @param context - Additional metadata.
 */
export const logError = (
  event: string,
  error: unknown,
  context: LogContext = {},
) => {
  write("error", event, {
    ...context,
    error:
      error instanceof Error
        ? `${error.name}: ${error.message}`
        : "Unknown error",
  });
};
