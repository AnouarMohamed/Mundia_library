import { Session } from "next-auth";

const Header = ({ session }: { session: Session }) => {
  return (
    <header className="admin-header">
      <div className="space-y-1">
        <h2 className="font-serif text-2xl font-normal tracking-tight text-[var(--mundia-ink)] sm:text-3xl">
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
