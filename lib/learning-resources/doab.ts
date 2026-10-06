import { createHash } from "crypto";
import { XMLParser, XMLValidator } from "fast-xml-parser";
import { classifyResourceLicense } from "./license-policy";
import { decodeDisplayText } from "./display-text";
import type { LearningResourceCandidate } from "./types";

const SOURCE_NAME = "Directory of Open Access Books";
const OAI_ENDPOINT = "https://directory.doabooks.org/oai/request";
const MAX_RESPONSE_BYTES = 5_000_000;
const MAX_COVER_BYTES = 2_000_000;
const ADAPTER_VERSION = "doab-v7";

type XmlNode = Record<string, unknown>;

export interface DoabPage {
  revision: string;
  candidates: LearningResourceCandidate[];
  nextToken: string | null;
  sourceRecords: number;
}

export async function fetchDoabPage(input: {
  from?: string;
  resumptionToken?: string;
  fetcher?: typeof fetch;
}): Promise<DoabPage> {
  if (input.from && input.resumptionToken) {
    throw new Error("DOAB_CURSOR_CONFLICT");
  }
  if (input.from && !/^\d{4}-\d{2}-\d{2}$/.test(input.from)) {
    throw new Error("DOAB_INVALID_FROM_DATE");
  }
  if (input.resumptionToken && input.resumptionToken.length > 2_048) {
    throw new Error("DOAB_INVALID_RESUMPTION_TOKEN");
  }

  const endpoint = new URL(OAI_ENDPOINT);
  endpoint.searchParams.set("verb", "ListRecords");
  if (input.resumptionToken) {
    endpoint.searchParams.set("resumptionToken", input.resumptionToken);
  } else {
    endpoint.searchParams.set("metadataPrefix", "xoai");
    if (input.from) endpoint.searchParams.set("from", input.from);
  }

  const response = await (input.fetcher ?? fetch)(endpoint, {
    headers: {
      Accept: "application/xml, text/xml;q=0.9",
      "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
    },
    redirect: "error",
    signal: AbortSignal.timeout(20_000),
  });
  if (!response.ok) throw new Error(`DOAB_HTTP_${response.status}`);
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("xml")) {
    throw new Error("DOAB_INVALID_CONTENT_TYPE");
  }
  const declaredLength = Number(response.headers.get("content-length") ?? 0);
  if (declaredLength > MAX_RESPONSE_BYTES) {
    throw new Error("DOAB_RESPONSE_TOO_LARGE");
  }
  const content = await response.text();
  if (Buffer.byteLength(content, "utf8") > MAX_RESPONSE_BYTES) {
    throw new Error("DOAB_RESPONSE_TOO_LARGE");
  }
  return parseDoabPage(content);
}

export function parseDoabPage(content: string): DoabPage {
  if (!content || /<!DOCTYPE|<!ENTITY/iu.test(content)) {
    throw new Error("DOAB_UNSAFE_XML");
  }
  if (XMLValidator.validate(content) !== true) {
    throw new Error("DOAB_INVALID_XML");
  }
  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: "@_",
    parseTagValue: false,
    processEntities: false,
    trimValues: true,
  });
  const parsed = parser.parse(content) as XmlNode;
  const root = asNode(parsed["OAI-PMH"]);
  const list = asNode(root?.ListRecords);
  if (!root || !list) {
    throw new Error("DOAB_INVALID_OAI_RESPONSE");
  }
  const records = asArray(list.record).map(asNode).filter(isNode);
  const tokenNode = list.resumptionToken;
  const nextToken =
    typeof tokenNode === "string"
      ? normalizeText(tokenNode) || null
      : normalizeText(String(asNode(tokenNode)?.["#text"] ?? "")) || null;
  // OAI-PMH requires datestamp changes when a record changes. Hash the ordered
  // identifier/datestamp page plus its continuation token, excluding volatile
  // XML serialization and envelope fields.
  const recordVersions = records.map((record) => {
    const header = asNode(record.header);
    return {
      identifier: normalizeText(String(header?.identifier ?? "")),
      datestamp: normalizeText(String(header?.datestamp ?? "")),
      status: normalizeText(String(header?.["@_status"] ?? "")),
    };
  });
  const revision = sha256(
    JSON.stringify({
      adapterVersion: ADAPTER_VERSION,
      recordVersions,
      nextToken,
    }),
  );
  const candidates = records
    .map((record) => parseRecord(record, revision))
    .filter((candidate): candidate is LearningResourceCandidate =>
      Boolean(candidate),
    );
  return { revision, candidates, nextToken, sourceRecords: records.length };
}

function parseRecord(
  record: XmlNode,
  revision: string,
): LearningResourceCandidate | null {
  const header = asNode(record.header);
  const identifier = normalizeText(String(header?.identifier ?? ""));
  const metadata = asNode(asNode(record.metadata)?.metadata);
  if (!identifier || identifier.length > 512 || !metadata) return null;

  const fields = collectFields(metadata.element);
  const title = firstField(fields, "dc.title")?.slice(0, 500);
  const subjects = fields
    .filter(({ path }) => path.startsWith("dc.subject."))
    .map(({ value }) => value);
  if (!title || !isEngineeringResource(title, subjects)) return null;

  const sourceUrl = normalizeHttpsUrl(firstField(fields, "dc.identifier.uri"));
  if (!sourceUrl) return null;
  const bitstreams = collectBitstreams(metadata.element);
  const selected = chooseBitstream(bitstreams);
  const licenceText = [selected?.rights, selected?.rightsUri]
    .filter(Boolean)
    .join(" ");
  const licence = classifyResourceLicense(licenceText);
  const evidenceUrl = normalizeHttpsUrl(selected?.rightsUri ?? null);
  const verified = licence.verified && Boolean(evidenceUrl);
  const downloadUrl = normalizeHttpsUrl(selected?.downloadUrl ?? null);
  const author =
    firstField(fields, "dc.contributor.author") ??
    firstField(fields, "dc.contributor.editor");
  const description = cleanText(firstField(fields, "dc.description"), 4_000);
  const language = normalizeLanguage(firstField(fields, "dc.language"));
  const category = classifyCategory(title, subjects);
  const coverUrl = verified ? chooseCoverUrl(bitstreams) : null;
  const base = {
    sourceName: SOURCE_NAME,
    sourceRecordKey: identifier,
    title,
    author: author?.slice(0, 500) ?? null,
    description,
    coverUrl,
    coverAlt: coverUrl ? `Official cover of ${title}`.slice(0, 300) : null,
    category,
    language,
    licenseExpression: verified ? licence.expression : null,
    licenseUrl: verified ? licence.licenseUrl : null,
    sourceUrl,
    downloadUrl,
    readUrl: downloadUrl ? null : sourceUrl,
    verificationStatus: verified
      ? ("VERIFIED" as const)
      : ("QUARANTINED" as const),
    verificationReason: verified
      ? "Verified from the per-file licence URI in the official DOAB OAI record."
      : licence.reason || "No allowlisted per-file licence evidence was found.",
    verificationEvidenceUrl: verified ? evidenceUrl : null,
    sourceRevision: revision,
  };
  return { ...base, contentHash: sha256(JSON.stringify(base)) };
}

interface FieldValue {
  path: string;
  value: string;
}

function collectFields(value: unknown, parent = ""): FieldValue[] {
  const fields: FieldValue[] = [];
  for (const node of asArray(value).map(asNode).filter(isNode)) {
    const name = normalizeText(String(node["@_name"] ?? ""));
    const path = name ? (parent ? `${parent}.${name}` : name) : parent;
    for (const field of asArray(node.field)) {
      const fieldNode = asNode(field);
      const raw = fieldNode ? fieldNode["#text"] : field;
      const text = normalizeText(String(raw ?? ""));
      if (text) fields.push({ path, value: text });
    }
    fields.push(...collectFields(node.element, path));
  }
  return fields;
}

interface Bitstream {
  assetUrl: string | null;
  downloadUrl: string | null;
  description: string | null;
  format: string | null;
  size: number | null;
  rights: string | null;
  rightsUri: string | null;
}

function collectBitstreams(value: unknown): Bitstream[] {
  const output: Bitstream[] = [];
  for (const node of asArray(value).map(asNode).filter(isNode)) {
    if (node["@_name"] === "bitstream") {
      const fields = new Map<string, string>();
      for (const field of asArray(node.field)) {
        const fieldNode = asNode(field);
        const name = normalizeText(String(fieldNode?.["@_name"] ?? ""));
        const text = normalizeText(String(fieldNode?.["#text"] ?? ""));
        if (name && text && !fields.has(name)) fields.set(name, text);
      }
      output.push({
        assetUrl: fields.get("url") ?? null,
        downloadUrl:
          fields.get("oapenidentifierdownloadUrl") ?? fields.get("url") ?? null,
        description: fields.get("description") ?? null,
        format: fields.get("format") ?? null,
        size: toPositiveInteger(fields.get("size")),
        rights: fields.get("rights") ?? null,
        rightsUri: fields.get("rightsuri") ?? null,
      });
    }
    output.push(...collectBitstreams(node.element));
  }
  return output;
}

function chooseCoverUrl(values: Bitstream[]) {
  for (const item of values) {
    if (
      !/^image\/(?:jpeg|png|webp)$/iu.test(item.format ?? "") ||
      !item.size ||
      item.size > MAX_COVER_BYTES
    ) {
      continue;
    }
    const normalized = normalizeDoabCoverUrl(item.assetUrl);
    if (normalized) return normalized;
  }
  return null;
}

function chooseBitstream(values: Bitstream[]) {
  const documents = values.filter((item) =>
    /pdf|epub/iu.test(`${item.format ?? ""} ${item.downloadUrl ?? ""}`),
  );
  return (
    documents.find(
      (item) =>
        classifyResourceLicense(`${item.rights ?? ""} ${item.rightsUri ?? ""}`)
          .verified,
    ) ??
    documents[0] ??
    null
  );
}

function firstField(fields: FieldValue[], path: string) {
  return (
    fields.find(
      (field) => field.path === path || field.path.startsWith(`${path}.`),
    )?.value ?? null
  );
}

function isEngineeringResource(title: string, subjects: string[]) {
  return /(computer|computing|software|programming|algorithm|data science|information technolog|cyber|network|telecommunication|mathemat|statistic|engineering|electronic|electrical|mechanical|civil engineering|industrial|manufactur|operations research|production system|supply chain|aeronaut|aerospace|aviation|aircraft|robot|artificial intelligence|machine learning|cloud computing|devops)/iu.test(
    `${title} ${subjects.join(" ")}`,
  );
}

function classifyCategory(title: string, subjects: string[]) {
  const value = `${title} ${subjects.join(" ")}`;
  if (/(aeronaut|aerospace|aviation|aircraft|flight dynamics)/iu.test(value)) {
    return "Aerospace Engineering";
  }
  if (
    /(industrial engineering|manufactur|operations research|production system|supply chain|process engineering)/iu.test(
      value,
    )
  ) {
    return "Industrial Engineering";
  }
  if (/(cyber|security|cryptograph)/iu.test(value)) return "Security";
  if (
    /(network|telecommunication|routing|wireless communication)/iu.test(value)
  ) {
    return "Networking & Telecommunications";
  }
  if (/(mathemat|statistic)/iu.test(value)) return "Mathematics";
  if (/(electrical|electronic|power system)/iu.test(value)) {
    return "Electrical Engineering";
  }
  if (/(mechanical|thermodynamic|fluid mechanics)/iu.test(value)) {
    return "Mechanical Engineering";
  }
  if (/(civil engineering|structural engineering|construction)/iu.test(value)) {
    return "Civil Engineering";
  }
  if (
    /(software|programming|algorithm|computer|computing|data science|artificial intelligence|machine learning|devops|cloud)/iu.test(
      value,
    )
  ) {
    return "Computer Science";
  }
  return "Engineering";
}

function normalizeLanguage(value: string | null) {
  const normalized = normalizeText(value ?? "").toLowerCase();
  const aliases: Record<string, string> = {
    eng: "en",
    fra: "fr",
    fre: "fr",
    deu: "de",
    ger: "de",
  };
  return (aliases[normalized] ?? normalized.slice(0, 16)) || "und";
}

function normalizeHttpsUrl(value: string | null): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    if (url.protocol === "http:") url.protocol = "https:";
    if (
      url.protocol !== "https:" ||
      url.username ||
      url.password ||
      url.port ||
      !url.hostname
    )
      return null;
    url.hash = "";
    return url.toString();
  } catch {
    return null;
  }
}

function normalizeDoabCoverUrl(value: string | null) {
  const normalized = normalizeHttpsUrl(value);
  if (!normalized) return null;
  const url = new URL(normalized);
  if (
    url.hostname !== "directory.doabooks.org" ||
    url.search ||
    !/^\/bitstream\/20\.500\.12854\/\d+\/\d+\/[A-Za-z0-9._%~-]+\.(?:jpe?g|png|webp)$/iu.test(
      url.pathname,
    )
  ) {
    return null;
  }
  return url.toString();
}

function cleanText(value: string | null, max: number) {
  const normalized = normalizeText(value ?? "");
  return normalized ? normalized.slice(0, max) : null;
}

function toPositiveInteger(value: string | undefined) {
  if (!value || !/^\d+$/u.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

function normalizeText(value: string) {
  return decodeDisplayText(value).replace(/\s+/gu, " ").trim();
}

function asArray(value: unknown): unknown[] {
  if (value === undefined || value === null) return [];
  return Array.isArray(value) ? value : [value];
}

function asNode(value: unknown): XmlNode | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as XmlNode)
    : null;
}

function isNode(value: XmlNode | null): value is XmlNode {
  return value !== null;
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
