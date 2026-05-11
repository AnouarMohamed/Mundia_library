"use server";

import { updateUserStatus } from "@/lib/admin/actions/user";
import { redirect } from "next/navigation";

/**
 * Approve a pending user account request.
 */
export async function approveUserAction(userId: string) {
  const result = await updateUserStatus(userId, "APPROVED");
  if (result.success) {
    redirect("/admin/account-requests?success=account-approved");
  } else {
    redirect("/admin/account-requests?error=failed");
  }
}

/**
 * Reject a pending user account request.
 */
export async function rejectUserAction(userId: string) {
  const result = await updateUserStatus(userId, "REJECTED");
  if (result.success) {
    redirect("/admin/account-requests?success=account-rejected");
  } else {
    redirect("/admin/account-requests?error=failed");
  }
}
