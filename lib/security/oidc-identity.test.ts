import { describe, expect, it, vi } from "vitest";
import {
  FederatedIdentityRejectedError,
  resolveInstitutionalUser,
  validateInstitutionalProfile,
  type FederatedIdentityLookup,
  type InstitutionalOidcProfile,
} from "@/lib/security/oidc-identity";

const settings = {
  issuer: "https://identity.example.test/tenant",
  allowedEmailDomains: ["student.example.test", "staff.example.test"],
};

const profile = (
  overrides: Partial<InstitutionalOidcProfile> = {},
): InstitutionalOidcProfile => ({
  iss: settings.issuer,
  sub: "stable-provider-subject",
  email: "student@student.example.test",
  email_verified: true,
  ...overrides,
});

const localUser = {
  id: "00000000-0000-4000-8000-000000000001",
  email: "student@student.example.test",
  name: "Student",
  role: "USER" as const,
  status: "APPROVED" as const,
  universityId: 90000001,
  federatedBindingId: "10000000-0000-4000-8000-000000000001",
};

const rejectionReason = (run: () => unknown) => {
  try {
    run();
  } catch (error) {
    expect(error).toBeInstanceOf(FederatedIdentityRejectedError);
    return (error as FederatedIdentityRejectedError).reason;
  }

  throw new Error("Expected validation to reject the profile");
};

describe("institutional OIDC identity validation", () => {
  it("retains the exact issuer and subject and normalizes only email", () => {
    expect(
      validateInstitutionalProfile(
        profile({ email: "Student@Student.Example.Test" }),
        settings,
      ),
    ).toEqual({
      issuer: settings.issuer,
      subject: "stable-provider-subject",
      email: "student@student.example.test",
    });
  });

  it("rejects an issuer that differs by even a trailing slash", () => {
    expect(
      rejectionReason(() =>
        validateInstitutionalProfile(
          profile({ iss: `${settings.issuer}/` }),
          settings,
        ),
      ),
    ).toBe("issuer_mismatch");
  });

  it("requires a boolean verified-email claim", () => {
    expect(
      rejectionReason(() =>
        validateInstitutionalProfile(
          profile({ email_verified: "true" }),
          settings,
        ),
      ),
    ).toBe("email_not_verified");
  });

  it("matches the email domain exactly rather than by suffix", () => {
    expect(
      rejectionReason(() =>
        validateInstitutionalProfile(
          profile({ email: "student@evil-student.example.test" }),
          settings,
        ),
      ),
    ).toBe("email_domain_not_allowed");
  });

  it("rejects malformed claims before attempting a database lookup", async () => {
    const lookup = vi.fn<FederatedIdentityLookup>();

    await expect(
      resolveInstitutionalUser(
        profile({ sub: "subject\nforged-log-line" }),
        settings,
        lookup,
      ),
    ).rejects.toMatchObject({ reason: "invalid_subject" });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("does not discover a local user by email when the tuple is unprovisioned", async () => {
    const lookup = vi.fn<FederatedIdentityLookup>().mockResolvedValue(null);

    await expect(
      resolveInstitutionalUser(profile(), settings, lookup),
    ).rejects.toMatchObject({ reason: "identity_not_provisioned" });
    expect(lookup).toHaveBeenCalledExactlyOnceWith({
      issuer: settings.issuer,
      subject: "stable-provider-subject",
      email: "student@student.example.test",
    });
  });

  it("returns the stable local account from a provisioned tuple", async () => {
    const lookup = vi
      .fn<FederatedIdentityLookup>()
      .mockResolvedValue(localUser);

    await expect(
      resolveInstitutionalUser(profile(), settings, lookup),
    ).resolves.toEqual(localUser);
  });
});
