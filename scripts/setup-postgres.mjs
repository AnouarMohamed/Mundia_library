import { neon } from "@neondatabase/serverless";
import { config } from "dotenv";

config({ path: ".env.local" });
config({ path: ".env" });

if (!process.env.DATABASE_URL) {
  throw new Error("DATABASE_URL is required");
}

const sql = neon(process.env.DATABASE_URL);

const statements = [
  `CREATE EXTENSION IF NOT EXISTS pgcrypto`,
  `DO $$ BEGIN CREATE TYPE user_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED'); EXCEPTION WHEN duplicate_object THEN NULL; END $$`,
  `DO $$ BEGIN CREATE TYPE user_role AS ENUM ('USER', 'ADMIN'); EXCEPTION WHEN duplicate_object THEN NULL; END $$`,
  `DO $$ BEGIN CREATE TYPE borrow_status AS ENUM ('PENDING', 'BORROWED', 'RETURNED'); EXCEPTION WHEN duplicate_object THEN NULL; END $$`,
  `DO $$ BEGIN CREATE TYPE request_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED'); EXCEPTION WHEN duplicate_object THEN NULL; END $$`,
  `DO $$ BEGIN CREATE TYPE notification_type AS ENUM ('INFO', 'SUCCESS', 'WARNING', 'ERROR'); EXCEPTION WHEN duplicate_object THEN NULL; END $$`,
  `CREATE TABLE IF NOT EXISTS users (
    id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    full_name varchar(255) NOT NULL,
    email varchar(255) NOT NULL UNIQUE,
    university_id integer NOT NULL UNIQUE,
    password text NOT NULL,
    university_card text NOT NULL,
    status user_status NOT NULL DEFAULT 'PENDING',
    role user_role NOT NULL DEFAULT 'USER',
    last_activity_date date DEFAULT CURRENT_DATE,
    last_login timestamp,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS books (
    id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    title varchar(255) NOT NULL,
    author varchar(255) NOT NULL,
    genre text NOT NULL,
    rating integer NOT NULL,
    cover_url text NOT NULL,
    cover_color varchar(7) NOT NULL,
    description text NOT NULL,
    total_copies integer NOT NULL DEFAULT 1,
    available_copies integer NOT NULL DEFAULT 0,
    video_url text NOT NULL,
    summary text NOT NULL,
    isbn varchar(20),
    publication_year integer,
    publisher varchar(255),
    language varchar(50) DEFAULT 'English',
    page_count integer,
    edition varchar(50),
    is_active boolean NOT NULL DEFAULT true,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP,
    updated_by varchar(36) REFERENCES users(id),
    created_at timestamp DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS borrow_records (
    id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    user_id varchar(36) NOT NULL REFERENCES users(id),
    book_id varchar(36) NOT NULL REFERENCES books(id),
    borrow_date timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    due_date date,
    return_date date,
    status borrow_status NOT NULL DEFAULT 'BORROWED',
    borrowed_by text,
    returned_by text,
    fine_amount numeric(10, 2) DEFAULT '0.00',
    notes text,
    renewal_count integer NOT NULL DEFAULT 0,
    last_reminder_sent timestamp,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP,
    updated_by text,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS system_config (
    id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    key varchar(100) NOT NULL UNIQUE,
    value text NOT NULL,
    description text,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP,
    updated_by text,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS book_reviews (
    id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    book_id varchar(36) NOT NULL REFERENCES books(id),
    user_id varchar(36) NOT NULL REFERENCES users(id),
    rating integer NOT NULL,
    comment text NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS admin_requests (
    id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    user_id varchar(36) NOT NULL REFERENCES users(id),
    request_reason text NOT NULL,
    status request_status NOT NULL DEFAULT 'PENDING',
    reviewed_by varchar(36) REFERENCES users(id),
    reviewed_at timestamp,
    rejection_reason text,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS audit_logs (
    id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    user_id varchar(36) NOT NULL REFERENCES users(id),
    action varchar(100) NOT NULL,
    target_id varchar(36),
    target_type varchar(50),
    details text,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS renewal_requests (
    id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    borrow_record_id varchar(36) NOT NULL REFERENCES borrow_records(id),
    user_id varchar(36) NOT NULL REFERENCES users(id),
    status request_status NOT NULL DEFAULT 'PENDING',
    request_reason text,
    rejection_reason text,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS notifications (
    id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    user_id varchar(36) NOT NULL REFERENCES users(id),
    title varchar(255) NOT NULL,
    message text NOT NULL,
    type notification_type NOT NULL DEFAULT 'INFO',
    is_read boolean NOT NULL DEFAULT false,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE INDEX IF NOT EXISTS borrow_records_user_id_idx ON borrow_records(user_id)`,
  `CREATE INDEX IF NOT EXISTS borrow_records_book_id_idx ON borrow_records(book_id)`,
  `CREATE INDEX IF NOT EXISTS borrow_records_status_idx ON borrow_records(status)`,
  `CREATE INDEX IF NOT EXISTS notifications_user_id_idx ON notifications(user_id)`,
];

for (const statement of statements) {
  await sql.query(statement);
}

console.log("Postgres schema is ready.");
