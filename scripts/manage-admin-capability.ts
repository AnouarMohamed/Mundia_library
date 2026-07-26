/**
 * Privileged, non-HTTP capability grant and revocation workflow.
 *
 * The executor identity is established by the protected job/terminal running
 * this command; --actor-user-id is recorded and validated against application
 * state, but is not itself an authentication factor.
 */

import { and, count, eq, gt, isNull, or, sql } from "drizzle-orm";
import { z } from "zod";

import { closeDb, db } from "@/database/drizzle";
import {
  adminCapabilityAssignments,
  adminCapabilityValues,
  auditLogs,
  users,
} from "@/database/schema";
import config from "@/lib/config";

const usage =
  "Usage: npm run auth:capability -- <grant|revoke> --capability <name> --target-user-id <uuid> --actor-user-id <uuid> --reason <10-500 chars> [--expires-at <ISO-8601>] [--bootstrap] [--confirm-protected-tier]";

const allowedOptions = new Set([
  "capability",
  "target-user-id",
  "actor-user-id",
  "reason",
  "expires-at",
  "bootstrap",
  "confirm-protected-tier",
]);

const parseArguments = (argv: string[]) => {
  const [operation, ...tokens] = argv;
  if (operation !== "grant" && operation !== "revoke") {
    throw new Error(usage);
  }

  const options = new Map<string, string>();
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (!token?.startsWith("--")) throw new Error(usage);

    const key = token.slice(2);
    if (!allowedOptions.has(key) || options.has(key)) {
      throw new Error(`Unknown or duplicate option --${key}. ${usage}`);
    }

    if (key === "bootstrap" || key === "confirm-protected-tier") {
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
    capability: options.get("capability"),
    targetUserId: options.get("target-user-id"),
    actorUserId: options.get("actor-user-id"),
    reason: options.get("reason"),
    expiresAt: options.get("expires-at"),
    bootstrap: options.get("bootstrap") === "true",
    confirmedProtectedTier:
      options.get("confirm-protected-tier") === "true",
  };
};

const inputSchema = z
  .object({
    operation: z.enum(["grant", "revoke"]),
    capability: z.enum(adminCapabilityValues),
    targetUserId: z.string().uuid(),
    actorUserId: z.string().uuid(),
    reason: z.string().trim().min(10).max(500),
    expiresAt: z
      .string()
      .datetime({ offset: true })
      .transform((value) => new Date(value))
      .optional(),
    bootstrap: z.boolean(),
    confirmedProtectedTier: z.boolean(),
  })
  .superRefine((input, context) => {
    if (input.operation === "revoke" && input.expiresAt) {
      context.addIssue({
        code: "custom",
        message: "--expires-at is valid only for grants",
      });
    }
    if (input.bootstrap && input.operation !== "grant") {
      context.addIssue({
        code: "custom",
        message: "--bootstrap is valid only for a grant",
      });
    }
  });

const activeAssignmentWhere = (
  userId: string,
  capability: (typeof adminCapabilityValues)[number],
  now: Date,
) =>
  and(
    eq(adminCapabilityAssignments.userId, userId),
    eq(adminCapabilityAssignments.capability, capability),
    isNull(adminCapabilityAssignments.revokedAt),
    or(
      isNull(adminCapabilityAssignments.expiresAt),
      gt(adminCapabilityAssignments.expiresAt, now),
    ),
  );

const manageCapability = async () => {
  const input = inputSchema.parse(parseArguments(process.argv.slice(2)));
  const protectedTier = ["staging", "production"].includes(
    config.env.appEnvironment,
  );
  if (protectedTier && !input.confirmedProtectedTier) {
    throw new Error(
      "--confirm-protected-tier is required in staging and production",
    );
  }
  if (input.expiresAt && input.expiresAt <= new Date()) {
    throw new Error("--expires-at must be in the future");
  }

  const outcome = await db.transaction(async (tx) => {
    // Serialize grants/revocations and one-time bootstrap evaluation.
    await tx.execute(sql`select pg_advisory_xact_lock(71202502)`);

    const [actor] = await tx
      .select({ id: users.id, role: users.role, status: users.status })
      .from(users)
      .where(eq(users.id, input.actorUserId))
      .limit(1)
      .for("update");
    if (!actor || actor.role !== "ADMIN" || actor.status !== "APPROVED") {
      throw new Error("Actor must be an existing approved administrator");
    }

    const [target] = await tx
      .select({ id: users.id, role: users.role, status: users.status })
      .from(users)
      .where(eq(users.id, input.targetUserId))
      .limit(1)
      .for("update");
    if (!target || target.role !== "ADMIN" || target.status !== "APPROVED") {
      throw new Error("Target must be an existing approved administrator");
    }

    const now = new Date();
    const [actorManagerAssignment] = await tx
      .select({ id: adminCapabilityAssignments.id })
      .from(adminCapabilityAssignments)
      .where(
        activeAssignmentWhere(
          input.actorUserId,
          "capabilities.manage",
          now,
        ),
      )
      .limit(1)
      .for("update");

    let isBootstrap = false;
    if (!actorManagerAssignment) {
      const [assignmentCount] = await tx
        .select({ count: count() })
        .from(adminCapabilityAssignments);
      isBootstrap =
        input.bootstrap &&
        process.env.ALLOW_ADMIN_CAPABILITY_BOOTSTRAP === "true" &&
        input.operation === "grant" &&
        input.capability === "capabilities.manage" &&
        input.actorUserId === input.targetUserId &&
        Number(assignmentCount?.count ?? 0) === 0;

      if (!isBootstrap) {
        throw new Error(
          "Actor requires capabilities.manage; the one-time bootstrap is separately gated",
        );
      }
    }

    if (
      input.operation === "grant" &&
      input.actorUserId === input.targetUserId &&
      !isBootstrap
    ) {
      throw new Error("Administrators cannot grant capabilities to themselves");
    }

    const [openAssignment] = await tx
      .select({
        id: adminCapabilityAssignments.id,
        expiresAt: adminCapabilityAssignments.expiresAt,
      })
      .from(adminCapabilityAssignments)
      .where(
        and(
          eq(adminCapabilityAssignments.userId, input.targetUserId),
          eq(adminCapabilityAssignments.capability, input.capability),
          isNull(adminCapabilityAssignments.revokedAt),
        ),
      )
      .limit(1)
      .for("update");

    if (input.operation === "grant") {
      if (openAssignment) {
        if (
          !openAssignment.expiresAt ||
          openAssignment.expiresAt.getTime() > now.getTime()
        ) {
          return "already-granted";
        }
        throw new Error(
          "The previous grant is expired but open; revoke it before re-granting",
        );
      }

      await tx.insert(adminCapabilityAssignments).values({
        userId: input.targetUserId,
        capability: input.capability,
        grantedBy: input.actorUserId,
        grantReason: input.reason,
        expiresAt: input.expiresAt,
      });
      return isBootstrap ? "bootstrapped" : "granted";
    }

    if (!openAssignment) return "already-revoked";

    if (input.capability === "capabilities.manage") {
      const [managerCount] = await tx
        .select({ count: count() })
        .from(adminCapabilityAssignments)
        .innerJoin(
          users,
          and(
            eq(users.id, adminCapabilityAssignments.userId),
            eq(users.role, "ADMIN"),
            eq(users.status, "APPROVED"),
          ),
        )
        .where(
          and(
            eq(
              adminCapabilityAssignments.capability,
              "capabilities.manage",
            ),
            isNull(adminCapabilityAssignments.revokedAt),
            or(
              isNull(adminCapabilityAssignments.expiresAt),
              gt(adminCapabilityAssignments.expiresAt, now),
            ),
          ),
        );
      if (Number(managerCount?.count ?? 0) <= 1) {
        throw new Error(
          "The final active capability manager cannot be revoked",
        );
      }
    }

    await tx
      .update(adminCapabilityAssignments)
      .set({
        revokedAt: now,
        revokedBy: input.actorUserId,
        revokeReason: input.reason,
      })
      .where(eq(adminCapabilityAssignments.id, openAssignment.id));
    return "revoked";
  });

  // No-op attempts do not mutate the assignment table, so record them here.
  if (outcome === "already-granted" || outcome === "already-revoked") {
    await db.insert(auditLogs).values({
      userId: input.actorUserId,
      action: "ADMIN_CAPABILITY_NOOP",
      targetId: input.targetUserId,
      targetType: "USER",
      details: JSON.stringify({
        capability: input.capability,
        operation: input.operation,
        outcome,
        reason: input.reason,
      }),
    });
  }

  console.log(`Administrative capability ${outcome}.`);
};

manageCapability()
  .catch((error) => {
    console.error(
      "Administrative capability operation failed:",
      error instanceof Error ? error.message : "unknown error",
    );
    process.exitCode = 1;
  })
  .finally(closeDb);
