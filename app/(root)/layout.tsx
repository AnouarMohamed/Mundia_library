import { ReactNode } from "react";
import { redirect } from "next/navigation";
import Header from "@/components/Header";
import { requireApprovedUser } from "@/lib/security/auth-guards";

/**
 * Root layout that requires a valid session.
 */
const Layout = async ({ children }: { children: ReactNode }) => {
  const guard = await requireApprovedUser();

  if (!guard.ok && guard.status === 401) {
    redirect("/sign-in");
  }

  if (!guard.ok) {
    redirect("/sign-in");
  }

  return (
    <main className="root-container">
      <Header session={guard.session} />
      {children}
    </main>
  );
};

export default Layout;
