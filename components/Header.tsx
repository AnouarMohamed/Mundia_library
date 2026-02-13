import Link from "next/link";
import { Session } from "next-auth";
import AdminDropdown from "@/components/AdminDropdown";
import ProfileDropdown from "@/components/ProfileDropdown";
import MobileMenu from "@/components/MobileMenu";

interface HeaderProps {
  session: Session;
}

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
      <div className="rounded-2xl border border-white/10 bg-[rgba(8,14,22,0.72)] px-4 py-3 shadow-xl backdrop-blur-xl sm:px-6 sm:py-4">
        <div className="flex items-center justify-between gap-3">
          <Link href="/" className="group inline-flex min-w-0 items-center gap-2 sm:gap-3">
            <img src="/icons/logo.svg" alt="logo" width={40} height={40} className="size-8 shrink-0 sm:size-10" />
            <div className="min-w-0">
              <p className="truncate font-bebas-neue text-2xl leading-none tracking-[0.1em] text-white sm:text-3xl">
                Mundiapolis
              </p>
              <p className="truncate text-[10px] uppercase tracking-[0.28em] text-light-200/80 sm:text-xs">
                Library Hub
              </p>
            </div>
          </Link>

          {/* Desktop Navigation */}
          <ul className="hidden items-center gap-1 md:flex lg:gap-2">
            {navLinks.map((link) => (
              <li key={link.href}>
                <Link
                  href={link.href}
                  className="rounded-full px-3 py-2 text-sm font-medium text-light-100/85 transition hover:bg-white/10 hover:text-white"
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
