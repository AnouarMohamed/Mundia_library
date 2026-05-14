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

const formatStatusLabel = (status: string): string =>
  status
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());

const AdminRenewalRequestsList: React.FC<AdminRenewalRequestsListProps> = ({
  initialRequests,
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
      <div className="status-danger rounded-lg border p-4 text-center">
        Failed to load renewal requests.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-semibold text-slate-900">
        Renewal Requests ({requests.length})
      </h2>

      {requests.length === 0 ? (
        <div className="rounded-lg border border-dashed border-[var(--mundia-line)] bg-[var(--mundia-paper)] py-8 text-center text-slate-500">
          No pending renewal requests found.
        </div>
      ) : (
        requests.map((request) => (
          <div
            key={request.id}
            className="flex flex-col gap-4 rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-paper)] p-4 transition hover:border-[var(--mundia-navy)] sm:flex-row sm:items-center"
          >
            <div className="flex-1">
              <div className="mb-1 flex items-center gap-2">
                <h3 className="text-base font-bold text-slate-900">
                  {request.bookTitle}
                </h3>
                <span
                  className={`status-pill ${
                    request.status === "PENDING"
                      ? "status-warning"
                      : request.status === "APPROVED"
                        ? "status-success"
                        : "status-danger"
                  }`}
                >
                  {formatStatusLabel(request.status)}
                </span>
              </div>

              <div className="grid grid-cols-1 gap-x-4 gap-y-1 text-sm text-slate-600 sm:grid-cols-2">
                <p>
                  <span className="font-medium text-slate-800">User:</span>{" "}
                  {request.userName} ({request.userEmail})
                </p>
                <p>
                  <span className="font-medium text-slate-800">
                    Current Due:
                  </span>{" "}
                  {request.dueDate
                    ? new Date(request.dueDate).toLocaleDateString()
                    : "N/A"}
                </p>
                <p>
                  <span className="font-medium text-slate-800">Renewals:</span>{" "}
                  {request.renewalCount}
                </p>
                <p>
                  <span className="font-medium text-slate-800">Requested:</span>{" "}
                  {request.createdAt
                    ? new Date(request.createdAt).toLocaleDateString()
                    : "N/A"}
                </p>
              </div>

              {request.requestReason && (
                <p className="mt-2 rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-paper-warm)] p-2 text-xs italic text-slate-600">
                  &quot;{request.requestReason}&quot;
                </p>
              )}
            </div>

            <div className="flex shrink-0 gap-2">
              {request.status === "PENDING" ? (
                <>
                  <Button
                    size="sm"
                    className="bg-[var(--mundia-success)] text-white hover:bg-[var(--mundia-success-strong)]"
                    onClick={() =>
                      approveMutation.mutate({
                        requestId: request.id,
                        bookTitle: request.bookTitle || "",
                        userName: request.userName || "",
                      })
                    }
                    disabled={
                      approveMutation.isPending || rejectMutation.isPending
                    }
                  >
                    <CheckCircle className="mr-1.5 size-4" aria-hidden="true" />
                    Approve
                  </Button>
                  <Button
                    size="sm"
                    variant="destructive"
                    onClick={() =>
                      rejectMutation.mutate({
                        requestId: request.id,
                        bookTitle: request.bookTitle || "",
                        userName: request.userName || "",
                      })
                    }
                    disabled={
                      approveMutation.isPending || rejectMutation.isPending
                    }
                  >
                    <XCircle className="mr-1.5 size-4" aria-hidden="true" />
                    Reject
                  </Button>
                </>
              ) : (
                <div className="text-sm text-slate-500">Processed</div>
              )}
            </div>
          </div>
        ))
      )}
    </div>
  );
};

export default AdminRenewalRequestsList;
