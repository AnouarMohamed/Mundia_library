"use client";

import Link from "next/link";
import { useState } from "react";

const AdminDropdown = () => {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <div
      className="relative"
      onMouseEnter={() => setIsOpen(true)}
      onMouseLeave={() => setIsOpen(false)}
    >
      {/* Main Admin Dashboard Link with padding for better hover area */}
      <div className="px-1 py-1 sm:px-2">
        <Link
          href="/admin"
          className="rounded-lg px-3 py-2 text-sm font-medium text-[var(--mundia-ink)]/72 transition hover:bg-[var(--mundia-panel)] hover:text-[var(--mundia-ink)]"
        >
          Admin
        </Link>
      </div>

      {/* Dropdown Menu */}
      {isOpen && (
        <div className="absolute left-0 top-full z-50 mt-2 w-44 rounded-xl border border-[var(--mundia-line)] bg-[var(--surface-card-strong)] p-1.5 shadow-xl sm:w-52">
          {/* Add a small invisible bridge to prevent hover gap */}
          <div className="absolute inset-x-0 -top-2 h-2"></div>
          <div className="py-1 sm:py-1.5">
            <Link
              href="/admin"
              className="block rounded-lg px-3 py-2 text-xs text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] sm:px-4 sm:text-sm"
            >
              Dashboard Overview
            </Link>
            <Link
              href="/admin/automation"
              className="block rounded-lg px-3 py-2 text-xs text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] sm:px-4 sm:text-sm"
            >
              Automation
            </Link>
            <Link
              href="/admin/users"
              className="block rounded-lg px-3 py-2 text-xs text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] sm:px-4 sm:text-sm"
            >
              Users
            </Link>
            <Link
              href="/admin/books"
              className="block rounded-lg px-3 py-2 text-xs text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] sm:px-4 sm:text-sm"
            >
              Books
            </Link>
            <Link
              href="/admin/book-requests"
              className="block rounded-lg px-3 py-2 text-xs text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] sm:px-4 sm:text-sm"
            >
              Borrow Requests
            </Link>
            <Link
              href="/admin/account-requests"
              className="block rounded-lg px-3 py-2 text-xs text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)] sm:px-4 sm:text-sm"
            >
              Account Requests
            </Link>
          </div>
        </div>
      )}
    </div>
  );
};

export default AdminDropdown;
