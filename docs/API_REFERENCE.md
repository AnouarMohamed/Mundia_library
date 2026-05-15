# API Reference

This reference covers route handlers under `app/api`. Many product mutations are implemented as server actions instead of public JSON endpoints. If a workflow is not listed here, check `lib/actions/**` and `lib/admin/actions/**`.

## Conventions

Base URL:

```text
http://localhost:3000
```

Production base URL:

```text
https://mundia-library.vercel.app
```

Common JSON envelope:

```json
{
  "success": true
}
```

Error envelope:

```json
{
  "success": false,
  "error": "Error name",
  "message": "Human-readable detail"
}
```

Status codes:

| Status | Meaning |
| --- | --- |
| `200` | Request succeeded. |
| `201` | Resource created. |
| `400` | Invalid request input. |
| `401` | Authentication required. |
| `403` | Authenticated user does not have access. |
| `404` | Resource not found. |
| `429` | Rate limit exceeded. |
| `500` | Server error. |

## Authentication

Auth is handled by NextAuth route handlers:

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET/POST` | `/api/auth/[...nextauth]` | NextAuth session, callback, sign-in, and sign-out internals. |

Most browser-facing authentication should use the app pages and NextAuth helpers, not direct API calls.

## Public And Authenticated Catalog APIs

### `GET /api/books`

Returns active books with optional filters, sorting, pagination, Redis SWR caching, and Next.js cache tags.

File: `app/api/books/route.ts`

Query parameters:

| Parameter | Values | Default | Notes |
| --- | --- | --- | --- |
| `search` | string | empty | Searches title/author through advanced search service. |
| `genre` | string | empty | Exact genre filter. |
| `availability` | `available`, `unavailable` | empty | Filters by `availableCopies`. |
| `rating` | `1` to `5` | empty | Minimum rating. |
| `sort` | `title`, `author`, `rating`, `date`, `relevance` for search | `title` | Search defaults to relevance when valid. |
| `page` | integer | `1` | Clamped to `>= 1`. |
| `limit` | integer | `12` | Clamped to `1..50`. |

Example:

```bash
curl "http://localhost:3000/api/books?search=design&availability=available&limit=12&page=1"
```

Response fields:

- `success`
- `books`
- `pagination.currentPage`
- `pagination.totalPages`
- `pagination.totalBooks`
- `pagination.booksPerPage`

### `GET /api/books/[id]`

Returns one active book by ID.

File: `app/api/books/[id]/route.ts`

Example:

```bash
curl "http://localhost:3000/api/books/<book-id>"
```

### `GET /api/books/[id]/borrow-stats`

Returns borrow statistics for a book.

File: `app/api/books/[id]/borrow-stats/route.ts`

Example:

```bash
curl "http://localhost:3000/api/books/<book-id>/borrow-stats"
```

### `GET /api/books/genres`

Returns known active catalog genres.

File: `app/api/books/genres/route.ts`

Example:

```bash
curl "http://localhost:3000/api/books/genres"
```

### `GET /api/books/recommendations`

Returns personalized or fallback recommendations.

File: `app/api/books/recommendations/route.ts`

Query parameters:

| Parameter | Values | Default | Notes |
| --- | --- | --- | --- |
| `limit` | integer | `10` | Clamped by route implementation. |

Example:

```bash
curl "http://localhost:3000/api/books/recommendations?limit=10"
```

## Borrow APIs

### `GET /api/borrow-records`

Returns borrow records visible to the authenticated user. Admin users can query across users; non-admin users can only access their own records.

File: `app/api/borrow-records/route.ts`

Auth: required.

Query parameters:

| Parameter | Values | Default | Notes |
| --- | --- | --- | --- |
| `userId` | user UUID | current user | Non-admin users cannot request another user. |
| `bookId` | book UUID | empty | Filter by book. |
| `status` | `PENDING`, `BORROWED`, `RETURNED` | empty | Filter by lifecycle status. |
| `dateFrom` | `YYYY-MM-DD` | empty | Borrow date lower bound. |
| `dateTo` | `YYYY-MM-DD` | empty | Borrow date upper bound. |
| `overdue` | `true`, `false` | `false` | Active overdue records only. |
| `sort` | `date`, `dueDate`, `status`, `user` | `date` | Sort order. |
| `page` | integer | `1` | Clamped to `>= 1`. |
| `limit` | integer | `50` | Clamped to `1..100`. |

Example:

```bash
curl "http://localhost:3000/api/borrow-records?status=BORROWED&limit=20"
```

Borrow creation, approval, return, and renewal are implemented through server actions and admin action files.

## Review APIs

### `GET /api/reviews/[bookId]`

Returns reviews for a book.

File: `app/api/reviews/[bookId]/route.ts`

### `POST /api/reviews/[bookId]`

Creates a review for a book.

File: `app/api/reviews/[bookId]/route.ts`

Auth: required.

Expected body includes rating and comment fields as implemented by the route and form validation.

### `GET /api/reviews/eligibility/[bookId]`

Checks whether the authenticated user is eligible to review a book.

File: `app/api/reviews/eligibility/[bookId]/route.ts`

Auth: required.

### `PUT /api/reviews/edit/[reviewId]`

Updates a review.

File: `app/api/reviews/edit/[reviewId]/route.ts`

Auth: required. User must own the review or have admin-level access, depending on route logic.

### `DELETE /api/reviews/edit/[reviewId]`

Deletes a review through the edit route.

File: `app/api/reviews/edit/[reviewId]/route.ts`

### `DELETE /api/reviews/delete/[reviewId]`

Deletes a review through the dedicated delete route.

File: `app/api/reviews/delete/[reviewId]/route.ts`

## Notification APIs

### `GET /api/notifications`

Returns notifications for the authenticated user.

File: `app/api/notifications/route.ts`

Auth: required.

### `POST /api/notifications`

Creates a notification.

File: `app/api/notifications/route.ts`

Auth and authorization depend on route implementation. Treat notification creation as privileged unless explicitly opened.

### `PATCH /api/notifications/[id]`

Updates a notification, typically marking it read.

File: `app/api/notifications/[id]/route.ts`

Auth: required.

## User APIs

### `GET /api/users`

Returns users visible to the authenticated caller.

File: `app/api/users/route.ts`

Auth: route-dependent. Treat as admin-sensitive data and verify guard behavior before exposing new clients.

## Upload APIs

### `GET /api/auth/imagekit`

Returns ImageKit authentication parameters for client uploads.

File: `app/api/auth/imagekit/route.ts`

Rate limited. Authentication is optional so the sign-up flow can upload a university card before a session exists.

Example:

```bash
curl "http://localhost:3000/api/auth/imagekit"
```

Production requires:

- `NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY`
- `NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT`
- `IMAGEKIT_PRIVATE_KEY`

## Workflow APIs

### `/api/workflows/onboarding`

Handles onboarding workflow requests.

File: `app/api/workflows/onboarding/route.ts`

This route is part of the Upstash Workflow integration and should not be treated as a general public endpoint.

## Admin APIs

All admin APIs should call `requireAdminRouteAccess()` from `lib/admin/route-guard.ts`.

### `GET /api/admin/stats`

Returns admin dashboard statistics.

File: `app/api/admin/stats/route.ts`

### `GET /api/admin/borrow-requests`

Returns borrow requests with user and book details.

File: `app/api/admin/borrow-requests/route.ts`

Query parameters:

| Parameter | Values | Default | Notes |
| --- | --- | --- | --- |
| `search` | string | empty | Matches title, author, user name, email, or university ID. |
| `status` | `PENDING`, `BORROWED`, `RETURNED` | empty | Filters borrow lifecycle status. |

### `GET /api/admin/admin-requests`

Returns admin access requests.

File: `app/api/admin/admin-requests/route.ts`

### `GET /api/admin/fine-config`

Returns fine configuration.

File: `app/api/admin/fine-config/route.ts`

### `POST /api/admin/fine-config`

Updates fine configuration.

File: `app/api/admin/fine-config/route.ts`

### `POST /api/admin/update-overdue-fines`

Calculates and persists overdue fines.

File: `app/api/admin/update-overdue-fines/route.ts`

Operational caution: confirm idempotency and expected date window before repeated production runs.

### `POST /api/admin/send-due-reminders`

Sends due-soon reminders.

File: `app/api/admin/send-due-reminders/route.ts`

Requires email provider configuration.

### `POST /api/admin/send-overdue-reminders`

Sends overdue reminders.

File: `app/api/admin/send-overdue-reminders/route.ts`

Requires email provider configuration.

### `GET /api/admin/reminder-stats`

Returns reminder summary statistics.

File: `app/api/admin/reminder-stats/route.ts`

### `POST /api/admin/generate-recommendations`

Generates recommendation data.

File: `app/api/admin/generate-recommendations/route.ts`

### `POST /api/admin/refresh-recommendation-cache`

Refreshes recommendation cache.

File: `app/api/admin/refresh-recommendation-cache/route.ts`

### `POST /api/admin/update-trending-books`

Updates trending-book data.

File: `app/api/admin/update-trending-books/route.ts`

### `GET /api/admin/export-stats`

Returns export statistics.

File: `app/api/admin/export-stats/route.ts`

### `POST /api/admin/export/[type]`

Generates an export for the requested type.

File: `app/api/admin/export/[type]/route.ts`

Exports can include sensitive operational data. Handle files accordingly.

## Rate Limiting

Public routes use the shared limiter in `lib/ratelimit.ts` where implemented.

Default production policy:

- Fixed window.
- 200 requests per minute per identifier.
- Identifier is usually IP from `x-forwarded-for`.

Development bypass:

- `NODE_ENV !== "production"`
- or `DISABLE_RATE_LIMIT=true`
- or Redis config is missing

## API Benchmark Coverage

The benchmark workflow currently covers:

- `GET /api/books?limit=12&sort=rating&page=1`
- `GET /api/books/genres`
- `GET /api/books/recommendations?limit=10`
- `GET /api/books/[id]`

Script:

```bash
npm run benchmark:api
```

Workflow:

- `.github/workflows/api-benchmarks.yml`

## Adding Or Changing An API Route

Required checklist:

1. Decide whether the endpoint is public, authenticated, user-owned, or admin-only.
2. Add server-side guard. Do not rely on client-side visibility.
3. Validate query params and body data.
4. Clamp pagination and limits.
5. Avoid returning sensitive fields unless the caller needs them.
6. Add or update tests for shared logic and permission-sensitive behavior.
7. Update this API reference.
8. Update benchmarks if the route is performance-critical.
