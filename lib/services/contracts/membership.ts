/**
 * Phase 4 Membership Service Contract Definitions
 *
 * Defines canonical data contracts and client interfaces for the Membership Service,
 * decoupling user eligibility, profile management, and identity evidence handling
 * from the monolithic Next.js database schema.
 */

export type AccountStatus = "PENDING" | "APPROVED" | "REJECTED";
export type MembershipRole = "USER" | "ADMIN";

export interface MemberProfile {
  memberId: string;
  email: string;
  fullName: string;
  universityId: number;
  status: AccountStatus;
  role: MembershipRole;
  createdAt: string;
  updatedAt: string;
}

export interface MemberEligibility {
  memberId: string;
  eligible: boolean;
  status: AccountStatus;
  maxActiveLoans: number;
  currentActiveLoans: number;
  hasUnpaidOverdueFines: boolean;
  reason?: string;
  evaluatedAt: string;
}

export interface IdentityEvidenceRef {
  evidenceId: string;
  memberId: string;
  mimeType: string;
  fileSize: number;
  checksumSha256: string;
  uploadedAt: string;
  signedReadUrl?: string;
}

export interface MembershipService {
  getMemberProfile(memberId: string): Promise<MemberProfile | null>;
  checkEligibility(memberId: string): Promise<MemberEligibility>;
  getIdentityEvidenceRef(memberId: string): Promise<IdentityEvidenceRef | null>;
}
