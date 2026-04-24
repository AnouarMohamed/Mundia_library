import process from "node:process";
import { performance } from "node:perf_hooks";
import { writeFile } from "node:fs/promises";

const BASE_URL = process.env.BENCH_BASE_URL || "http://127.0.0.1:3000";
const ITERATIONS = Number(process.env.BENCH_ITERATIONS || 15);
const WARMUP = Number(process.env.BENCH_WARMUP || 3);

const thresholds = {
  booksListP95: Number(process.env.BENCH_BOOKS_LIST_P95_MS || 500),
  booksGenresP95: Number(process.env.BENCH_BOOKS_GENRES_P95_MS || 350),
  booksRecommendationsP95: Number(
    process.env.BENCH_BOOKS_RECOMMENDATIONS_P95_MS || 550
  ),
  bookDetailsP95: Number(process.env.BENCH_BOOK_DETAILS_P95_MS || 450),
};

function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, index)];
}

function summarize(route, samples) {
  const total = samples.reduce((sum, value) => sum + value, 0);
  return {
    route,
    count: samples.length,
    avg: total / samples.length,
    p95: percentile(samples, 95),
    p99: percentile(samples, 99),
    min: Math.min(...samples),
    max: Math.max(...samples),
  };
}

async function hit(pathname) {
  const url = `${BASE_URL}${pathname}`;
  const started = performance.now();
  const response = await fetch(url, { method: "GET" });
  const duration = performance.now() - started;

  if (!response.ok) {
    const body = await response.text().catch(() => "<failed to read body>");
    throw new Error(
      `Request failed for ${pathname} with ${response.status}: ${body.slice(0, 300)}`
    );
  }

  const payload = await response.json();
  if (payload?.success === false) {
    throw new Error(`Route ${pathname} returned success=false`);
  }

  return duration;
}

async function benchmarkRoute(pathname) {
  const warmups = [];
  for (let i = 0; i < WARMUP; i += 1) {
    warmups.push(await hit(pathname));
  }

  const samples = [];
  for (let i = 0; i < ITERATIONS; i += 1) {
    samples.push(await hit(pathname));
  }

  return summarize(pathname, samples);
}

function asMarkdown(results) {
  const lines = [
    "## API benchmark results",
    "",
    "| Route | Avg (ms) | P95 (ms) | P99 (ms) | Min (ms) | Max (ms) |",
    "| --- | ---: | ---: | ---: | ---: | ---: |",
  ];

  for (const result of results) {
    lines.push(
      `| ${result.route} | ${result.avg.toFixed(2)} | ${result.p95.toFixed(2)} | ${result.p99.toFixed(2)} | ${result.min.toFixed(2)} | ${result.max.toFixed(2)} |`
    );
  }

  return `${lines.join("\n")}\n`;
}

async function persistArtifacts(results, markdown) {
  const summaryPath = process.env.BENCH_SUMMARY_FILE || "/tmp/api-benchmark-summary.md";
  const resultsPath = process.env.BENCH_RESULTS_FILE || "/tmp/api-benchmark-results.json";

  await writeFile(summaryPath, markdown, "utf8");
  await writeFile(
    resultsPath,
    `${JSON.stringify({
      baseUrl: BASE_URL,
      warmup: WARMUP,
      iterations: ITERATIONS,
      generatedAt: new Date().toISOString(),
      results,
      thresholds,
    }, null, 2)}\n`,
    "utf8"
  );
}

async function main() {
  console.log(`Benchmarking API routes at ${BASE_URL}`);
  console.log(`Warmup=${WARMUP}, iterations=${ITERATIONS}`);

  const booksListPath = "/api/books?limit=12&sort=rating&page=1";
  const booksGenresPath = "/api/books/genres";
  const recommendationsPath = "/api/books/recommendations?limit=10";

  const bookListResponse = await fetch(`${BASE_URL}/api/books?limit=1&page=1`);
  if (!bookListResponse.ok) {
    throw new Error(`Cannot resolve benchmark book id: ${bookListResponse.status}`);
  }
  const listPayload = await bookListResponse.json();
  const firstBookId = listPayload?.books?.[0]?.id;
  if (!firstBookId) {
    throw new Error("No books found for /api/books benchmark preparation");
  }

  const bookDetailsPath = `/api/books/${firstBookId}`;

  const results = [];
  results.push(await benchmarkRoute(booksListPath));
  results.push(await benchmarkRoute(booksGenresPath));
  results.push(await benchmarkRoute(recommendationsPath));
  results.push(await benchmarkRoute(bookDetailsPath));

  const markdown = asMarkdown(results);
  console.log("\n" + markdown);

  await persistArtifacts(results, markdown);

  if (process.env.GITHUB_STEP_SUMMARY) {
    await writeFile(process.env.GITHUB_STEP_SUMMARY, markdown, { encoding: "utf8", flag: "a" });
  }

  const checks = [
    {
      route: booksListPath,
      p95: results[0].p95,
      threshold: thresholds.booksListP95,
    },
    {
      route: booksGenresPath,
      p95: results[1].p95,
      threshold: thresholds.booksGenresP95,
    },
    {
      route: recommendationsPath,
      p95: results[2].p95,
      threshold: thresholds.booksRecommendationsP95,
    },
    {
      route: bookDetailsPath,
      p95: results[3].p95,
      threshold: thresholds.bookDetailsP95,
    },
  ];

  const failures = checks.filter((item) => item.p95 > item.threshold);
  if (failures.length > 0) {
    console.error("Performance regression detected:");
    for (const failure of failures) {
      console.error(
        ` - ${failure.route}: p95=${failure.p95.toFixed(2)}ms > threshold=${failure.threshold}ms`
      );
    }
    process.exit(1);
  }

  console.log("All API benchmark thresholds passed.");
}

main().catch((error) => {
  console.error("Benchmark execution failed:", error);
  process.exit(1);
});
