import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { lstat, mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const repositoryRoot = process.cwd();
const spaRoot = path.join(repositoryRoot, "dist", "web-spa");
const manifestPath = path.join(repositoryRoot, "dist", "web-spa-manifest.json");

async function collectFiles(directory, prefix = "") {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries.sort((left, right) =>
    left.name < right.name ? -1 : left.name > right.name ? 1 : 0,
  )) {
    const absolutePath = path.join(directory, entry.name);
    const relativePath = path.posix.join(prefix, entry.name);
    if (entry.isSymbolicLink()) {
      throw new Error(`SPA release contains a forbidden symbolic link: ${relativePath}`);
    }
    if (entry.isDirectory()) {
      files.push(...(await collectFiles(absolutePath, relativePath)));
    } else if (entry.isFile()) {
      const metadata = await lstat(absolutePath);
      const content = await readFile(absolutePath);
      files.push({
        path: relativePath,
        bytes: metadata.size,
        sha256: createHash("sha256").update(content).digest("hex"),
        cacheControl: relativePath.startsWith("assets/")
          ? "public,max-age=31536000,immutable"
          : "no-cache,no-store,must-revalidate",
      });
    }
  }
  return files;
}

function sourceRevision() {
  const revision = (
    process.env.GITHUB_SHA ??
    execFileSync("git", ["rev-parse", "HEAD"], { cwd: repositoryRoot, encoding: "utf8" })
  ).trim();
  if (!/^[a-f0-9]{40}$/.test(revision)) {
    throw new Error("SPA release revision must be a full lowercase Git SHA");
  }
  return revision;
}

const files = await collectFiles(spaRoot);
if (!files.some((file) => file.path === "index.html")) {
  throw new Error("SPA release is missing index.html");
}
if (!files.some((file) => file.path.startsWith("assets/"))) {
  throw new Error("SPA release is missing fingerprinted assets");
}
const buildMetadata = JSON.parse(
  await readFile(path.join(spaRoot, "build-metadata.json"), "utf8"),
);
if (
  buildMetadata.schemaVersion !== 1 ||
  typeof buildMetadata.featureFlags?.learningResources !== "boolean"
) {
  throw new Error("SPA build metadata is missing or invalid");
}

const aggregate = createHash("sha256");
for (const file of files) {
  aggregate.update(
    `${file.path}\0${file.sha256}\0${file.bytes}\0${file.cacheControl}\n`,
  );
}

const manifest = {
  schemaVersion: 1,
  sourceRevision: sourceRevision(),
  artifactSha256: aggregate.digest("hex"),
  featureFlags: buildMetadata.featureFlags,
  fileCount: files.length,
  totalBytes: files.reduce((total, file) => total + file.bytes, 0),
  files,
};

await mkdir(path.dirname(manifestPath), { recursive: true });
await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, {
  encoding: "utf8",
  mode: 0o644,
});
console.log(`Packaged SPA ${manifest.artifactSha256} (${manifest.fileCount} files)`);
