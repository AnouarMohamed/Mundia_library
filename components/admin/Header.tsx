/**
 * Admin Header Component
 *
 * The specific navigational header for the administrator dashboard.
 * Displays the current session context and workspace identity.
 *
 * @author Mundia Library Team
 * @version 1.0.0
 */

import { Session } from "next-auth";
import MobileNavigation from "@/components/admin/MobileNavigation";

/**
 * Props for Admin Header
 */
interface AdminHeaderProps {
  /**
   * The current authenticated session object from NextAuth
   */
  session: Session;
}

/**
 * Admin Header
 *
 * Provides a specialized context for the administrative dashboard,
 * signaling to the user that they are in a high-privilege workspace.
 *
 * @param {AdminHeaderProps} props - Component properties
 * @returns {JSX.Element} The rendered admin header
 */
const Header = ({ session }: AdminHeaderProps) => {
  return (
    <header className="admin-header">
      {/* Title and User Context */}
      <div className="flex min-w-0 flex-1 items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <h2 className="font-serif text-2xl font-normal tracking-tight text-[var(--mundia-ink)] sm:text-3xl">
            Circulation desk
          </h2>
          <p className="truncate text-sm text-slate-600 sm:text-base">
            Signed in as {session.user?.name || "administrator"}
          </p>
        </div>

        <MobileNavigation session={session} />
      </div>

      {/* Workspace Indicator Badge */}
      <div className="hidden shrink-0 rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-panel)] px-3 py-2 text-xs font-medium text-[var(--mundia-ink)]/70 md:block">
        Admin workspace
      </div>
    </header>
  );
};

export default Header;
