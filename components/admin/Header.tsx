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
      <div className="space-y-1">
        <h2 className="font-serif text-2xl font-normal tracking-tight text-[var(--mundia-ink)] sm:text-3xl">
          Circulation desk
        </h2>
        <p className="text-sm text-slate-600 sm:text-base">
          Signed in as {session?.user?.name || "administrator"}
        </p>
      </div>

      {/* Workspace Indicator Badge */}
      <div className="rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-panel)] px-3 py-2 text-xs font-medium text-[var(--mundia-ink)]/70">
        Admin workspace
      </div>
    </header>
  );
};

export default Header;
