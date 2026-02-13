import { Session } from "next-auth";

const Header = ({ session }: { session: Session }) => {
  return (
    <header className="admin-header rounded-2xl border border-slate-200/80 bg-white/80 px-4 py-4 shadow-sm backdrop-blur-sm sm:px-6 sm:py-5">
      <div className="space-y-1 sm:space-y-2">
        <h2 className="text-xl font-semibold text-slate-900 sm:text-2xl">
          {session?.user?.name}
        </h2>
        <p className="text-sm text-slate-600 sm:text-base">
          Monitor all of your users and books here
        </p>
      </div>

      {/*<p>Search</p>*/}
    </header>
  );
};
export default Header;
