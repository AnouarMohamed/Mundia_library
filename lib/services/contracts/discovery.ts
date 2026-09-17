/**
 * Phase 5 Discovery Service Contract Definitions
 *
 * Defines canonical data contracts and client interfaces for the Discovery Service,
 * decoupling OpenSearch catalog projections, full-text availability search, and recommendation
 * read-models from the monolithic Next.js backend.
 */

export interface CatalogIndexDocument {
  editionId: string;
  workId: string;
  title: string;
  authorNames: string[];
  genre: string;
  isbn: string;
  description: string;
  rating: number;
  totalCopies: number;
  availableCopies: number;
  isAvailable: boolean;
  publishedYear: number;
  updatedAt: string;
}

export interface SearchFacetBucket {
  key: string;
  count: number;
}

export interface SearchFacets {
  genres: SearchFacetBucket[];
  authors: SearchFacetBucket[];
  availability: SearchFacetBucket[];
}

export interface OpenSearchQueryResult {
  documents: CatalogIndexDocument[];
  total: number;
  tookMs: number;
  facets?: SearchFacets;
}

export interface DiscoveryServiceContract {
  searchIndex(query: string, filters?: Record<string, unknown>): Promise<OpenSearchQueryResult>;
  rebuildEditionProjection(editionId: string): Promise<{ indexed: boolean }>;
}
