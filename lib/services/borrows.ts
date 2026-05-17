/**
 * Borrows Service
 *
 * This module provides a clean API for managing borrow record operations within the University Library System.
 * It strictly adheres to the "Pure Service" pattern:
 * - Contains only stateless fetch calls to the application's API routes.
 * - Does NOT include React hooks, React Query logic, or side effects.
 * - Centralizes data transformation and error handling for all borrow-related transactions.
 *
 * This service is designed to be consumed by:
 * 1. Custom React Query hooks (for client-side state management).
 * 2. Server Components (for initial data fetching during SSR).
 * 3. Testing suites (for API integration testing).
 */

import { ApiError, getApiErrorMessage } from "./apiError";

/**
 * Valid statuses for a borrow record.
 * - PENDING: User has requested the book; awaiting admin approval.
 * - BORROWED: Request approved; book is currently with the user.
 * - RETURNED: Book has been returned to the library.
 */
export type BorrowStatus = "PENDING" | "BORROWED" | "RETURNED";

/**
 * Represents a standard borrow record as stored in the database.
 */
export interface BorrowRecord {
  /** Unique identifier for the borrow record (UUID). */
  id: string;
  /** Unique identifier of the user who borrowed the book. */
  userId: string;
  /** Unique identifier of the book being borrowed. */
  bookId: string;
  /** The date and time the borrow was initiated or approved. */
  borrowDate: Date | null;
  /** The date the book is expected to be returned (YYYY-MM-DD). */
  dueDate: string | null;
  /** The actual date the book was returned (YYYY-MM-DD). */
  returnDate: string | null;
  /** Current lifecycle status of the loan. */
  status: BorrowStatus;
  /** ID or Name of the administrator who processed the borrow. */
  borrowedBy: string | null;
  /** ID or Name of the administrator who processed the return. */
  returnedBy: string | null;
  /** Accumulated fine amount for late returns (stored as string to preserve precision). */
  fineAmount: string | null;
  /** Internal administrative or student notes. */
  notes: string | null;
  /** Number of times the due date has been extended. */
  renewalCount: number;
  /** Timestamp of the last automated reminder email. */
  lastReminderSent: Date | null;
  /** Timestamp of the last record update. */
  updatedAt: Date | null;
  /** ID of the entity that last updated the record. */
  updatedBy: string | null;
  /** Record creation timestamp. */
  createdAt: Date | null;
}

/**
 * Extended borrow record including denormalized user and book metadata.
 * Primarily used in administrative dashboards for improved readability.
 */
export interface BorrowRecordWithDetails extends BorrowRecord {
  /** User's full name. */
  userName: string;
  /** User's university email address. */
  userEmail: string;
  /** User's unique university identification number. */
  userUniversityId: number;
  /** Title of the borrowed book. */
  bookTitle: string;
  /** Author of the borrowed book. */
  bookAuthor: string;
  /** Genre of the borrowed book. */
  bookGenre: string;
  /** URL to the book's cover image. */
  bookCoverUrl: string | null;
  /** Dominant color of the book cover (for UI placeholders). */
  bookCoverColor: string | null;
}

/**
 * Configuration options for filtering and paginating borrow records.
 */
export interface BorrowFilters {
  /** Filter by specific student. */
  userId?: string;
  /** Filter by specific book. */
  bookId?: string;
  /** Filter by loan status. */
  status?: BorrowStatus | "all";
  /** Filter records starting from this date (inclusive). */
  dateFrom?: string;
  /** Filter records up to this date (inclusive). */
  dateTo?: string;
  /** If true, returns only records where the due date has passed. */
  overdue?: boolean;
  /** Sorting criteria. */
  sort?: "date" | "dueDate" | "status" | "user";
  /** Page number for pagination. */
  page?: number;
  /** Number of records per page. */
  limit?: number;
}

/**
 * Standard paginated response for borrow record lists.
 */
export interface BorrowsListResponse {
  /** Array of borrow records (optionally with details). */
  borrows: BorrowRecord[] | BorrowRecordWithDetails[];
  /** Total count of records matching the filters across all pages. */
  total: number;
  /** Current page number. */
  page: number;
  /** Total number of pages available. */
  totalPages: number;
  /** Maximum records per page. */
  limit: number;
}

/**
 * Wrapper for a single borrow record response.
 */
export interface BorrowResponse {
  borrow: BorrowRecord | BorrowRecordWithDetails;
}

/**
 * Fetches a paginated and filtered list of borrow records from the API.
 * 
 * @param filters - Options for filtering, sorting, and pagination.
 * @returns A promise resolving to the paginated list of borrow records.
 * @throws {ApiError} If the server returns a non-OK status or invalid format.
 * 
 * @example
 * ```typescript
 * const { borrows } = await getBorrowsList({ status: "BORROWED", overdue: true });
 * ```
 */
export async function getBorrowsList(
  filters: BorrowFilters = {}
): Promise<BorrowsListResponse> {
  const params = new URLSearchParams();

  if (filters.userId) params.append("userId", filters.userId);
  if (filters.bookId) params.append("bookId", filters.bookId);
  if (filters.status && filters.status !== "all") {
    params.append("status", filters.status);
  }
  if (filters.dateFrom) params.append("dateFrom", filters.dateFrom);
  if (filters.dateTo) params.append("dateTo", filters.dateTo);
  if (filters.overdue !== undefined) {
    params.append("overdue", filters.overdue.toString());
  }
  if (filters.sort) params.append("sort", filters.sort);
  if (filters.page) params.append("page", filters.page.toString());
  if (filters.limit) params.append("limit", filters.limit.toString());

  const queryString = params.toString();
  const url = queryString
    ? `/api/borrow-records?${queryString}`
    : "/api/borrow-records";

  const response = await fetch(url, {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new ApiError(await getApiErrorMessage(response), response.status);
  }

  const data = await response.json();

  // Support multiple response formats to ensure backward compatibility and flexibility
  if (data.borrows && Array.isArray(data.borrows)) {
    return {
      borrows: data.borrows,
      total: data.total || data.borrows.length,
      page: data.page || 1,
      totalPages: data.totalPages || 1,
      limit: data.limit || data.borrows.length,
    };
  }

  if (data.data && Array.isArray(data.data)) {
    return {
      borrows: data.data,
      total: data.data.length,
      page: 1,
      totalPages: 1,
      limit: data.data.length,
    };
  }

  if (Array.isArray(data)) {
    return {
      borrows: data,
      total: data.length,
      page: 1,
      totalPages: 1,
      limit: data.length,
    };
  }

  throw new ApiError("Invalid response format from borrow-records API", 500);
}

/**
 * Fetches all borrow records associated with a specific user.
 * 
 * CRITICAL: This function disables standard pagination (limit=10000) 
 * to ensure the UI has access to the user's complete history.
 * 
 * @param userId - Unique identifier of the user.
 * @param status - Optional status to filter the user's records.
 * @returns A promise resolving to an array of borrow records.
 */
export async function getUserBorrows(
  userId: string,
  status?: BorrowStatus
): Promise<BorrowRecord[]> {
  if (!userId) {
    throw new ApiError("User ID is required", 400);
  }

  const filters: BorrowFilters = { userId, limit: 10000 };
  if (status) filters.status = status;

  const response = await getBorrowsList(filters);
  return response.borrows as BorrowRecord[];
}

/**
 * Fetches borrow requests specifically formatted for administrative review.
 * Includes joined user and book data.
 * 
 * @param status - Filter by request status (e.g., "PENDING").
 * @param search - Optional search string for user or book titles.
 * @returns A promise resolving to an array of detailed borrow records.
 */
export async function getBorrowRequests(
  status?: BorrowStatus,
  search?: string
): Promise<BorrowRecordWithDetails[]> {
  const params = new URLSearchParams();
  if (status) params.append("status", status);
  if (search) params.append("search", search);

  const queryString = params.toString();
  const url = queryString
    ? `/api/admin/borrow-requests?${queryString}`
    : "/api/admin/borrow-requests";

  const response = await fetch(url, {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new ApiError(await getApiErrorMessage(response), response.status);
  }

  const data = await response.json();

  if (data.requests && Array.isArray(data.requests)) {
    return data.requests;
  }

  if (data.data && Array.isArray(data.data)) {
    return data.data;
  }

  if (Array.isArray(data)) {
    return data;
  }

  throw new ApiError("Invalid response format from borrow-requests API", 500);
}

/**
 * Fetches a single borrow record by its unique identifier.
 * 
 * @param borrowId - The ID of the record to fetch.
 * @param includeDetails - If true, joins user and book metadata into the response.
 * @returns A promise resolving to the borrow record.
 */
export async function getBorrow(
  borrowId: string,
  includeDetails: boolean = false
): Promise<BorrowRecord | BorrowRecordWithDetails> {
  if (!borrowId) {
    throw new ApiError("Borrow record ID is required", 400);
  }

  const params = new URLSearchParams();
  if (includeDetails) params.append("includeDetails", "true");

  const queryString = params.toString();
  const url = queryString
    ? `/api/borrow-records/${borrowId}?${queryString}`
    : `/api/borrow-records/${borrowId}`;

  const response = await fetch(url, {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new ApiError(await getApiErrorMessage(response), response.status);
  }

  const data = await response.json();

  if (data.borrow) {
    return data.borrow;
  }

  if (data.id) {
    return data;
  }

  throw new ApiError("Invalid response format from borrow-record API", 500);
}

/**
 * Convenience function to fetch all currently overdue loans.
 * 
 * @param userId - Optional filter to check overdue records for a specific student.
 * @returns A promise resolving to an array of overdue borrow records.
 */
export async function getOverdueBorrows(
  userId?: string
): Promise<BorrowRecord[]> {
  const filters: BorrowFilters = {
    status: "BORROWED",
    overdue: true,
  };

  if (userId) filters.userId = userId;

  const response = await getBorrowsList(filters);
  return response.borrows as BorrowRecord[];
}

/**
 * Convenience function to fetch borrow records by status with an optional limit.
 * 
 * @param status - The status to filter by.
 * @param limit - Optional maximum number of records to return.
 * @returns A promise resolving to an array of borrow records.
 */
export async function getBorrowsByStatus(
  status: BorrowStatus,
  limit?: number
): Promise<BorrowRecord[]> {
  const filters: BorrowFilters = { status };
  if (limit) filters.limit = limit;

  const response = await getBorrowsList(filters);
  return response.borrows as BorrowRecord[];
}

/**
 * Fetches borrow records created within a specific date range.
 * Useful for reporting, auditing, and analytics.
 * 
 * @param dateFrom - Start date (YYYY-MM-DD).
 * @param dateTo - End date (YYYY-MM-DD).
 * @param status - Optional status filter.
 * @returns A promise resolving to an array of borrow records.
 */
export async function getBorrowsByDateRange(
  dateFrom: string,
  dateTo: string,
  status?: BorrowStatus
): Promise<BorrowRecord[]> {
  const filters: BorrowFilters = { dateFrom, dateTo };
  if (status) filters.status = status;

  const response = await getBorrowsList(filters);
  return response.borrows as BorrowRecord[];
}
