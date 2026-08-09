import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { stripVTControlCharacters } from "node:util";

const nextCli = fileURLToPath(
  new URL("../node_modules/next/dist/bin/next", import.meta.url),
);
const routeBudgets = new Map([
  ["/all-books", 120],
  ["/library", 165],
  ["/my-profile", 155],
  ["/books/[id]", 190],
  ["/sign-in", 155],
]);
const sharedBudgetKb = 110;
let output = "";

const build = spawn(process.execPath, [nextCli, "build", ...process.argv.slice(2)], {
  env: process.env,
  stdio: ["inherit", "pipe", "pipe"],
});

build.stdout.on("data", (chunk) => {
  const text = chunk.toString();
  output += text;
  process.stdout.write(text);
});

build.stderr.on("data", (chunk) => {
  const text = chunk.toString();
  output += text;
  process.stderr.write(text);
});

build.on("error", (error) => {
  console.error("Unable to start the Next.js production build:", error);
  process.exitCode = 1;
});

build.on("close", (code, signal) => {
  if (code !== 0) {
    process.exitCode = code ?? 1;
    return;
  }

  if (signal) {
    console.error(`Next.js build terminated by ${signal}`);
    process.exitCode = 1;
    return;
  }

  const normalized = stripVTControlCharacters(output).replaceAll("\r", "");
  const routeSizes = new Map();
  const routePattern =
    /^[┌├└]\s+[ƒ○●]\s+(\/\S*)\s+[\d.]+\s+(?:B|kB)\s+([\d.]+)\s+kB\s*$/gm;

  for (const match of normalized.matchAll(routePattern)) {
    routeSizes.set(match[1], Number(match[2]));
  }

  const sharedMatch = normalized.match(
    /^\+ First Load JS shared by all\s+([\d.]+)\s+kB\s*$/m,
  );
  const failures = [];

  for (const [route, budgetKb] of routeBudgets) {
    const sizeKb = routeSizes.get(route);
    if (sizeKb === undefined) {
      failures.push(`${route}: route size was not present in Next.js build output`);
    } else if (sizeKb > budgetKb) {
      failures.push(`${route}: ${sizeKb} kB exceeds ${budgetKb} kB`);
    }
  }

  if (!sharedMatch) {
    failures.push("shared bundle: size was not present in Next.js build output");
  } else if (Number(sharedMatch[1]) > sharedBudgetKb) {
    failures.push(
      `shared bundle: ${sharedMatch[1]} kB exceeds ${sharedBudgetKb} kB`,
    );
  }

  if (failures.length > 0) {
    console.error("\nMobile JavaScript budget failed:");
    for (const failure of failures) console.error(`- ${failure}`);
    process.exitCode = 1;
    return;
  }

  console.log("\nMobile JavaScript budgets passed.");
});
