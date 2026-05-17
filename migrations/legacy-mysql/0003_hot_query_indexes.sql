-- Hot query index optimization pass for API and admin analytics paths

SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'books'
    AND index_name = 'books_is_active_genre_rating_created_idx'
);
SET @sql = IF(
  @idx_exists = 0,
  'CREATE INDEX books_is_active_genre_rating_created_idx ON books (is_active, genre(120), rating, created_at)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'borrow_records'
    AND index_name = 'borrow_records_user_status_created_idx'
);
SET @sql = IF(
  @idx_exists = 0,
  'CREATE INDEX borrow_records_user_status_created_idx ON borrow_records (user_id, status, created_at)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'borrow_records'
    AND index_name = 'borrow_records_status_due_created_idx'
);
SET @sql = IF(
  @idx_exists = 0,
  'CREATE INDEX borrow_records_status_due_created_idx ON borrow_records (status, due_date, created_at)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'book_reviews'
    AND index_name = 'book_reviews_book_created_idx'
);
SET @sql = IF(
  @idx_exists = 0,
  'CREATE INDEX book_reviews_book_created_idx ON book_reviews (book_id, created_at)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'admin_requests'
    AND index_name = 'admin_requests_status_created_idx'
);
SET @sql = IF(
  @idx_exists = 0,
  'CREATE INDEX admin_requests_status_created_idx ON admin_requests (status, created_at)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
