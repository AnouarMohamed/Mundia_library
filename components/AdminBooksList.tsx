/**
 * AdminBooksList Component
 * 
 * A robust administrative dashboard component for managing the library's book catalog.
 * It provides a detailed list view with advanced filtering, searching, and 
 * high-level inventory statistics for each book.
 * 
 * Features:
 * - Responsive grid and list layouts for book catalog.
 * - Multi-criteria filtering (search, genre, availability).
 * - Real-time inventory status indicators (Available, Unavailable, Inactive).
 * - Debounced search integration with URL parameters for shareable states.
 * - Seamless integration with TanStack Query and SSR initial data.
 * 
 * Type: Client Component
 */

"use client";

import React, { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import Link from "next/link";
import BookCover from "@/components/BookCover";
import { useAllBooks } from "@/hooks/useQueries";
import { getBookGenres } from "@/lib/services/books";
import BookCardSkeleton from "@/components/skeletons/BookCardSkeleton";
import type { BookFilters } from "@/lib/services/books";

/**
 * Props for the AdminBooksList component.
 */
interface AdminBooksListProps {
  /** Initial books data from SSR (prevents duplicate fetch and loading flickers). */
  initialBooks?: Book[];
}

const AdminBooksList: React.FC<AdminBooksListProps> = ({ initialBooks }) => {
  const router = useRouter();
  const searchParamsHook = useSearchParams();
  const queryClient = useQueryClient();

  // URL State: Extract current filters from URL parameters
  const currentSearch = searchParamsHook.get("search") || "";
  const currentGenre = searchParamsHook.get("genre") || "all";
  const currentAvailability = searchParamsHook.get("availability") || "all";

  // Local State: Track UI-only states like pending search input and available genres
  const [localSearch, setLocalSearch] = useState(currentSearch);
  const [genres, setGenres] = useState<string[]>([]);
  const lastSyncedSearchRef = React.useRef(currentSearch);
  const searchInputId = React.useId();
  const genreFilterId = React.useId();
  const availabilityFilterId = React.useId();

  /**
   * Effect: Sync local search state with URL params.
   * Ensures that external URL changes (back button, manual edits) are reflected in the input field.
   */
  React.useEffect(() => {
    if (
      currentSearch !== lastSyncedSearchRef.current &&
      localSearch === lastSyncedSearchRef.current
    ) {
      setLocalSearch(currentSearch);
      lastSyncedSearchRef.current = currentSearch;
    }
  }, [currentSearch, localSearch]);

  /**
   * Effect: Fetch unique genres from the database for the filter dropdown.
   */
  React.useEffect(() => {
    getBookGenres()
      .then((genresList) => setGenres(genresList))
      .catch((error) => console.error("Error fetching genres:", error));
  }, []);

  /**
   * Effect: Debounced URL update for search.
   * Updates the URL search parameter after the user stops typing for 300ms.
   */
  React.useEffect(() => {
    const timer = setTimeout(() => {
      if (localSearch !== currentSearch) {
        const params = new URLSearchParams(searchParamsHook.toString());
        const trimmedSearch = localSearch.trim();

        if (trimmedSearch) {
          params.set("search", trimmedSearch);
        } else {
          params.delete("search");
        }

        const newUrl = `/admin/books?${params.toString()}`;
        lastSyncedSearchRef.current = trimmedSearch;
        queryClient.invalidateQueries({ queryKey: ["all-books"] });
        router.replace(newUrl, { scroll: false });
      }
    }, 300);

    return () => clearTimeout(timer);
  }, [localSearch, currentSearch, searchParamsHook, queryClient, router]);

  // Memoized filter configuration for the data query
  const filters: BookFilters = React.useMemo(
    () => ({
      search: currentSearch || undefined,
      genre: currentGenre !== "all" ? currentGenre : undefined,
      availability:
        currentAvailability !== "all"
          ? (currentAvailability as BookFilters["availability"])
          : undefined,
      limit: 1000, // High limit to ensure comprehensive results in the admin list
      page: 1,
    }),
    [currentSearch, currentGenre, currentAvailability],
  );

  const hasActiveFilters =
    currentSearch || currentGenre !== "all" || currentAvailability !== "all";

  // Wrap initial data in the expected structure for useAllBooks
  const initialBooksData =
    !hasActiveFilters && initialBooks
      ? {
          books: initialBooks,
          total: initialBooks.length,
          page: 1,
          totalPages: 1,
          limit: initialBooks.length,
        }
      : undefined;

  /**
   * Data Fetching: Primary query for the book catalog.
   */
  const {
    data: booksData,
    isLoading,
    isError,
    error,
  } = useAllBooks(filters, initialBooksData);

  // Final data source: Prefer fresh query data over SSR initial data
  const allBooks: Book[] = ((booksData?.books ?? initialBooks) || []) as Book[];

  /**
   * Updates multiple URL search parameters simultaneously and invalidates cache.
   */
  const updateSearchParams = (newParams: Record<string, string>) => {
    const params = new URLSearchParams(searchParamsHook.toString());

    Object.entries(newParams).forEach(([key, value]) => {
      if (value && value !== "all") {
        params.set(key, value);
      } else {
        params.delete(key);
      }
    });

    queryClient.invalidateQueries({ queryKey: ["all-books"] });
    router.replace(`/admin/books?${params.toString()}`, { scroll: false });
  };

  const handleFilterChange = (key: string, value: string) => {
    updateSearchParams({ [key]: value });
  };

  const clearFilters = () => {
    setLocalSearch("");
    router.push("/admin/books");
  };

  // Show skeleton while loading (only if no initial data)
  if (isLoading && (!initialBooks || initialBooks.length === 0)) {
    return (
      <section className="admin-page-panel">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold text-slate-900 sm:text-xl">
            All Books
          </h2>
          <Button
            className="bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)]"
            asChild
          >
            <Link href="/admin/books/new">Create book</Link>
          </Button>
        </div>

        <div className="mt-4 w-full overflow-hidden sm:mt-7">
          <div className="grid grid-cols-1 gap-4 sm:gap-6 md:grid-cols-2 lg:grid-cols-3">
            {[...Array(6)].map((_, i) => (
              <BookCardSkeleton key={`skeleton-${i}`} />
            ))}
          </div>
        </div>
      </section>
    );
  }

  // Show error state
  if (isError && (!initialBooks || initialBooks.length === 0)) {
    return (
      <section className="admin-page-panel">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold text-slate-900 sm:text-xl">
            All Books
          </h2>
          <Button
            className="bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)]"
            asChild
          >
            <Link href="/admin/books/new">Create book</Link>
          </Button>
        </div>

        <div className="mt-4 w-full overflow-hidden sm:mt-7">
          <div className="py-6 text-center sm:py-8">
            <p className="mb-2 text-base font-semibold text-[var(--mundia-danger)] sm:text-lg">
              Failed to load books
            </p>
            <p className="text-xs text-slate-600 sm:text-sm">
              {error instanceof Error
                ? error.message
                : "An unknown error occurred"}
            </p>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="admin-page-panel">
      <div className="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
        <div>
          <p className="text-sm text-[var(--mundia-muted)]">
            Catalog management
          </p>
          <h2 className="mt-1 font-serif text-2xl font-normal tracking-tight text-[var(--mundia-ink)]">
            Books ({allBooks.length})
          </h2>
        </div>

        <Button
          className="w-full rounded-lg bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)] sm:w-fit"
          asChild
        >
          <Link href="/admin/books/new">Create book</Link>
        </Button>
      </div>

      <div className="mt-5 grid gap-3 xl:grid-cols-[minmax(18rem,1fr)_auto_auto] xl:items-end">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const trimmedSearch = localSearch.trim();
            updateSearchParams({ search: trimmedSearch });
          }}
          className="min-w-0"
        >
          <label
            htmlFor={searchInputId}
            className="mb-1.5 block text-xs font-semibold text-slate-600"
          >
            Search
          </label>
          <Input
            id={searchInputId}
            type="text"
            placeholder="Title, author, ISBN"
            value={localSearch}
            onChange={(e) => setLocalSearch(e.target.value)}
            className="admin-field w-full"
          />
        </form>

        <div>
          <label
            htmlFor={genreFilterId}
            className="mb-1.5 block text-xs font-semibold text-slate-600"
          >
            Genre
          </label>
          <select
            id={genreFilterId}
            value={currentGenre}
            onChange={(e) => handleFilterChange("genre", e.target.value)}
            className="admin-field w-full xl:min-w-[180px]"
          >
            <option value="all">All genres</option>
            {genres.map((genre) => (
              <option key={genre} value={genre}>
                {genre}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label
            htmlFor={availabilityFilterId}
            className="mb-1.5 block text-xs font-semibold text-slate-600"
          >
            Availability
          </label>
          <select
            id={availabilityFilterId}
            value={currentAvailability}
            onChange={(e) => handleFilterChange("availability", e.target.value)}
            className="admin-field w-full xl:min-w-[180px]"
          >
            <option value="all">All statuses</option>
            <option value="available">Available</option>
            <option value="unavailable">Unavailable</option>
          </select>
        </div>
      </div>

      <div className="mt-6 w-full overflow-hidden">
        {allBooks.length === 0 ? (
          <div className="rounded-lg border border-dashed border-[var(--mundia-line)] py-10 text-center">
            <p className="mb-4 text-base font-medium text-slate-600">
              {hasActiveFilters
                ? "No books found matching your criteria."
                : "No books found. Create your first book."}
            </p>
            {hasActiveFilters && (
              <Button
                variant="outline"
                onClick={clearFilters}
                className="border-[var(--mundia-line)] text-slate-700 hover:bg-[var(--mundia-panel)]"
              >
                Clear filters
              </Button>
            )}
          </div>
        ) : (
          <div className="overflow-hidden rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-paper)]">
            <div className="hidden grid-cols-[minmax(18rem,1.4fr)_minmax(10rem,0.7fr)_minmax(12rem,0.8fr)_auto] gap-4 border-b border-[var(--mundia-line)] bg-[var(--mundia-panel)] px-4 py-2 text-xs font-semibold text-slate-600 lg:grid">
              <span>Title</span>
              <span>Inventory</span>
              <span>Metadata</span>
              <span className="text-right">Actions</span>
            </div>

            <div className="divide-y divide-[var(--mundia-line)]">
              {allBooks.map((book) => {
                const isAvailable = book.availableCopies > 0;
                const statusClass = book.isActive
                  ? isAvailable
                    ? "status-success"
                    : "status-warning"
                  : "status-danger";

                return (
                  <div
                    key={book.id}
                    className="grid gap-4 px-4 py-4 transition hover:bg-[var(--surface-2)]/70 lg:grid-cols-[minmax(18rem,1.4fr)_minmax(10rem,0.7fr)_minmax(12rem,0.8fr)_auto] lg:items-center"
                  >
                    <div className="flex min-w-0 gap-3">
                      <BookCover
                        coverColor={book.coverColor}
                        coverImage={book.coverUrl}
                        className="h-20 w-14 shrink-0"
                      />
                      <div className="min-w-0">
                        <h3 className="line-clamp-2 text-base font-semibold text-[var(--mundia-ink)]">
                          {book.title}
                        </h3>
                        <p className="mt-1 truncate text-sm text-slate-600">
                          {book.author}
                        </p>
                        <p className="mt-2 inline-flex rounded-full border border-[var(--mundia-line)] bg-[var(--surface-0)] px-2.5 py-1 text-xs font-medium text-slate-600">
                          {book.genre}
                        </p>
                      </div>
                    </div>

                    <div className="grid grid-cols-2 gap-3 text-sm lg:block lg:space-y-1">
                      <p className="text-slate-600">
                        Total{" "}
                        <span className="font-semibold text-[var(--mundia-ink)]">
                          {book.totalCopies}
                        </span>
                      </p>
                      <p className="text-slate-600">
                        Available{" "}
                        <span
                          className={
                            isAvailable
                              ? "font-semibold text-[var(--mundia-success-strong)]"
                              : "font-semibold text-[var(--mundia-danger)]"
                          }
                        >
                          {book.availableCopies}
                        </span>
                      </p>
                      <span className={`status-pill mt-1 w-fit ${statusClass}`}>
                        {book.isActive
                          ? isAvailable
                            ? "Active"
                            : "Unavailable"
                          : "Inactive"}
                      </span>
                    </div>

                    <div className="space-y-1 text-sm text-slate-600">
                      <p>
                        Rating{" "}
                        <span className="font-semibold text-[var(--mundia-ink)]">
                          {book.rating}/5
                        </span>
                      </p>
                      {book.publicationYear && (
                        <p>Published {book.publicationYear}</p>
                      )}
                      {book.isbn && (
                        <p className="truncate font-mono text-xs">
                          ISBN {book.isbn}
                        </p>
                      )}
                    </div>

                    <div className="flex gap-2 lg:justify-end">
                      <Button
                        size="sm"
                        variant="outline"
                        className="rounded-lg border-[var(--mundia-line)]"
                        asChild
                      >
                        <Link href={`/books/${book.id}`}>View</Link>
                      </Button>
                      <Button
                        size="sm"
                        className="rounded-lg bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)]"
                        asChild
                      >
                        <Link href={`/admin/books/${book.id}/edit`}>Edit</Link>
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>
    </section>
  );
};

export default AdminBooksList;
