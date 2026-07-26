/**
 * LogoutButton Component
 * 
 * Provides a specialized logout trigger that handles both authentication state 
 * and client-side cache management.
 * 
 * @author Mundia Library Team
 * @version 1.1.0
 */

"use client";

import React, { useState } from "react";
import { signOut } from "next-auth/react";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { showToast } from "@/lib/toast";

/**
 * LogoutButton
 * 
 * Client component for handling logout with React Query cache clearing.
 * Prevents white screen flash by using optimized logout flow.
 */
const LogoutButton: React.FC = () => {
  const queryClient = useQueryClient();
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  /**
   * Orchestrates the secure and smooth sign-out process.
   */
  const handleLogout = async () => {
    // Safety check to prevent duplicate trigger during transition
    if (isLoggingOut) return;

    try {
      setIsLoggingOut(true);

      // Feedback: Show success toast immediately to acknowledge user intent
      showToast.auth.logoutSuccess();

      /**
       * CRITICAL PERFORMANCE WORKAROUND:
       * Set a temporary cookie that signals the middleware and components 
       * that a logout is in progress. This helps prevent flicker by 
       * suppressing aggressive refetches during the redirect phase.
       */
      document.cookie =
        "logout-in-progress=true; path=/; max-age=10; SameSite=Lax";

      /**
       * Execute standard NextAuth sign-out.
       * - Handles session clearing on server.
       * - Clears CSRF tokens.
       * - Redirects to the specified callbackUrl.
       */
      await signOut({
        redirect: true,
        callbackUrl: "/sign-in",
      });

      /**
       * CACHE CLEANUP:
       * We wait for a short period to ensure the navigation is well underway 
       * before clearing the React Query cache. Clearing it too early can 
       * cause images and data to vanish before the page unmounts.
       */
      setTimeout(() => {
        queryClient.clear();
      }, 500); 
    } catch (error) {
      console.error("Logout error:", error);
      setIsLoggingOut(false);
      showToast.error(
        "Logout Failed",
        "There was an error logging out. Please try again."
      );
    }
  };

  return (
    <Button onClick={handleLogout} type="button" disabled={isLoggingOut} className="text-sm sm:text-base">
      {isLoggingOut ? "Logging out..." : "Logout"}
    </Button>
  );
};

export default LogoutButton;
