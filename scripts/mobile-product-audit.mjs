import { mkdir, writeFile } from "node:fs/promises";
import { chromium } from "@playwright/test";

const baseURL = process.env.AUDIT_BASE_URL ?? "http://127.0.0.1:3100";
const email = process.env.AUDIT_EMAIL ?? "test@user.com";
const password = process.env.AUDIT_PASSWORD ?? "12345678";
const outputDirectory = "artifacts/mobile-audit";
const auditLabel = process.env.AUDIT_LABEL ?? "after";
const viewport = { width: 390, height: 844 };

await mkdir(outputDirectory, { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({
  viewport,
  deviceScaleFactor: 1,
  hasTouch: true,
  isMobile: true,
  locale: "en-US",
});
const page = await context.newPage();

await page.addInitScript(() => {
  window.__mobileAudit = { cls: 0, longTasks: [] };

  new PerformanceObserver((entries) => {
    for (const entry of entries.getEntries()) {
      if (!entry.hadRecentInput) window.__mobileAudit.cls += entry.value;
    }
  }).observe({ type: "layout-shift", buffered: true });

  new PerformanceObserver((entries) => {
    for (const entry of entries.getEntries()) {
      window.__mobileAudit.longTasks.push(entry.duration);
    }
  }).observe({ type: "longtask", buffered: true });
});

await page.goto(`${baseURL}/sign-in`, { waitUntil: "networkidle" });
await page.screenshot({
  path: `${outputDirectory}/${auditLabel}-sign-in.png`,
  fullPage: true,
});
await page.getByLabel(/email/i).fill(email);
await page.getByLabel(/password/i).fill(password);
await page.getByRole("button", { name: /^sign in$/i }).click();
await page.waitForURL((url) => !url.pathname.startsWith("/sign-in"));

const routeDefinitions = [
  ["library", "/library"],
  ["catalog", "/all-books"],
  ["profile", "/my-profile"],
];
const results = [];

for (const [name, pathname] of routeDefinitions) {
  await page.goto(`${baseURL}${pathname}`, { waitUntil: "networkidle" });
  await page.screenshot({
    path: `${outputDirectory}/${auditLabel}-${name}.png`,
    fullPage: true,
  });

  results.push(
    await page.evaluate((routeName) => {
      const interactive = [
        ...document.querySelectorAll(
          'a[href], button, input:not([type="hidden"]), select, textarea',
        ),
      ];
      const undersizedTargets = interactive
        .map((element) => {
          const bounds = element.getBoundingClientRect();
          return {
            element: element.tagName.toLowerCase(),
            label:
              element.getAttribute("aria-label") ??
              element.textContent?.trim().replace(/\s+/g, " ").slice(0, 80) ??
              "",
            width: Math.round(bounds.width),
            height: Math.round(bounds.height),
          };
        })
        .filter(
          ({ width, height }) => width > 0 && height > 0 && (width < 44 || height < 44),
        );
      const resources = performance.getEntriesByType("resource");
      const scripts = resources.filter((entry) => entry.initiatorType === "script");
      const images = resources.filter((entry) => entry.initiatorType === "img");
      const audit = window.__mobileAudit;
      const coverImages = [
        ...document.querySelectorAll("[data-book-cover] img"),
      ];

      return {
        route: routeName,
        viewport: window.innerWidth,
        documentWidth: document.documentElement.scrollWidth,
        horizontalOverflow:
          document.documentElement.scrollWidth > window.innerWidth,
        cls: Number(audit.cls.toFixed(4)),
        longTaskCount: audit.longTasks.length,
        longestTaskMs: Math.round(Math.max(0, ...audit.longTasks)),
        scriptTransferBytes: Math.round(
          scripts.reduce((total, resource) => total + resource.transferSize, 0),
        ),
        imageTransferBytes: Math.round(
          images.reduce((total, resource) => total + resource.transferSize, 0),
        ),
        imageCount: document.images.length,
        eagerImageCount: [...document.images].filter(
          (image) => image.loading !== "lazy",
        ).length,
        bookCoverCount: coverImages.length,
        eagerBookCoverCount: coverImages.filter(
          (image) => image.loading !== "lazy",
        ).length,
        undersizedTargets,
      };
    }, name),
  );
}

await page.goto(`${baseURL}/all-books`, { waitUntil: "networkidle" });
const firstBookLink = page.locator('a[href^="/books/"]').first();
const bookHref = await firstBookLink.getAttribute("href");
if (!bookHref) {
  throw new Error("Mobile audit could not find a book-detail link");
}
await page.goto(`${baseURL}${bookHref}`, { waitUntil: "networkidle" });
await page.screenshot({
  path: `${outputDirectory}/${auditLabel}-book-detail.png`,
  fullPage: true,
});

await page.goto(`${baseURL}/library`, { waitUntil: "networkidle" });
await page.getByRole("button", { name: /open account menu/i }).click();
await page.screenshot({
  path: `${outputDirectory}/${auditLabel}-menu.png`,
  fullPage: true,
});

await writeFile(
  `${outputDirectory}/${auditLabel}-metrics.json`,
  `${JSON.stringify(results, null, 2)}\n`,
);

console.log(JSON.stringify(results, null, 2));
await browser.close();
