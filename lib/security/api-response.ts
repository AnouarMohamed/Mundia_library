/**
 * API Response Utility
 * 
 * This module provides a set of standardized helper functions for generating
 * JSON responses in Next.js Route Handlers. It ensures consistent error formats
 * across the entire application, making it easier for client-side consumers
 * to handle different HTTP status codes.
 * 
 * Standard Error Format:
 * {
 *   success: false,
 *   error: string,   // Technical error identifier (e.g., "Unauthorized")
 *   message: string  // Human-readable message
 * }
 */

import { NextResponse } from "next/server";

/**
 * Creates a standardized JSON error response.
 * 
 * @param error - A short, upper-camel-case error identifier.
 * @param message - A descriptive message explaining the error to the user.
 * @param status - The HTTP status code (e.g., 400, 401, 403, 500).
 * @returns A NextResponse object containing the formatted error JSON.
 */
export const jsonError = (
  error: string,
  message: string,
  status: number,
) =>
  NextResponse.json(
    {
      success: false,
      error,
      message,
    },
    { status },
  );

/**
 * Convenience helper for 401 Unauthorized responses.
 * Used when a user is not authenticated but tries to access a protected resource.
 */
export const unauthorizedResponse = () =>
  jsonError("Unauthorized", "Authentication required", 401);

/**
 * Convenience helper for 403 Forbidden responses.
 * Used when a user is authenticated but lacks the necessary permissions (e.g., non-admin).
 * 
 * @param message - Custom message (defaults to "Access denied").
 */
export const forbiddenResponse = (message = "Access denied") =>
  jsonError("Forbidden", message, 403);

/**
 * Convenience helper for 429 Too Many Requests responses.
 * Triggered when a rate limit is exceeded.
 */
export const tooManyRequestsResponse = () =>
  jsonError(
    "Too Many Requests",
    "Rate limit exceeded. Please try again later.",
    429,
  );

/**
 * Convenience helper for 500 Internal Server Error responses.
 * Used for unexpected failures or database connection issues.
 */
export const internalServerErrorResponse = () =>
  jsonError("Internal Server Error", "Request could not be completed", 500);

/**
 * Convenience helper for 400 Bad Request responses.
 * Used for validation failures or malformed request payloads.
 * 
 * @param message - Custom message (defaults to "Invalid request").
 */
export const badRequestResponse = (message = "Invalid request") =>
  jsonError("Bad Request", message, 400);
