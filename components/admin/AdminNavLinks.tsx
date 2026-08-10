"use client";

import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";

import { adminSideBarLinks } from "@/constants";
import { cn } from "@/lib/utils";

interface AdminNavLinksProps {
  onNavigate?: () => void;
}

const AdminNavLinks = ({ onNavigate }: AdminNavLinksProps) => {
  const pathname = usePathname();

  return (
    <ul className="flex flex-col gap-1">
      {adminSideBarLinks.map((link) => {
        const isSelected =
          pathname === link.route ||
          (link.route !== "/admin" && pathname.startsWith(`${link.route}/`));

        return (
          <li key={link.route}>
            <Link
              href={link.route}
              onClick={onNavigate}
              aria-current={isSelected ? "page" : undefined}
              className={cn(
                "group flex min-h-12 w-full items-center gap-3 rounded-lg border border-transparent px-3 text-sm font-medium text-slate-700 transition-colors hover:bg-[var(--mundia-panel)] hover:text-[var(--mundia-navy)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)] focus-visible:ring-offset-2",
                isSelected &&
                  "border-[var(--mundia-line)] bg-[var(--mundia-panel)] font-semibold text-[var(--mundia-navy)]",
              )}
            >
              <span className="flex size-6 shrink-0 items-center justify-center">
                <Image
                  src={link.img}
                  alt=""
                  aria-hidden="true"
                  width={20}
                  height={20}
                  className={cn(
                    "size-5 object-contain opacity-70 transition-opacity group-hover:opacity-100",
                    isSelected && "opacity-100",
                  )}
                />
              </span>
              <span className="min-w-0 truncate">{link.text}</span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
};

export default AdminNavLinks;
