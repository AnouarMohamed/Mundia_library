import React, { ReactNode } from "react";
import { redirect } from "next/navigation";

import "@/styles/admin.css";
import Sidebar from "@/components/admin/Sidebar";
import Header from "@/components/admin/Header";
import { db } from "@/database/drizzle";
import { users } from "@/database/schema";
import { eq } from "drizzle-orm";
import { getSession } from "@/lib/session";

const Layout = async ({ children }: { children: ReactNode }) => {
  const session = await getSession();

  if (!session?.user?.id) redirect("/sign-in");

  const roleFromSession = (session.user as { role?: string }).role;
  let isAdmin = roleFromSession === "ADMIN";

  if (!isAdmin) {
    // Fallback for old/stale sessions where role in JWT is missing or outdated.
    isAdmin = await db
      .select({ role: users.role })
      .from(users)
      .where(eq(users.id, session.user.id))
      .limit(1)
      .then((res) => res[0]?.role === "ADMIN");
  }

  if (!isAdmin) redirect("/");

  return (
    <main className="admin-theme flex min-h-screen w-full flex-row">
      <Sidebar session={session} />

      <div className="admin-container">
        <Header session={session} />
        {children}
      </div>
    </main>
  );
};
export default Layout;
