import { randomUUID } from "node:crypto";
import { constants } from "node:fs";
import {
  link,
  lstat,
  open,
  realpath,
  rename,
  stat,
  unlink,
} from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";

const MAX_ARTIFACT_BYTES = 512 * 1024 * 1024;
const MAX_SECRET_BYTES = 16 * 1024;

async function syncDirectory(path: string): Promise<void> {
  const directory = await open(path, "r");
  try {
    await directory.sync();
  } finally {
    await directory.close();
  }
}

async function writePrivateFile(path: string, value: unknown): Promise<void> {
  const handle = await open(path, "wx", 0o600);
  try {
    await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`, "utf8");
    await handle.sync();
  } finally {
    await handle.close();
  }
}

async function readPrivateFile(path: string, maximumBytes: number): Promise<Buffer> {
  const resolved = resolve(path);
  const parent = dirname(resolved);
  if ((await realpath(parent)) !== parent) {
    throw new Error(`Artifact parent directory contains a symbolic link: ${parent}`);
  }
  let handle;
  try {
    handle = await open(
      resolved,
      constants.O_RDONLY | constants.O_NOFOLLOW,
    );
  } catch (error) {
    if (
      error instanceof Error &&
      "code" in error &&
      (error.code === "ELOOP" || error.code === "EMLINK")
    ) {
      throw new Error(`Refusing symbolic-link artifact: ${resolved}`);
    }
    throw error;
  }
  try {
    const metadata = await handle.stat();
    if (!metadata.isFile())
      throw new Error(`${resolved} is not a regular file`);
    if (metadata.nlink !== 1) {
      throw new Error(`${resolved} must have exactly one filesystem link`);
    }
    if ((metadata.mode & 0o077) !== 0) {
      throw new Error(`${resolved} must not grant group or other permissions`);
    }
    if (
      typeof process.getuid === "function" &&
      metadata.uid !== process.getuid()
    ) {
      throw new Error(`${resolved} must be owned by the current operator`);
    }
    if (metadata.size > maximumBytes) {
      throw new Error(
        `Private file exceeds the ${maximumBytes} byte safety limit`,
      );
    }
    const bytes = await handle.readFile();
    if (bytes.byteLength > maximumBytes) {
      throw new Error(
        `Private file exceeds the ${maximumBytes} byte safety limit`,
      );
    }
    return bytes;
  } finally {
    await handle.close();
  }
}

export async function readJsonArtifact(path: string): Promise<unknown> {
  return JSON.parse(
    (await readPrivateFile(path, MAX_ARTIFACT_BYTES)).toString("utf8"),
  ) as unknown;
}

export async function readSecretUrlFile(path: string): Promise<string> {
  const value = (await readPrivateFile(path, MAX_SECRET_BYTES))
    .toString("utf8")
    .replace(/\r?\n$/, "");
  if (
    value.length === 0 ||
    value.includes("\n") ||
    value.includes("\r") ||
    value.includes("\0")
  ) {
    throw new Error(
      "Database URL secret file must contain exactly one non-empty line",
    );
  }
  return value;
}

async function safeOutputPath(path: string): Promise<string> {
  const resolved = resolve(path);
  const parent = dirname(resolved);
  const canonicalParent = await realpath(parent);
  if (canonicalParent !== parent) {
    throw new Error(`Artifact parent directory contains a symbolic link: ${parent}`);
  }
  const metadata = await stat(parent);
  if (!metadata.isDirectory()) {
    throw new Error(`Artifact parent is not a directory: ${parent}`);
  }
  if ((metadata.mode & 0o022) !== 0) {
    throw new Error(
      `Artifact parent directory must not be group- or world-writable: ${parent}`,
    );
  }
  if (
    typeof process.getuid === "function" &&
    metadata.uid !== process.getuid()
  ) {
    throw new Error(`Artifact parent must be owned by the current operator`);
  }
  return resolved;
}

export async function writeJsonArtifact(
  path: string,
  value: unknown,
): Promise<void> {
  const resolved = await safeOutputPath(path);
  const temporary = join(
    dirname(resolved),
    `.${basename(resolved)}.${process.pid}.${randomUUID()}.tmp`,
  );
  await writePrivateFile(temporary, value);
  try {
    // A hard link is an atomic create-if-absent operation. Unlike rename(), it
    // cannot replace a file another operator created between our checks.
    await link(temporary, resolved);
    await unlink(temporary);
    await syncDirectory(dirname(resolved));
  } catch (error) {
    await unlink(temporary).catch(() => undefined);
    if (error instanceof Error && "code" in error && error.code === "EEXIST") {
      throw new Error(`Refusing to overwrite existing artifact: ${resolved}`);
    }
    throw error;
  }
}

export interface JsonArtifactReservation {
  path: string;
  complete(value: unknown): Promise<void>;
}

export async function reserveJsonArtifact(
  path: string,
  pendingValue: unknown,
): Promise<JsonArtifactReservation> {
  const resolved = await safeOutputPath(path);
  let reservationDevice = -1;
  let reservationInode = -1;
  try {
    await writePrivateFile(resolved, pendingValue);
    await syncDirectory(dirname(resolved));
    const metadata = await lstat(resolved);
    reservationDevice = metadata.dev;
    reservationInode = metadata.ino;
  } catch (error) {
    if (error instanceof Error && "code" in error && error.code === "EEXIST") {
      throw new Error(`Refusing to overwrite existing artifact: ${resolved}`);
    }
    throw error;
  }

  let completed = false;
  return {
    path: resolved,
    async complete(value: unknown): Promise<void> {
      if (completed)
        throw new Error("Artifact reservation is already complete");
      const temporary = join(
        dirname(resolved),
        `.${basename(resolved)}.${process.pid}.${randomUUID()}.complete.tmp`,
      );
      await writePrivateFile(temporary, value);
      try {
        const reservation = await lstat(resolved);
        if (
          !reservation.isFile() ||
          reservation.isSymbolicLink() ||
          reservation.dev !== reservationDevice ||
          reservation.ino !== reservationInode ||
          reservation.nlink !== 1 ||
          (reservation.mode & 0o777) !== 0o600 ||
          (typeof process.getuid === "function" &&
            reservation.uid !== process.getuid())
        ) {
          throw new Error(
            "Artifact reservation identity or permissions changed before completion",
          );
        }
        await safeOutputPath(resolved);
        // Replacing this path is intentional: this process created and owns the
        // PENDING reservation before any database write was attempted.
        await rename(temporary, resolved);
        await syncDirectory(dirname(resolved));
        completed = true;
      } catch (error) {
        await unlink(temporary).catch(() => undefined);
        throw error;
      }
    },
  };
}
