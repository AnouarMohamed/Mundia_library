import { ReactNode } from "react";
import { redirect } from "next/navigation";
import Header from "@/components/Header";
import { getSession } from "@/lib/session";

const Layout = async ({ children }: { children: ReactNode }) => {
  const session = await getSession();

  if (!session?.user?.id) {
    redirect("/sign-in");
  }

  return (
    <main className="root-container">
      <Header session={session} />
      {children}
    </main>
  );
};

export default Layout;
