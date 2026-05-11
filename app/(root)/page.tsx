import { redirect } from "next/navigation";
import { getSession } from "@/lib/session";

/**
 * Root entrypoint that routes users to the library view.
 */
const Home = async () => {
  const session = await getSession();

  if (!session?.user?.id) {
    redirect("/sign-in");
  }

  // Default landing for authenticated users.
  redirect("/library");
}
  /*
  const roleFromSession = (session.user as { role?: string }).role;
  if (roleFromSession === "ADMIN") {
    redirect("/library");
  }

  if (roleFromSession === "USER") {
    redirect("/library");
  }

  if (!roleFromSession) {
    const roleFromDb = await db
      .select({ role: users.role })
      .from(users)
      .where(eq(users.id, session.user.id))
      .limit(1)
      .then((rows) => rows[0]?.role);

    if (roleFromDb === "ADMIN") {
      redirect("/library");
    }
  }

  redirect("/library");
};

   */
export default Home;
