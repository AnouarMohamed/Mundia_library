/**
 * AdminStatsSkeleton Component
 * 
 * Provides a low-fidelity loading placeholder for the administrator statistics 
 * dashboard. Designed to minimize layout shift (CLS) during data fetching.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

import React from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import { Card, CardContent, CardHeader } from "@/components/ui/card";

/**
 * Props for AdminStatsSkeleton
 */
interface AdminStatsSkeletonProps {
  /**
   * Display variant: 
   * - "stat": standard minimalist stat cards (Admin Dashboard)
   * - "card": detailed shadcn/ui Card format (Automation Page)
   * @default "stat"
   */
  variant?: "stat" | "card";
  /**
   * Additional CSS classes for custom overrides
   */
  className?: string;
}

/**
 * AdminStatsSkeleton
 * 
 * A skeleton loader that matches the exact dimensions and layout of admin statistics cards.
 */
const AdminStatsSkeleton: React.FC<AdminStatsSkeletonProps> = ({
  variant = "stat",
  className,
}) => {
  /**
   * BRANCH: Card Variant
   * Matches the visual weight of standard Dashboard Cards with headers.
   */
  if (variant === "card") {
    return (
      <Card
        className={cn(
          "border-[var(--mundia-line)] bg-[var(--mundia-paper)]",
          className,
        )}
      >
        <CardHeader className="pb-2">
          <Skeleton className="h-5 w-24" />
        </CardHeader>
        <CardContent>
          {/* Main Stat Number placeholder */}
          <Skeleton className="mb-1 h-8 w-16" />
          {/* Descriptive subtext placeholder */}
          <Skeleton className="h-4 w-32" />
        </CardContent>
      </Card>
    );
  }

  /**
   * BRANCH: Stat Variant (Default)
   * Matches the minimalist stat blocks used in the primary administrative grid.
   */
  return (
    <div
      className={cn(
        "rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-surface)] p-4 sm:p-5",
        className,
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="space-y-2">
          {/* Label placeholder */}
          <Skeleton className="h-4 w-20" />
          {/* Large Value placeholder */}
          <Skeleton className="h-8 w-16" />
        </div>
        {/* Decorative Icon placeholder */}
        <Skeleton className="size-10 rounded-lg" />
      </div>
      
      {/* Footer/Trend text placeholder */}
      <Skeleton className="mt-4 h-3.5 w-40" />
    </div>
  );
};

export default AdminStatsSkeleton;
