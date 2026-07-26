/**
 * Admin Sidebar Component
 * 
 * The primary vertical navigation for the administrator dashboard.
 * Includes logo branding, dynamic navigational links, and a user profile footer.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import { adminSideBarLinks } from "@/constants";
import Link from "next/link";
import { cn, getInitials } from "@/lib/utils";
import { usePathname } from "next/navigation";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Session } from "next-auth";

/**
 * Props for Admin Sidebar
 */
interface AdminSidebarProps {
  /**
   * The current authenticated session object from NextAuth
   */
  session: Session;
}

/**
 * Sidebar
 * 
 * Client-side component that manages the administrative navigation state.
 * Automatically highlights the active route based on the current pathname.
 * 
 * @param {AdminSidebarProps} props - Component properties
 * @returns {JSX.Element} The rendered admin sidebar
 */
const Sidebar = ({ session }: AdminSidebarProps) => {
  const pathname = usePathname();

  return (
    <div className="admin-sidebar">
      <div>
        {/* Responsive Logo Section */}
        <Link href="/" className="logo">
          {/* Small Mark for mobile/tight spaces */}
          <img
            src="/images/mundiapolis-mark.png"
            alt="Mundiapolis Library"
            height={40}
            width={40}
            className="h-10 w-10 object-contain sm:hidden"
          />
          {/* Full Logo for desktop */}
          <img
            src="/images/mundiapolis-logo-transparent.png"
            alt=""
            height={50}
            width={161}
            className="hidden h-auto w-[161px] object-contain sm:block"
          />
          <h1 className="sr-only">Mundiapolis Library</h1>
        </Link>

        {/* Primary Navigation Links */}
        <div className="my-2 flex flex-col gap-1.5 sm:gap-2">
          {adminSideBarLinks.map((link) => {
            /**
             * ACTIVE LINK LOGIC:
             * Highlights a link if it matches the current path exactly, 
             * or if the current path starts with the link's route (for sub-pages).
             */
            const isSelected =
              (link.route !== "/admin" &&
                pathname.includes(link.route) &&
                link.route.length > 1) ||
              pathname === link.route;

            return (
              <Link href={link.route} key={link.route}>
                <div className={cn("link", isSelected && "is-active")}>
                  {/* Menu Icon */}
                  <div className="relative size-4 sm:size-5">
                    <img
                      src={link.img}
                      alt="icon"
                      className={cn(
                        "object-contain opacity-75",
                        isSelected && "opacity-100",
                      )}
                      style={{ width: "100%", height: "100%" }} 
                    />
                  </div>

                  {/* Menu Label - hidden on mobile viewports */}
                  <p
                    className={cn(
                      "hidden sm:block",
                      isSelected
                        ? "font-semibold text-[var(--mundia-navy)]"
                        : "text-slate-700",
                    )}
                  >
                    {link.text}
                  </p>
                </div>
              </Link>
            );
          })}
        </div>
      </div>

      {/* User Profile Footer Section */}
      <div className="user">
        <Avatar className="size-8 sm:size-10">
          <AvatarFallback className="bg-amber-100 text-xs sm:text-sm">
            {getInitials(session?.user?.name || "IN")}
          </AvatarFallback>
        </Avatar>

        <div className="hidden flex-col sm:flex">
          <p className="font-semibold text-slate-900">{session?.user?.name}</p>
          <p className="text-xs text-slate-600">{session?.user?.email}</p>
        </div>
      </div>
    </div>
  );
};

export default Sidebar;
