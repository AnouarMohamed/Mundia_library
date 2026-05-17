ALTER TABLE `admin_requests` MODIFY COLUMN `reviewed_at` datetime;--> statement-breakpoint
ALTER TABLE `admin_requests` MODIFY COLUMN `created_at` datetime DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `admin_requests` MODIFY COLUMN `updated_at` datetime DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `book_reviews` MODIFY COLUMN `created_at` datetime DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `book_reviews` MODIFY COLUMN `updated_at` datetime DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `books` MODIFY COLUMN `updated_at` datetime DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `books` MODIFY COLUMN `created_at` datetime DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `borrow_records` MODIFY COLUMN `borrow_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `borrow_records` MODIFY COLUMN `last_reminder_sent` datetime;--> statement-breakpoint
ALTER TABLE `borrow_records` MODIFY COLUMN `updated_at` datetime DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `borrow_records` MODIFY COLUMN `created_at` datetime DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `system_config` MODIFY COLUMN `updated_at` datetime DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `system_config` MODIFY COLUMN `created_at` datetime DEFAULT CURRENT_TIMESTAMP;--> statement-breakpoint
ALTER TABLE `users` MODIFY COLUMN `last_login` datetime;--> statement-breakpoint
ALTER TABLE `users` MODIFY COLUMN `created_at` datetime DEFAULT CURRENT_TIMESTAMP;