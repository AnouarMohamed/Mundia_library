/**
 * Header Component
 * 
 * The main navigational header of the application. 
 * Responsively displays navigation links, admin shortcuts, and user profile controls.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

import React from "react";
import Link from "next/link";
import { Session } from "next-auth";
import AdminDropdown from "@/components/AdminDropdown";
import ProfileDropdown from "@/components/ProfileDropdown";
import MobileMenu from "@/components/MobileMenu";

/**
 * Props for Header
 */
interface HeaderProps {
  /**
   * The current authenticated session object from NextAuth
   */
  session: Session;
}

/**
 * Header
 * 
 * Top navigation with user context and admin shortcuts.
 * Automatically adapts its layout for mobile and desktop views.
 * 
 * @param {HeaderProps} props - Component properties
 * @returns {Promise<JSX.Element>} The rendered header component
 */
const Header = async ({ session }: HeaderProps) => {
  // Extract user details and roles from the session
  const isAdmin = (session.user as { role?: string }).role === "ADMIN";
  const fullName = session.user?.name || "User";
  const email = session.user?.email || "";
  const universityId = (session.user as { universityId?: number }).universityId;

  // Define primary navigation links for the application
  const navLinks = [
    { href: "/library", label: "Library" },
    { href: "/all-books", label: "Catalog" },
    { href: "/my-profile", label: "My account" },
  ];

  return (
    <header className="sticky top-0 z-40 -mx-4 mb-6 border-b border-[var(--mundia-line)] bg-[var(--surface-0)] px-4 sm:-mx-7 sm:mb-8 sm:px-7 md:-mx-10 md:px-10 lg:-mx-14 lg:px-14">
      <div className="mx-auto max-w-[1500px] py-3 sm:py-4">
        <div className="flex items-center justify-between gap-3">
          
          {/* Logo Section */}
          <Link
            href="/"
            className="group inline-flex min-h-11 min-w-0 items-center"
          >
            <img
              src="/images/mundiapolis-logo-transparent.png"
              alt="Mundiapolis Library"
              width={161}
              height={50}
              className="h-auto w-[132px] shrink-0 sm:w-[161px]"
            />
          </Link>

          {/* Desktop Navigation - Hidden on mobile */}
          <ul className="hidden items-center gap-1 md:flex lg:gap-2">
            {navLinks.map((link) => (
              <li key={link.href}>
                <Link
                  href={link.href}
                  className="rounded-lg px-3 py-2 text-sm font-medium text-[var(--mundia-ink)]/72 transition hover:bg-[var(--mundia-panel)] hover:text-[var(--mundia-ink)] lg:px-4"
                >
                  {link.label}
                </Link>
              </li>
            ))}

            {/* Admin-only dropdown shortcuts */}
            {isAdmin && (
              <li className="pl-1">
                <AdminDropdown />
              </li>
            )}

            {/* User Profile Dropdown */}
            {session.user && (
              <li className="pl-1">
                <ProfileDropdown
                  fullName={fullName}
                  email={email}
                  universityId={universityId}
                  isAdmin={isAdmin}
                />
              </li>
            )}
          </ul>

          {/* Mobile Menu - Shown only on small screens for authenticated users */}
          {session.user && (
            <div className="md:hidden">
              <MobileMenu
                fullName={fullName}
                email={email}
                universityId={universityId}
                isAdmin={isAdmin}
              />
            </div>
          )}
        </div>
      </div>
    </header>
  );
};

export default Header;
