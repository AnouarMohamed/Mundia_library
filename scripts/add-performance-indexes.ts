/**
 * Add query-performance indexes for large local/dev datasets.
 *
 * This script is safe to run multiple times (uses IF NOT EXISTS).
 * It targets known hot query paths:
 * - books search/sort/filter
 * - user/profile borrow lookups
 * - admin borrow status/date lookups
 */

import { config } from "dotenv";
import { sql } from "drizzle-orm";

config({ path: ".env.local" });

type IndexStatement = {
  name: string;
  ddl: string;
};

const statements: IndexStatement[] = [
  {
    name: "pg_trgm extension",
    ddl: `create extension if not exists pg_trgm;`,
  },
  {
    name: "books is_active + rating + created_at",
    ddl: `create index if not exists books_is_active_rating_created_idx
          on books (is_active, rating desc, created_at desc);`,
  },
  {
    name: "books title btree",
    ddl: `create index if not exists books_title_btree_idx
          on books (title);`,
  },
  {
    name: "books author btree",
    ddl: `create index if not exists books_author_btree_idx
          on books (author);`,
  },
  {
    name: "books genre btree",
    ddl: `create index if not exists books_genre_btree_idx
          on books (genre);`,
  },
  {
    name: "books available_copies",
    ddl: `create index if not exists books_available_copies_idx
          on books (available_copies);`,
  },
  {
    name: "books title trigram",
    ddl: `create index if not exists books_title_trgm_idx
          on books using gin (title gin_trgm_ops);`,
  },
  {
    name: "books author trigram",
    ddl: `create index if not exists books_author_trgm_idx
          on books using gin (author gin_trgm_ops);`,
  },
  {
    name: "borrow_records user + created_at",
    ddl: `create index if not exists borrow_records_user_created_idx
          on borrow_records (user_id, created_at desc);`,
  },
  {
    name: "borrow_records book + created_at",
    ddl: `create index if not exists borrow_records_book_created_idx
          on borrow_records (book_id, created_at desc);`,
  },
  {
    name: "borrow_records status + created_at",
    ddl: `create index if not exists borrow_records_status_created_idx
          on borrow_records (status, created_at desc);`,
  },
  {
    name: "borrow_records due_date",
    ddl: `create index if not exists borrow_records_due_date_idx
          on borrow_records (due_date);`,
  },
  {
    name: "users status",
    ddl: `create index if not exists users_status_idx
          on users (status);`,
  },
  {
    name: "users role",
    ddl: `create index if not exists users_role_idx
          on users (role);`,
  },
];

async function main() {
  const { db } = await import("@/database/drizzle");
  console.log("Adding performance indexes...");

  for (const statement of statements) {
    const started = Date.now();
    try {
      await db.execute(sql.raw(statement.ddl));
      const ms = Date.now() - started;
      console.log(`  + ${statement.name} (${ms}ms)`);
    } catch (error) {
      console.error(`  x ${statement.name}`);
      throw error;
    }
  }

  console.log("Done. Performance indexes are in place.");
}

main()
  .then(() => process.exit(0))
  .catch((error) => {
    console.error("Failed to add performance indexes:", error);
    process.exit(1);
  });
