import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { performance } from "node:perf_hooks";

const BASE_URL = process.env.LOADTEST_BASE_URL || "http://127.0.0.1:3000";
const TOTAL_REQUESTS = Number(process.env.LOADTEST_TOTAL_REQUESTS || 240);
const CONCURRENCY = Number(process.env.LOADTEST_CONCURRENCY || 20);
const ARTIFACT_DIR = path.resolve(process.cwd(), "artifacts/perf");
const BASELINE_FILE = path.resolve(
  process.cwd(),
  "perf-baselines/nightly-load-baseline.json"
);

const absoluteThresholds = {
  "/api/books?limit=12&sort=rating&page=1": Number(
    process.env.LOADTEST_BOOKS_P95_MS || 700
  ),
  "/api/books/genres": Number(process.env.LOADTEST_GENRES_P95_MS || 500),
  "/api/books/recommendations?limit=10": Number(
    process.env.LOADTEST_RECOMMENDATIONS_P95_MS || 800
  ),
  "/api/books/:id": Number(process.env.LOADTEST_BOOK_ID_P95_MS || 650),
};

function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, idx)];
}

async function requestDuration(pathname) {
  const started = performance.now();
  const response = await fetch(`${BASE_URL}${pathname}`);
  const ms = performance.now() - started;

  if (!response.ok) {
    const body = await response.text().catch(() => "<body unavailable>");
    throw new Error(`${pathname} returned ${response.status}: ${body.slice(0, 160)}`);
  }

  const payload = await response.json();
  if (payload?.success === false) {
    throw new Error(`${pathname} returned success=false`);
  }

  return ms;
}

async function runLoad(pathname, totalRequests, concurrency) {
  let nextIndex = 0;
  const durations = [];
  let failures = 0;

  const worker = async () => {
    while (true) {
      const current = nextIndex;
      nextIndex += 1;
      if (current >= totalRequests) return;

      try {
        const ms = await requestDuration(pathname);
        durations.push(ms);
      } catch (error) {
        failures += 1;
        console.error(String(error));
      }
    }
  };

  const workers = Array.from({ length: concurrency }, () => worker());
  await Promise.all(workers);

  const count = durations.length;
  const avg = count
    ? durations.reduce((sum, duration) => sum + duration, 0) / count
    : 0;

  return {
    route: pathname,
    count,
    failures,
    requested: totalRequests,
    avgMs: avg,
    p95Ms: percentile(durations, 95),
    p99Ms: percentile(durations, 99),
    minMs: count ? Math.min(...durations) : 0,
    maxMs: count ? Math.max(...durations) : 0,
  };
}

function markdownReport(results, checks) {
  const lines = [
    "## Nightly API load test",
    "",
    `Base URL: ${BASE_URL}`,
    `Total requests per route: ${TOTAL_REQUESTS}`,
    `Concurrency per route: ${CONCURRENCY}`,
    "",
    "| Route | Requests | Failures | Avg (ms) | P95 (ms) | P99 (ms) | Threshold (ms) | Status |",
    "| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
  ];

  for (const result of results) {
    const check = checks.find((item) => item.route === result.route);
    const status = check && check.passed ? "PASS" : "FAIL";
    const threshold = check ? check.thresholdMs.toFixed(2) : "n/a";

    lines.push(
      `| ${result.route} | ${result.requested} | ${result.failures} | ${result.avgMs.toFixed(2)} | ${result.p95Ms.toFixed(2)} | ${result.p99Ms.toFixed(2)} | ${threshold} | ${status} |`
    );
  }

  return `${lines.join("\n")}\n`;
}

async function loadBaseline() {
  try {
    const file = await fs.readFile(BASELINE_FILE, "utf8");
    return JSON.parse(file);
  } catch {
    return {
      routes: {},
      maxRegressionPercent: 25,
    };
  }
}

async function ensureArtifactDir() {
  await fs.mkdir(ARTIFACT_DIR, { recursive: true });
}

async function main() {
  await ensureArtifactDir();

  const warmList = await fetch(`${BASE_URL}/api/books?limit=1&page=1`);
  if (!warmList.ok) {
    throw new Error(`Unable to resolve book id for load test (${warmList.status})`);
  }
  const listPayload = await warmList.json();
  const firstBookId = listPayload?.books?.[0]?.id;
  if (!firstBookId) {
    throw new Error("No books available for /api/books/:id load route");
  }

  const measuredRoutes = [
    "/api/books?limit=12&sort=rating&page=1",
    "/api/books/genres",
    "/api/books/recommendations?limit=10",
    `/api/books/${firstBookId}`,
  ];

  const results = [];
  for (const route of measuredRoutes) {
    console.log(`Running load test: ${route}`);
    const result = await runLoad(route, TOTAL_REQUESTS, CONCURRENCY);
    results.push(result);
  }

  const baseline = await loadBaseline();
  const maxRegressionPercent = Number(
    process.env.LOADTEST_MAX_REGRESSION_PERCENT ||
      baseline.maxRegressionPercent ||
      25
  );

  const checks = results.map((result) => {
    const baselineKey = result.route.startsWith("/api/books/") && result.route.split("/").length === 4
      ? "/api/books/:id"
      : result.route;

    const baselineP95 = baseline?.routes?.[baselineKey]?.p95Ms;
    const baselineThreshold = baselineP95
      ? baselineP95 * (1 + maxRegressionPercent / 100)
      : undefined;
    const absoluteThreshold = absoluteThresholds[baselineKey] || 800;

    const thresholdMs = baselineThreshold
      ? Math.max(absoluteThreshold, baselineThreshold)
      : absoluteThreshold;

    const passed = result.failures === 0 && result.p95Ms <= thresholdMs;

    return {
      route: result.route,
      baselineKey,
      p95Ms: result.p95Ms,
      thresholdMs,
      baselineP95: baselineP95 || null,
      passed,
      failures: result.failures,
    };
  });

  const markdown = markdownReport(results, checks);
  console.log("\n" + markdown);

  const jsonReport = {
    generatedAt: new Date().toISOString(),
    baseUrl: BASE_URL,
    totalRequests: TOTAL_REQUESTS,
    concurrency: CONCURRENCY,
    maxRegressionPercent,
    results,
    checks,
  };

  const jsonFile = path.join(ARTIFACT_DIR, "nightly-load-report.json");
  const mdFile = path.join(ARTIFACT_DIR, "nightly-load-report.md");

  await fs.writeFile(jsonFile, JSON.stringify(jsonReport, null, 2));
  await fs.writeFile(mdFile, markdown);

  if (process.env.GITHUB_STEP_SUMMARY) {
    await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, markdown);
  }

  const failed = checks.filter((check) => !check.passed);
  if (failed.length > 0) {
    console.error("Load-test regression detected:");
    for (const item of failed) {
      console.error(
        ` - ${item.route}: p95=${item.p95Ms.toFixed(2)}ms, threshold=${item.thresholdMs.toFixed(2)}ms, failures=${item.failures}`
      );
    }
    process.exit(1);
  }
}

main().catch((error) => {
  console.error("Nightly load test failed:", error);
  process.exit(1);
});
