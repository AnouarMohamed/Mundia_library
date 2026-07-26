import { link, open, readFile, rename, stat, unlink } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";

const MAX_ARTIFACT_BYTES = 512 * 1024 * 1024;

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

export async function readJsonArtifact(path: string): Promise<unknown> {
  const resolved = resolve(path);
  const metadata = await stat(resolved);
  if (!metadata.isFile()) throw new Error(`${resolved} is not a regular file`);
  if (metadata.size > MAX_ARTIFACT_BYTES) {
    throw new Error(
      `Artifact exceeds the ${MAX_ARTIFACT_BYTES} byte safety limit`,
    );
  }
  return JSON.parse(await readFile(resolved, "utf8")) as unknown;
}

export async function writeJsonArtifact(
  path: string,
  value: unknown,
): Promise<void> {
  const resolved = resolve(path);
  const temporary = join(
    dirname(resolved),
    `.${basename(resolved)}.${process.pid}.tmp`,
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
  const resolved = resolve(path);
  try {
    await writePrivateFile(resolved, pendingValue);
    await syncDirectory(dirname(resolved));
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
        `.${basename(resolved)}.${process.pid}.complete.tmp`,
      );
      await writePrivateFile(temporary, value);
      try {
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
