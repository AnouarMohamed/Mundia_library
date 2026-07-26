/**
 * Admin Dashboard Home Page
 * 
 * This is the main entry point for the admin dashboard.
 * It fetches high-level statistics server-side (SSR) and passes them to a client-side 
 * component for interactive display and further data management.
 * 
 * @module app/admin/page
 */

import React from "react";
import AdminDashboardContent from "@/components/AdminDashboardContent";
import { getAdminDashboardStats } from "@/lib/admin/actions/dashboard";

/**
 * Use Node.js runtime for server-side data fetching and complex business logic.
 */
export const runtime = "nodejs";

/**
 * Admin dashboard page component (Server Component).
 * 
 * Fetches initial statistics from the database using a server action.
 * Also handles success messages passed via URL query parameters.
 * 
 * @param {Object} props - Component properties
 * @param {Promise<Object>} props.searchParams - Promise resolving to URL query parameters
 */
const Page = async ({
  searchParams,
}: {
  searchParams: Promise<{ success?: string }>;
}) => {
  // Await the search parameters.
  const params = await searchParams;

  // Fetch dashboard statistics server-side for initial render.
  const statsResult = await getAdminDashboardStats();

  return (
    /* 
       AdminDashboardContent is a client component that manages the dashboard UI.
       We pass the initial stats to it to avoid a loading state on first paint.
    */
    <AdminDashboardContent
      initialStats={statsResult.success ? statsResult.data : undefined}
      successMessage={params.success}
    />
  );
};

export default Page;
