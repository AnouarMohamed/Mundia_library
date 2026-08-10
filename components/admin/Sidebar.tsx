/**
 * Admin Sidebar Component
 *
 * The primary vertical navigation for the administrator dashboard.
 * Includes logo branding, dynamic navigational links, and a user profile footer.
 *
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import Link from "next/link";
import { getInitials } from "@/lib/utils";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Session } from "next-auth";
import AdminNavLinks from "@/components/admin/AdminNavLinks";

/**
 * Props for Admin Sidebar
 */
interface AdminSidebarProps {
  /**
   * The current authenticated session object from NextAuth
   */
  session: Session;
}

/**
 * Sidebar
 *
 * Client-side component that manages the administrative navigation state.
 * Automatically highlights the active route based on the current pathname.
 *
 * @param {AdminSidebarProps} props - Component properties
 * @returns {JSX.Element} The rendered admin sidebar
 */
const Sidebar = ({ session }: AdminSidebarProps) => {
  return (
    <aside className="admin-sidebar" aria-label="Admin workspace sidebar">
      <div className="flex min-h-0 flex-1 flex-col">
        {/* Responsive Logo Section */}
        <Link href="/admin" className="logo" aria-label="Admin dashboard home">
          <img
            src="/images/mundiapolis-logo-transparent.png"
            alt="Mundiapolis Library"
            height={50}
            width={161}
            className="h-auto w-[161px] object-contain"
          />
          <h1 className="sr-only">Mundiapolis Library</h1>
        </Link>

        {/* Primary Navigation Links */}
        <nav
          className="mt-4 min-h-0 flex-1 overflow-y-auto overscroll-contain pr-1"
          aria-label="Admin navigation"
        >
          <AdminNavLinks />
        </nav>
      </div>

      {/* User Profile Footer Section */}
      <div className="user">
        <Avatar className="size-10">
          <AvatarFallback className="bg-amber-100 text-sm text-[var(--mundia-ink)]">
            {getInitials(session.user?.name || "Administrator")}
          </AvatarFallback>
        </Avatar>

        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-slate-900">
            {session.user?.name || "Administrator"}
          </p>
          <p className="truncate text-xs text-slate-600">
            {session.user?.email}
          </p>
        </div>
      </div>
    </aside>
  );
};

export default Sidebar;
