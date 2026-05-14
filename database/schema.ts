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

/**
 * Enumeration of possible user account statuses.
 * - PENDING: Initial state after registration, awaiting approval.
 * - APPROVED: Active account allowed to use the library.
 * - REJECTED: Account denied by an administrator.
 */
export const userStatusEnum = pgEnum("user_status", [
  "PENDING",
  "APPROVED",
  "REJECTED",
]);

/**
 * Enumeration of user roles within the system.
 * - USER: Standard student access (borrow, return, review).
 * - ADMIN: Administrative access (approve requests, manage catalog, analytics).
 */
export const userRoleEnum = pgEnum("user_role", ["USER", "ADMIN"]);

/**
 * Enumeration of borrow record statuses.
 * - PENDING: Request submitted by a user, awaiting admin approval.
 * - BORROWED: Request approved, book is currently with the user.
 * - RETURNED: Book has been successfully returned.
 */
export const borrowStatusEnum = pgEnum("borrow_status", [
  "PENDING",
  "BORROWED",
  "RETURNED",
]);

/**
 * Enumeration for generic administrative request statuses (e.g., admin role requests, renewal requests).
 * - PENDING: Initial state of the request.
 * - APPROVED: Request granted by an administrator.
 * - REJECTED: Request denied by an administrator.
 */
export const requestStatusEnum = pgEnum("request_status", [
  "PENDING",
  "APPROVED",
  "REJECTED",
]);

/**
 * Enumeration of notification types for UI display and categorization.
 */
export const notificationTypeEnum = pgEnum("notification_type", [
  "INFO",
  "SUCCESS",
  "WARNING",
  "ERROR",
]);

/**
 * Users table storing both student and administrator accounts.
 */
export const users = pgTable("users", {
  /** Unique identifier for the user (UUID v4). */
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  /** Full legal name of the user. */
  fullName: varchar("full_name", { length: 255 }).notNull(),
  /** Email address used for login and notifications. */
  email: varchar("email", { length: 255 }).notNull().unique(),
  /** Unique university identification number. */
  universityId: integer("university_id").notNull().unique(),
  /** Hashed password. */
  password: text("password").notNull(),
  /** URL or reference to the user's university ID card image. */
  universityCard: text("university_card").notNull(),
  /** Current account approval status. */
  status: userStatusEnum("status")
    .notNull()
    .default("PENDING"),
  /** Assigned role determining access levels. */
  role: userRoleEnum("role").notNull().default("USER"),
  /** Date of the user's last interaction with the platform. */
  lastActivityDate: date("last_activity_date", { mode: "string" }).default(
    sql`CURRENT_DATE`
  ),
  /** Timestamp of the last successful login. */
  lastLogin: timestamp("last_login", { mode: "date" }),
  /** Timestamp when the user record was created. */
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

/**
 * Books table containing the library's catalog.
 */
export const books = pgTable("books", {
  /** Unique identifier for the book (UUID v4). */
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  /** Title of the book. */
  title: varchar("title", { length: 255 }).notNull(),
  /** Author(s) of the book. */
  author: varchar("author", { length: 255 }).notNull(),
  /** Genre(s) or category of the book. */
  genre: text("genre").notNull(),
  /** Average user rating (stored as integer for simplicity, e.g., 1-5). */
  rating: integer("rating").notNull(),
  /** URL to the book's cover image. */
  coverUrl: text("cover_url").notNull(),
  /** Dominant color of the cover for UI placeholder/theming. */
  coverColor: varchar("cover_color", { length: 7 }).notNull(),
  /** Long-form description or blurb of the book. */
  description: text("description").notNull(),
  /** Total number of physical copies owned by the library. */
  totalCopies: integer("total_copies").notNull().default(1),
  /** Number of copies currently available on the shelf. */
  availableCopies: integer("available_copies").notNull().default(0),
  /** URL to a video trailer or related content. */
  videoUrl: text("video_url").notNull(),
  /** Short summary or tagline of the book. */
  summary: text("summary").notNull(),
  /** International Standard Book Number. */
  isbn: varchar("isbn", { length: 20 }),
  /** Year of publication. */
  publicationYear: integer("publication_year"),
  /** Publishing house. */
  publisher: varchar("publisher", { length: 255 }),
  /** Primary language of the text. */
  language: varchar("language", { length: 50 }).default("English"),
  /** Total number of pages. */
  pageCount: integer("page_count"),
  /** Edition information (e.g., "3rd Edition"). */
  edition: varchar("edition", { length: 50 }),
  /** Flag to soft-delete or hide books from the catalog. */
  isActive: boolean("is_active").notNull().default(true),
  /** Timestamp of the last update to the book record. */
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
  /** ID of the user (admin) who last updated the record. */
  updatedBy: varchar("updated_by", { length: 36 }).references(() => users.id),
  /** Timestamp when the book was added to the catalog. */
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

/**
 * Borrow records tracking the lifecycle of a book loan.
 */
export const borrowRecords = pgTable("borrow_records", {
  /** Unique identifier for the borrow record (UUID v4). */
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  /** ID of the user who borrowed the book. */
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  /** ID of the book being borrowed. */
  bookId: varchar("book_id", { length: 36 })
    .notNull()
    .references(() => books.id),
  /** Timestamp when the borrow request was initially made or approved. */
  borrowDate: timestamp("borrow_date", { mode: "date" }).notNull().defaultNow(),
  /** Expected return date. */
  dueDate: date("due_date", { mode: "string" }),
  /** Actual return date. */
  returnDate: date("return_date", { mode: "string" }),
  /** Current status of the borrow lifecycle. */
  status: borrowStatusEnum("status")
    .notNull()
    .default("BORROWED"),
  /** Name or ID of the person who authorized the borrow (if applicable). */
  borrowedBy: text("borrowed_by"),
  /** Name or ID of the person who processed the return (if applicable). */
  returnedBy: text("returned_by"),
  /** Accumulated fine amount for overdue returns. */
  fineAmount: numeric("fine_amount", { precision: 10, scale: 2 }).default(
    "0.00"
  ),
  /** Administrative or student notes regarding the loan. */
  notes: text("notes"),
  /** Number of times the due date has been extended. */
  renewalCount: integer("renewal_count").notNull().default(0),
  /** Timestamp when the last automated reminder email was sent. */
  lastReminderSent: timestamp("last_reminder_sent", { mode: "date" }),
  /** Timestamp of the last record update. */
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
  /** Identifier for the person/system that last updated the record. */
  updatedBy: text("updated_by"),
  /** Timestamp when the record was created. */
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

/**
 * System configuration table for storing application-wide settings (e.g., fine rates).
 */
export const systemConfig = pgTable("system_config", {
  /** Unique identifier for the config entry. */
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  /** Configuration key (e.g., "DAILY_FINE_RATE"). */
  key: varchar("key", { length: 100 }).notNull().unique(),
  /** Serialized configuration value. */
  value: text("value").notNull(),
  /** Human-readable description of what this setting controls. */
  description: text("description"),
  /** Last update timestamp. */
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
  /** Identifier for who last changed the setting. */
  updatedBy: text("updated_by"),
  /** Creation timestamp. */
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

/**
 * User-submitted reviews for books.
 */
export const bookReviews = pgTable("book_reviews", {
  /** Unique identifier for the review. */
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  /** ID of the book being reviewed. */
  bookId: varchar("book_id", { length: 36 })
    .notNull()
    .references(() => books.id),
  /** ID of the user who wrote the review. */
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  /** Star rating (e.g., 1-5). */
  rating: integer("rating").notNull(),
  /** Detailed written feedback. */
  comment: text("comment").notNull(),
  /** Creation timestamp. */
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
  /** Last update timestamp. */
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
});

/**
 * Requests for administrative privileges or account status changes.
 */
export const adminRequests = pgTable("admin_requests", {
  /** Unique identifier for the request. */
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  /** ID of the user making the request. */
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  /** Reason provided by the user for why they need admin access. */
  requestReason: text("request_reason").notNull(),
  /** Current status of the request. */
  status: requestStatusEnum("status")
    .notNull()
    .default("PENDING"),
  /** ID of the administrator who reviewed this request. */
  reviewedBy: varchar("reviewed_by", { length: 36 }).references(() => users.id),
  /** Timestamp of when the review occurred. */
  reviewedAt: timestamp("reviewed_at", { mode: "date" }),
  /** Feedback provided by the reviewer if the request was rejected. */
  rejectionReason: text("rejection_reason"),
  /** Creation timestamp. */
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
  /** Last update timestamp. */
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
});

/**
 * Audit logs tracking sensitive or critical actions performed in the system.
 */
export const auditLogs = pgTable("audit_logs", {
  /** Unique identifier for the log entry. */
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  /** ID of the user who performed the action. */
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  /** Short name of the action performed (e.g., "DELETE_BOOK"). */
  action: varchar("action", { length: 100 }).notNull(),
  /** ID of the entity affected by the action (if applicable). */
  targetId: varchar("target_id", { length: 36 }),
  /** Type of the entity affected (e.g., "BOOK", "USER"). */
  targetType: varchar("target_type", { length: 50 }),
  /** Detailed JSON-serialized information about the change. */
  details: text("details"),
  /** Timestamp of the action. */
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

/**
 * Requests from users to extend the due date of a borrowed book.
 */
export const renewalRequests = pgTable("renewal_requests", {
  /** Unique identifier for the renewal request. */
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  /** ID of the original borrow record. */
  borrowRecordId: varchar("borrow_record_id", { length: 36 })
    .notNull()
    .references(() => borrowRecords.id),
  /** ID of the user requesting the renewal. */
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  /** Status of the renewal request. */
  status: requestStatusEnum("status")
    .notNull()
    .default("PENDING"),
  /** Reason provided by the student for needing more time. */
  requestReason: text("request_reason"),
  /** Feedback from the admin if the renewal was denied. */
  rejectionReason: text("rejection_reason"),
  /** Creation timestamp. */
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
  /** Last update timestamp. */
  updatedAt: timestamp("updated_at", { mode: "date" }).defaultNow(),
});

/**
 * In-app notifications sent to users regarding their account or loans.
 */
export const notifications = pgTable("notifications", {
  /** Unique identifier for the notification. */
  id: varchar("id", { length: 36 })
    .notNull()
    .primaryKey()
    .$defaultFn(() => globalThis.crypto.randomUUID()),
  /** Recipient of the notification. */
  userId: varchar("user_id", { length: 36 })
    .notNull()
    .references(() => users.id),
  /** Brief title of the notification. */
  title: varchar("title", { length: 255 }).notNull(),
  /** Detailed notification message. */
  message: text("message").notNull(),
  /** Categorization for UI styling (INFO, SUCCESS, etc.). */
  type: notificationTypeEnum("type").notNull().default("INFO"),
  /** Flag indicating if the user has seen the notification. */
  isRead: boolean("is_read").notNull().default(false),
  /** Creation timestamp. */
  createdAt: timestamp("created_at", { mode: "date" }).defaultNow(),
});

