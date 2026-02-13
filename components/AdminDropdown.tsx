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
          className="rounded-full px-3 py-2 text-sm font-medium text-light-100/85 transition hover:bg-white/10 hover:text-white"
        >
          Admin
        </Link>
      </div>

      {/* Dropdown Menu */}
      {isOpen && (
        <div className="absolute left-0 top-full z-50 mt-2 w-44 rounded-2xl border border-white/12 bg-[rgba(7,14,22,0.96)] p-1.5 shadow-2xl backdrop-blur-xl sm:w-52">
          {/* Add a small invisible bridge to prevent hover gap */}
          <div className="absolute inset-x-0 -top-2 h-2"></div>
          <div className="py-1 sm:py-1.5">
            <Link
              href="/admin"
              className="block rounded-xl px-3 py-2 text-xs text-light-100/90 transition-colors hover:bg-white/10 hover:text-white sm:px-4 sm:text-sm"
            >
              Dashboard Overview
            </Link>
            <Link
              href="/admin/automation"
              className="block rounded-xl px-3 py-2 text-xs text-light-100/90 transition-colors hover:bg-white/10 hover:text-white sm:px-4 sm:text-sm"
            >
              Automation
            </Link>
            <Link
              href="/admin/users"
              className="block rounded-xl px-3 py-2 text-xs text-light-100/90 transition-colors hover:bg-white/10 hover:text-white sm:px-4 sm:text-sm"
            >
              Users
            </Link>
            <Link
              href="/admin/books"
              className="block rounded-xl px-3 py-2 text-xs text-light-100/90 transition-colors hover:bg-white/10 hover:text-white sm:px-4 sm:text-sm"
            >
              Books
            </Link>
            <Link
              href="/admin/book-requests"
              className="block rounded-xl px-3 py-2 text-xs text-light-100/90 transition-colors hover:bg-white/10 hover:text-white sm:px-4 sm:text-sm"
            >
              Borrow Requests
            </Link>
            <Link
              href="/admin/account-requests"
              className="block rounded-xl px-3 py-2 text-xs text-light-100/90 transition-colors hover:bg-white/10 hover:text-white sm:px-4 sm:text-sm"
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
