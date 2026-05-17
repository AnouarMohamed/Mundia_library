import { config } from "dotenv";
import fs from "node:fs/promises";
import path from "node:path";
import { Pool } from "pg";

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
  nodeType?: string;
  relationName?: string;
  indexName?: string;
  planRows?: number;
  totalCost?: number;
};

const plans: QueryPlan[] = [
  {
    endpoint: "GET /api/books",
    description: "Public books list ordered by rating/date with active-only filter",
    sql: `
      EXPLAIN (FORMAT JSON)
      SELECT id, title, author, genre, rating, available_copies, created_at
      FROM books
      WHERE is_active = true
      ORDER BY rating DESC, created_at DESC
      LIMIT 12 OFFSET 0
    `,
  },
  {
    endpoint: "GET /api/books/genres",
    description: "Distinct active genres",
    sql: `
      EXPLAIN (FORMAT JSON)
      SELECT DISTINCT genre
      FROM books
      WHERE is_active = true
      ORDER BY genre ASC
    `,
  },
  {
    endpoint: "GET /api/books/recommendations",
    description: "Genre-based active recommendations",
    sql: `
      EXPLAIN (FORMAT JSON)
      SELECT id, title, author, genre, rating, created_at
      FROM books
      WHERE is_active = true
        AND genre IN ('Science', 'History', 'Technology')
      ORDER BY rating DESC, created_at DESC
      LIMIT 10
    `,
  },
  {
    endpoint: "GET /api/borrow-records",
    description: "User borrow history with status/date ordering",
    sql: `
      EXPLAIN (FORMAT JSON)
      SELECT br.id, br.user_id, br.book_id, br.status, br.created_at, b.title
      FROM borrow_records br
      INNER JOIN books b ON br.book_id = b.id
      WHERE br.user_id = $1 AND br.status = 'BORROWED'
      ORDER BY br.created_at DESC
      LIMIT 50 OFFSET 0
    `,
    params: ["00000000-0000-0000-0000-000000000000"],
  },
  {
    endpoint: "GET /api/reviews/[bookId]",
    description: "Book review timeline ordered by created_at",
    sql: `
      EXPLAIN (FORMAT JSON)
      SELECT br.id, br.book_id, br.user_id, br.rating, br.created_at
      FROM book_reviews br
      WHERE br.book_id = $1
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
    const relationName = record["Relation Name"];

    if (typeof relationName === "string") {
      summaries.push({
        endpoint,
        nodeType:
          typeof record["Node Type"] === "string"
            ? record["Node Type"]
            : undefined,
        relationName,
        indexName:
          typeof record["Index Name"] === "string"
            ? record["Index Name"]
            : undefined,
        planRows:
          typeof record["Plan Rows"] === "number"
            ? record["Plan Rows"]
            : undefined,
        totalCost:
          typeof record["Total Cost"] === "number"
            ? record["Total Cost"]
            : undefined,
      });
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
    "| Endpoint | Relation | Plan node | Index | Planned rows | Total cost |",
    "| --- | --- | --- | --- | ---: | ---: |",
  ];

  for (const item of summary) {
    lines.push(
      `| ${item.endpoint} | ${item.relationName || "n/a"} | ${item.nodeType || "n/a"} | ${item.indexName || "(none)"} | ${item.planRows ?? 0} | ${item.totalCost ?? 0} |`,
    );
  }

  return `${lines.join("\n")}\n`;
}

async function run() {
  const artifactDir = path.resolve(process.cwd(), "artifacts/perf");
  await fs.mkdir(artifactDir, { recursive: true });

  const pool = new Pool({
    connectionString: process.env.DATABASE_URL,
    max: 5,
  });

  try {
    console.log("Running PostgreSQL EXPLAIN plans for hot API queries...\n");

    const summaryRows: TableAccessSummary[] = [];
    const planDump: Array<{
      endpoint: string;
      description: string;
      plan: unknown;
    }> = [];

    for (const plan of plans) {
      const result = await pool.query(plan.sql, plan.params ?? []);
      const parsed = result.rows[0]?.["QUERY PLAN"]?.[0];

      console.log("=".repeat(88));
      console.log(`${plan.endpoint}`);
      console.log(`- ${plan.description}`);
      console.log("=".repeat(88));

      if (!parsed) {
        console.log("No EXPLAIN output returned.\n");
        continue;
      }

      const accesses = extractTableAccesses(parsed, plan.endpoint);
      summaryRows.push(...accesses);

      planDump.push({
        endpoint: plan.endpoint,
        description: plan.description,
        plan: parsed,
      });

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
        2,
      ),
      "utf8",
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
