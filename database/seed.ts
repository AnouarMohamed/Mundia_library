import dummyBooks from "../dummybooks.json";
import { books, users } from "@/database/schema";
import { sha256 } from "@noble/hashes/sha256";
import { randomBytes } from "@noble/hashes/utils";
import { config } from "dotenv";

config({ path: ".env.local" });
config({ path: ".env" });

type Db = typeof import("@/database/drizzle").db;

const concatUint8Arrays = (a: Uint8Array, b: Uint8Array): Uint8Array => {
  const c = new Uint8Array(a.length + b.length);
  c.set(a, 0);
  c.set(b, a.length);
  return c;
};

const hashPassword = (password: string) => {
  const salt = randomBytes(16);
  const passwordBytes = new TextEncoder().encode(password);
  const hashBuffer = sha256(concatUint8Arrays(passwordBytes, salt));

  return `${Buffer.from(salt).toString("base64")}:${Buffer.from(hashBuffer).toString("base64")}`;
};

const seedGuestUsers = async (db: Db) => {
  const guestPassword = hashPassword("12345678");
  const guestUsers = [
    {
      fullName: "Guest User",
      email: "test@user.com",
      universityId: 90000001,
      password: guestPassword,
      universityCard: "guest-user-card",
      status: "APPROVED" as const,
      role: "USER" as const,
    },
    {
      fullName: "Guest Admin",
      email: "test@admin.com",
      universityId: 90000002,
      password: guestPassword,
      universityCard: "guest-admin-card",
      status: "APPROVED" as const,
      role: "ADMIN" as const,
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
    await seedGuestUsers(db);

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

main()
  .catch((error) => {
    console.error("Seeding failed:", error);
    process.exitCode = 1;
  })
