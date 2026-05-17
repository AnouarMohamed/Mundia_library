/**
 * Workflow and Messaging Integration
 *
 * This module integrates Upstash Workflow and QStash into the application to handle:
 * 1. Background Workflows: Complex, multi-step operations (e.g., student onboarding emails).
 * 2. Reliable Messaging (QStash): Decoupled asynchronous task execution.
 * 3. Email Delivery: Integrated with Resend via QStash.
 *
 * It uses a lazy-loading pattern for all clients to:
 * - Prevent unnecessary overhead in non-workflow requests.
 * - Allow the application to start even if some infrastructure keys are missing (graceful degradation).
 * - Ensure clean separation between configuration and runtime instances.
 */

import {
  Client as WorkflowClient,
  type TriggerOptions,
  type WorkflowServeOptions,
} from "@upstash/workflow";
import { Client as QStashClient, Receiver, resend } from "@upstash/qstash";
import config from "@/lib/config";
import { logError } from "@/lib/security/logger";

let workflowClientInstance: WorkflowClient | null = null;
let qstashClient: QStashClient | null = null;

/** Helper to create standard configuration errors. */
const createMissingQStashConfigError = () =>
  new Error(
    "Upstash QStash is not configured. Please check QSTASH_URL and QSTASH_TOKEN."
  );

/**
 * Lazily creates and returns the Upstash Workflow client.
 * @throws Error if configuration is missing.
 */
const getWorkflowClient = () => {
  if (workflowClientInstance) {
    return workflowClientInstance;
  }

  if (!config.env.upstash.qstashUrl || !config.env.upstash.qstashToken) {
    throw createMissingQStashConfigError();
  }

  workflowClientInstance = new WorkflowClient({
    baseUrl: config.env.upstash.qstashUrl,
    token: config.env.upstash.qstashToken,
  });

  return workflowClientInstance;
};

/**
 * Lazily creates and returns the standard QStash client.
 * @throws Error if token is missing.
 */
const getQStashClient = () => {
  if (qstashClient) {
    return qstashClient;
  }

  if (!config.env.upstash.qstashToken) {
    throw createMissingQStashConfigError();
  }

  qstashClient = new QStashClient({
    token: config.env.upstash.qstashToken,
  });

  return qstashClient;
};

/**
 * Configures the Resend provider for QStash-based email delivery.
 * @throws Error if Resend token is missing.
 */
const getResendProvider = () => {
  if (!config.env.resendToken) {
    throw new Error(
      "Resend is not configured. Please check RESEND_TOKEN before sending workflow email."
    );
  }

  return resend({ token: config.env.resendToken });
};

/**
 * Fallback QStash client for environments where QStash is disabled or missing.
 */
const createMissingWorkflowQStashClient = () => {
  const fail = async () => {
    throw createMissingQStashConfigError();
  };

  return {
    batch: fail,
    batchJSON: fail,
    publish: fail,
    publishJSON: fail,
    http: {},
  } as NonNullable<WorkflowServeOptions["qstashClient"]>;
};

/**
 * Initializes the QStash Receiver for verifying signatures in production.
 */
const createWorkflowReceiver = () => {
  const {
    qstashCurrentSigningKey,
    qstashNextSigningKey,
  } = config.env.upstash;

  if (!qstashCurrentSigningKey || !qstashNextSigningKey) {
    // Enforce signing keys at production runtime. Next evaluates route modules
    // during `next build`, so defer the hard failure until the server runs.
    const isProductionBuild =
      process.env.NEXT_PHASE === "phase-production-build";

    if (process.env.NODE_ENV === "production" && !isProductionBuild) {
      throw new Error(
        "QStash signing keys are required for production workflow endpoints."
      );
    }

    return undefined;
  }

  return new Receiver({
    currentSigningKey: qstashCurrentSigningKey,
    nextSigningKey: qstashNextSigningKey,
  });
};

/**
 * Public Workflow Client
 * Wraps the trigger method with lazy initialization.
 */
export const workflowClient = {
  /**
   * Triggers a specific workflow by its ID or route.
   */
  trigger: (params: TriggerOptions) => getWorkflowClient().trigger(params),
};

/**
 * Generates options for the workflow 'serve' endpoint.
 * 
 * @template TInitialPayload - The expected payload type for the workflow.
 * @template TResult - The expected result type.
 * @returns Configured ServeOptions including QStash client and signature receiver.
 */
export const getWorkflowServeOptions = <
  TInitialPayload = unknown,
  TResult = unknown,
>(): WorkflowServeOptions<TInitialPayload, TResult> => ({
  qstashClient: config.env.upstash.qstashToken
    ? getQStashClient()
    : createMissingWorkflowQStashClient(),
  receiver: createWorkflowReceiver(),
  failureFunction({ failStatus, failResponse }) {
    // Audit log workflow failures for observability.
    logError("workflow.execution_failed", new Error(failResponse), {
      failStatus,
    });
  },
});

/**
 * Sends a transactional email using QStash's built-in Resend integration.
 * This ensures email delivery is retried reliably by QStash if Resend is down.
 * 
 * @param params - Email recipient, subject, and HTML message body.
 */
export const sendEmail = async ({
  email,
  subject,
  message,
}: {
  email: string;
  subject: string;
  message: string;
}) => {
  await getQStashClient().publishJSON({
    api: {
      name: "email",
      provider: getResendProvider(),
    },
    body: {
      from: "JS Mastery <contact@adrianjsmastery.com>",
      to: [email],
      subject,
      html: message,
    },
  });
};
