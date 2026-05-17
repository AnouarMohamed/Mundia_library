import { expect, test, type Page } from "@playwright/test";

const e2eUser = {
  email: process.env.E2E_USER_EMAIL,
  password: process.env.E2E_USER_PASSWORD,
};

const e2eAdmin = {
  email: process.env.E2E_ADMIN_EMAIL,
  password: process.env.E2E_ADMIN_PASSWORD,
};

const e2ePendingUser = {
  email: process.env.E2E_PENDING_EMAIL,
  password: process.env.E2E_PENDING_PASSWORD,
};

const e2eOtherUserId = process.env.E2E_OTHER_USER_ID;

const hasCredentials = (credentials: {
  email?: string;
  password?: string;
}) => Boolean(credentials.email && credentials.password);

const signIn = async (
  page: Page,
  credentials: { email?: string; password?: string },
) => {
  if (!credentials.email || !credentials.password) {
    throw new Error("Missing E2E credentials");
  }

  await page.goto("/sign-in");
  await page.getByLabel(/email/i).fill(credentials.email);
  await page.getByLabel(/password/i).fill(credentials.password);
  await page.getByRole("button", { name: /^sign in$/i }).click();
};

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

test("normal user cannot open admin", async ({ page }) => {
  test.skip(
    !hasCredentials(e2eUser),
    "Set E2E_USER_EMAIL and E2E_USER_PASSWORD to run this check",
  );

  await signIn(page, e2eUser);
  await expect(page).not.toHaveURL(/\/sign-in/);

  await page.goto("/admin");

  await expect(page).not.toHaveURL(/\/admin(?:\/|$)/);
});

test("normal user cannot fetch another user's borrow records", async ({
  page,
}) => {
  test.skip(
    !hasCredentials(e2eUser) || !e2eOtherUserId,
    "Set E2E_USER_EMAIL, E2E_USER_PASSWORD, and E2E_OTHER_USER_ID to run this check",
  );

  await signIn(page, e2eUser);
  await expect(page).not.toHaveURL(/\/sign-in/);

  const response = await page.request.get(
    `/api/borrow-records?userId=${encodeURIComponent(e2eOtherUserId ?? "")}`,
  );

  expect(response.status()).toBe(403);
});

test("admin can open dashboard and admin stats API", async ({ page }) => {
  test.skip(
    !hasCredentials(e2eAdmin),
    "Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD to run this check",
  );

  await signIn(page, e2eAdmin);
  await expect(page).not.toHaveURL(/\/sign-in/);

  await page.goto("/admin");

  await expect(page).toHaveURL(/\/admin(?:\/|$)/);
  await expect(page.locator("main")).toBeVisible();

  const response = await page.request.get("/api/admin/stats");
  expect(response.status()).toBe(200);
});

test("pending user cannot access protected app pages", async ({ page }) => {
  test.skip(
    !hasCredentials(e2ePendingUser),
    "Set E2E_PENDING_EMAIL and E2E_PENDING_PASSWORD to run this check",
  );

  await signIn(page, e2ePendingUser);
  await expect(page).toHaveURL(/\/sign-in/);

  await page.goto("/library");

  await expect(page).toHaveURL(/\/sign-in/);
});
