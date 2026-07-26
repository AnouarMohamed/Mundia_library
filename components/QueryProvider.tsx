/**
 * QueryProvider Component
 * 
 * Configures and provides the TanStack React Query client to the application.
 * Optimized for a library system where data is relatively static but requires 
 * precise invalidation after mutations.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";
import { useState } from "react";

/**
 * QueryProvider
 * 
 * Sets up the global React Query client with tuned defaults.
 * 
 * @param {Object} props - Component properties
 * @param {React.ReactNode} props.children - Child components that will have access to the query client
 * @returns {JSX.Element} The provider component wrapping its children
 */
export default function QueryProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  // Initialize the QueryClient within state to ensure it's only created once per application lifecycle
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            /**
             * CRITICAL STRATEGY: Infinite cache.
             * Data is cached forever (staleTime: Infinity) until manually invalidated.
             * This prevents redundant API calls and ensures optimal performance in a 
             * catalog-heavy application.
             */
            staleTime: Infinity,

            /**
             * GC STRATEGY:
             * Keep unused data in cache for 30 minutes after component unmounts.
             * This allows faster subsequent loads while managing memory efficiently.
             */
            gcTime: 30 * 60 * 1000,

            /**
             * RETRY STRATEGY:
             * Retry failed requests once. Fast failure leads to a better UX as 
             * users see error states more quickly.
             */
            retry: 1,

            /**
             * REFETCH STRATEGY:
             * With staleTime: Infinity, this only triggers after explicit invalidation 
             * (e.g., after borrowing a book).
             */
            refetchOnMount: true,

            // Disable automatic background refetches to save bandwidth and prevent flicker
            refetchOnWindowFocus: false,
            refetchOnReconnect: false,

            // Smooth UI transition: use cached data as placeholder during refetches
            placeholderData: (previousData: unknown) => previousData,

            // Ensure we only attempt fetches when the device is online
            networkMode: "online",
          },
          mutations: {
            // User actions (mutations) should not be automatically retried
            retry: 0,
          },
        },
      })
  );

  return (
    <QueryClientProvider client={queryClient}>
      {children}
      {/* DevTools: Enabled only in development environment for debugging */}
      {process.env.NODE_ENV === "development" && (
        <ReactQueryDevtools initialIsOpen={false} />
      )}
    </QueryClientProvider>
  );
}
