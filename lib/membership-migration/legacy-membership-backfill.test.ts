import { describe, expect, it, vi } from "vitest";
import {
  applyLegacyMembershipBatch,
  planLegacyMembershipBackfill,
  type LegacyMembershipRecord,
} from "./legacy-membership-backfill";

const record = (suffix: string, role: LegacyMembershipRecord["role"] = "USER"): LegacyMembershipRecord => ({
  id: `10000000-0000-4000-8000-${suffix.padStart(12, "0")}`,
  email: `Member${suffix}@Example.edu`,
  fullName: `Member ${suffix}`,
  universityId: Number(suffix) + 100,
  status: "APPROVED",
  role,
  universityCard: `private-legacy-reference-${suffix}`,
  maxActiveLoans: 5,
  currentActiveLoans: 1,
  hasUnpaidOverdueFines: false,
  createdAt: new Date("2025-04-01T09:00:00Z"),
  updatedAt: new Date("2025-04-02T09:00:00Z"),
});

describe("legacy membership backfill", () => {
  it("creates stable batches and strips raw evidence references", () => {
    const first = planLegacyMembershipBackfill([record("1", "SUPER_ADMIN"), record("2")], 1);
    const second = planLegacyMembershipBackfill([record("2"), record("1", "SUPER_ADMIN")], 1);
    expect(first).toEqual(second);
    expect(first.status).toBe("READY");
    expect(first.batches).toHaveLength(2);
    expect(JSON.stringify(first.batches)).not.toContain("private-legacy-reference");
    expect(first.batches[0]!.items[0]!.evidenceReferenceSha256).toMatch(/^[0-9a-f]{64}$/);
  });

  it("blocks duplicate identities and a snapshot without an approved administrator", () => {
    const first = record("1");
    const second = record("2");
    second.email = first.email.toLowerCase();
    const plan = planLegacyMembershipBackfill([first, second]);
    expect(plan.status).toBe("BLOCKED");
    expect(plan.batches).toEqual([]);
    expect(plan.findings.flatMap((finding) => finding.fields)).toContain("missingApprovedAdministrator");
    expect(plan.findings.flatMap((finding) => finding.fields).some((field) => field.startsWith("emailDuplicate:"))).toBe(true);
  });

  it("applies and reconciles immutable privacy-safe counts", async () => {
    const batch = planLegacyMembershipBackfill([record("1", "ADMIN")]).batches[0]!;
    const receipt = {
      sourceRevision: batch.sourceRevision,
      manifestSha256: "c".repeat(64),
      memberCount: 1,
      quarantinedEvidenceCount: 1,
    };
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(receipt), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(receipt), { status: 200 }));
    await expect(applyLegacyMembershipBatch({
      baseUrl: "https://membership.example.test",
      bearerToken: "migration-token-that-is-never-logged",
      batch,
      fetcher,
    })).resolves.toEqual(receipt);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
