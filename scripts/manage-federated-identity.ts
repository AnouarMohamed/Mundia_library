/**
 * Privileged, non-HTTP provisioning for institutional OIDC identities.
 *
 * Example:
 * npm run auth:identity -- provision \
 *   --subject-file '/run/secrets/exact-idp-subject' \
 *   --user-id '<local-user-uuid>' \
 *   --expected-email 'student@example.edu' \
 *   --actor-user-id '<approved-admin-uuid>' \
 *   --reason 'Initial institutional identity rollout'
 */

import { createHash } from "node:crypto";
import {
  closeSync,
  constants,
  fstatSync,
  openSync,
  readFileSync,
} from "node:fs";
import { and, eq } from "drizzle-orm";
import { z } from "zod";
import { closeDb, db } from "@/database/drizzle";
import { auditLogs, federatedIdentities, users } from "@/database/schema";
import config from "@/lib/config";

const usage =
  "Usage: npm run auth:identity -- <provision|revoke> --subject-file <owner-only-file> --user-id <uuid> --expected-email <email> --actor-user-id <uuid> --reason <10-500 chars> [--confirm-protected-tier]";

const allowedOptions = new Set([
  "subject-file",
  "user-id",
  "expected-email",
  "actor-user-id",
  "reason",
  "confirm-protected-tier",
]);
const hasUnsafeSubjectCharacter = (value: string) =>
  Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined && (codePoint <= 31 || codePoint === 127);
  });

const readProtectedSubject = (path: string | undefined) => {
  if (!path) throw new Error(`Missing --subject-file. ${usage}`);

  let descriptor: number | undefined;
  try {
    descriptor = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (!metadata.isFile()) {
      throw new Error("Subject source must be a regular file");
    }
    if ((metadata.mode & 0o077) !== 0) {
      throw new Error(
        "Subject file must not grant permissions to group or other users",
      );
    }
    if (
      typeof process.getuid === "function" &&
      metadata.uid !== process.getuid()
    ) {
      throw new Error("Subject file must be owned by the current user");
    }
    if (metadata.size < 1 || metadata.size > 1026) {
      throw new Error("Subject file has an invalid size");
    }

    const source = readFileSync(descriptor, "utf8");
    return source.endsWith("\r\n")
      ? source.slice(0, -2)
      : source.endsWith("\n")
        ? source.slice(0, -1)
        : source;
  } finally {
    if (descriptor !== undefined) closeSync(descriptor);
  }
};

const parseArguments = (argv: string[]) => {
  const [operation, ...tokens] = argv;
  if (operation !== "provision" && operation !== "revoke") {
    throw new Error(usage);
  }

  const options = new Map<string, string>();
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (!token.startsWith("--")) throw new Error(usage);

    const key = token.slice(2);
    if (!allowedOptions.has(key) || options.has(key)) {
      throw new Error(`Unknown or duplicate option --${key}. ${usage}`);
    }

    if (key === "confirm-protected-tier") {
      options.set(key, "true");
      continue;
    }

    const value = tokens[index + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`Missing value for --${key}. ${usage}`);
    }
    options.set(key, value);
    index += 1;
  }

  return {
    operation,
    subjectFile: options.get("subject-file"),
    userId: options.get("user-id"),
    expectedEmail: options.get("expected-email"),
    actorUserId: options.get("actor-user-id"),
    reason: options.get("reason"),
    confirmedProtectedTier: options.get("confirm-protected-tier") === "true",
  };
};

const inputSchema = z.object({
  operation: z.enum(["provision", "revoke"]),
  subjectFile: z.string().min(1).max(4096),
  userId: z.string().uuid(),
  expectedEmail: z
    .string()
    .email()
    .max(254)
    .transform((email) => email.toLowerCase()),
  actorUserId: z.string().uuid(),
  reason: z.string().trim().min(10).max(500),
  confirmedProtectedTier: z.boolean(),
});

const manageIdentity = async () => {
  const input = inputSchema.parse(parseArguments(process.argv.slice(2)));
  const subject = readProtectedSubject(input.subjectFile);
  if (
    subject.length === 0 ||
    subject.length > 1024 ||
    hasUnsafeSubjectCharacter(subject)
  ) {
    throw new Error("Subject file contains an invalid OIDC subject");
  }
  if (!config.env.oidc.enabled) {
    throw new Error("Complete institutional OIDC configuration is required");
  }

  const protectedTier = ["staging", "production"].includes(
    config.env.appEnvironment,
  );
  if (protectedTier && !input.confirmedProtectedTier) {
    throw new Error(
      "--confirm-protected-tier is required in staging and production",
    );
  }

  const expectedDomain = input.expectedEmail.slice(
    input.expectedEmail.lastIndexOf("@") + 1,
  );
  if (!config.env.oidc.allowedEmailDomains.includes(expectedDomain)) {
    throw new Error("Expected email is outside the configured OIDC domains");
  }

  const subjectSha256 = createHash("sha256")
    .update(subject, "utf8")
    .digest("hex");

  const result = await db.transaction(async (tx) => {
    const [actor] = await tx
      .select({
        id: users.id,
        role: users.role,
        status: users.status,
      })
      .from(users)
      .where(eq(users.id, input.actorUserId))
      .limit(1)
      .for("update");
    if (!actor || actor.role !== "ADMIN" || actor.status !== "APPROVED") {
      throw new Error("Actor must be an existing approved administrator");
    }

    const [target] = await tx
      .select({
        id: users.id,
        email: users.email,
      })
      .from(users)
      .where(eq(users.id, input.userId))
      .limit(1)
      .for("update");
    if (!target || target.email.toLowerCase() !== input.expectedEmail) {
      throw new Error("Target user and expected email do not match");
    }

    const [existingExactIdentity] = await tx
      .select({ userId: federatedIdentities.userId })
      .from(federatedIdentities)
      .where(
        and(
          eq(federatedIdentities.issuer, config.env.oidc.issuer),
          eq(federatedIdentities.subject, subject),
        ),
      )
      .limit(1)
      .for("update");

    const [existingUserIdentity] = await tx
      .select({ userId: federatedIdentities.userId })
      .from(federatedIdentities)
      .where(
        and(
          eq(federatedIdentities.issuer, config.env.oidc.issuer),
          eq(federatedIdentities.userId, input.userId),
        ),
      )
      .limit(1)
      .for("update");

    let outcome: string;
    let failure: string | undefined;

    if (input.operation === "provision") {
      if (existingExactIdentity?.userId === input.userId) {
        outcome = "already-provisioned";
      } else if (existingExactIdentity) {
        outcome = "rejected-subject-bound-to-another-user";
        failure =
          "Exact issuer/subject tuple is already bound to another user";
      } else if (existingUserIdentity) {
        outcome = "rejected-user-already-bound-for-issuer";
        failure =
          "Local user already has a different identity for this issuer";
      } else {
        const inserted = await tx
          .insert(federatedIdentities)
          .values({
            issuer: config.env.oidc.issuer,
            subject,
            userId: input.userId,
          })
          .onConflictDoNothing()
          .returning({ bindingId: federatedIdentities.bindingId });

        if (inserted.length === 1) {
          outcome = "provisioned";
        } else {
          outcome = "rejected-concurrent-binding-conflict";
          failure = "Identity binding changed concurrently; retry after review";
        }
      }
    } else {
      if (!existingExactIdentity && existingUserIdentity) {
        outcome = "rejected-user-bound-to-different-subject";
        failure =
          "Local user has a different identity for this issuer; no mapping was revoked";
      } else if (!existingExactIdentity) {
        outcome = "already-revoked";
      } else if (existingExactIdentity.userId !== input.userId) {
        outcome = "rejected-subject-bound-to-another-user";
        failure =
          "Exact issuer/subject tuple is bound to another user; no mapping was revoked";
      } else {
        await tx
          .delete(federatedIdentities)
          .where(
            and(
              eq(federatedIdentities.issuer, config.env.oidc.issuer),
              eq(federatedIdentities.subject, subject),
              eq(federatedIdentities.userId, input.userId),
            ),
          );
        outcome = "revoked";
      }
    }

    await tx.insert(auditLogs).values({
      userId: input.actorUserId,
      action:
        input.operation === "provision"
          ? "PROVISION_FEDERATED_IDENTITY"
          : "REVOKE_FEDERATED_IDENTITY",
      targetId: input.userId,
      targetType: "USER",
      details: JSON.stringify({
        issuer: config.env.oidc.issuer,
        subjectSha256,
        reason: input.reason,
        outcome,
      }),
    });

    return { failure, outcome };
  });

  if (result.failure) throw new Error(result.failure);
  console.log(`Federated identity ${result.outcome}.`);
};

manageIdentity()
  .catch((error) => {
    console.error(
      "Federated identity operation failed:",
      error instanceof Error ? error.message : "unknown error",
    );
    process.exitCode = 1;
  })
  .finally(closeDb);
