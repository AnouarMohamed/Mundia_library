/**
 * Users Service
 *
 * This module centralizes all user-related API operations for the University Library System.
 * It provides a consistent interface for managing student profiles, administrator accounts, 
 * and access requests.
 *
 * Architecture:
 * - Pure Service: Contains only stateless fetch calls to the application's backend routes.
 * - Decoupled: No React-specific dependencies, making it usable in both client and server contexts.
 * - Secure: Adheres to the principle of least privilege; sensitive fields like passwords are 
 *   never handled by this service (managed exclusively server-side).
 */

import { ApiError, getApiErrorMessage } from "./apiError";

/**
 * Valid approval statuses for a user account.
 * - PENDING: Initial state after registration, awaiting admin verification.
 * - APPROVED: Account is active and authorized to borrow books.
 * - REJECTED: Account verification failed; access denied.
 */
export type UserStatus = "PENDING" | "APPROVED" | "REJECTED";

/**
 * System roles defining access levels.
 * - USER: Standard student access (view catalog, borrow books, manage own profile).
 * - ADMIN: Administrative access (manage catalog, approve requests, view analytics).
 */
export type UserRole = "USER" | "ADMIN";

/**
 * Represents a user profile as exposed by the API.
 * Sensitive data (e.g., password hashes) is omitted for security.
 */
export interface User {
  /** Unique identifier for the user (UUID). */
  id: string;
  /** Full legal name of the user. */
  fullName: string;
  /** Unique university email address. */
  email: string;
  /** Unique university identification number. */
  universityId: number;
  /** URL to the hosted image of the user's university ID card. */
  universityCard: string;
  /** Current verification status of the account. */
  status: UserStatus | null;
  /** Assigned system role. */
  role: UserRole | null;
  /** Date of the user's last interaction with the platform (YYYY-MM-DD). */
  lastActivityDate: string | null;
  /** Timestamp of the last successful login. */
  lastLogin: Date | null;
  /** Timestamp when the account was created. */
  createdAt: Date | null;
}

/**
 * Configuration for filtering and searching the user database.
 */
export interface UserFilters {
  /** Search string matching name, email, or university ID. */
  search?: string;
  /** Filter by verification status. */
  status?: UserStatus | "all";
  /** Filter by system role. */
  role?: UserRole | "all";
  /** Sorting criteria. */
  sort?: "name" | "email" | "created" | "status";
  /** Page number for pagination. */
  page?: number;
  /** Records per page. */
  limit?: number;
}

/**
 * Paginated response structure for user lists.
 */
export interface UsersListResponse {
  /** Array of user profiles. */
  users: User[];
  /** Total matching records across all pages. */
  total: number;
  /** Current page number. */
  page: number;
  /** Total number of pages available. */
  totalPages: number;
  /** Maximum records per page. */
  limit: number;
}

/**
 * Wrapper for a single user profile response.
 */
export interface UserResponse {
  user: User;
}

/**
 * Fetches a filtered and paginated list of users from the API.
 * 
 * @param filters - Search, filter, and pagination options.
 * @returns A promise resolving to the paginated user list.
 * @throws {ApiError} If the API call fails or returns an invalid format.
 * 
 * @example
 * ```typescript
 * const { users } = await getUsersList({ status: "PENDING", sort: "created" });
 * ```
 */
export async function getUsersList(
  filters: UserFilters = {}
): Promise<UsersListResponse> {
  const params = new URLSearchParams();

  if (filters.search) params.append("search", filters.search);
  if (filters.status && filters.status !== "all") {
    params.append("status", filters.status);
  }
  if (filters.role && filters.role !== "all") {
    params.append("role", filters.role);
  }
  if (filters.sort) params.append("sort", filters.sort);
  if (filters.page) params.append("page", filters.page.toString());
  if (filters.limit) params.append("limit", filters.limit.toString());

  const queryString = params.toString();
  const url = queryString ? `/api/users?${queryString}` : "/api/users";

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

  // Support multiple response formats (standard paginated, raw array, or wrapped data)
  if (data.users && Array.isArray(data.users)) {
    return {
      users: data.users,
      total: data.total || data.users.length,
      page: data.page || 1,
      totalPages: data.totalPages || 1,
      limit: data.limit || data.users.length,
    };
  }

  if (data.data && Array.isArray(data.data)) {
    return {
      users: data.data,
      total: data.data.length,
      page: 1,
      totalPages: 1,
      limit: data.data.length,
    };
  }

  if (Array.isArray(data)) {
    return {
      users: data,
      total: data.length,
      page: 1,
      totalPages: 1,
      limit: data.length,
    };
  }

  throw new ApiError("Invalid response format from users API", 500);
}

/**
 * Fetches a single user profile by their unique ID.
 * 
 * @param userId - The UUID of the user.
 * @returns A promise resolving to the user profile.
 * @throws {ApiError} If the user is not found or the API fails.
 */
export async function getUser(userId: string): Promise<User> {
  if (!userId) {
    throw new ApiError("User ID is required", 400);
  }

  const response = await fetch(`/api/users/${userId}`, {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new ApiError(await getApiErrorMessage(response), response.status);
  }

  const data = await response.json();

  if (data.user) {
    return data.user;
  }

  if (data.id) {
    return data;
  }

  throw new ApiError("Invalid response format from user API", 500);
}

/**
 * Fetches the currently authenticated user's profile.
 * 
 * @returns A promise resolving to the current user's data.
 * @throws {ApiError} If no active session exists (401 Unauthorized).
 */
export async function getCurrentUser(): Promise<User> {
  const response = await fetch("/api/users/me", {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new ApiError(await getApiErrorMessage(response), response.status);
  }

  const data = await response.json();

  if (data.user) {
    return data.user;
  }

  if (data.id) {
    return data;
  }

  throw new ApiError("Invalid response format from current user API", 500);
}

/**
 * Convenience function to fetch users by their approval status.
 * 
 * @param status - The status to filter by (e.g., "PENDING").
 * @param limit - Optional maximum number of users to return.
 * @returns A promise resolving to an array of matching user profiles.
 */
export async function getUsersByStatus(
  status: UserStatus,
  limit?: number
): Promise<User[]> {
  const filters: UserFilters = { status };
  if (limit) filters.limit = limit;

  const response = await getUsersList(filters);
  return response.users;
}

/**
 * Convenience function to fetch users by their system role.
 * 
 * @param role - The role to filter by (e.g., "ADMIN").
 * @param limit - Optional maximum number of users to return.
 * @returns A promise resolving to an array of matching user profiles.
 */
export async function getUsersByRole(
  role: UserRole,
  limit?: number
): Promise<User[]> {
  const filters: UserFilters = { role };
  if (limit) filters.limit = limit;

  const response = await getUsersList(filters);
  return response.users;
}

/**
 * Fetches users awaiting account approval.
 * Useful for administrator verification workflows.
 * 
 * @param search - Optional search string to filter pending requests.
 * @returns A promise resolving to an array of pending users.
 */
export async function getPendingUsers(search?: string): Promise<User[]> {
  const filters: UserFilters = { status: "PENDING" };
  if (search) filters.search = search;

  const response = await getUsersList(filters);
  return response.users;
}

/**
 * Represents a formal request for elevated (Admin) privileges.
 */
export interface AdminRequest {
  /** Unique identifier for the request. */
  id: string;
  /** ID of the user making the request. */
  userId: string;
  /** Email of the requesting user. */
  userEmail: string;
  /** Full name of the requesting user. */
  userFullName: string;
  /** Stated reason for needing administrative access. */
  requestReason: string;
  /** Current status of the request. */
  status: "PENDING" | "APPROVED" | "REJECTED";
  /** ID of the admin who reviewed the request. */
  reviewedBy: string | null | undefined;
  /** Timestamp of the review. */
  reviewedAt: Date | null | undefined;
  /** Feedback provided if the request was rejected. */
  rejectionReason: string | null | undefined;
  /** Request creation timestamp. */
  createdAt: Date | null;
  /** Last update timestamp. */
  updatedAt: Date | null;
}

/**
 * Fetches all pending requests for administrative access.
 * 
 * @returns A promise resolving to an array of pending admin requests.
 */
export async function getPendingAdminRequests(): Promise<AdminRequest[]> {
  const response = await fetch("/api/admin/admin-requests", {
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

  throw new ApiError("Invalid response format from admin-requests API", 500);
}
