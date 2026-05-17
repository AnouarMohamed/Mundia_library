/**
 * Renewal Service
 * 
 * This module provides functions for managing book renewal requests.
 * It serves as an abstraction layer between the administrative UI and the underlying 
 * server actions, centralizing data retrieval for renewal-related workflows.
 * 
 * Capabilities:
 * - Fetching all pending and processed renewal requests.
 * - Mapping server-side response data to standardized client-side interfaces.
 */

import { getAllRenewalRequests } from "@/lib/admin/actions/renewal";

/**
 * Represents a renewal request enriched with user and book metadata.
 * This structure is optimized for display in administrative tables and dashboards.
 */
export interface RenewalRequestWithDetails {
  /** Unique identifier for the renewal request. */
  id: string;
  /** Current lifecycle status of the request. */
  status: "PENDING" | "APPROVED" | "REJECTED";
  /** The reason provided by the student for requesting more time. */
  requestReason: string | null;
  /** Timestamp of when the request was submitted. */
  createdAt: Date | null;
  /** ID of the associated borrow record being renewed. */
  borrowRecordId: string;
  /** Full name of the student who submitted the request. */
  userName: string | null;
  /** University email of the student. */
  userEmail: string | null;
  /** Title of the book being renewed. */
  bookTitle: string | null;
  /** Current due date of the book (before renewal). */
  dueDate: string | null;
  /** Number of times this specific loan has already been renewed. */
  renewalCount: number;
}

/**
 * Retrieves all renewal requests from the database via server actions.
 * 
 * @returns A promise resolving to an array of enriched renewal request objects.
 * @throws Error if the server action returns a failure or fails to execute.
 */
export async function getRenewalRequests(): Promise<RenewalRequestWithDetails[]> {
  const result = await getAllRenewalRequests();
  
  // Unwrap the standard action response format
  if (result.success && result.data) {
    return result.data as RenewalRequestWithDetails[];
  }
  
  throw new Error(result.error || "Failed to fetch renewal requests");
}
