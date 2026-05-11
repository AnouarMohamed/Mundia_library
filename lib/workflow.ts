import {
  Client as WorkflowClient,
  type TriggerOptions,
  type WorkflowServeOptions,
} from "@upstash/workflow";
import { Client as QStashClient, resend } from "@upstash/qstash";
import config from "@/lib/config";

let workflowClientInstance: WorkflowClient | null = null;
let qstashClient: QStashClient | null = null;

const createMissingQStashConfigError = () =>
  new Error(
    "Upstash QStash is not configured. Please check QSTASH_URL and QSTASH_TOKEN."
  );

/**
 * Lazily create the Upstash Workflow client.
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
 * Lazily create the QStash client.
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
 * Create the Resend provider for email delivery.
 */
const getResendProvider = () => {
  if (!config.env.resendToken) {
    throw new Error(
      "Resend is not configured. Please check RESEND_TOKEN before sending workflow email."
    );
  }

  return resend({ token: config.env.resendToken });
};

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
 * Workflow client wrapper with lazy initialization.
 */
export const workflowClient = {
  trigger: (params: TriggerOptions) => getWorkflowClient().trigger(params),
};

/**
 * Build serve options with an optional QStash client.
 */
export const getWorkflowServeOptions = <
  TInitialPayload = unknown,
  TResult = unknown,
>(): WorkflowServeOptions<TInitialPayload, TResult> => ({
  qstashClient: config.env.upstash.qstashToken
    ? getQStashClient()
    : createMissingWorkflowQStashClient(),
});

/**
 * Send an email via QStash + Resend.
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
