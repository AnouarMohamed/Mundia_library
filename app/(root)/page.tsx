import { db } from "@/database/drizzle";
import { users } from "@/database/schema";
import { eq } from "drizzle-orm";
import { redirect } from "next/navigation";
import { getSession } from "@/lib/session";

const Home = async () => {
  const session = await getSession();

  if (!session?.user?.id) {
    redirect("/sign-in");
  }

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

export default Home;
