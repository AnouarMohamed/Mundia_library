"use client";

import Link from "next/link";
import { BookOpen, LibraryBig, UserRound } from "lucide-react";
import { usePathname } from "next/navigation";

const items = [
  { href: "/library", label: "Library", icon: LibraryBig },
  { href: "/all-books", label: "Catalog", icon: BookOpen },
  { href: "/my-profile", label: "My account", icon: UserRound },
];

const MobileBottomNav = () => {
  const pathname = usePathname();

  return (
    <nav
      className="fixed inset-x-0 bottom-0 z-40 border-t border-[var(--mundia-line)] bg-[var(--surface-card-strong)] pb-[env(safe-area-inset-bottom)] md:hidden"
      aria-label="Primary navigation"
    >
      <ul className="mx-auto grid max-w-lg grid-cols-3">
        {items.map(({ href, label, icon: Icon }) => {
          const isCurrent =
            pathname === href ||
            (href === "/all-books" && pathname.startsWith("/books/"));

          return (
            <li key={href}>
              <Link
                href={href}
                aria-current={isCurrent ? "page" : undefined}
                className="flex min-h-16 flex-col items-center justify-center gap-1 border-t-2 border-transparent px-2 text-[11px] font-semibold text-[var(--mundia-muted)] transition-colors hover:bg-[var(--mundia-panel)] hover:text-[var(--mundia-navy)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[var(--mundia-navy)] aria-[current=page]:border-[var(--mundia-navy)] aria-[current=page]:bg-[var(--mundia-panel)] aria-[current=page]:text-[var(--mundia-navy)]"
              >
                <Icon className="size-5" strokeWidth={1.8} aria-hidden="true" />
                {label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
};

export default MobileBottomNav;
