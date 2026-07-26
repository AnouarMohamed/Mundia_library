import { users } from "@/database/schema";

/**
 * Explicit browser-safe projection. Never replace this with db.select() because
 * full user rows include password hashes.
 */
export const adminUserColumns = {
  id: users.id,
  fullName: users.fullName,
  email: users.email,
  universityId: users.universityId,
  status: users.status,
  role: users.role,
  lastActivityDate: users.lastActivityDate,
  lastLogin: users.lastLogin,
  createdAt: users.createdAt,
};
