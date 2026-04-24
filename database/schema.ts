import { sql } from "drizzle-orm";
import {
  mysqlTable,
  varchar,
  int,
  text,
  date,
  datetime,
  boolean,
  decimal,
  mysqlEnum,
} from "drizzle-orm/mysql-core";

export const users = mysqlTable("users", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  fullName: varchar("full_name", { length: 255 }).notNull(),
  email: varchar("email", { length: 255 }).notNull().unique(),
  universityId: int("university_id").notNull().unique(),
  password: text("password").notNull(),
  universityCard: text("university_card").notNull(),
  status: mysqlEnum("status", ["PENDING", "APPROVED", "REJECTED"])
    .notNull()
    .default("PENDING"),
  role: mysqlEnum("role", ["USER", "ADMIN"]).notNull().default("USER"),
  lastActivityDate: date("last_activity_date", { mode: "string" }).default(
    sql`(CURRENT_DATE)`
  ),
  lastLogin: datetime("last_login", { mode: "date" }),
  createdAt: datetime("created_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
});

export const books = mysqlTable("books", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  title: varchar("title", { length: 255 }).notNull(),
  author: varchar("author", { length: 255 }).notNull(),
  genre: text("genre").notNull(),
  rating: int("rating").notNull(),
  coverUrl: text("cover_url").notNull(),
  coverColor: varchar("cover_color", { length: 7 }).notNull(),
  description: text("description").notNull(),
  totalCopies: int("total_copies").notNull().default(1),
  availableCopies: int("available_copies").notNull().default(0),
  videoUrl: text("video_url").notNull(),
  summary: text("summary").notNull(),
  isbn: varchar("isbn", { length: 20 }),
  publicationYear: int("publication_year"),
  publisher: varchar("publisher", { length: 255 }),
  language: varchar("language", { length: 50 }).default("English"),
  pageCount: int("page_count"),
  edition: varchar("edition", { length: 50 }),
  isActive: boolean("is_active").notNull().default(true),
  updatedAt: datetime("updated_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
  updatedBy: varchar("updated_by", { length: 36 }).references(() => users.id),
  createdAt: datetime("created_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
});

export const borrowRecords = mysqlTable("borrow_records", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  bookId: varchar("book_id", { length: 36 })
    .notNull()
    .references(() => books.id),
  borrowDate: datetime("borrow_date", { mode: "date" }).notNull().default(sql`CURRENT_TIMESTAMP`),
  dueDate: date("due_date", { mode: "string" }),
  returnDate: date("return_date", { mode: "string" }),
  status: mysqlEnum("status", ["PENDING", "BORROWED", "RETURNED"])
    .notNull()
    .default("BORROWED"),
  borrowedBy: text("borrowed_by"),
  returnedBy: text("returned_by"),
  fineAmount: decimal("fine_amount", { precision: 10, scale: 2 }).default(
    "0.00"
  ),
  notes: text("notes"),
  renewalCount: int("renewal_count").notNull().default(0),
  lastReminderSent: datetime("last_reminder_sent", { mode: "date" }),
  updatedAt: datetime("updated_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
  updatedBy: text("updated_by"),
  createdAt: datetime("created_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
});

export const systemConfig = mysqlTable("system_config", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  key: varchar("key", { length: 100 }).notNull().unique(),
  value: text("value").notNull(),
  description: text("description"),
  updatedAt: datetime("updated_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
  updatedBy: text("updated_by"),
  createdAt: datetime("created_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
});

export const bookReviews = mysqlTable("book_reviews", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  bookId: varchar("book_id", { length: 36 })
    .notNull()
    .references(() => books.id),
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  rating: int("rating").notNull(),
  comment: text("comment").notNull(),
  createdAt: datetime("created_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
  updatedAt: datetime("updated_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
});

export const adminRequests = mysqlTable("admin_requests", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  requestReason: text("request_reason").notNull(),
  status: mysqlEnum("status", ["PENDING", "APPROVED", "REJECTED"])
    .notNull()
    .default("PENDING"),
  reviewedBy: varchar("reviewed_by", { length: 36 }).references(() => users.id),
  reviewedAt: datetime("reviewed_at", { mode: "date" }),
  rejectionReason: text("rejection_reason"),
  createdAt: datetime("created_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
  updatedAt: datetime("updated_at", { mode: "date" }).default(sql`CURRENT_TIMESTAMP`),
});


