import { Session } from "next-auth";

const Header = ({ session }: { session: Session }) => {
  return (
    <header className="admin-header">
      <div className="space-y-1">
        <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-[var(--mundia-teal-strong)]">
          Library operations
        </p>
        <h2 className="text-xl font-semibold tracking-tight text-[var(--mundia-ink)] sm:text-2xl">
          Circulation desk
        </h2>
        <p className="text-sm text-slate-600 sm:text-base">
          Signed in as {session?.user?.name || "administrator"}
        </p>
      </div>

      <div className="rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-panel)] px-3 py-2 text-xs font-medium text-[var(--mundia-ink)]/70">
        Admin workspace
      </div>
    </header>
  );
};
export default Header;
