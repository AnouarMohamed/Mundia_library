"use client";

/**
 * ProfileDropdown Component
 *
 * Client component that displays user profile image with dropdown menu.
 * Shows user info (Full name, Email, University ID) and actions (Become Admin, Logout).
 */

import React, { useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { signOut } from "next-auth/react";
import { useQueryClient } from "@tanstack/react-query";
import { IKImage } from "imagekitio-next";
import config from "@/lib/config";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { showToast } from "@/lib/toast";

interface ProfileDropdownProps {
  fullName: string;
  email: string;
  universityId?: number;
  universityCard?: string;
  isAdmin: boolean;
}

/**
 * Profile menu with account actions.
 */
const ProfileDropdown: React.FC<ProfileDropdownProps> = ({
  fullName,
  email,
  universityId,
  universityCard,
  isAdmin,
}) => {
  const queryClient = useQueryClient();
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  const handleLogout = async () => {
    // Prevent multiple clicks
    if (isLoggingOut) return;

    try {
      setIsLoggingOut(true);

      // Show toast notification first
      showToast.auth.logoutSuccess();

      // CRITICAL: Set logout flag to prevent UI updates during logout
      // This prevents flickering/blinking of images and components during logout transition
      document.cookie =
        "logout-in-progress=true; path=/; max-age=10; SameSite=Lax";

      // CRITICAL: Use NextAuth's standard built-in redirect
      // This is the recommended approach - NextAuth handles:
      // 1. Session clearing (CSRF token validation)
      // 2. Cookie removal
      // 3. Navigation to callbackUrl
      // No need for manual navigation or cookie workarounds
      await signOut({
        redirect: true, // Standard NextAuth redirect (handles everything)
        callbackUrl: "/sign-in", // Where to redirect after logout
      });

      // CRITICAL: Clear cache AFTER redirect completes (longer delay)
      // This ensures smooth transition - UI stays intact during entire logout process
      // The redirect happens immediately, but we wait longer to ensure page has navigated
      // before clearing cache. This prevents images from disappearing during logout.
      setTimeout(() => {
        queryClient.clear();
      }, 500); // Longer delay to ensure redirect has completed and page has navigated
    } catch (error) {
      console.error("Logout error:", error);
      setIsLoggingOut(false);
      showToast.error(
        "Logout Failed",
        "There was an error logging out. Please try again.",
      );
    }
  };

  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <button className="relative size-8 overflow-hidden rounded-full border border-[var(--mundia-line)] bg-[var(--mundia-paper)] transition-all hover:border-[var(--mundia-navy)] focus:outline-none focus:ring-2 focus:ring-primary/50 focus:ring-offset-2 focus:ring-offset-transparent sm:size-10">
          {universityCard ? (
            universityCard.startsWith("http") ||
            universityCard.startsWith("data:") ? (
              <Image
                src={universityCard}
                alt="Profile"
                fill
                className="object-cover"
                sizes="(max-width: 640px) 32px, 40px"
              />
            ) : config.env.imagekit.urlEndpoint ? (
              <IKImage
                path={
                  universityCard.startsWith("/")
                    ? universityCard.slice(1)
                    : universityCard
                }
                urlEndpoint={config.env.imagekit.urlEndpoint}
                alt="Profile"
                fill
                className="rounded-full object-cover"
              />
            ) : (
              <div className="flex size-full items-center justify-center bg-[var(--mundia-panel)] text-[var(--mundia-ink)]">
                <span className="text-[10px] font-semibold sm:text-xs">
                  {fullName.charAt(0).toUpperCase()}
                </span>
              </div>
            )
          ) : (
            <div className="flex size-full items-center justify-center bg-[var(--mundia-panel)] text-[var(--mundia-ink)]">
              <span className="text-[10px] font-semibold sm:text-xs">
                {fullName.charAt(0).toUpperCase()}
              </span>
            </div>
          )}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        className="w-56 rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-card-strong)] p-1 text-[var(--mundia-ink)] sm:w-64"
      >
        <DropdownMenuLabel className="rounded-lg bg-[var(--surface-0)] px-2.5 py-1.5 sm:px-3 sm:py-2">
          <div className="space-y-0.5 sm:space-y-1">
            <p className="text-xs font-semibold text-[var(--mundia-ink)] sm:text-sm">
              {fullName}
            </p>
            <p className="text-[10px] text-slate-600 sm:text-xs">{email}</p>
            {typeof universityId === "number" && (
              <p className="text-[10px] text-slate-600 sm:text-xs">
                University ID: {universityId}
              </p>
            )}
          </div>
        </DropdownMenuLabel>
        <DropdownMenuSeparator className="my-1 bg-[var(--mundia-line)]" />
        {!isAdmin && (
          <DropdownMenuItem
            asChild
            className="cursor-pointer rounded-lg px-0 py-2 text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] focus:bg-[var(--mundia-panel)] sm:py-3 [&>a]:block [&>a]:w-full"
          >
            <Link
              href="/make-admin"
              className="px-2.5 text-xs sm:px-3 sm:text-sm"
            >
              Become Admin
            </Link>
          </DropdownMenuItem>
        )}
        <DropdownMenuItem
          onClick={handleLogout}
          disabled={isLoggingOut}
          className="cursor-pointer rounded-lg px-0 py-2 text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] focus:bg-[var(--mundia-panel)] disabled:opacity-50 sm:py-3"
        >
          <span className="block w-full px-2.5 py-0 text-left text-xs sm:px-3 sm:text-sm">
            {isLoggingOut ? "Logging out..." : "Logout"}
          </span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ProfileDropdown;
