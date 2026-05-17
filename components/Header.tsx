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
    <header className="sticky top-0 z-40 -mx-4 mb-6 border-b border-[var(--mundia-line)] bg-[var(--surface-0)] px-4 sm:-mx-7 sm:mb-8 sm:px-7 md:-mx-10 md:px-10 lg:-mx-14 lg:px-14">
      <div className="mx-auto max-w-[1500px] py-3 sm:py-4">
        <div className="flex items-center justify-between gap-3">
          <Link
            href="/"
            className="group inline-flex min-w-0 items-center"
          >
            <img
              src="/images/mundiapolis-logo-transparent.png"
              alt="Mundiapolis Library"
              width={161}
              height={50}
              className="h-auto w-[132px] shrink-0 sm:w-[161px]"
            />
          </Link>

          {/* Desktop Navigation */}
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
