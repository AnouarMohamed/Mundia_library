"use server";

import { eq } from "drizzle-orm";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { db } from "@/database/drizzle";
import {
  auditLogs,
  learningResourceReviews,
  learningResources,
} from "@/database/schema";
import { requireAdmin } from "@/lib/security/auth-guards";
import {
  getVerifiedLicenseUrl,
  isVerifiedLicenseExpression,
} from "@/lib/learning-resources/license-policy";

const reviewSchema = z.object({
  resourceId: z.string().uuid(),
  decision: z.enum(["APPROVED", "REJECTED"]),
  reason: z.string().trim().min(10).max(1000),
  licenseExpression: z.string().max(64).optional(),
  evidenceUrl: z
    .string()
    .trim()
    .max(2048)
    .url()
    .refine((value) => {
      const url = new URL(value);
      return (
        url.protocol === "https:" &&
        !url.username &&
        !url.password &&
        !url.hash
      );
    })
    .optional(),
});

export async function reviewLearningResource(formData: FormData) {
  const guard = await requireAdmin();
  if (!guard.ok) throw new Error("Unauthorized");
  const parsed = reviewSchema.safeParse({
    resourceId: formData.get("resourceId"),
    decision: formData.get("decision"),
    reason: formData.get("reason"),
    licenseExpression: formData.get("licenseExpression") || undefined,
    evidenceUrl: formData.get("evidenceUrl") || undefined,
  });
  if (!parsed.success) throw new Error("Invalid review submission");
  if (
    parsed.data.decision === "APPROVED" &&
    (!parsed.data.licenseExpression ||
      !isVerifiedLicenseExpression(parsed.data.licenseExpression) ||
      !parsed.data.evidenceUrl)
  ) {
    throw new Error("Approval requires an allowlisted licence and HTTPS evidence");
  }

  await db.transaction(async (tx) => {
    const [resource] = await tx
      .select()
      .from(learningResources)
      .where(eq(learningResources.id, parsed.data.resourceId))
      .limit(1)
      .for("update");
    if (!resource) throw new Error("Learning resource not found");
    if (resource.verificationStatus !== "QUARANTINED") return;

    const now = new Date();
    const approved = parsed.data.decision === "APPROVED";
    const expression = approved
      ? parsed.data.licenseExpression
      : resource.licenseExpression;
    await tx.insert(learningResourceReviews).values({
      resourceId: resource.id,
      reviewerId: guard.user.id,
      decision: parsed.data.decision,
      reason: parsed.data.reason,
    });
    await tx
      .update(learningResources)
      .set({
        verificationStatus: approved ? "VERIFIED" : "REJECTED",
        verificationReason: parsed.data.reason,
        verificationEvidenceUrl: approved
          ? parsed.data.evidenceUrl
          : resource.verificationEvidenceUrl,
        licenseExpression: expression,
        licenseUrl:
          approved && expression && isVerifiedLicenseExpression(expression)
            ? getVerifiedLicenseUrl(expression)
            : resource.licenseUrl,
        verifiedAt: approved ? now : null,
        verifiedBy: approved ? guard.user.id : null,
        updatedAt: now,
      })
      .where(eq(learningResources.id, resource.id));
    await tx.insert(auditLogs).values({
      userId: guard.user.id,
      action: approved
        ? "APPROVE_LEARNING_RESOURCE"
        : "REJECT_LEARNING_RESOURCE",
      targetId: resource.id,
      targetType: "LEARNING_RESOURCE",
      details: JSON.stringify({
        sourceName: resource.sourceName,
        decision: parsed.data.decision,
        licenseExpression: approved ? expression : undefined,
      }),
    });
  });

  revalidatePath("/resources");
  revalidatePath(`/resources/${parsed.data.resourceId}`);
  revalidatePath("/admin/learning-resources");
}
