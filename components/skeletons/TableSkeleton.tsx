/**
 * TableSkeleton Component
 * 
 * A configurable loading placeholder for tabular data.
 * Mimics the structure of standard HTML tables with headers and rows to minimize layout shift.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

import React from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/**
 * Props for TableSkeleton
 */
interface TableSkeletonProps {
  /**
   * Total number of columns to render
   * @default 7
   */
  columns?: number;
  /**
   * Total number of data rows to render
   * @default 5
   */
  rows?: number;
  /**
   * Optional array of Tailwind width classes (e.g., ["w-20", "w-40"]) for specific column sizing.
   */
  columnWidths?: string[];
  /**
   * Whether to include the table header (thead) row.
   * @default true
   */
  showHeader?: boolean;
  /**
   * Additional CSS classes for the table element.
   */
  className?: string;
}

/**
 * TableSkeleton
 * 
 * Renders a ghost table structure using shadcn/ui Skeleton primitives.
 */
const TableSkeleton: React.FC<TableSkeletonProps> = ({
  columns = 7,
  rows = 5,
  columnWidths,
  showHeader = true,
  className,
}) => {
  /**
   * Helper: Determines the width of a specific column based on its index.
   * Uses provided widths or falls back to a sensible default pattern.
   */
  const getColumnWidth = (index: number): string => {
    if (columnWidths && columnWidths[index]) {
      return columnWidths[index];
    }
    
    // Default width progression for standard library tables (Title, Email, ID, etc.)
    const defaultWidths = [
      "w-32", // Col 0
      "w-48", // Col 1
      "w-24", // Col 2
      "w-20", // Col 3
      "w-20", // Col 4
      "w-24", // Col 5
      "w-40", // Col 6 (Actions)
    ];
    return defaultWidths[index] || "w-32";
  };

  return (
    <div className="w-full overflow-hidden">
      <div className="overflow-x-auto">
        <table
          className={cn(
            "w-full border-collapse border border-gray-200",
            className
          )}
        >
          {/* Header Row Placeholder */}
          {showHeader && (
            <thead>
              <tr className="bg-gray-50">
                {Array.from({ length: columns }).map((_, index) => (
                  <th
                    key={index}
                    className="border border-gray-200 px-4 py-2 text-left"
                  >
                    <Skeleton className={cn("h-5", getColumnWidth(index))} />
                  </th>
                ))}
              </tr>
            </thead>
          )}
          
          {/* Body Rows Placeholders */}
          <tbody>
            {Array.from({ length: rows }).map((_, rowIndex) => (
              <tr key={rowIndex} className="hover:bg-gray-50">
                {Array.from({ length: columns }).map((_, colIndex) => (
                  <td
                    key={colIndex}
                    className="border border-gray-200 px-4 py-2"
                  >
                    <Skeleton
                      className={cn(
                        "h-5",
                        getColumnWidth(colIndex),
                        /**
                         * Visual Variety: 
                         * Make action buttons and badges visually distinct in the skeleton.
                         */
                        colIndex === columns - 1 && "h-8 w-24", 
                        (colIndex === columns - 2 ||
                          colIndex === columns - 3) && "h-6 w-20 rounded-full" 
                      )}
                    />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default TableSkeleton;

