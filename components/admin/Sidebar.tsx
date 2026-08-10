/**
 * Admin Sidebar Component
 *
 * The primary vertical navigation for the administrator dashboard.
 * Includes logo branding, dynamic navigational links, and a user profile footer.
 *
 * @author Mundia Library Team
 * @version 1.0.0
 */

import Link from "next/link";
import type { Session } from "next-auth";
import AdminNavLinks from "@/components/admin/AdminNavLinks";
import AdminUserCard from "@/components/admin/AdminUserCard";

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
 * Server-rendered navigation shell. AdminNavLinks owns client-side active-route
 * highlighting so the rest of the sidebar does not need to hydrate.
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
      <AdminUserCard session={session} variant="sidebar" />
    </aside>
  );
};

export default Sidebar;
