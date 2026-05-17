CREATE TABLE `audit_logs` (
	`id` varchar(36) NOT NULL,
	`user_id` varchar(36) NOT NULL,
	`action` varchar(100) NOT NULL,
	`target_id` varchar(36),
	`target_type` varchar(50),
	`details` text,
	`created_at` datetime DEFAULT CURRENT_TIMESTAMP,
	CONSTRAINT `audit_logs_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
ALTER TABLE `admin_requests` MODIFY COLUMN `reviewed_at` datetime;--> statement-breakpoint
ALTER TABLE `borrow_records` MODIFY COLUMN `last_reminder_sent` datetime;--> statement-breakpoint
ALTER TABLE `users` MODIFY COLUMN `last_login` datetime;--> statement-breakpoint
ALTER TABLE `audit_logs` ADD CONSTRAINT `audit_logs_user_id_users_id_fk` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE no action ON UPDATE no action;