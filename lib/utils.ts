/**
 * General Utilities Module
 *
 * Contains shared helper functions used across the application for string manipulation, 
 * UI styling, and data formatting.
 */

import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Merges Tailwind CSS classes with proper conflict resolution.
 * Combines 'clsx' for conditional classes and 'tailwind-merge' to ensure 
 * the last class wins in case of conflicts (e.g., 'px-2 px-4' becomes 'px-4').
 * 
 * @param inputs - Array of class values (strings, objects, arrays, etc.)
 * @returns A single merged className string.
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Extracts the first letters of the first two words in a name to create initials.
 * 
 * @param name - The full name string (e.g., "John Doe").
 * @returns A 2-character uppercase string (e.g., "JD") or empty string if input is invalid.
 * 
 * @example
 * ```typescript
 * getInitials("Jane Smith") // "JS"
 * getInitials("Prince")     // "P"
 * ```
 */
export const getInitials = (name: string): string => {
  const parts = name.trim().split(/\s+/).filter(Boolean);

  if (parts.length === 0) {
    return "";
  }

  return parts
    .map((part) => part[0])
    .join("")
    .toUpperCase()
    .slice(0, 2);
};
