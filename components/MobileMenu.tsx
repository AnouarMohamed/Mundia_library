"use client";

import { useEffect, useRef, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import {
  BookCopy,
  BookOpen,
  BookmarkCheck,
  CalendarClock,
  GraduationCap,
  LayoutDashboard,
  LibraryBig,
  LogOut,
  Menu,
  UserCheck,
  UserRound,
  UsersRound,
  Workflow,
  X,
  type LucideIcon,
} from "lucide-react";
import { signOut } from "next-auth/react";
import { usePathname } from "next/navigation";
import { showToast } from "@/lib/toast";
import { cn } from "@/lib/utils";

interface MobileMenuProps {
  fullName: string;
  email: string;
  universityId?: number;
  isAdmin: boolean;
}

interface NavigationItem {
  href: string;
  label: string;
  icon: LucideIcon;
}

const libraryItems: NavigationItem[] = [
  { href: "/library", label: "Library", icon: LibraryBig },
  { href: "/all-books", label: "Catalog", icon: BookOpen },
  { href: "/resources", label: "Open learning", icon: GraduationCap },
  { href: "/my-profile", label: "My account", icon: UserRound },
];

const adminItems: NavigationItem[] = [
  { href: "/admin", label: "Dashboard", icon: LayoutDashboard },
  { href: "/admin/automation", label: "Automation", icon: Workflow },
  { href: "/admin/users", label: "Users", icon: UsersRound },
  { href: "/admin/books", label: "Catalog management", icon: BookCopy },
  { href: "/admin/learning-resources", label: "Learning resources", icon: GraduationCap },
  { href: "/admin/book-requests", label: "Borrow requests", icon: BookmarkCheck },
  { href: "/admin/renewal-requests", label: "Renewal requests", icon: CalendarClock },
  { href: "/admin/account-requests", label: "Account requests", icon: UserCheck },
];

const MobileMenu = ({
  fullName,
  email,
  universityId,
  isAdmin,
}: MobileMenuProps) => {
  const pathname = usePathname();
  const [isOpen, setIsOpen] = useState(false);
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [isHydrated, setIsHydrated] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDialogElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  const closeMenu = () => {
    setIsOpen(false);
    requestAnimationFrame(() => triggerRef.current?.focus());
  };

  useEffect(() => {
    setIsHydrated(true);
  }, []);

  useEffect(() => {
    const desktopViewport = window.matchMedia("(min-width: 768px)");
    const closeAtDesktop = (event: MediaQueryListEvent | MediaQueryList) => {
      if (event.matches) setIsOpen(false);
    };

    closeAtDesktop(desktopViewport);
    desktopViewport.addEventListener("change", closeAtDesktop);
    return () => desktopViewport.removeEventListener("change", closeAtDesktop);
  }, []);

  useEffect(() => {
    const dialog = panelRef.current;
    if (!dialog) return;

    if (!isOpen) {
      if (dialog.open) dialog.close();
      return;
    }

    if (!dialog.open) dialog.showModal();

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    closeButtonRef.current?.focus();
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [isOpen]);

  const handleLogout = async () => {
    if (isLoggingOut) return;

    try {
      setIsLoggingOut(true);
      document.cookie = `logout-in-progress=true; path=/; max-age=10; SameSite=Lax${
        window.location.protocol === "https:" ? "; Secure" : ""
      }`;
      await signOut({ redirect: true, callbackUrl: "/sign-in" });
    } catch (error) {
      console.error("Logout error:", error);
      setIsLoggingOut(false);
      showToast.error(
        "Sign out failed",
        "Your session is still active. Please try again.",
      );
    }
  };

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setIsOpen(true)}
        disabled={!isHydrated}
        className="flex size-11 items-center justify-center rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-paper)] text-[var(--mundia-ink)] transition-colors hover:border-[var(--mundia-navy)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)] disabled:opacity-60 md:hidden"
        aria-label="Open navigation"
        aria-expanded={isOpen}
        aria-controls="mobile-account-menu"
      >
        <Menu className="size-5" aria-hidden="true" />
      </button>

      <dialog
        ref={panelRef}
        id="mobile-account-menu"
        aria-labelledby="mobile-account-menu-title"
        onCancel={(event) => {
          event.preventDefault();
          closeMenu();
        }}
        className="mobile-account-dialog fixed inset-0 z-50 m-0 h-dvh w-screen max-w-none border-0 bg-transparent p-0 md:hidden"
      >
        <button
          type="button"
          className="fixed inset-0 cursor-default"
          onClick={closeMenu}
          aria-hidden="true"
          tabIndex={-1}
        />
        <aside className="mobile-navigation-panel relative z-10 flex h-full w-[min(86vw,20rem)] flex-col overflow-hidden border-r border-[var(--mundia-line)] bg-[var(--surface-card-strong)] pb-[env(safe-area-inset-bottom)] pt-[env(safe-area-inset-top)]">
          <div className="flex min-h-16 shrink-0 items-center justify-between border-b border-[var(--mundia-line)] px-4">
            <h2 id="mobile-account-menu-title" className="sr-only">Navigation</h2>
            <Image
              src="/images/mundiapolis-logo-transparent.png"
              alt="Mundiapolis Library"
              width={145}
              height={45}
              className="h-auto w-[132px]"
              priority
            />
            <button
              ref={closeButtonRef}
              type="button"
              onClick={closeMenu}
              className="flex size-11 items-center justify-center rounded-lg text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)]"
              aria-label="Close navigation"
            >
              <X className="size-5" aria-hidden="true" />
            </button>
          </div>

          <nav className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 py-4" aria-label="Primary navigation">
            <NavigationList items={libraryItems} pathname={pathname} onNavigate={closeMenu} />
            {isAdmin && (
              <div className="mt-5 border-t border-[var(--mundia-line)] pt-4">
                <p className="px-3 pb-2 text-xs font-medium text-[var(--mundia-muted)]">
                  Administration
                </p>
                <NavigationList items={adminItems} pathname={pathname} onNavigate={closeMenu} />
              </div>
            )}
          </nav>

          <div className="shrink-0 border-t border-[var(--mundia-line)] p-3">
            <div className="mb-2 flex min-w-0 items-center gap-3 px-2 py-2">
              <span className="flex size-9 shrink-0 items-center justify-center bg-[var(--mundia-panel)] text-sm font-semibold text-[var(--mundia-navy)]">
                {fullName.charAt(0).toUpperCase()}
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-semibold text-[var(--mundia-ink)]">{fullName}</p>
                <p className="truncate text-xs text-[var(--mundia-muted)]">
                  {typeof universityId === "number" ? `ID ${universityId} · ` : ""}{email}
                </p>
              </div>
            </div>
            <button
              type="button"
              onClick={handleLogout}
              disabled={isLoggingOut}
              className="flex min-h-11 w-full items-center gap-3 border border-transparent px-3 text-sm font-medium text-[var(--mundia-ink)] transition-colors hover:border-[var(--mundia-line)] hover:bg-[var(--mundia-panel)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[var(--mundia-navy)] disabled:opacity-50"
            >
              <LogOut className="size-4" aria-hidden="true" />
              {isLoggingOut ? "Signing out…" : "Sign out"}
            </button>
          </div>
        </aside>
      </dialog>
    </>
  );
};

const NavigationList = ({
  items,
  pathname,
  onNavigate,
}: {
  items: NavigationItem[];
  pathname: string;
  onNavigate: () => void;
}) => (
  <ul className="flex flex-col gap-1">
    {items.map(({ href, label, icon: Icon }) => {
      const isCurrent =
        pathname === href ||
        (href === "/library" && pathname.startsWith("/library/")) ||
        (href === "/all-books" && pathname.startsWith("/books/")) ||
        (href !== "/admin" && pathname.startsWith(`${href}/`));
      return (
        <li key={href}>
          <Link
            href={href}
            onClick={onNavigate}
            aria-current={isCurrent ? "page" : undefined}
            className={cn(
              "flex min-h-11 items-center gap-3 border border-transparent px-3 text-sm font-medium text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] hover:text-[var(--mundia-navy)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[var(--mundia-navy)]",
              isCurrent && "border-[var(--mundia-line)] bg-[var(--mundia-panel)] font-semibold text-[var(--mundia-navy)]",
            )}
          >
            <Icon className="size-[18px] shrink-0" strokeWidth={1.8} aria-hidden="true" />
            <span>{label}</span>
          </Link>
        </li>
      );
    })}
  </ul>
);

export default MobileMenu;
