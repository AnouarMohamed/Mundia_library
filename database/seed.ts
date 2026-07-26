import dummyBooks from "../dummybooks.json";
import { books, users } from "@/database/schema";
import { config } from "dotenv";
import { hashPassword } from "@/lib/security/password";

config({ path: ".env.local" });
config({ path: ".env" });

type Db = typeof import("@/database/drizzle").db;

const seedGuestUsers = async (db: Db) => {
  const guestPassword = await hashPassword("12345678");
  const guestUsers = [
    {
      id: "00000000-0000-4000-8000-000000000001",
      fullName: "Guest User",
      email: "test@user.com",
      universityId: 90000001,
      password: guestPassword,
      universityCard: "guest-user-card",
      status: "APPROVED" as const,
      role: "USER" as const,
    },
    {
      id: "00000000-0000-4000-8000-000000000002",
      fullName: "Guest Admin",
      email: "test@admin.com",
      universityId: 90000002,
      password: guestPassword,
      universityCard: "guest-admin-card",
      status: "APPROVED" as const,
      role: "ADMIN" as const,
    },
    {
      id: "00000000-0000-4000-8000-000000000003",
      fullName: "Pending E2E User",
      email: "pending-e2e@example.test",
      universityId: 90000003,
      password: guestPassword,
      universityCard: "pending-e2e-card",
      status: "PENDING" as const,
      role: "USER" as const,
    },
  ];

  for (const guestUser of guestUsers) {
    await db
      .insert(users)
      .values(guestUser)
      .onConflictDoUpdate({
        target: users.email,
        set: {
          fullName: guestUser.fullName,
          password: guestUser.password,
          universityCard: guestUser.universityCard,
          status: guestUser.status,
          role: guestUser.role,
        },
      });
  }
};

const seed = async (db: Db) => {
  console.log("Seeding data...");

  try {
    const databaseUrl = process.env.DATABASE_URL;
    if (!databaseUrl) {
      throw new Error("DATABASE_URL is required for seeding");
    }

    const databaseHost = new URL(databaseUrl).hostname;
    const localDatabaseHosts = new Set([
      "localhost",
      "127.0.0.1",
      "::1",
      "db",
      "postgres",
    ]);
    const fixturesExplicitlyAllowed =
      process.env.ALLOW_TEST_FIXTURES === "true";

    if (fixturesExplicitlyAllowed && localDatabaseHosts.has(databaseHost)) {
      await seedGuestUsers(db);
    } else {
      console.log(
        "Skipping fixture accounts; they are allowed only for an explicitly configured local database.",
      );
    }

    for (const book of dummyBooks) {
      await db
        .insert(books)
        .values({
          ...book,
          coverUrl: book.coverUrl,
          videoUrl: book.videoUrl,
        })
        .onConflictDoNothing();
    }

    console.log("Data seeded successfully!");
  } catch (error) {
    console.error("Error seeding data:", error);
    throw error;
  }
};

const main = async () => {
  const { closeDb, db } = await import("@/database/drizzle");

  try {
    await seed(db);
  } finally {
    await closeDb();
  }
};

main().catch((error) => {
  console.error("Seeding failed:", error);
  process.exitCode = 1;
});
