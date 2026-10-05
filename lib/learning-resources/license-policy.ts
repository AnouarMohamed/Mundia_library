import type { VerifiedLicenseExpression } from "./types";

interface LicenseDecision {
  expression: VerifiedLicenseExpression | null;
  licenseUrl: string | null;
  verified: boolean;
  reason: string;
}

const LICENSE_URLS: Record<VerifiedLicenseExpression, string> = {
  "CC-BY": "https://creativecommons.org/licenses/by/4.0/",
  "CC-BY-SA": "https://creativecommons.org/licenses/by-sa/4.0/",
  CC0: "https://creativecommons.org/publicdomain/zero/1.0/",
  "PUBLIC-DOMAIN": "https://creativecommons.org/publicdomain/mark/1.0/",
};

const disallowedLicense =
  /\b(?:CC\s*[- ]?BY\s*[- ]?(?:NC|ND)|NON[- ]?COMMERCIAL|ALL RIGHTS RESERVED|COPYRIGHTED)\b/i;

/**
 * Fail-closed licence classification. Repository visibility and a repository
 * licence never substitute for an explicit per-resource rights statement.
 */
export function classifyResourceLicense(value: string): LicenseDecision {
  if (disallowedLicense.test(value)) {
    return {
      expression: null,
      licenseUrl: null,
      verified: false,
      reason: "The stated licence is outside the redistribution allowlist.",
    };
  }

  const expression = detectAllowedLicense(value);
  if (!expression) {
    return {
      expression: null,
      licenseUrl: null,
      verified: false,
      reason: "No explicit allowlisted per-book licence was found.",
    };
  }

  return {
    expression,
    licenseUrl: canonicalLicenseUrl(expression, value),
    verified: true,
    reason: `Automatically verified from an explicit ${expression} marker in the source catalog.`,
  };
}

function canonicalLicenseUrl(
  expression: VerifiedLicenseExpression,
  evidence: string,
) {
  if (expression !== "CC-BY" && expression !== "CC-BY-SA") {
    return LICENSE_URLS[expression];
  }
  const slug = expression === "CC-BY" ? "by" : "by-sa";
  const escapedSlug = slug.replace("-", "[- ]?");
  const version =
    new RegExp(`creativecommons\\.org/licenses/${slug}/([1-4]\\.0)`, "iu").exec(
      evidence,
    )?.[1] ??
    new RegExp(`CC\\s*[- ]?${escapedSlug}\\s*([1-4]\\.0)`, "iu").exec(
      evidence,
    )?.[1];
  return version
    ? `https://creativecommons.org/licenses/${slug}/${version}/`
    : LICENSE_URLS[expression];
}

function detectAllowedLicense(value: string): VerifiedLicenseExpression | null {
  if (/\bCC\s*[- ]?BY\s*[- ]?SA(?:\s*[0-9.]+)?\b/i.test(value)) {
    return "CC-BY-SA";
  }
  if (/\bCC\s*[- ]?BY(?:\s*[0-9.]+)?\b/i.test(value)) {
    return "CC-BY";
  }
  if (/\bCC\s*0(?:\s*[0-9.]+)?\b|\bCC0(?:\s*[0-9.]+)?\b/i.test(value)) {
    return "CC0";
  }
  if (/\bPUBLIC DOMAIN\b|\bPDM(?:\s*[0-9.]+)?\b/i.test(value)) {
    return "PUBLIC-DOMAIN";
  }
  return null;
}

export function isVerifiedLicenseExpression(
  value: string,
): value is VerifiedLicenseExpression {
  return Object.hasOwn(LICENSE_URLS, value);
}

export function getVerifiedLicenseUrl(expression: VerifiedLicenseExpression) {
  return LICENSE_URLS[expression];
}
