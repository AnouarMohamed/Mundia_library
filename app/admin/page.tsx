/**
 * Admin Dashboard Page
 *
 * Server Component that fetches admin statistics server-side for SSR.
 * Passes initial data to Client Component for React Query integration.
 */

import React from "react";
import AdminDashboardContent from "@/components/AdminDashboardContent";
import { getAdminDashboardStats } from "@/lib/admin/actions/dashboard";

/**
 * Use Node.js runtime for server-side data fetching.
 */
export const runtime = "nodejs";

/**
 * Admin dashboard entry with initial stats payload.
 */
const Page = async ({
  searchParams,
}: {
  searchParams: Promise<{ success?: string }>;
}) => {
  const params = await searchParams;
  const statsResult = await getAdminDashboardStats();

  return (
    <AdminDashboardContent
      initialStats={statsResult.success ? statsResult.data : undefined}
      successMessage={params.success}
    />
  );
};

export default Page;
