import { z } from "zod";

/**
 * Validation schema for the student registration (sign-up) form.
 * 
 * Requirements:
 * - fullName: Required, 3-255 characters.
 * - email: Required, valid email format.
 * - password: Required, minimum 8 characters.
 * - universityId: Required, numeric ID (up to 8 digits).
 * - universityCard: Optional, URL to the ID card image.
 */
export const signUpSchema = z.object({
  /** Student's full legal name. */
  fullName: z
    .string()
    .min(1, "Full name is required")
    .min(3, "Full name must be at least 3 characters")
    .max(255, "Full name must be less than 255 characters"),
  /** Unique university email address. */
  email: z
    .string()
    .min(1, "Email is required")
    .email("Please enter a valid email address"),
  /** Account password. Minimum 8 characters for security. */
  password: z
    .string()
    .min(1, "Password is required")
    .min(8, "Password must be at least 8 characters"),
  /** Numeric university ID. Preprocessed to handle empty string inputs. */
  universityId: z.preprocess(
    (val) => {
      // Convert empty string, null, or undefined to undefined
      if (val === "" || val === null || val === undefined) {
        return undefined;
      }
      return val;
    },
    z.coerce
      .number({
        required_error: "University ID is required",
        invalid_type_error: "University ID must be a number",
      })
      .int("University ID must be a whole number (no decimals)")
      .min(1, "University ID must be a positive number")
      .max(
        99999999,
        "University ID is too large. Maximum allowed 8-digit number"
      )
  ),
  /** URL or reference to the uploaded university card image. */
  universityCard: z
    .string()
    .optional()
    .or(z.literal("")),
});

/**
 * Validation schema for the student login (sign-in) form.
 */
export const signInSchema = z.object({
  /** User email. */
  email: z.string().email(),
  /** User password. */
  password: z.string().min(8),
});

/**
 * Validation schema for creating or updating a book record.
 * 
 * This schema covers core bibliographic data and inventory tracking.
 */
export const bookSchema = z.object({
  /** Official title of the book. */
  title: z
    .string()
    .trim()
    .min(1, "Book title is required")
    .min(2, "Book title must be at least 2 characters")
    .max(100, "Book title must be less than 100 characters"),
  /** Long-form description or abstract. */
  description: z
    .string()
    .trim()
    .min(1, "Book description is required")
    .min(10, "Book description must be at least 10 characters")
    .max(1000, "Book description must be less than 1000 characters"),
  /** Primary author or authors. */
  author: z
    .string()
    .trim()
    .min(1, "Author name is required")
    .min(2, "Author name must be at least 2 characters")
    .max(100, "Author name must be less than 100 characters"),
  /** Category or genre (e.g., "Computer Science", "Fiction"). */
  genre: z
    .string()
    .trim()
    .min(1, "Genre is required")
    .min(2, "Genre must be at least 2 characters")
    .max(50, "Genre must be less than 50 characters"),
  /** Average star rating (1-5). */
  rating: z.coerce
    .number({
      required_error: "Rating is required",
      invalid_type_error: "Rating must be a number",
    })
    .int("Rating must be a whole number")
    .min(1, "Rating must be at least 1 star")
    .max(5, "Rating cannot exceed 5 stars"),
  /** Total number of copies owned by the library. */
  totalCopies: z.coerce
    .number({
      required_error: "Total copies is required",
      invalid_type_error: "Total copies must be a number",
    })
    .int("Total copies must be a whole number")
    .positive("Total copies must be a positive number")
    .lte(10000, "Total copies cannot exceed 10,000"),
  /** URL to the hosted cover image. */
  coverUrl: z
    .string()
    .min(1, "Book cover image is required. Please upload a cover image."),
  /** Hex code color used for UI theming based on the cover. */
  coverColor: z
    .string()
    .trim()
    .min(1, "Primary color is required")
    .regex(
      /^#[0-9A-F]{6}$/i,
      "Primary color must be a valid hex color (e.g., #FF5733)"
    ),
  /** URL to a video trailer or introduction. */
  videoUrl: z
    .string()
    .min(1, "Book trailer is required. Please upload a book trailer video."),
  /** One-line summary of the book. */
  summary: z
    .string()
    .trim()
    .min(1, "Book summary is required")
    .min(10, "Book summary must be at least 10 characters"),
  /** International Standard Book Number. */
  isbn: z
    .string()
    .trim()
    .max(20, "ISBN must be less than 20 characters")
    .optional(),
  /** Year of publication (1000 - Current Year). */
  publicationYear: z.coerce
    .number({
      invalid_type_error: "Publication year must be a number",
    })
    .int("Publication year must be a whole number")
    .min(1000, "Publication year must be at least 1000")
    .max(
      new Date().getFullYear(),
      `Publication year cannot exceed ${new Date().getFullYear()}`
    )
    .optional(),
  /** Publishing house. */
  publisher: z
    .string()
    .trim()
    .max(255, "Publisher name must be less than 255 characters")
    .optional(),
  /** Language of the text. */
  language: z
    .string()
    .trim()
    .max(50, "Language must be less than 50 characters")
    .optional(),
  /** Total number of pages. */
  pageCount: z.coerce
    .number({
      invalid_type_error: "Page count must be a number",
    })
    .int("Page count must be a whole number")
    .positive("Page count must be a positive number")
    .optional(),
  /** Book edition. */
  edition: z
    .string()
    .trim()
    .max(50, "Edition must be less than 50 characters")
    .optional(),
  /** Flag to determine if the book is visible in the public catalog. */
  isActive: z.boolean().optional(),
});
