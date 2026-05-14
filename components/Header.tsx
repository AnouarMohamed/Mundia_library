import Link from "next/link";
import { Session } from "next-auth";
import AdminDropdown from "@/components/AdminDropdown";
import ProfileDropdown from "@/components/ProfileDropdown";
import MobileMenu from "@/components/MobileMenu";

interface HeaderProps {
  session: Session;
}

/**
 * Top navigation with user context and admin shortcuts.
 */
const Header = async ({ session }: HeaderProps) => {
  const isAdmin = (session.user as { role?: string }).role === "ADMIN";
  const fullName = session.user?.name || "User";
  const email = session.user?.email || "";
  const universityId = (session.user as { universityId?: number }).universityId;
  const universityCard = (session.user as { universityCard?: string })
    .universityCard;

  const navLinks = [
    { href: "/library", label: "Library" },
    { href: "/all-books", label: "All Books" },
    { href: "/my-profile", label: "My Profile" },
  ];

  return (
    <header className="sticky top-3 z-40 mb-6 sm:mb-8">
      <div
        className="rounded-[1.35rem] border border-white/10 px-4 py-3 sm:px-6 sm:py-4"
        style={{
          background: "oklch(14% 0.025 225 / 0.94)",
          boxShadow: "0 20px 60px oklch(10% 0.02 225 / 0.34)",
        }}
      >
        <div className="flex items-center justify-between gap-3">
          <Link
            href="/"
            className="group inline-flex min-w-0 items-center gap-2 sm:gap-3"
          >
            <span className="flex size-10 shrink-0 items-center justify-center rounded-xl border border-white/15 bg-white/10 shadow-inner sm:size-12">
              <img
                src="/icons/logo.svg"
                alt="logo"
                width={40}
                height={40}
                className="size-7 sm:size-8"
              />
            </span>
            <div className="min-w-0">
              <p className="truncate font-bebas-neue text-2xl leading-none tracking-[0.12em] text-light-100 sm:text-3xl">
                Mundiapolis
              </p>
              <p className="truncate text-[10px] uppercase tracking-[0.28em] text-[var(--mundia-gold)] sm:text-xs">
                University Library
              </p>
            </div>
          </Link>

          {/* Desktop Navigation */}
          <ul className="hidden items-center gap-1 md:flex lg:gap-2">
            {navLinks.map((link) => (
              <li key={link.href}>
                <Link
                  href={link.href}
                  className="rounded-full px-3 py-2 text-sm font-medium text-light-100/85 transition hover:bg-white/10 hover:text-light-100 lg:px-4"
                >
                  {link.label}
                </Link>
              </li>
            ))}

            {isAdmin && (
              <li className="pl-1">
                <AdminDropdown />
              </li>
            )}

            {session.user && (
              <li className="pl-1">
                <ProfileDropdown
                  fullName={fullName}
                  email={email}
                  universityId={universityId}
                  universityCard={universityCard}
                  isAdmin={isAdmin}
                />
              </li>
            )}
          </ul>

          {/* Mobile Menu */}
          {session.user && (
            <div className="md:hidden">
              <MobileMenu
                fullName={fullName}
                email={email}
                universityId={universityId}
                universityCard={universityCard}
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
