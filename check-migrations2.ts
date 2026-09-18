import { db } from "./database/drizzle";
import { sql } from "drizzle-orm";

async function main() {
  try {
    // Check if _drizzle_migrations exists
    const migExists = await db.execute(sql`
      SELECT EXISTS (
        SELECT FROM information_schema.tables 
        WHERE table_schema = 'public' 
        AND table_name = '_drizzle_migrations'
      );
    `);
    console.log("_drizzle_migrations exists:", migExists.rows[0].exists);
    
    // List all tables
    const tables = await db.execute(sql`
      SELECT table_name 
      FROM information_schema.tables 
      WHERE table_schema = 'public' 
      ORDER BY table_name;
    `);
    console.log("\nTables in public schema:");
    for (const row of tables.rows) {
      console.log(`- ${row.table_name}`);
    }
  } catch (err) {
    console.error("Error:", err);
  }
}
main();
