import { config } from "dotenv";
import mysql from "mysql2/promise";

config({ path: ".env.local" });
config({ path: ".env" });

if (!process.env.DATABASE_URL) {
  throw new Error("DATABASE_URL environment variable is not set");
}

type QueryPlan = {
  endpoint: string;
  description: string;
  sql: string;
  params?: Array<string | number>;
};

const plans: QueryPlan[] = [
  {
    endpoint: "GET /api/books",
    description: "Public books list ordered by rating/date with active-only filter",
    sql: `
      EXPLAIN FORMAT=JSON
      SELECT id, title, author, genre, rating, available_copies, created_at
      FROM books
      WHERE is_active = 1
      ORDER BY rating DESC, created_at DESC
      LIMIT 12 OFFSET 0
    `,
  },
  {
    endpoint: "GET /api/books/genres",
    description: "Distinct active genres",
    sql: `
      EXPLAIN FORMAT=JSON
      SELECT DISTINCT genre
      FROM books
      WHERE is_active = 1
      ORDER BY genre ASC
    `,
  },
  {
    endpoint: "GET /api/books/recommendations",
    description: "Genre-based active recommendations",
    sql: `
      EXPLAIN FORMAT=JSON
      SELECT id, title, author, genre, rating, created_at
      FROM books
      WHERE is_active = 1
        AND genre IN ('Science', 'History', 'Technology')
      ORDER BY rating DESC, created_at DESC
      LIMIT 10
    `,
  },
  {
    endpoint: "GET /api/borrow-records",
    description: "User borrow history with status/date ordering",
    sql: `
      EXPLAIN FORMAT=JSON
      SELECT br.id, br.user_id, br.book_id, br.status, br.created_at, b.title
      FROM borrow_records br
      INNER JOIN books b ON br.book_id = b.id
      WHERE br.user_id = ? AND br.status = 'BORROWED'
      ORDER BY br.created_at DESC
      LIMIT 50 OFFSET 0
    `,
    params: ["00000000-0000-0000-0000-000000000000"],
  },
  {
    endpoint: "GET /api/reviews/[bookId]",
    description: "Book review timeline ordered by created_at",
    sql: `
      EXPLAIN FORMAT=JSON
      SELECT br.id, br.book_id, br.user_id, br.rating, br.created_at
      FROM book_reviews br
      WHERE br.book_id = ?
      ORDER BY br.created_at DESC
    `,
    params: ["00000000-0000-0000-0000-000000000000"],
  },
];

async function run() {
  const pool = mysql.createPool({
    uri: process.env.DATABASE_URL,
    connectionLimit: 5,
  });

  try {
    console.log("Running EXPLAIN plans for hot API queries...\n");

    for (const plan of plans) {
      const [rows] = await pool.execute(plan.sql, plan.params ?? []);
      const explainRows = rows as Array<{ EXPLAIN: string }>;

      console.log("=".repeat(88));
      console.log(`${plan.endpoint}`);
      console.log(`- ${plan.description}`);
      console.log("=".repeat(88));

      if (explainRows.length === 0) {
        console.log("No EXPLAIN output returned.\n");
        continue;
      }

      const explainJson = explainRows[0]?.EXPLAIN;
      if (!explainJson) {
        console.log("EXPLAIN output format unexpected.\n");
        continue;
      }

      const parsed = JSON.parse(explainJson) as {
        query_block?: {
          cost_info?: { query_cost?: string };
        };
      };

      const queryCost = parsed.query_block?.cost_info?.query_cost ?? "unknown";
      console.log(`Estimated query cost: ${queryCost}`);
      console.log(JSON.stringify(parsed, null, 2));
      console.log("");
    }
  } finally {
    await pool.end();
  }
}

run().catch((error) => {
  console.error("Failed to run EXPLAIN hot queries:", error);
  process.exit(1);
});
