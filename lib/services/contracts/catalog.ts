/**
 * Phase 4 Catalog Service Contract Definitions
 *
 * Defines canonical data contracts and client interfaces for the Catalog Service,
 * decoupling works, editions, authors, contributors, and physical inventory availability
 * from the monolithic Next.js database schema.
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

export interface CatalogService {
  getEdition(editionId: string): Promise<Edition | null>;
  searchCatalog(filters: CatalogSearchFilters): Promise<CatalogSearchResult>;
  getDistinctGenres(): Promise<string[]>;
}
