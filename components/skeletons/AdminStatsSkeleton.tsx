import React from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import { Card, CardContent, CardHeader } from "@/components/ui/card";

/**
 * AdminStatsSkeleton Component
 *
 * A skeleton loader that matches the exact dimensions and layout of admin statistics cards.
 * Supports both the standard stat card format (used in admin dashboard) and Card-based format
 * (used in automation page).
 * Used to show loading states while admin statistics are being fetched.
 *
 * Features:
 * - Exact size matching to prevent layout shift
 * - Supports standard stat format (variant="stat")
 * - Supports Card format (variant="card")
 * - Matches grid layout (grid-cols-1 md:grid-cols-2 lg:grid-cols-5)
 * - Responsive layout matching
 *
 * Usage:
 * ```tsx
 * // Standard stat cards (admin dashboard)
 * <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-5">
 *   {[...Array(5)].map((_, i) => (
 *     <AdminStatsSkeleton key={i} variant="stat" />
 *   ))}
 * </div>
 *
 * // Card format (automation page)
 * <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-4">
 *   {[...Array(4)].map((_, i) => (
 *     <AdminStatsSkeleton key={i} variant="card" />
 *   ))}
 * </div>
 * ```
 *
 * Dimensions matched:
 * - Stat: bg-white rounded-xl p-5 space-y-5 flex-1
 * - Stat info: flex justify-between items-center gap-5
 * - Stat label: font-medium text-base
 * - Stat number: text-2xl font-bold
 * - Stat description: text-sm text-gray-500
 */
interface AdminStatsSkeletonProps {
  /**
   * Display variant: "stat" for standard stat cards, "card" for Card-based format
   * Default: "stat"
   */
  variant?: "stat" | "card";
  /**
   * Additional CSS classes to apply
   */
  className?: string;
}

const AdminStatsSkeleton: React.FC<AdminStatsSkeletonProps> = ({
  variant = "stat",
  className,
}) => {
  if (variant === "card") {
    return (
      <Card className={cn("border-blue-200 bg-blue-50", className)}>
        <CardHeader className="pb-2">
          <Skeleton className="h-5 w-24" />
        </CardHeader>
        <CardContent>
          {/* Number */}
          <Skeleton className="mb-1 h-8 w-16" />
          {/* Description */}
          <Skeleton className="h-4 w-32" />
        </CardContent>
      </Card>
    );
  }

  // Standard stat variant
  return (
    <div
      className={cn(
        "rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm sm:p-5",
        className
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="space-y-2">
          <Skeleton className="h-4 w-20" />
          <Skeleton className="h-8 w-16" />
        </div>
        <Skeleton className="size-9 rounded-xl" />
      </div>
      <Skeleton className="mt-4 h-3.5 w-40" />
    </div>
  );
};

export default AdminStatsSkeleton;
