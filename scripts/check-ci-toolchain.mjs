#!/usr/bin/env node

import { execFileSync } from "node:child_process";

const minimumNode = [24, 17, 0];
const currentNode = process.versions.node.split(".").map(Number);

const versionComparison = currentNode.reduce((comparison, part, index) => {
  if (comparison !== 0) return comparison;
  return Math.sign(part - minimumNode[index]);
}, 0);
const isSupported = versionComparison >= 0;

if (!isSupported) {
  console.error(`Node.js ${minimumNode.join(".")} or newer is required; found ${process.versions.node}.`);
  process.exit(1);
}

for (const command of ["npm", "docker", "git", "jq", "java"]) {
  try {
    execFileSync("sh", ["-c", `command -v ${command}`], { stdio: "ignore" });
  } catch {
    console.error(`Required CI tool is unavailable: ${command}`);
    process.exit(1);
  }
}

console.log(`CI toolchain ready (Node.js ${process.versions.node}).`);
