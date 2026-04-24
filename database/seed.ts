import dummyBooks from "../dummybooks.json";
import { books } from "@/database/schema";
import { drizzle } from "drizzle-orm/mysql2";
import mysql from "mysql2/promise";
import { config } from "dotenv";

config({ path: ".env.local" });
config({ path: ".env" });

const pool = mysql.createPool({
  uri: process.env.DATABASE_URL!,
  connectionLimit: 10,
});
export const db = drizzle({ client: pool, casing: "snake_case" });

const seed = async () => {
  console.log("Seeding data...");

  try {
    for (const book of dummyBooks) {
      await db.insert(books).values({
        ...book,
        coverUrl: book.coverUrl,
        videoUrl: book.videoUrl,
      });
    }

    console.log("Data seeded successfully!");
  } catch (error) {
    console.error("Error seeding data:", error);
  }
};

seed()
  .catch((error) => {
    console.error("Seeding failed:", error);
    process.exitCode = 1;
  })
  .finally(async () => {
    await pool.end();
  });
