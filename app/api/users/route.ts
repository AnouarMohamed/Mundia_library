/**
 * Users API Endpoint (Admin Only)
 * 
 * Provides administrative access to the user database.
 * Supports searching by name, email, or university ID, as well as filtering by status and role.
 * 
 * @module app/api/users/route
 */

import { NextRequest, NextResponse } from "next/server";
import ratelimit from "@/lib/ratelimit";
import { db } from "@/database/drizzle";
import { users } from "@/database/schema";
import { desc, asc, eq, and, or, like, sql } from "drizzle-orm";
import { requireAdminCapabilityRouteAccess } from "@/lib/admin/route-guard";
import {
  getClientIp,
  normalizeTextParam,
} from "@/lib/security/api-request";
import { logError } from "@/lib/security/logger";

/**
 * Force Node.js runtime for database connectivity.
 */
export const runtime = "nodejs";

/**
 * GET Handler for /api/users
 * 
 * Expected Query Parameters:
 * - search (string): Search term for name, email, or university ID.
 * - status (PENDING|APPROVED|REJECTED|all): Filter by account status.
 * - role (USER|ADMIN|all): Filter by user role.
 * - sort (name|email|created|status): Sort field.
 * - page (number): Page index (starts at 1).
 * - limit (number): Results per page (max 100).
 * 
 * @param {NextRequest} request - Next.js Request object
 * @returns {NextResponse} JSON response containing user list and pagination metadata
 */
export async function GET(request: NextRequest) {
  try {
    // Authenticate before consuming an administrator-scoped limiter bucket.
    const guard = await requireAdminCapabilityRouteAccess(
      "users.manage_status",
    );
    if (!guard.ok) {
      return guard.response;
    }

    const ip = await getClientIp();
    const { success } = await ratelimit.limit(
      `admin-users:${guard.user.id}:${ip}`,
    );
    if (!success) {
      return NextResponse.json(
        {
          success: false,
          error: "Too Many Requests",
          message: "Rate limit exceeded. Please try again later.",
        },
        { status: 429 },
      );
    }

    const { searchParams } = new URL(request.url);

    // Bound every value before it reaches a query, allocation, or response.
    const search = normalizeTextParam(searchParams.get("search"), 100);
    const status = searchParams.get("status") || "";
    const role = searchParams.get("role") || "";
    const sort = searchParams.get("sort") || "name";
    const requestedPage = Number.parseInt(searchParams.get("page") || "1", 10);
    const requestedLimit = Number.parseInt(
      searchParams.get("limit") || "50",
      10,
    );
    const page =
      Number.isSafeInteger(requestedPage) && requestedPage > 0
        ? requestedPage
        : 1;
    const limit =
      Number.isSafeInteger(requestedLimit) && requestedLimit > 0
        ? Math.min(requestedLimit, 100)
        : 50;

    const allowedStatuses = new Set([
      "",
      "all",
      "PENDING",
      "APPROVED",
      "REJECTED",
    ]);
    const allowedRoles = new Set(["", "all", "USER", "ADMIN"]);
    const allowedSorts = new Set([
      "name",
      "email",
      "created",
      "status",
    ]);
    if (
      !allowedStatuses.has(status) ||
      !allowedRoles.has(role) ||
      !allowedSorts.has(sort)
    ) {
      return NextResponse.json(
        { success: false, error: "Invalid query parameters" },
        { status: 400 },
      );
    }

    // 4. Build database query conditions.
    const whereConditions = [];

    // Full-text search emulation across multiple fields.
    if (search) {
      const searchPattern = `%${search}%`;
      whereConditions.push(
        or(
          like(users.fullName, searchPattern),
          like(users.email, searchPattern),
          sql`CAST(${users.universityId} AS text) LIKE ${searchPattern}`
        )
      );
    }

    if (status && status !== "all") {
      whereConditions.push(
        eq(users.status, status as "PENDING" | "APPROVED" | "REJECTED")
      );
    }

    if (role && role !== "all") {
      whereConditions.push(eq(users.role, role as "USER" | "ADMIN"));
    }

    // Define sort order.
    let orderBy;
    switch (sort) {
      case "email":
        orderBy = asc(users.email);
        break;
      case "created":
        orderBy = desc(users.createdAt);
        break;
      case "status":
        orderBy = asc(users.status);
        break;
      case "name":
      default:
        orderBy = asc(users.fullName);
        break;
    }

    // 5. Execute paginated data query.
    const offset = (page - 1) * limit;
    const allUsers = await db
      .select({
        id: users.id,
        fullName: users.fullName,
        email: users.email,
        universityId: users.universityId,
        status: users.status,
        role: users.role,
        lastActivityDate: users.lastActivityDate,
        lastLogin: users.lastLogin,
        createdAt: users.createdAt,
        // CRITICAL: sensitive fields like passwords are NEVER returned.
      })
      .from(users)
      .where(whereConditions.length > 0 ? and(...whereConditions) : undefined)
      .orderBy(orderBy)
      .limit(limit)
      .offset(offset);

    // Get total count for pagination.
    const totalUsersResult = await db
      .select({ count: sql<number>`count(*)` })
      .from(users)
      .where(whereConditions.length > 0 ? and(...whereConditions) : undefined);

    const totalUsers = totalUsersResult[0]?.count || 0;
    const totalPages = Math.ceil(totalUsers / limit);

    return NextResponse.json({
      success: true,
      users: allUsers.map((user) => ({
        ...user,
        universityCard: `/api/admin/users/${user.id}/identity-card`,
      })),
      total: totalUsers,
      page,
      totalPages,
      limit,
    });
  } catch (error) {
    logError("admin.users_api_fetch_failed", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to fetch users",
        message: "Request could not be completed",
      },
      { status: 500 }
    );
  }
}
