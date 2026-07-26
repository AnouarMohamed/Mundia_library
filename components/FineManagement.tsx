/**
 * FineManagement Component
 * 
 * Provides an administrative interface for configuring and applying overdue 
 * book fines. Integrates with React Query for configuration management.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import { useState, useEffect, useId } from "react";
import { Button } from "@/components/ui/button";
import { useFineConfig } from "@/hooks/useQueries";
import {
  useUpdateFineConfig,
  useUpdateOverdueFines,
} from "@/hooks/useMutations";
import type { FineConfig } from "@/lib/services/admin";

/**
 * Props for FineManagement
 */
interface FineManagementProps {
  /**
   * Initial fine configuration from SSR to prevent hydration flicker
   */
  initialFineConfig?: FineConfig;
}

/**
 * FineManagement
 * 
 * Component for managing fine configuration. Uses React Query hooks.
 * Features:
 * - Uses useFineConfig hook for fetching current fine amount
 * - Uses useUpdateFineConfig mutation for updating fine amount
 * - Automatic cache invalidation on success
 * - Supports SSR initial data to prevent duplicate fetches
 */
export default function FineManagement({
  initialFineConfig,
}: FineManagementProps) {
  // Fetch live fine configuration with optional SSR hydration
  const { data: fineConfig, isLoading: configLoading } =
    useFineConfig(initialFineConfig);

  // Initialize mutations for updating config and existing fines
  const updateFineConfigMutation = useUpdateFineConfig();
  const updateOverdueFinesMutation = useUpdateOverdueFines();

  // Local state for editing the amount
  const fineAmount = fineConfig?.fineAmount || 1.0;
  const [editableAmount, setEditableAmount] = useState<number>(fineAmount);
  const [isEditing, setIsEditing] = useState(false);
  const fineAmountInputId = useId();

  // Keep local editable amount in sync with incoming server data
  useEffect(() => {
    if (fineConfig?.fineAmount) {
      setEditableAmount(fineConfig.fineAmount);
    }
  }, [fineConfig?.fineAmount]);

  /**
   * Resets the UI and local state to the current server values.
   */
  const handleCancelEdit = () => {
    setIsEditing(false);
    setEditableAmount(fineAmount);
  };

  /**
   * Orchestrates the two-step save process:
   * 1. Update the global fine configuration.
   * 2. Re-calculate all existing overdue fines with the new rate.
   */
  const handleSaveAmount = () => {
    if (isNaN(editableAmount) || editableAmount < 0) {
      return; 
    }

    // Step 1: Update the configuration
    updateFineConfigMutation.mutate(
      {
        fineAmount: editableAmount,
        updatedBy: "admin",
      },
      {
        onSuccess: () => {
          // Step 2: Trigger a bulk update for overdue records
          updateOverdueFinesMutation.mutate(
            {
              customFineAmount: editableAmount,
            },
            {
              onSuccess: (_data) => {
                setIsEditing(false);
                // SUCCESS: React Query automatically invalidates related caches
              },
            }
          );
        },
      }
    );
  };

  /**
   * Enters the editing mode.
   */
  const handleEditMode = () => {
    setIsEditing(true);
    setEditableAmount(fineAmount);
  };

  return (
    <div className="space-y-3 sm:space-y-4">
      {/* Header section with description */}
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h6 className="text-sm font-medium text-gray-900 sm:text-base">
            Fine Management
          </h6>
          <p className="text-xs text-gray-600 sm:text-sm">
            Update fines for overdue books
          </p>
        </div>
      </div>

      {/* Dynamic Fine Amount Configuration Panel */}
      <div className="rounded-lg bg-blue-50 p-3 sm:p-4">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex-1">
            <label
              htmlFor={fineAmountInputId}
              className="mb-1 block text-xs font-medium text-blue-900 sm:text-sm"
            >
              Daily Fine Amount
            </label>
            <p className="mb-2 text-[10px] text-blue-600 sm:text-xs">
              Set the amount charged per day for overdue books
            </p>
            
            {/* Amount input/display area */}
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs text-blue-700 sm:text-sm">$</span>
              {isEditing ? (
                <input
                  id={fineAmountInputId}
                  type="number"
                  step="0.01"
                  min="0"
                  max="100"
                  value={editableAmount}
                  onChange={(e) =>
                    setEditableAmount(parseFloat(e.target.value) || 0)
                  }
                  className="w-20 rounded border border-blue-200 px-2 py-1 text-xs focus:border-blue-400 focus:outline-none focus:ring-1 focus:ring-blue-400 sm:text-sm"
                  placeholder="1.00"
                />
              ) : (
                <span className="w-20 rounded bg-blue-100 px-2 py-1 text-xs font-medium text-blue-900 sm:text-sm">
                  {configLoading ? "..." : fineAmount.toFixed(2)}
                </span>
              )}
              <span className="text-xs text-blue-700 sm:text-sm">per day</span>
            </div>
          </div>
          
          {/* Action buttons area */}
          <div className="flex w-full flex-col gap-2 sm:w-auto">
            {isEditing ? (
              <>
                <Button
                  onClick={handleSaveAmount}
                  disabled={
                    updateFineConfigMutation.isPending ||
                    updateOverdueFinesMutation.isPending ||
                    configLoading
                  }
                  variant="outline"
                  size="sm"
                  className="w-full border-green-200 bg-green-100 text-green-700 hover:bg-green-200 sm:w-auto"
                >
                  {updateFineConfigMutation.isPending ||
                  updateOverdueFinesMutation.isPending
                    ? "Saving..."
                    : "Save Fine"}
                </Button>
                <Button
                  onClick={handleCancelEdit}
                  disabled={
                    updateFineConfigMutation.isPending ||
                    updateOverdueFinesMutation.isPending
                  }
                  variant="outline"
                  size="sm"
                  className="w-full border-gray-200 bg-gray-100 text-gray-700 hover:bg-gray-200 sm:w-auto"
                >
                  Cancel
                </Button>
              </>
            ) : (
              <Button
                onClick={handleEditMode}
                disabled={configLoading}
                variant="outline"
                size="sm"
                className="w-full border-orange-200 bg-orange-50 text-orange-700 hover:bg-orange-100 sm:w-auto"
              >
                Update Fines
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
