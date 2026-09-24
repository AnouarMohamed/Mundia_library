/**
 * Phase 4 Catalog Service Contract Definitions
 *
 * Defines canonical data contracts and client interfaces for the Catalog Service,
 * decoupling works, editions, authors, and contributors from the monolithic
 * Next.js database schema. Copy counts are a disposable Catalog projection;
 * Circulation remains authoritative for physical inventory.
 */

export interface Author {
  id: string;
  name: string;
  bio?: string;
}

export interface Work {
  workId: string;
  title: string;
  summary: string;
  description: string;
  genre: string;
  rating: number;
  authors: Author[];
}

export interface Edition {
  editionId: string;
  workId: string;
  title: string;
  isbn: string;
  publisher: string;
  publicationYear: number;
  language: string;
  pageCount: number;
  coverUrl: string | null;
  coverColor: string | null;
  videoUrl: string | null;
  totalCopies: number;
  availableCopies: number;
  isActive: boolean;
}

export interface CatalogSearchFilters {
  query?: string;
  genre?: string;
  authorId?: string;
  availableOnly?: boolean;
  minRating?: number;
  sortBy?: "title" | "rating" | "publicationYear";
  page?: number;
  limit?: number;
}

export interface CatalogSearchResult {
  editions: Edition[];
  total: number;
  page: number;
  totalPages: number;
}

export interface CatalogReview {
  reviewId: string;
  workId: string;
  rating: number;
  content: string;
  reviewerLabel: "Verified reader";
  createdAt: string;
  updatedAt: string;
}

export interface CatalogReviewPage {
  reviews: CatalogReview[];
  total: number;
  page: number;
  totalPages: number;
}

export interface CreateCatalogAuthorInput {
  contributorId: string;
  name: string;
  bio?: string | null;
}

export interface CreateCatalogWorkInput {
  workId: string;
  title: string;
  summary: string;
  description: string;
  genre: string;
  authors: CreateCatalogAuthorInput[];
  reason: string;
}

export interface CreateCatalogEditionInput {
  editionId: string;
  title: string;
  isbn: string;
  publisher: string;
  publicationYear: number;
  language: string;
  pageCount: number;
  coverUrl?: string | null;
  coverColor?: string | null;
  videoUrl?: string | null;
  isActive: boolean;
  reason: string;
}

export interface UpdateCatalogWorkInput {
  title: string;
  summary: string;
  description: string;
  genre: string;
  authors: CreateCatalogAuthorInput[];
  reason: string;
}

export interface UpdateCatalogEditionInput {
  title: string;
  isbn: string;
  publisher: string;
  publicationYear: number;
  language: string;
  pageCount: number;
  coverUrl?: string | null;
  coverColor?: string | null;
  videoUrl?: string | null;
  reason: string;
}

export interface SetCatalogEditionActiveInput {
  isActive: boolean;
  reason: string;
}

export interface CatalogCommandResult {
  aggregateType: "work" | "edition";
  aggregateId: string;
  aggregateVersion: number;
  occurredAt: string;
}

export interface CatalogService {
  getWork(workId: string): Promise<Work | null>;
  getEdition(editionId: string): Promise<Edition | null>;
  searchCatalog(filters: CatalogSearchFilters): Promise<CatalogSearchResult>;
  getDistinctGenres(): Promise<string[]>;
  getPublishedReviews(
    workId: string,
    page?: number,
    limit?: number
  ): Promise<CatalogReviewPage>;
  createWork(
    input: CreateCatalogWorkInput,
    idempotencyKey: string
  ): Promise<CatalogCommandResult>;
  createEdition(
    workId: string,
    input: CreateCatalogEditionInput,
    idempotencyKey: string
  ): Promise<CatalogCommandResult>;
  updateWork(
    workId: string,
    expectedVersion: number,
    input: UpdateCatalogWorkInput,
    idempotencyKey: string
  ): Promise<CatalogCommandResult>;
  updateEdition(
    editionId: string,
    expectedVersion: number,
    input: UpdateCatalogEditionInput,
    idempotencyKey: string
  ): Promise<CatalogCommandResult>;
  setEditionActive(
    editionId: string,
    expectedVersion: number,
    input: SetCatalogEditionActiveInput,
    idempotencyKey: string
  ): Promise<CatalogCommandResult>;
}
