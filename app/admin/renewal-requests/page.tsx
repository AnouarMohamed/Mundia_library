/**
 * Admin Renewal Requests Page
 * 
 * Server component that fetches initial renewal requests and renders the
 * AdminRenewalRequestsList client component.
 */

import React from "react";
import AdminRenewalRequestsList from "@/components/AdminRenewalRequestsList";
import { getRenewalRequests, type RenewalRequestWithDetails } from "@/lib/services/renewals";

export const metadata = {
  title: "Renewal Requests | Admin Dashboard",
  description: "Manage book renewal requests from students.",
};

const RenewalRequestsPage = async () => {
  // Fetch initial data for SSR
  let initialRequests: RenewalRequestWithDetails[] = [];
  try {
    initialRequests = await getRenewalRequests();
  } catch (error) {
    console.error("Failed to load initial renewal requests:", error);
  }

  return (
    <div className="flex flex-col gap-5 sm:gap-7">
      <div className="flex flex-col gap-2">
        <h1 className="text-2xl font-bold text-dark-400 sm:text-3xl">Renewal Management</h1>
        <p className="text-sm text-gray-500 sm:text-base">
          Review and process requests from students to extend book due dates.
        </p>
      </div>

      <AdminRenewalRequestsList initialRequests={initialRequests} />
    </div>
  );
};

export default RenewalRequestsPage;
