import { db } from "./database/drizzle";
import { sql } from "drizzle-orm";

async function main() {
  try {
    const result = await db.execute(sql`
      SELECT * FROM _drizzle_migrations ORDER BY version;
    `);
    console.log("Applied migrations:", result.rows.length);
    for (const row of result.rows) {
      console.log(`- ${row.version} ${row.name} at ${row.executed_at}`);
    }
  } catch (err) {
    console.error("Error querying migrations:", err);
    // Maybe the table doesn't exist
    console.log("Assuming no migrations applied.");
  }
}
main();
