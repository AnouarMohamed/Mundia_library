/**
 * Renewal Service
 * 
 * This file contains client-side service functions for managing renewal requests.
 * It acts as a bridge between the React components/hooks and the server actions.
 */

import { getAllRenewalRequests } from "@/lib/admin/actions/renewal";

/**
 * Interface for a renewal request with details
 */
export interface RenewalRequestWithDetails {
  id: string;
  status: "PENDING" | "APPROVED" | "REJECTED";
  requestReason: string | null;
  createdAt: Date | null;
  borrowRecordId: string;
  userName: string | null;
  userEmail: string | null;
  bookTitle: string | null;
  dueDate: string | null;
  renewalCount: number;
}

/**
 * Fetch all renewal requests from the server
 * 
 * @returns A promise resolving to an array of renewal requests.
 * @throws Error if the server action fails.
 */
export async function getRenewalRequests(): Promise<RenewalRequestWithDetails[]> {
  const result = await getAllRenewalRequests();
  
  if (result.success && result.data) {
    return result.data as RenewalRequestWithDetails[];
  }
  
  throw new Error(result.error || "Failed to fetch renewal requests");
}
