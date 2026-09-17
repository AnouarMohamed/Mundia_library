/**
 * Phase 5 Notification Service Contract Definitions
 *
 * Defines canonical data contracts and client interfaces for the Notification Service,
 * decoupling transactional emails, SMS alerts, and QStash/Kafka outbox notification delivery
 * from the monolithic Next.js backend.
 */

export type NotificationChannel = "EMAIL" | "SMS" | "IN_APP";
export type NotificationPriority = "HIGH" | "NORMAL" | "LOW";

export interface NotificationIntent {
  intentId: string;
  recipientId: string;
  recipientEmail?: string;
  recipientPhone?: string;
  templateId: string;
  channel: NotificationChannel;
  priority: NotificationPriority;
  variables: Record<string, string | number>;
  deduplicationKey: string;
  createdAt: string;
}

export interface NotificationDeliveryResult {
  intentId: string;
  delivered: boolean;
  provider: "BREVO" | "RESEND" | "TWILIO";
  messageId?: string;
  error?: string;
  deliveredAt?: string;
}

export interface NotificationServiceContract {
  publishNotificationIntent(intent: NotificationIntent): Promise<{ queued: boolean; intentId: string }>;
  getDeliveryStatus(intentId: string): Promise<NotificationDeliveryResult | null>;
}
