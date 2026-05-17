import React, { ReactNode } from "react";
import { redirect } from "next/navigation";

import "@/styles/admin.css";
import Sidebar from "@/components/admin/Sidebar";
import Header from "@/components/admin/Header";
import { requireAdmin } from "@/lib/security/auth-guards";

/**
 * Ensure admin pages are always rendered dynamically.
 */
export const dynamic = "force-dynamic";

/**
 * Admin layout that enforces authenticated admin access.
 */
const Layout = async ({ children }: { children: ReactNode }) => {
  const guard = await requireAdmin();

  if (!guard.ok && guard.status === 401) redirect("/sign-in");
  if (!guard.ok) redirect("/");

  return (
    <main className="admin-theme flex min-h-screen w-full flex-row">
      <Sidebar session={guard.session} />

      <div className="admin-container">
        <Header session={guard.session} />
        {children}
      </div>
    </main>
  );
};
export default Layout;
