/**
 * Admin Users Page
 *
 * Server Component that fetches users and admin requests server-side for SSR.
 * Passes initial data to Client Component for React Query integration.
 */

import React from "react";
import { getAllUsers } from "@/lib/admin/actions/user";
import { getPendingAdminRequests } from "@/lib/admin/actions/admin-requests";
import { redirect } from "next/navigation";
import AdminUsersList from "@/components/AdminUsersList";
import { requireAdmin } from "@/lib/security/auth-guards";

/**
 * Use Node.js runtime for server-side data fetching.
 */
export const runtime = "nodejs";

/**
 * Admin users page with pending requests summary.
 */
const Page = async ({
  searchParams,
}: {
  searchParams: Promise<{ success?: string; error?: string }>;
}) => {
  const params = await searchParams;
  const guard = await requireAdmin();

  if (!guard.ok) {
    redirect("/sign-in");
  }
  const canManageRoles = guard.user.role === "SUPER_ADMIN";

  // Fetch all data server-side for SSR
  const [usersResult, adminRequestsResult] = await Promise.all([
    getAllUsers(),
    canManageRoles
      ? getPendingAdminRequests()
      : Promise.resolve({ success: true, data: [] }),
  ]);

  if (!usersResult.success) {
    return (
      <div className="admin-page-panel">
        <div className="mx-auto max-w-7xl">
          <div className="py-6 text-center sm:py-8">
            <p className="mb-2 text-base font-semibold text-red-500 sm:text-lg">
              Failed to load users
            </p>
            <p className="text-xs text-gray-500 sm:text-sm">
              {usersResult.error || "An unknown error occurred"}
            </p>
          </div>
        </div>
      </div>
    );
  }

  const users = usersResult.data || [];
  const adminRequests = adminRequestsResult.success
    ? adminRequestsResult.data || []
    : [];

  return (
    <AdminUsersList
      initialUsers={users}
      initialAdminRequests={adminRequests}
      successMessage={params.success}
      errorMessage={params.error}
      currentUserId={guard.user.id}
      canManageRoles={canManageRoles}
    />
  );
};

export default Page;
