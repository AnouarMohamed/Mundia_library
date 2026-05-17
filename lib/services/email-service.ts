/**
 * Multi-provider Email Service
 *
 * This module provides a robust email delivery system with built-in redundancy.
 * It features a primary provider (Brevo) and a fallback provider (Resend) to ensure
 * high deliverability even if one service is unavailable.
 *
 * Key features:
 * - Automatic failover: If the primary provider fails, it seamlessly switches to the fallback.
 * - Comprehensive logging: Tracks attempts, successes, and failures across all providers.
 * - Provider-agnostic API: Consumers only interact with the `sendEmailWithFallback` function.
 *
 * Requirements:
 * - BREVO_API_KEY and BREVO_SENDER_EMAIL must be set in environment variables.
 * - RESEND_TOKEN (RESEND_API_KEY) should be set for fallback capability.
 */

// Brevo Configuration
const BREVO_API_KEY = process.env.BREVO_API_KEY;
const BREVO_SENDER_EMAIL = process.env.BREVO_SENDER_EMAIL || "arnobt78@gmail.com";
const BREVO_SENDER_NAME = process.env.BREVO_SENDER_NAME || "Mundiapolis Library";
const BREVO_API_URL = "https://api.brevo.com/v3/smtp/email";

// Resend Configuration (Fallback)
const RESEND_API_KEY = process.env.RESEND_TOKEN;
const RESEND_SENDER_EMAIL = "Mundiapolis Library <onboarding@resend.dev>";

/**
 * Sends an email via the Brevo Transactional Email API.
 * Brevo is our primary provider as it supports a wide range of recipient domains.
 *
 * @param to - Recipient email address.
 * @param subject - Email subject line.
 * @param htmlContent - Rich HTML content for the email body.
 * @param textContent - Plain text version of the email body (for non-HTML clients).
 * @returns Object containing the provider name and unique message identifier.
 * @throws Error if the API key is missing or the request fails.
 */
async function sendEmailViaBrevo(
  to: string,
  subject: string,
  htmlContent: string,
  textContent: string
): Promise<{ messageId: string; provider: string }> {
  if (!BREVO_API_KEY) {
    throw new Error("BREVO_API_KEY not configured");
  }

  // Construct the standardized Brevo API payload
  const emailData = {
    sender: {
      name: BREVO_SENDER_NAME,
      email: BREVO_SENDER_EMAIL,
    },
    to: [{ email: to }],
    subject: subject,
    htmlContent: htmlContent,
    textContent: textContent,
    replyTo: {
      email: BREVO_SENDER_EMAIL,
      name: BREVO_SENDER_NAME,
    },
    headers: {
      "X-Mailer": "Mundiapolis Library Email System",
      "Auto-Submitted": "auto-generated",
    },
  };

  const response = await fetch(BREVO_API_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "api-key": BREVO_API_KEY,
    },
    body: JSON.stringify(emailData),
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Brevo API error: ${response.status} - ${errorText}`);
  }

  const result = await response.json();
  return {
    messageId: result.messageId || result.id || "unknown",
    provider: "Brevo",
  };
}

/**
 * Sends an email via the Resend API.
 * Resend serves as our fallback provider and is dynamically imported to reduce bundle size.
 *
 * @param to - Recipient email address.
 * @param subject - Email subject line.
 * @param htmlContent - Rich HTML content for the email body.
 * @param textContent - Plain text version of the email body.
 * @returns Object containing the provider name and unique message identifier.
 * @throws Error if the API key is missing or the Resend SDK returns an error.
 */
async function sendEmailViaResend(
  to: string,
  subject: string,
  htmlContent: string,
  textContent: string
): Promise<{ messageId: string; provider: string }> {
  if (!RESEND_API_KEY) {
    throw new Error("RESEND_API_KEY not configured");
  }

  // Lazy-load Resend SDK to avoid unnecessary overhead when Brevo is working
  const { Resend } = await import("resend");
  const resend = new Resend(RESEND_API_KEY);

  const { data, error } = await resend.emails.send({
    from: RESEND_SENDER_EMAIL,
    to: [to],
    subject: subject,
    html: htmlContent,
    text: textContent,
  });

  if (error) {
    throw new Error(`Resend API error: ${error.message}`);
  }

  return {
    messageId: data?.id || "unknown",
    provider: "Resend",
  };
}

/**
 * High-level orchestration function to send an email with automatic provider fallback.
 * It attempts to send via Brevo first, and if that fails, it tries Resend.
 *
 * @param to - Recipient email address.
 * @param subject - Email subject line.
 * @param htmlContent - HTML content of the email.
 * @param textContent - Plain text content of the email.
 * @returns An object indicating success, the provider used, and any errors encountered.
 * 
 * @example
 * ```typescript
 * const result = await sendEmailWithFallback(
 *   "student@example.com",
 *   "Book Overdue",
 *   "<h1>Please return your book</h1>",
 *   "Please return your book"
 * );
 * ```
 */
export async function sendEmailWithFallback(
  to: string,
  subject: string,
  htmlContent: string,
  textContent: string
): Promise<{ success: boolean; messageId?: string; provider?: string; error?: string }> {
  // Define the chain of providers to attempt
  const providers = [
    {
      name: "Brevo",
      send: () => sendEmailViaBrevo(to, subject, htmlContent, textContent),
    },
    {
      name: "Resend",
      send: () => sendEmailViaResend(to, subject, htmlContent, textContent),
    },
  ];

  let lastError: Error | null = null;

  // Iterate through providers until one succeeds or all fail
  for (const provider of providers) {
    try {
      console.log(` Attempting to send email via ${provider.name}...`);
      const result = await provider.send();
      console.log(` Email sent successfully via ${provider.name} to ${to}`);
      return {
        success: true,
        provider: result.provider,
        messageId: result.messageId,
      };
    } catch (error) {
      console.warn(` ${provider.name} failed:`, error instanceof Error ? error.message : "Unknown error");
      lastError = error instanceof Error ? error : new Error("Unknown error");
      // Fall through to the next provider in the loop
    }
  }

  // Final exit point if no providers successfully sent the email
  console.error(" All email providers failed");
  return {
    success: false,
    error: `Failed to send email via all providers. Last error: ${lastError?.message || "Unknown error"}`,
  };
}
