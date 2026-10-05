export const verifiedLicenseExpressions = [
  "CC-BY",
  "CC-BY-SA",
  "CC0",
  "PUBLIC-DOMAIN",
] as const;

export type VerifiedLicenseExpression =
  (typeof verifiedLicenseExpressions)[number];

export type LearningResourceVerificationStatus =
  "QUARANTINED" | "VERIFIED" | "REJECTED";

export interface LearningResourceCandidate {
  sourceName: string;
  sourceRecordKey: string;
  title: string;
  author: string | null;
  description?: string | null;
  coverUrl?: string | null;
  coverAlt?: string | null;
  category: string;
  language: string;
  licenseExpression: VerifiedLicenseExpression | null;
  licenseUrl: string | null;
  sourceUrl: string;
  downloadUrl: string | null;
  readUrl: string | null;
  verificationStatus: LearningResourceVerificationStatus;
  verificationReason: string;
  verificationEvidenceUrl: string | null;
  contentHash: string;
  sourceRevision: string;
}

export interface LearningResourceSourceSnapshot {
  revision: string;
  content: string;
}
