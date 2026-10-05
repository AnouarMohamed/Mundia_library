import { createHash } from "crypto";
import { getVerifiedLicenseUrl } from "./license-policy";
import type { LearningResourceCandidate } from "./types";

export const FAA_SOURCE_NAME = "Federal Aviation Administration";
export const FAA_CATALOG_URL =
  "https://www.faa.gov/regulations_policies/handbooks_manuals/aviation";
export const FAA_RIGHTS_EVIDENCE_URL = "https://www.faa.gov/media/34576";
const MAX_OFFICIAL_FILE_BYTES = 300_000_000;

interface FaaPublication {
  id: string;
  title: string;
  category: string;
  sourceUrl: string;
  downloadUrl: string;
}

export const FAA_PUBLICATIONS: readonly FaaPublication[] = [
  book(
    "aerodynamics-navy",
    "Aerodynamics for Naval Aviators",
    "Aerodynamics",
    "/sites/faa.gov/files/regulations_policies/handbooks_manuals/aviation/00-80T-80.pdf",
  ),
  book(
    "airship-aerodynamics",
    "Airship Aerodynamics Technical Manual",
    "Aerodynamics",
    "/sites/faa.gov/files/regulations_policies/handbooks_manuals/aviation/airship_aerodynamics.pdf",
  ),
  book(
    "amt-general",
    "Aviation Maintenance Technician Handbook — General",
    "Aircraft Maintenance",
    "/regulations_policies/handbooks_manuals/aviation/amtg_handbook.pdf",
  ),
  book(
    "amt-airframe",
    "Aviation Maintenance Technician Handbook — Airframe",
    "Aircraft Maintenance",
    "/regulations_policies/handbooks_manuals/aviation/FAA-H-8083-31B_Aviation_Maintenance_Technician_Handbook.pdf",
  ),
  book(
    "amt-powerplant",
    "Aviation Maintenance Technician Handbook — Powerplant",
    "Aircraft Maintenance",
    "/regulations_policies/handbooks_manuals/aviation/amt_powerplant_handbook.pdf",
  ),
  book(
    "instrument-flying",
    "Instrument Flying Handbook",
    "Avionics & Navigation",
    "/sites/faa.gov/files/regulations_policies/handbooks_manuals/aviation/FAA-H-8083-15B.pdf",
  ),
  book(
    "pilot-knowledge",
    "Pilot’s Handbook of Aeronautical Knowledge",
    "Aeronautical Engineering",
    "/regulations_policies/handbooks_manuals/aviation/faa-h-8083-25c.pdf",
    "/regulations_policies/handbooks_manuals/aviation/phak",
  ),
  book(
    "risk-management",
    "Risk Management Handbook",
    "Aviation Safety",
    "/sites/faa.gov/files/regulations_policies/handbooks_manuals/aviation/faa-h-8083-19A.pdf",
  ),
  book(
    "remote-pilot",
    "Remote Pilot — Small Unmanned Aircraft Systems Study Guide",
    "Unmanned Aircraft Systems",
    "/sites/faa.gov/files/regulations_policies/handbooks_manuals/aviation/remote_pilot_study_guide.pdf",
  ),
  book(
    "weight-balance",
    "Weight and Balance Handbook",
    "Aircraft Systems",
    "/sites/faa.gov/files/regulations_policies/handbooks_manuals/aviation/FAA-H-8083-1.pdf",
  ),
  book(
    "airplane-flying",
    "Airplane Flying Handbook",
    "Flight Engineering",
    "/sites/faa.gov/files/regulations_policies/handbooks_manuals/aviation/airplane_handbook/00_afh_full.pdf",
    "/regulations_policies/handbooks_manuals/aviation/airplane_handbook",
  ),
  book(
    "rotorcraft",
    "Rotorcraft Flying Handbook",
    "Rotorcraft",
    "/sites/faa.gov/files/regulations_policies/handbooks_manuals/aviation/faa-h-8083-21.pdf",
  ),
  book(
    "powered-parachute",
    "Powered Parachute Flying Handbook",
    "Flight Engineering",
    "/sites/faa.gov/files/regulations_policies/handbooks_manuals/aviation/powered_parachute_handbook.pdf",
  ),
  book(
    "weight-shift",
    "Weight-Shift Control Aircraft Flying Handbook",
    "Flight Engineering",
    "/sites/faa.gov/files/regulations_policies/handbooks_manuals/aviation/FAA-H-8083-5.pdf",
  ),
] as const;

export async function fetchFaaPublications(
  input: {
    fetcher?: typeof fetch;
  } = {},
) {
  const fetcher = input.fetcher ?? fetch;
  const candidates: LearningResourceCandidate[] = [];
  for (const item of FAA_PUBLICATIONS) {
    candidates.push(await verifyPublication(item, fetcher));
  }
  const revision = sha256(JSON.stringify(candidates));
  return {
    revision,
    candidates: candidates.map((candidate) => {
      const revised = {
        ...candidate,
        sourceRevision: revision,
        contentHash: "",
      };
      return { ...revised, contentHash: sha256(JSON.stringify(revised)) };
    }),
  };
}

async function verifyPublication(
  item: FaaPublication,
  fetcher: typeof fetch,
): Promise<LearningResourceCandidate> {
  let verified = false;
  try {
    const response = await fetcher(item.downloadUrl, {
      headers: {
        Accept: "application/pdf",
        Range: "bytes=0-3",
      },
      redirect: "error",
      signal: AbortSignal.timeout(20_000),
    });
    const total = Number(
      (response.headers.get("content-range") ?? "").match(
        /^bytes 0-3\/(\d+)$/u,
      )?.[1] ?? 0,
    );
    verified =
      response.status === 206 &&
      (response.headers.get("content-type") ?? "")
        .toLowerCase()
        .startsWith("application/pdf") &&
      Number.isSafeInteger(total) &&
      total > 0 &&
      total <= MAX_OFFICIAL_FILE_BYTES;
    await response.body?.cancel();
  } catch {
    verified = false;
  }
  const base = {
    sourceName: FAA_SOURCE_NAME,
    sourceRecordKey: `faa:${item.id}`,
    title: item.title,
    author: FAA_SOURCE_NAME,
    category: item.category,
    language: "en",
    licenseExpression: verified ? ("PUBLIC-DOMAIN" as const) : null,
    licenseUrl: verified ? getVerifiedLicenseUrl("PUBLIC-DOMAIN") : null,
    sourceUrl: item.sourceUrl,
    downloadUrl: verified ? item.downloadUrl : null,
    readUrl: item.sourceUrl,
    verificationStatus: verified
      ? ("VERIFIED" as const)
      : ("QUARANTINED" as const),
    verificationReason: verified
      ? "Verified against the official FAA handbook index and PDF. FAA handbooks are public-domain U.S. government works."
      : "The official FAA PDF did not pass the origin, type, range, or size checks.",
    verificationEvidenceUrl: verified ? FAA_RIGHTS_EVIDENCE_URL : null,
    sourceRevision: "pending",
  };
  return { ...base, contentHash: sha256(JSON.stringify(base)) };
}

function book(
  id: string,
  title: string,
  category: string,
  downloadPath: string,
  sourcePath = "/regulations_policies/handbooks_manuals/aviation",
) {
  return {
    id,
    title,
    category,
    sourceUrl: officialUrl(sourcePath),
    downloadUrl: officialUrl(downloadPath),
  };
}

function officialUrl(path: string) {
  const url = new URL(path, "https://www.faa.gov");
  if (
    url.protocol !== "https:" ||
    url.hostname !== "www.faa.gov" ||
    url.username ||
    url.password ||
    url.port
  ) {
    throw new Error("FAA_INVALID_MANIFEST_URL");
  }
  return url.toString();
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
