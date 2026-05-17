CREATE TABLE `admin_requests` (
	`id` varchar(36) NOT NULL,
	`user_id` varchar(36) NOT NULL,
	`request_reason` text NOT NULL,
	`status` enum('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
	`reviewed_by` varchar(36),
	`reviewed_at` timestamp,
	`rejection_reason` text,
	`created_at` timestamp DEFAULT (now()),
	`updated_at` timestamp DEFAULT (now()),
	CONSTRAINT `admin_requests_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `book_reviews` (
	`id` varchar(36) NOT NULL,
	`book_id` varchar(36) NOT NULL,
	`user_id` varchar(36) NOT NULL,
	`rating` int NOT NULL,
	`comment` text NOT NULL,
	`created_at` timestamp DEFAULT (now()),
	`updated_at` timestamp DEFAULT (now()),
	CONSTRAINT `book_reviews_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `books` (
	`id` varchar(36) NOT NULL,
	`title` varchar(255) NOT NULL,
	`author` varchar(255) NOT NULL,
	`genre` text NOT NULL,
	`rating` int NOT NULL,
	`cover_url` text NOT NULL,
	`cover_color` varchar(7) NOT NULL,
	`description` text NOT NULL,
	`total_copies` int NOT NULL DEFAULT 1,
	`available_copies` int NOT NULL DEFAULT 0,
	`video_url` text NOT NULL,
	`summary` text NOT NULL,
	`isbn` varchar(20),
	`publication_year` int,
	`publisher` varchar(255),
	`language` varchar(50) DEFAULT 'English',
	`page_count` int,
	`edition` varchar(50),
	`is_active` boolean NOT NULL DEFAULT true,
	`updated_at` timestamp DEFAULT (now()),
	`updated_by` varchar(36),
	`created_at` timestamp DEFAULT (now()),
	CONSTRAINT `books_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `borrow_records` (
	`id` varchar(36) NOT NULL,
	`user_id` varchar(36) NOT NULL,
	`book_id` varchar(36) NOT NULL,
	`borrow_date` timestamp NOT NULL DEFAULT (now()),
	`due_date` date,
	`return_date` date,
	`status` enum('PENDING','BORROWED','RETURNED') NOT NULL DEFAULT 'BORROWED',
	`borrowed_by` text,
	`returned_by` text,
	`fine_amount` decimal(10,2) DEFAULT '0.00',
	`notes` text,
	`renewal_count` int NOT NULL DEFAULT 0,
	`last_reminder_sent` timestamp,
	`updated_at` timestamp DEFAULT (now()),
	`updated_by` text,
	`created_at` timestamp DEFAULT (now()),
	CONSTRAINT `borrow_records_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `system_config` (
	`id` varchar(36) NOT NULL,
	`key` varchar(100) NOT NULL,
	`value` text NOT NULL,
	`description` text,
	`updated_at` timestamp DEFAULT (now()),
	`updated_by` text,
	`created_at` timestamp DEFAULT (now()),
	CONSTRAINT `system_config_id` PRIMARY KEY(`id`),
	CONSTRAINT `system_config_key_unique` UNIQUE(`key`)
);
--> statement-breakpoint
CREATE TABLE `users` (
	`id` varchar(36) NOT NULL,
	`full_name` varchar(255) NOT NULL,
	`email` varchar(255) NOT NULL,
	`university_id` int NOT NULL,
	`password` text NOT NULL,
	`university_card` text NOT NULL,
	`status` enum('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
	`role` enum('USER','ADMIN') NOT NULL DEFAULT 'USER',
	`last_activity_date` date DEFAULT (CURRENT_DATE),
	`last_login` timestamp,
	`created_at` timestamp DEFAULT (now()),
	CONSTRAINT `users_id` PRIMARY KEY(`id`),
	CONSTRAINT `users_email_unique` UNIQUE(`email`),
	CONSTRAINT `users_university_id_unique` UNIQUE(`university_id`)
);
--> statement-breakpoint
ALTER TABLE `admin_requests` ADD CONSTRAINT `admin_requests_user_id_users_id_fk` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `admin_requests` ADD CONSTRAINT `admin_requests_reviewed_by_users_id_fk` FOREIGN KEY (`reviewed_by`) REFERENCES `users`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `book_reviews` ADD CONSTRAINT `book_reviews_book_id_books_id_fk` FOREIGN KEY (`book_id`) REFERENCES `books`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `book_reviews` ADD CONSTRAINT `book_reviews_user_id_users_id_fk` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `books` ADD CONSTRAINT `books_updated_by_users_id_fk` FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `borrow_records` ADD CONSTRAINT `borrow_records_user_id_users_id_fk` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `borrow_records` ADD CONSTRAINT `borrow_records_book_id_books_id_fk` FOREIGN KEY (`book_id`) REFERENCES `books`(`id`) ON DELETE no action ON UPDATE no action;