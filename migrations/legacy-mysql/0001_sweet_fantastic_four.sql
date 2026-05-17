ALTER TABLE `admin_requests` MODIFY COLUMN `reviewed_at` timestamp DEFAULT null;--> statement-breakpoint
ALTER TABLE `borrow_records` MODIFY COLUMN `last_reminder_sent` timestamp DEFAULT null;--> statement-breakpoint
ALTER TABLE `users` MODIFY COLUMN `last_login` timestamp DEFAULT null;