import { spawn } from "node:child_process";

console.warn(
  "scripts/setup-postgres.mjs is deprecated; delegating to the canonical migration chain.",
);

const npmCommand = process.platform === "win32" ? "npm.cmd" : "npm";
const migration = spawn(npmCommand, ["run", "db:migrate"], {
  cwd: process.cwd(),
  env: process.env,
  stdio: "inherit",
});

const exitCode = await new Promise((resolve, reject) => {
  migration.once("error", reject);
  migration.once("exit", resolve);
});

if (exitCode !== 0) {
  throw new Error(`Canonical database migration failed with exit code ${exitCode}`);
}
