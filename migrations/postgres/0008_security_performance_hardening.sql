-- Security and performance hardening for PostgreSQL deployments.
-- This migration is intentionally non-destructive: indexes are idempotent,
-- and new constraints are added NOT VALID so existing production data can be
-- cleaned up deliberately before validation.

DO $$
BEGIN
  IF to_regclass('public.books') IS NOT NULL THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS books_is_active_rating_created_idx ON public.books (is_active, rating DESC, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS books_is_active_genre_rating_created_idx ON public.books (is_active, genre, rating DESC, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS books_title_lower_idx ON public.books (lower(title))';
    EXECUTE 'CREATE INDEX IF NOT EXISTS books_author_lower_idx ON public.books (lower(author))';
    EXECUTE 'CREATE INDEX IF NOT EXISTS books_genre_lower_idx ON public.books (lower(genre))';
    EXECUTE 'CREATE INDEX IF NOT EXISTS books_available_copies_idx ON public.books (available_copies)';

    IF NOT EXISTS (
      SELECT 1 FROM pg_constraint WHERE conname = 'books_total_copies_nonnegative'
    ) THEN
      ALTER TABLE public.books
        ADD CONSTRAINT books_total_copies_nonnegative
        CHECK (total_copies >= 0) NOT VALID;
    END IF;

    IF NOT EXISTS (
      SELECT 1 FROM pg_constraint WHERE conname = 'books_available_copies_nonnegative'
    ) THEN
      ALTER TABLE public.books
        ADD CONSTRAINT books_available_copies_nonnegative
        CHECK (available_copies >= 0) NOT VALID;
    END IF;

    IF NOT EXISTS (
      SELECT 1 FROM pg_constraint WHERE conname = 'books_available_copies_lte_total'
    ) THEN
      ALTER TABLE public.books
        ADD CONSTRAINT books_available_copies_lte_total
        CHECK (available_copies <= total_copies) NOT VALID;
    END IF;

    IF NOT EXISTS (
      SELECT 1 FROM pg_constraint WHERE conname = 'books_rating_range'
    ) THEN
      ALTER TABLE public.books
        ADD CONSTRAINT books_rating_range
        CHECK (rating BETWEEN 0 AND 5) NOT VALID;
    END IF;
  END IF;

  IF to_regclass('public.borrow_records') IS NOT NULL THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS borrow_records_user_created_idx ON public.borrow_records (user_id, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS borrow_records_book_created_idx ON public.borrow_records (book_id, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS borrow_records_status_created_idx ON public.borrow_records (status, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS borrow_records_user_status_created_idx ON public.borrow_records (user_id, status, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS borrow_records_status_due_created_idx ON public.borrow_records (status, due_date, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS borrow_records_due_date_idx ON public.borrow_records (due_date)';

    IF NOT EXISTS (
      SELECT 1 FROM pg_constraint WHERE conname = 'borrow_records_fine_amount_nonnegative'
    ) THEN
      ALTER TABLE public.borrow_records
        ADD CONSTRAINT borrow_records_fine_amount_nonnegative
        CHECK (fine_amount IS NULL OR fine_amount >= 0) NOT VALID;
    END IF;

    IF NOT EXISTS (
      SELECT 1 FROM pg_constraint WHERE conname = 'borrow_records_renewal_count_nonnegative'
    ) THEN
      ALTER TABLE public.borrow_records
        ADD CONSTRAINT borrow_records_renewal_count_nonnegative
        CHECK (renewal_count >= 0) NOT VALID;
    END IF;
  END IF;

  IF to_regclass('public.book_reviews') IS NOT NULL THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS book_reviews_book_created_idx ON public.book_reviews (book_id, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS book_reviews_user_created_idx ON public.book_reviews (user_id, created_at DESC)';

    IF NOT EXISTS (
      SELECT 1 FROM pg_constraint WHERE conname = 'book_reviews_rating_range'
    ) THEN
      ALTER TABLE public.book_reviews
        ADD CONSTRAINT book_reviews_rating_range
        CHECK (rating BETWEEN 1 AND 5) NOT VALID;
    END IF;
  END IF;

  IF to_regclass('public.admin_requests') IS NOT NULL THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS admin_requests_status_created_idx ON public.admin_requests (status, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS admin_requests_user_status_idx ON public.admin_requests (user_id, status)';
  END IF;

  IF to_regclass('public.users') IS NOT NULL THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS users_status_idx ON public.users (status)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS users_role_idx ON public.users (role)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS users_email_lower_idx ON public.users (lower(email))';
  END IF;

  IF to_regclass('public.audit_logs') IS NOT NULL THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS audit_logs_user_created_idx ON public.audit_logs (user_id, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS audit_logs_action_created_idx ON public.audit_logs (action, created_at DESC)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS audit_logs_target_created_idx ON public.audit_logs (target_type, target_id, created_at DESC)';
  END IF;

  IF to_regclass('public.notifications') IS NOT NULL THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS notifications_user_read_created_idx ON public.notifications (user_id, is_read, created_at DESC)';
  END IF;

  IF to_regclass('public.renewal_requests') IS NOT NULL THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS renewal_requests_user_status_idx ON public.renewal_requests (user_id, status)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS renewal_requests_borrow_status_idx ON public.renewal_requests (borrow_record_id, status)';
  END IF;
END $$;
