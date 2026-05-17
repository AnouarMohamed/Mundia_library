CREATE TABLE `renewal_requests` (
	`id` varchar(36) NOT NULL,
	`borrow_record_id` varchar(36) NOT NULL,
	`user_id` varchar(36) NOT NULL,
	`status` enum('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
	`request_reason` text,
	`rejection_reason` text,
	`created_at` datetime DEFAULT CURRENT_TIMESTAMP,
	`updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
	CONSTRAINT `renewal_requests_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
ALTER TABLE `renewal_requests` ADD CONSTRAINT `renewal_requests_borrow_record_id_borrow_records_id_fk` FOREIGN KEY (`borrow_record_id`) REFERENCES `borrow_records`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `renewal_requests` ADD CONSTRAINT `renewal_requests_user_id_users_id_fk` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE no action ON UPDATE no action;