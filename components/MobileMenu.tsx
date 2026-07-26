/**
 * MobileMenu Component
 *
 * A responsive drawer-based navigation menu for small screens.
 * Includes user profile summary, navigational links, and admin shortcuts.
 *
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import React, { useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { Menu, X } from "lucide-react";
import { signOut } from "next-auth/react";
import { useQueryClient } from "@tanstack/react-query";
import { Image as IKImage } from "@imagekit/next";
import config from "@/lib/config";
import { showToast } from "@/lib/toast";

/**
 * Props for MobileMenu
 */
interface MobileMenuProps {
  /**
   * User's full display name
   */
  fullName: string;
  /**
   * User's primary email address
   */
  email: string;
  /**
   * Numeric university identifier (optional)
   */
  universityId?: number;
  /**
   * URL or path to the user's university ID card image
   */
  universityCard?: string;
  /**
   * True if the user has administrative privileges
   */
  isAdmin: boolean;
}

/**
 * MobileMenu
 *
 * Client component for mobile navigation menu (phone and sm screens).
 * Displays user info and navigation links in a drawer-style menu.
 */
const MobileMenu: React.FC<MobileMenuProps> = ({
  fullName,
  email,
  universityId,
  universityCard,
  isAdmin,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const queryClient = useQueryClient();
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  /**
   * Smooth logout flow with cache clearing.
   */
  const handleLogout = async () => {
    if (isLoggingOut) return;

    try {
      setIsLoggingOut(true);
      showToast.auth.logoutSuccess();

      // Temporary cookie to prevent flicker during redirect
      document.cookie =
        "logout-in-progress=true; path=/; max-age=10; SameSite=Lax";

      await signOut({
        redirect: true,
        callbackUrl: "/sign-in",
      });

      // Clear the Query Cache after the page has navigated away
      setTimeout(() => {
        queryClient.clear();
      }, 500);
    } catch (error) {
      console.error("Logout error:", error);
      setIsLoggingOut(false);
      showToast.error(
        "Logout Failed",
        "There was an error logging out. Please try again.",
      );
    }
  };

  /**
   * Closes the drawer menu.
   */
  const closeMenu = (e?: React.MouseEvent) => {
    e?.stopPropagation();
    setIsOpen(false);
  };

  // Consistent class for navigation links
  const navLinkClass =
    "block rounded-lg p-2.5 text-sm text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] active:bg-[var(--mundia-panel)] sm:p-3 sm:text-base";

  return (
    <>
      {/* Hamburger Menu Trigger Button */}
      <button
        onClick={(e) => {
          e.stopPropagation();
          setIsOpen(!isOpen);
        }}
        className="rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-paper)] p-2 text-[var(--mundia-ink)] transition hover:border-[var(--mundia-navy)] focus:outline-none md:hidden"
        aria-label="Toggle menu"
      >
        {isOpen ? (
          <X className="size-5 sm:size-6" />
        ) : (
          <Menu className="size-5 sm:size-6" />
        )}
      </button>

      {/* Backdrop Overlay - click outside to close */}
      {isOpen && (
        <div
          className="fixed inset-0 z-40 bg-slate-950/25 md:hidden"
          onClick={closeMenu}
          aria-hidden="true"
        />
      )}

      {/* Drawer Container */}
      <div
        className={`fixed right-0 top-0 z-50 h-full w-4/5 border-l border-[var(--mundia-line)] bg-[var(--surface-card-strong)] transition-transform duration-300 ease-in-out md:hidden ${
          isOpen ? "translate-x-0" : "translate-x-full"
        }`}
      >
        <div className="flex h-full flex-col overflow-y-auto">
          {/* Header section with Profile Image and Close button */}
          <div className="flex items-center justify-between border-b border-[var(--mundia-line)] p-3 sm:p-4">
            <div className="relative size-7 overflow-hidden rounded-full border border-[var(--mundia-line)] sm:size-8">
              {universityCard ? (
                universityCard.startsWith("http") ||
                universityCard.startsWith("data:") ? (
                  <Image
                    src={universityCard}
                    alt="Profile"
                    fill
                    className="object-cover"
                    sizes="(max-width: 640px) 28px, 32px"
                  />
                ) : config.env.imagekit.urlEndpoint ? (
                  <IKImage
                    src={
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
            </div>

            <h2 className="text-base font-semibold text-[var(--mundia-ink)] sm:text-lg">
              Menu
            </h2>

            <div className="flex items-center gap-2 sm:gap-3">
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  closeMenu();
                }}
                className="flex min-h-11 min-w-11 items-center justify-center rounded-lg text-[var(--mundia-ink)] transition hover:bg-[var(--mundia-panel)] focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                aria-label="Close menu"
              >
                <X className="size-5 sm:size-6" />
              </button>
            </div>
          </div>

          {/* User Details Summary */}
          <div className="border-b border-[var(--mundia-line)] p-3 sm:p-4">
            <p className="text-xs font-semibold text-[var(--mundia-ink)] sm:text-sm">
              {fullName}
            </p>
            <p className="mt-1 text-[10px] text-slate-600 sm:text-xs">
              {email}
            </p>
            {typeof universityId === "number" && (
              <p className="mt-1 text-[10px] text-slate-600 sm:text-xs">
                University ID: {universityId}
              </p>
            )}
          </div>

          {/* Navigation Links Group */}
          <div className="flex-1 space-y-1 p-3 sm:p-4">
            <Link href="/library" onClick={closeMenu} className={navLinkClass}>
              Library
            </Link>
            <Link
              href="/all-books"
              onClick={closeMenu}
              className={navLinkClass}
            >
              All Books
            </Link>
            <Link
              href="/my-profile"
              onClick={closeMenu}
              className={navLinkClass}
            >
              My Profile
            </Link>

            {/* Admin-specific links section */}
            {isAdmin && (
              <>
                <div className="my-2 border-t border-[var(--mundia-line)]"></div>
                <p className="px-2.5 py-1.5 text-[10px] font-medium text-slate-500 sm:px-3 sm:py-2 sm:text-xs">
                  Admin Panel
                </p>
                <Link
                  href="/admin"
                  onClick={closeMenu}
                  className={navLinkClass}
                >
                  Dashboard Overview
                </Link>
                <Link
                  href="/admin/automation"
                  onClick={closeMenu}
                  className={navLinkClass}
                >
                  Automation
                </Link>
                <Link
                  href="/admin/users"
                  onClick={closeMenu}
                  className={navLinkClass}
                >
                  Users
                </Link>
                <Link
                  href="/admin/books"
                  onClick={closeMenu}
                  className={navLinkClass}
                >
                  Books
                </Link>
                <Link
                  href="/admin/book-requests"
                  onClick={closeMenu}
                  className={navLinkClass}
                >
                  Borrow Requests
                </Link>
                <Link
                  href="/admin/account-requests"
                  onClick={closeMenu}
                  className={navLinkClass}
                >
                  Account Requests
                </Link>
              </>
            )}

            {/* CTA for non-admins */}
            {!isAdmin && (
              <Link
                href="/make-admin"
                onClick={closeMenu}
                className={navLinkClass}
              >
                Request Admin Access
              </Link>
            )}
          </div>

          {/* Logout Action at the bottom */}
          <div className="border-t border-[var(--mundia-line)] p-3 sm:p-4">
            <button
              onClick={handleLogout}
              disabled={isLoggingOut}
              className="w-full rounded-lg border border-red-300/30 bg-red-500/85 p-2.5 text-sm font-medium text-white transition hover:bg-red-500 disabled:opacity-50 sm:p-3 sm:text-base"
            >
              {isLoggingOut ? "Logging out..." : "Logout"}
            </button>
          </div>
        </div>
      </div>
    </>
  );
};

export default MobileMenu;
