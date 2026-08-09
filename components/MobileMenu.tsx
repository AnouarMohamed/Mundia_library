"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { LogOut, UserRound, X } from "lucide-react";
import { signOut } from "next-auth/react";
import { showToast } from "@/lib/toast";

interface MobileMenuProps {
  fullName: string;
  email: string;
  universityId?: number;
  isAdmin: boolean;
}

const MobileMenu = ({
  fullName,
  email,
  universityId,
  isAdmin,
}: MobileMenuProps) => {
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

  const navLinkClass =
    "flex min-h-12 items-center rounded-lg px-3 text-sm font-medium text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)]";

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setIsOpen(true)}
        disabled={!isHydrated}
        className="flex size-11 items-center justify-center rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-paper)] text-[var(--mundia-ink)] transition-colors hover:border-[var(--mundia-navy)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)] disabled:opacity-60 md:hidden"
        aria-label="Open account menu"
        aria-expanded={isOpen}
        aria-controls="mobile-account-menu"
      >
        <UserRound className="size-5" aria-hidden="true" />
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
        <div className="relative z-10 ml-auto flex h-full w-[min(88vw,22rem)] flex-col overflow-y-auto border-l border-[var(--mundia-line)] bg-[var(--surface-card-strong)] pb-[env(safe-area-inset-bottom)] pt-[env(safe-area-inset-top)]">
          <div className="flex min-h-16 items-center justify-between border-b border-[var(--mundia-line)] px-4">
            <h2
              id="mobile-account-menu-title"
              className="font-serif text-xl text-[var(--mundia-ink)]"
            >
              Account
            </h2>
            <button
              ref={closeButtonRef}
              type="button"
              onClick={closeMenu}
              className="flex size-11 items-center justify-center rounded-lg text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)]"
              aria-label="Close account menu"
            >
              <X className="size-5" aria-hidden="true" />
            </button>
          </div>

          <div className="border-b border-[var(--mundia-line)] px-4 py-5">
            <div className="flex items-center gap-3">
              <span className="flex size-11 shrink-0 items-center justify-center rounded-full bg-[var(--mundia-panel)] text-sm font-semibold text-[var(--mundia-navy)]">
                {fullName.charAt(0).toUpperCase()}
              </span>
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-[var(--mundia-ink)]">
                  {fullName}
                </p>
                <p className="mt-0.5 truncate text-xs text-[var(--mundia-muted)]">
                  {email}
                </p>
              </div>
            </div>
            {typeof universityId === "number" && (
              <p className="mt-3 text-xs text-[var(--mundia-muted)]">
                University ID: {universityId}
              </p>
            )}
          </div>

          <nav className="flex-1 px-3 py-4" aria-label="Account navigation">
            <Link href="/my-profile" onClick={closeMenu} className={navLinkClass}>
              Account and borrowing history
            </Link>

            {isAdmin && (
              <div className="mt-4 border-t border-[var(--mundia-line)] pt-4">
                <p className="px-3 pb-2 text-xs font-semibold uppercase tracking-wide text-[var(--mundia-muted)]">
                  Administration
                </p>
                <Link href="/admin" onClick={closeMenu} className={navLinkClass}>
                  Dashboard
                </Link>
                <Link
                  href="/admin/book-requests"
                  onClick={closeMenu}
                  className={navLinkClass}
                >
                  Borrow requests
                </Link>
                <Link
                  href="/admin/users"
                  onClick={closeMenu}
                  className={navLinkClass}
                >
                  Users
                </Link>
                <Link
                  href="/admin/books"
                  onClick={closeMenu}
                  className={navLinkClass}
                >
                  Catalog management
                </Link>
              </div>
            )}
          </nav>

          <div className="border-t border-[var(--mundia-line)] p-4">
            <button
              type="button"
              onClick={handleLogout}
              disabled={isLoggingOut}
              className="flex min-h-12 w-full items-center justify-center gap-2 rounded-lg border border-[var(--mundia-line)] px-4 text-sm font-semibold text-[var(--mundia-ink)] transition-colors hover:border-red-300 hover:bg-red-50 hover:text-red-900 disabled:opacity-50"
            >
              <LogOut className="size-4" aria-hidden="true" />
              {isLoggingOut ? "Signing out…" : "Sign out"}
            </button>
          </div>
        </div>
      </dialog>
    </>
  );
};

export default MobileMenu;
