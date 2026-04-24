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
    name: "books is_active + rating + created_at",
    ddl: `create index books_is_active_rating_created_idx
          on books (is_active, rating, created_at);`,
  },
  {
    name: "books is_active + genre + rating + created_at",
    ddl: `create index books_is_active_genre_rating_created_idx
          on books (is_active, genre(120), rating, created_at);`,
  },
  {
    name: "books title btree",
    ddl: `create index books_title_btree_idx
          on books (title);`,
  },
  {
    name: "books author btree",
    ddl: `create index books_author_btree_idx
          on books (author);`,
  },
  {
    name: "books genre btree",
    ddl: `create index books_genre_btree_idx
          on books (genre);`,
  },
  {
    name: "books available_copies",
    ddl: `create index books_available_copies_idx
          on books (available_copies);`,
  },
  {
    name: "borrow_records user + created_at",
    ddl: `create index borrow_records_user_created_idx
          on borrow_records (user_id, created_at);`,
  },
  {
    name: "borrow_records book + created_at",
    ddl: `create index borrow_records_book_created_idx
          on borrow_records (book_id, created_at);`,
  },
  {
    name: "borrow_records status + created_at",
    ddl: `create index borrow_records_status_created_idx
          on borrow_records (status, created_at);`,
  },
  {
    name: "borrow_records user + status + created_at",
    ddl: `create index borrow_records_user_status_created_idx
          on borrow_records (user_id, status, created_at);`,
  },
  {
    name: "borrow_records status + due_date + created_at",
    ddl: `create index borrow_records_status_due_created_idx
          on borrow_records (status, due_date, created_at);`,
  },
  {
    name: "borrow_records due_date",
    ddl: `create index borrow_records_due_date_idx
          on borrow_records (due_date);`,
  },
  {
    name: "book_reviews book + created_at",
    ddl: `create index book_reviews_book_created_idx
          on book_reviews (book_id, created_at);`,
  },
  {
    name: "admin_requests status + created_at",
    ddl: `create index admin_requests_status_created_idx
          on admin_requests (status, created_at);`,
  },
  {
    name: "users status",
    ddl: `create index users_status_idx
          on users (status);`,
  },
  {
    name: "users role",
    ddl: `create index users_role_idx
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
      const message =
        error instanceof Error ? error.message.toLowerCase() : "";
      if (message.includes("duplicate key name") || message.includes("exists")) {
        console.log(`  - ${statement.name} (already exists)`);
        continue;
      }
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
