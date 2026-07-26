import { and, eq } from "drizzle-orm";
import type { User } from "next-auth";
import { z } from "zod";
import { db } from "@/database/drizzle";
import { federatedIdentities, users } from "@/database/schema";

export type InstitutionalOidcProfile = Record<string, unknown>;

export type ValidatedInstitutionalIdentity = {
  issuer: string;
  subject: string;
  email: string;
};

export type InstitutionalOidcSettings = {
  issuer: string;
  allowedEmailDomains: readonly string[];
};

export type FederatedLocalUser = User & {
  id: string;
  email: string;
  name: string;
  role: "USER" | "ADMIN";
  status: "APPROVED";
  universityId: number;
  /** Opaque revocation handle, not an IdP token or provider identifier. */
  federatedBindingId: string;
};

export type FederatedIdentityRejection =
  | "issuer_mismatch"
  | "invalid_subject"
  | "email_not_verified"
  | "invalid_email"
  | "email_domain_not_allowed"
  | "identity_not_provisioned"
  | "local_account_not_approved"
  | "local_email_mismatch";

/**
 * Expected identity denials are intentionally represented by a stable reason
 * code. Claim values are never embedded in the error or application logs.
 */
export class FederatedIdentityRejectedError extends Error {
  constructor(readonly reason: FederatedIdentityRejection) {
    super("Institutional identity was rejected");
    this.name = "FederatedIdentityRejectedError";
  }
}

const emailSchema = z.string().email().max(254);
const hasUnsafeSubjectCharacter = (value: string) =>
  Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined && (codePoint <= 31 || codePoint === 127);
  });

/**
 * Validate defense-in-depth claims after Auth.js has completed the OIDC
 * protocol and ID-token checks. No normalization is applied to issuer or
 * subject because their exact tuple is the security identifier.
 */
export const validateInstitutionalProfile = (
  profile: InstitutionalOidcProfile,
  settings: InstitutionalOidcSettings,
): ValidatedInstitutionalIdentity => {
  if (typeof profile.iss !== "string" || profile.iss !== settings.issuer) {
    throw new FederatedIdentityRejectedError("issuer_mismatch");
  }

  if (
    typeof profile.sub !== "string" ||
    profile.sub.length === 0 ||
    profile.sub.length > 1024 ||
    hasUnsafeSubjectCharacter(profile.sub)
  ) {
    throw new FederatedIdentityRejectedError("invalid_subject");
  }

  if (profile.email_verified !== true) {
    throw new FederatedIdentityRejectedError("email_not_verified");
  }

  if (
    typeof profile.email !== "string" ||
    profile.email.trim() !== profile.email ||
    !emailSchema.safeParse(profile.email).success
  ) {
    throw new FederatedIdentityRejectedError("invalid_email");
  }

  const email = profile.email.toLowerCase();
  const domain = email.slice(email.lastIndexOf("@") + 1);
  if (!settings.allowedEmailDomains.includes(domain)) {
    throw new FederatedIdentityRejectedError("email_domain_not_allowed");
  }

  return {
    issuer: profile.iss,
    subject: profile.sub,
    email,
  };
};

export type FederatedIdentityLookup = (
  identity: ValidatedInstitutionalIdentity,
) => Promise<FederatedLocalUser | null>;

/**
 * Resolve an exact, pre-provisioned issuer/subject tuple under row locks.
 *
 * The local account supplies all authorization state. Verified institutional
 * email is only a post-binding defense; it is never used to discover or link a
 * local account. Locking both rows prevents deprovisioning or suspension from
 * racing a successful login.
 */
export const lookupFederatedLocalUser: FederatedIdentityLookup = async (
  identity,
) =>
  db.transaction(async (tx) => {
    const rows = await tx
      .select({
        federatedBindingId: federatedIdentities.bindingId,
        id: users.id,
        email: users.email,
        fullName: users.fullName,
        role: users.role,
        status: users.status,
        universityId: users.universityId,
      })
      .from(federatedIdentities)
      .innerJoin(users, eq(federatedIdentities.userId, users.id))
      .where(
        and(
          eq(federatedIdentities.issuer, identity.issuer),
          eq(federatedIdentities.subject, identity.subject),
        ),
      )
      .limit(1)
      .for("update");

    const localUser = rows[0];
    if (!localUser) return null;
    if (localUser.status !== "APPROVED") {
      throw new FederatedIdentityRejectedError("local_account_not_approved");
    }
    if (localUser.email.toLowerCase() !== identity.email) {
      throw new FederatedIdentityRejectedError("local_email_mismatch");
    }

    const updated = await tx
      .update(users)
      .set({ lastLogin: new Date() })
      .where(and(eq(users.id, localUser.id), eq(users.status, "APPROVED")))
      .returning({ id: users.id });
    if (updated.length !== 1) {
      throw new FederatedIdentityRejectedError("local_account_not_approved");
    }

    return {
      id: localUser.id,
      email: localUser.email,
      name: localUser.fullName,
      role: localUser.role,
      status: "APPROVED" as const,
      universityId: localUser.universityId,
      federatedBindingId: localUser.federatedBindingId,
    };
  });

/**
 * Convert a provider profile to the stable local Auth.js user. The injectable
 * lookup keeps claim/binding behavior independently testable.
 */
export const resolveInstitutionalUser = async (
  profile: InstitutionalOidcProfile,
  settings: InstitutionalOidcSettings,
  lookup: FederatedIdentityLookup = lookupFederatedLocalUser,
): Promise<FederatedLocalUser> => {
  const identity = validateInstitutionalProfile(profile, settings);
  const localUser = await lookup(identity);

  if (!localUser) {
    throw new FederatedIdentityRejectedError("identity_not_provisioned");
  }

  return localUser;
};
