import { expect, test } from "@playwright/test";

test("unauthenticated users are redirected to sign in", async ({ page }) => {
  await page.goto("/");

  await expect(page).toHaveURL(/\/sign-in/);
});

test("unauthenticated users cannot open admin", async ({ page }) => {
  await page.goto("/admin");

  await expect(page).toHaveURL(/\/sign-in/);
});

test("sign-in page renders", async ({ page }) => {
  await page.goto("/sign-in");

  await expect(page.getByRole("button", { name: /sign in/i })).toBeVisible();
});

test("health endpoint returns only safe operational fields", async ({ request }) => {
  const response = await request.get("/api/health");

  expect([200, 503]).toContain(response.status());

  const body = await response.json();
  expect(body).toHaveProperty("ok");
  expect(body).toHaveProperty("database");
  expect(body).toHaveProperty("redisConfigured");
  expect(body).toHaveProperty("timestamp");
  expect(JSON.stringify(body)).not.toMatch(
    /password|secret|token|private|DATABASE_URL|connectionString/i,
  );
});
