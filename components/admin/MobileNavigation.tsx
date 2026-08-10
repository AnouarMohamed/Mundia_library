"use client";

import { useEffect, useRef, useState } from "react";
import { Menu, X } from "lucide-react";
import type { Session } from "next-auth";

import AdminNavLinks from "@/components/admin/AdminNavLinks";
import AdminUserCard from "@/components/admin/AdminUserCard";

interface MobileNavigationProps {
  session: Session;
}

const MobileNavigation = ({ session }: MobileNavigationProps) => {
  const [isOpen, setIsOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  const closeNavigation = () => {
    setIsOpen(false);
    requestAnimationFrame(() => triggerRef.current?.focus());
  };

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
    const dialog = dialogRef.current;
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

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setIsOpen(true)}
        className="flex size-11 shrink-0 items-center justify-center rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-paper)] text-[var(--mundia-ink)] transition-colors hover:border-[var(--mundia-navy)] hover:text-[var(--mundia-navy)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)] md:hidden"
        aria-label="Open admin navigation"
        aria-expanded={isOpen}
        aria-controls="admin-mobile-navigation"
      >
        <Menu className="size-5" aria-hidden="true" />
      </button>

      <dialog
        ref={dialogRef}
        id="admin-mobile-navigation"
        aria-labelledby="admin-mobile-navigation-title"
        onCancel={(event) => {
          event.preventDefault();
          closeNavigation();
        }}
        className="admin-mobile-navigation-dialog fixed inset-0 z-50 m-0 h-dvh w-screen max-w-none border-0 bg-transparent p-0 md:hidden"
      >
        <button
          type="button"
          className="fixed inset-0 cursor-default"
          onClick={closeNavigation}
          aria-hidden="true"
          tabIndex={-1}
        />

        <div className="relative z-10 flex h-full w-[min(88vw,22rem)] flex-col overflow-hidden border-r border-[var(--mundia-line)] bg-[var(--mundia-surface)] pb-[env(safe-area-inset-bottom)] pt-[env(safe-area-inset-top)]">
          <div className="flex min-h-16 shrink-0 items-center justify-between border-b border-[var(--mundia-line)] px-4">
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.14em] text-[var(--mundia-muted)]">
                Mundiapolis Library
              </p>
              <h2
                id="admin-mobile-navigation-title"
                className="mt-0.5 font-serif text-xl text-[var(--mundia-ink)]"
              >
                Admin navigation
              </h2>
            </div>
            <button
              ref={closeButtonRef}
              type="button"
              onClick={closeNavigation}
              className="flex size-11 shrink-0 items-center justify-center rounded-lg text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)]"
              aria-label="Close admin navigation"
            >
              <X className="size-5" aria-hidden="true" />
            </button>
          </div>

          <nav
            className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 py-4"
            aria-label="Admin navigation"
          >
            <AdminNavLinks onNavigate={closeNavigation} />
          </nav>

          <div className="shrink-0 border-t border-[var(--mundia-line)] p-4">
            <AdminUserCard session={session} variant="mobile" />
          </div>
        </div>
      </dialog>
    </>
  );
};

export default MobileNavigation;
