import { sql } from "drizzle-orm";
import {
  pgEnum,
  pgTable,
  varchar,
  integer,
  text,
  date,
  boolean,
  numeric,
  timestamp,
} from "drizzle-orm/pg-core";

export const userStatusEnum = pgEnum("user_status", [
  "PENDING",
  "APPROVED",
  "REJECTED",
]);
export const userRoleEnum = pgEnum("user_role", ["USER", "ADMIN"]);
export const borrowStatusEnum = pgEnum("borrow_status", [
  "PENDING",
  "BORROWED",
  "RETURNED",
]);
export const requestStatusEnum = pgEnum("request_status", [
  "PENDING",
  "APPROVED",
  "REJECTED",
]);
export const notificationTypeEnum = pgEnum("notification_type", [
  "INFO",
  "SUCCESS",
  "WARNING",
  "ERROR",
]);

export const users = pgTable("users", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  fullName: varchar("full_name", { length: 255 }).notNull(),
  email: varchar("email", { length: 255 }).notNull().unique(),
  universityId: integer("university_id").notNull().unique(),
  password: text("password").notNull(),
  universityCard: text("university_card").notNull(),
  status: userStatusEnum("status")
    .notNull()
    .default("PENDING"),
  role: userRoleEnum("role").notNull().default("USER"),
  lastActivityDate: date("last_activity_date", { mode: "string" }).default(
    sql`CURRENT_DATE`
  ),
  lastLogin: timestamp("last_login", { mode: "date" }),
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

export const books = pgTable("books", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  title: varchar("title", { length: 255 }).notNull(),
  author: varchar("author", { length: 255 }).notNull(),
  genre: text("genre").notNull(),
  rating: integer("rating").notNull(),
  coverUrl: text("cover_url").notNull(),
  coverColor: varchar("cover_color", { length: 7 }).notNull(),
  description: text("description").notNull(),
  totalCopies: integer("total_copies").notNull().default(1),
  availableCopies: integer("available_copies").notNull().default(0),
  videoUrl: text("video_url").notNull(),
  summary: text("summary").notNull(),
  isbn: varchar("isbn", { length: 20 }),
  publicationYear: integer("publication_year"),
  publisher: varchar("publisher", { length: 255 }),
  language: varchar("language", { length: 50 }).default("English"),
  pageCount: integer("page_count"),
  edition: varchar("edition", { length: 50 }),
  isActive: boolean("is_active").notNull().default(true),
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
  updatedBy: varchar("updated_by", { length: 36 }).references(() => users.id),
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

export const borrowRecords = pgTable("borrow_records", {
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
  borrowDate: timestamp("borrow_date", { mode: "date" }).notNull().defaultNow(),
  dueDate: date("due_date", { mode: "string" }),
  returnDate: date("return_date", { mode: "string" }),
  status: borrowStatusEnum("status")
    .notNull()
    .default("BORROWED"),
  borrowedBy: text("borrowed_by"),
  returnedBy: text("returned_by"),
  fineAmount: numeric("fine_amount", { precision: 10, scale: 2 }).default(
    "0.00"
  ),
  notes: text("notes"),
  renewalCount: integer("renewal_count").notNull().default(0),
  lastReminderSent: timestamp("last_reminder_sent", { mode: "date" }),
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
  updatedBy: text("updated_by"),
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

export const systemConfig = pgTable("system_config", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  key: varchar("key", { length: 100 }).notNull().unique(),
  value: text("value").notNull(),
  description: text("description"),
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
  updatedBy: text("updated_by"),
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

export const bookReviews = pgTable("book_reviews", {
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
  rating: integer("rating").notNull(),
  comment: text("comment").notNull(),
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
});

export const adminRequests = pgTable("admin_requests", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  requestReason: text("request_reason").notNull(),
  status: requestStatusEnum("status")
    .notNull()
    .default("PENDING"),
  reviewedBy: varchar("reviewed_by", { length: 36 }).references(() => users.id),
  reviewedAt: timestamp("reviewed_at", { mode: "date" }),
  rejectionReason: text("rejection_reason"),
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
});

export const auditLogs = pgTable("audit_logs", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  action: varchar("action", { length: 100 }).notNull(),
  targetId: varchar("target_id", { length: 36 }),
  targetType: varchar("target_type", { length: 50 }),
  details: text("details"),
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

export const renewalRequests = pgTable("renewal_requests", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  borrowRecordId: varchar("borrow_record_id", { length: 36 })
    .notNull()
    .references(() => borrowRecords.id),
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  status: requestStatusEnum("status")
    .notNull()
    .default("PENDING"),
  requestReason: text("request_reason"),
  rejectionReason: text("rejection_reason"),
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
});

export const notifications = pgTable("notifications", {
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  title: varchar("title", { length: 255 }).notNull(),
  message: text("message").notNull(),
  type: notificationTypeEnum("type").notNull().default("INFO"),
  isRead: boolean("is_read").notNull().default(false),
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

