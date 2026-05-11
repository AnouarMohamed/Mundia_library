/**
 * AdminRenewalRequestsList Component
 * 
 * A client component for administrators to manage book renewal requests.
 * Uses React Query for data fetching and mutations.
 */

"use client";

import React from "react";
import { Button } from "@/components/ui/button";
import { useRenewalRequests } from "@/hooks/useQueries";
import { useApproveRenewal, useRejectRenewal } from "@/hooks/useMutations";
import BorrowSkeleton from "@/components/skeletons/BorrowSkeleton";
import { CheckCircle, XCircle } from "lucide-react";
import type { RenewalRequestWithDetails } from "@/lib/services/renewals";

interface AdminRenewalRequestsListProps {
  initialRequests?: RenewalRequestWithDetails[];
}

const AdminRenewalRequestsList: React.FC<AdminRenewalRequestsListProps> = ({ 
  initialRequests 
}) => {
  const { data, isLoading, isError } = useRenewalRequests(initialRequests);
  const approveMutation = useApproveRenewal();
  const rejectMutation = useRejectRenewal();

  const requests = data || [];

  if (isLoading && (!initialRequests || initialRequests.length === 0)) {
    return (
      <div className="space-y-4">
        {[...Array(3)].map((_, i) => (
          <BorrowSkeleton key={i} variant="admin" />
        ))}
      </div>
    );
  }

  if (isError) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-center text-red-800">
        Failed to load renewal requests.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-semibold text-dark-400">
        Renewal Requests ({requests.length})
      </h2>

      {requests.length === 0 ? (
        <div className="rounded-lg border-2 border-dashed border-gray-200 py-8 text-center text-gray-500">
          No pending renewal requests found.
        </div>
      ) : (
        requests.map((request) => (
          <div 
            key={request.id} 
            className="flex flex-col gap-4 rounded-xl border border-gray-200 bg-white p-4 shadow-sm transition-hover hover:shadow-md sm:flex-row sm:items-center"
          >
            <div className="flex-1">
              <div className="mb-1 flex items-center gap-2">
                <h3 className="text-base font-bold text-dark-400">{request.bookTitle}</h3>
                <span className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${
                  request.status === "PENDING" ? "bg-yellow-100 text-yellow-800" :
                  request.status === "APPROVED" ? "bg-green-100 text-green-800" :
                  "bg-red-100 text-red-800"
                }`}>
                  {request.status}
                </span>
              </div>
              
              <div className="grid grid-cols-1 gap-x-4 gap-y-1 text-sm text-gray-600 sm:grid-cols-2">
                <p><span className="font-medium">User:</span> {request.userName} ({request.userEmail})</p>
                <p><span className="font-medium">Current Due:</span> {request.dueDate ? new Date(request.dueDate).toLocaleDateString() : "N/A"}</p>
                <p><span className="font-medium">Renewals:</span> {request.renewalCount}</p>
                <p><span className="font-medium">Requested:</span> {request.createdAt ? new Date(request.createdAt).toLocaleDateString() : "N/A"}</p>
              </div>

              {request.requestReason && (
                <p className="mt-2 rounded bg-gray-50 p-2 text-xs italic text-gray-500">
                  &quot;{request.requestReason}&quot;
                </p>
              )}
            </div>

            <div className="flex shrink-0 gap-2">
              {request.status === "PENDING" ? (
                <>
                  <Button
                    size="sm"
                    className="bg-green-600 hover:bg-green-700"
                    onClick={() => approveMutation.mutate({ 
                      requestId: request.id, 
                      bookTitle: request.bookTitle || "",
                      userName: request.userName || ""
                    })}
                    disabled={approveMutation.isPending || rejectMutation.isPending}
                  >
                    <CheckCircle className="mr-1.5 size-4" />
                    Approve
                  </Button>
                  <Button
                    size="sm"
                    variant="destructive"
                    onClick={() => rejectMutation.mutate({ 
                      requestId: request.id,
                      bookTitle: request.bookTitle || "",
                      userName: request.userName || ""
                    })}
                    disabled={approveMutation.isPending || rejectMutation.isPending}
                  >
                    <XCircle className="mr-1.5 size-4" />
                    Reject
                  </Button>
                </>
              ) : (
                <div className="text-sm text-gray-400">
                  Processed
                </div>
              )}
            </div>
          </div>
        ))
      )}
    </div>
  );
};

export default AdminRenewalRequestsList;
