/**
 * Global Type Definitions
 * 
 * This file contains the shared TypeScript interfaces and types used across the entire 
 * application (Client, Server, and Database layers).
 * 
 * Centralizing these types ensures:
 * - Structural consistency when passing data between components and API routes.
 * - Improved developer experience with accurate Intellisense.
 * - Reduced redundancy by avoiding local re-definitions of core entities.
 */

/**
 * Extended user information stored in the NextAuth session.
 * Used for role-based access control (RBAC) and profile display.
 */
interface SessionUser {
  /** Unique user identifier (UUID). */
  id?: string;
  /** Full name of the user. */
  name?: string | null;
  /** Primary email address. */
  email?: string | null;
  /** System role (USER or ADMIN) determining permission levels. */
  role?: string;
  /** Account verification status. */
  status?: "PENDING" | "APPROVED" | "REJECTED";
  /** Unique university identification number. */
  universityId?: number;
}

/**
 * Standard NextAuth session object.
 */
interface Session {
  user?: SessionUser;
  /** ISO timestamp of session expiration. */
  expires: string;
}

/**
 * Represents a Book entity in the system.
 * Matches the 'books' table schema in the database.
 */
interface Book {
  /** Unique identifier for the book (UUID). */
  id: string;
  /** Official title. */
  title: string;
  /** Author name(s). */
  author: string;
  /** Literary genre or category. */
  genre: string;
  /** Average star rating (1-5). */
  rating: number;
  /** Total physical copies owned by the library. */
  totalCopies: number;
  /** Number of copies currently available on the shelf. */
  availableCopies: number;
  /** Long-form description or abstract. */
  description: string;
  /** Hex code for UI placeholders and themes. */
  coverColor: string;
  /** URL to the hosted cover image. */
  coverUrl: string;
  /** URL to a video trailer or related media. */
  videoUrl: string;
  /** One-line summary or tagline. */
  summary: string;
  /** International Standard Book Number. */
  isbn?: string | null;
  /** Year of publication. */
  publicationYear?: number | null;
  /** Publishing house. */
  publisher?: string | null;
  /** Primary language of the text. */
  language?: string | null;
  /** Total page count. */
  pageCount?: number | null;
  /** Specific edition information. */
  edition?: string | null;
  /** Flag to soft-delete or hide the book from the public catalog. */
  isActive: boolean;
  /** Timestamp of the last record update. */
  updatedAt: Date | null;
  /** ID of the entity that last updated the record. */
  updatedBy?: string | null;
  /** Record creation timestamp. */
  createdAt: Date | null;
}

/**
 * Data required for user registration and authentication.
 */
interface AuthCredentials {
  fullName: string;
  email: string;
  password: string;
  universityId: number;
  universityCard: string;
}

/**
 * Parameters for creating or updating a book record.
 */
interface BookParams {
  title: string;
  author: string;
  genre: string;
  rating: number;
  coverUrl: string;
  coverColor: string;
  description: string;
  totalCopies: number;
  videoUrl: string;
  summary: string;
  isbn?: string;
  publicationYear?: number;
  publisher?: string;
  language?: string;
  pageCount?: number;
  edition?: string;
  isActive?: boolean;
}

/**
 * Parameters required to initiate a borrow request.
 */
interface BorrowBookParams {
  bookId: string;
  userId: string;
}

/**
 * Represents a loan transaction in the system.
 * Matches the 'borrow_records' table schema.
 */
interface BorrowRecord {
  /** Unique identifier for the borrow record (UUID). */
  id: string;
  /** ID of the student borrower. */
  userId: string;
  /** ID of the borrowed book. */
  bookId: string;
  /** Timestamp when the borrow was initiated. */
  borrowDate: Date;
  /** Expected return date (can be null for pending requests). */
  dueDate: Date | null;
  /** Actual return date. */
  returnDate?: Date | null;
  /** Current lifecycle status of the loan. */
  status: "PENDING" | "BORROWED" | "RETURNED";
  /** ID of the admin who authorized the borrow. */
  borrowedBy?: string | null;
  /** ID of the admin who processed the return. */
  returnedBy?: string | null;
  /** Accumulated fine amount (numeric value). */
  fineAmount: number;
  /** Internal or student notes. */
  notes?: string | null;
  /** Number of times the due date has been extended. */
  renewalCount: number;
  /** Timestamp of the last automated reminder email. */
  lastReminderSent?: Date | null;
  /** Timestamp of the last record update. */
  updatedAt: Date | null;
  /** ID of the entity that last updated the record. */
  updatedBy?: string | null;
  /** Record creation timestamp. */
  createdAt: Date | null;
}
