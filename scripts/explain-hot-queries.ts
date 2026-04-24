import { config } from "dotenv";
import mysql from "mysql2/promise";
import fs from "node:fs/promises";
import path from "node:path";

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

type TableAccessSummary = {
  endpoint: string;
  tableName: string;
  accessType?: string;
  key?: string;
  rowsExaminedPerScan?: number;
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

function extractTableAccesses(node: unknown, endpoint: string): TableAccessSummary[] {
  const summaries: TableAccessSummary[] = [];

  const visit = (value: unknown) => {
    if (!value || typeof value !== "object") {
      return;
    }

    if (Array.isArray(value)) {
      for (const item of value) {
        visit(item);
      }
      return;
    }

    const record = value as Record<string, unknown>;
    const tableNode = record.table;
    if (tableNode && typeof tableNode === "object") {
      const table = tableNode as Record<string, unknown>;
      const tableName = table.table_name;

      if (typeof tableName === "string") {
        summaries.push({
          endpoint,
          tableName,
          accessType:
            typeof table.access_type === "string"
              ? table.access_type
              : undefined,
          key: typeof table.key === "string" ? table.key : undefined,
          rowsExaminedPerScan:
            typeof table.rows_examined_per_scan === "number"
              ? table.rows_examined_per_scan
              : undefined,
        });
      }
    }

    for (const nested of Object.values(record)) {
      visit(nested);
    }
  };

  visit(node);
  return summaries;
}

function buildMarkdown(summary: TableAccessSummary[]) {
  const lines = [
    "## Hot query index hit report",
    "",
    "| Endpoint | Table | Access type | Index key | Rows/scan |",
    "| --- | --- | --- | --- | ---: |",
  ];

  for (const item of summary) {
    lines.push(
      `| ${item.endpoint} | ${item.tableName} | ${item.accessType || "n/a"} | ${item.key || "(none)"} | ${item.rowsExaminedPerScan ?? 0} |`
    );
  }

  return `${lines.join("\n")}\n`;
}

async function run() {
  const artifactDir = path.resolve(process.cwd(), "artifacts/perf");
  await fs.mkdir(artifactDir, { recursive: true });

  const pool = mysql.createPool({
    uri: process.env.DATABASE_URL,
    connectionLimit: 5,
  });

  try {
    console.log("Running EXPLAIN plans for hot API queries...\n");

    const summaryRows: TableAccessSummary[] = [];
    const planDump: Array<{
      endpoint: string;
      description: string;
      queryCost: string | number;
      plan: unknown;
    }> = [];

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
      const accesses = extractTableAccesses(parsed, plan.endpoint);
      summaryRows.push(...accesses);

      planDump.push({
        endpoint: plan.endpoint,
        description: plan.description,
        queryCost,
        plan: parsed,
      });

      console.log(`Estimated query cost: ${queryCost}`);
      console.log(JSON.stringify(parsed, null, 2));
      console.log("");
    }

    const markdown = buildMarkdown(summaryRows);
    const markdownPath = path.join(artifactDir, "query-plan-index-report.md");
    const jsonPath = path.join(artifactDir, "query-plan-index-report.json");

    await fs.writeFile(markdownPath, markdown, "utf8");
    await fs.writeFile(
      jsonPath,
      JSON.stringify(
        {
          generatedAt: new Date().toISOString(),
          plans: planDump,
          summary: summaryRows,
        },
        null,
        2
      ),
      "utf8"
    );

    if (process.env.GITHUB_STEP_SUMMARY) {
      await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, markdown);
    }

    console.log(markdown);
    console.log(`Saved index hit report to ${markdownPath}`);
  } finally {
    await pool.end();
  }
}

run().catch((error) => {
  console.error("Failed to run EXPLAIN hot queries:", error);
  process.exit(1);
});
