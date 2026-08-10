import { expect, test, type Page } from "@playwright/test";

const user = {
  email: process.env.E2E_USER_EMAIL,
  password: process.env.E2E_USER_PASSWORD,
};

const admin = {
  email: process.env.E2E_ADMIN_EMAIL,
  password: process.env.E2E_ADMIN_PASSWORD,
};

const signIn = async (
  page: Page,
  credentials: { email?: string; password?: string } = user,
) => {
  if (!credentials.email || !credentials.password) {
    throw new Error("Missing E2E credentials");
  }

  await page.goto("/sign-in");
  await page.getByLabel(/email/i).fill(credentials.email);
  await page.getByLabel(/password/i).fill(credentials.password);
  await page.getByRole("button", { name: /^sign in$/i }).click();
  await expect(page).not.toHaveURL(/\/sign-in/);
};

const expectNoHorizontalOverflow = async (page: Page) => {
  const dimensions = await page.evaluate(() => ({
    viewport: window.innerWidth,
    document: document.documentElement.scrollWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
};

test.beforeEach(({ browserName }, testInfo) => {
  void browserName;
  // These gates intentionally exercise several authenticated routes across
  // multiple phone widths. Allow enough time for cold dev-server compilation
  // while keeping every individual Playwright assertion on its strict timeout.
  testInfo.setTimeout(120_000);
  test.skip(testInfo.project.name !== "mobile-chromium", "Mobile product gate");
});

test("sign-in is usable at the primary phone viewport", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto("/sign-in");
  await expectNoHorizontalOverflow(page);

  for (const control of [
    page.getByLabel(/email/i),
    page.getByLabel(/password/i),
    page.getByRole("button", { name: /^sign in$/i }),
  ]) {
    const bounds = await control.boundingBox();
    expect(bounds?.width ?? 0).toBeGreaterThanOrEqual(44);
    expect(bounds?.height ?? 0).toBeGreaterThanOrEqual(44);
  }
});

test("core student flows preserve mobile navigation and touch ergonomics", async ({
  page,
}) => {
  test.skip(
    !user.email || !user.password,
    "Set E2E_USER_EMAIL and E2E_USER_PASSWORD to run authenticated mobile checks",
  );

  await signIn(page);

  for (const viewport of [
    { width: 360, height: 800 },
    { width: 390, height: 844 },
    { width: 430, height: 932 },
  ]) {
    await page.setViewportSize(viewport);
    for (const pathname of ["/library", "/all-books", "/my-profile"]) {
      await page.goto(pathname);
      await expectNoHorizontalOverflow(page);
      await expect(
        page.getByRole("navigation", { name: "Primary navigation" }),
      ).toBeVisible();
    }
  }

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/all-books");
  const search = page.getByLabel("Search by title or author");
  await expect(search).toBeVisible();
  expect((await search.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
  await expect(page.getByText("Filters and sorting")).toBeVisible();
  const catalogCovers = page.locator("[data-book-cover] img");
  expect(await catalogCovers.count()).toBeGreaterThanOrEqual(3);
  await expect(catalogCovers.first()).toHaveAttribute("srcset", /\S/);
  await expect(catalogCovers.first()).toHaveAttribute("sizes", /114px/);
  await expect(catalogCovers.nth(2)).toHaveAttribute("loading", "lazy");
  await search.fill("Algorithms");
  await search.press("Enter");
  await expect(page).toHaveURL(/search=Algorithms/);
  await expect(
    page.getByText("Algorithms", { exact: true }).first(),
  ).toBeVisible();

  const bottomSpacing = await page.evaluate(() => {
    const main = document.querySelector("main");
    const navigation = document.querySelector(
      'nav[aria-label="Primary navigation"]',
    );
    return {
      mainPaddingBottom: Number.parseFloat(
        main ? getComputedStyle(main).paddingBottom : "0",
      ),
      navigationHeight: navigation?.getBoundingClientRect().height ?? 0,
    };
  });
  expect(bottomSpacing.mainPaddingBottom).toBeGreaterThanOrEqual(
    bottomSpacing.navigationHeight,
  );

  const accountMenuTrigger = page.getByRole("button", {
    name: "Open account menu",
  });
  await expect(accountMenuTrigger).toBeEnabled();
  await accountMenuTrigger.click();
  const accountDialog = page.getByRole("dialog", { name: "Account" });
  await expect(accountDialog).toBeVisible();
  expect(
    await accountDialog.evaluate((element) => element.matches(":modal")),
  ).toBe(true);
  await expect(page.getByText("Request Admin Access")).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "Account" })).not.toBeVisible();
  await expect(accountMenuTrigger).toBeFocused();
});

test("admin workspace uses a viewport-safe responsive navigation", async ({
  page,
}) => {
  test.skip(
    !admin.email || !admin.password,
    "Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD to run mobile admin checks",
  );

  await page.setViewportSize({ width: 360, height: 800 });
  await signIn(page, admin);
  await page.goto("/admin");
  await expectNoHorizontalOverflow(page);

  await expect(page.locator(".admin-sidebar")).toBeHidden();
  const navigationTrigger = page.getByRole("button", {
    name: "Open admin navigation",
  });
  const triggerBounds = await navigationTrigger.boundingBox();
  expect(triggerBounds?.width ?? 0).toBeGreaterThanOrEqual(44);
  expect(triggerBounds?.height ?? 0).toBeGreaterThanOrEqual(44);

  await navigationTrigger.click();
  const navigationDialog = page.getByRole("dialog", {
    name: "Admin navigation",
  });
  const navigationDialogElement = page.locator("#admin-mobile-navigation");
  await expect(navigationDialog).toBeVisible();
  expect(
    await navigationDialog.evaluate((element) => element.matches(":modal")),
  ).toBe(true);
  await expect(
    navigationDialog.getByRole("link", { name: "Account Requests" }),
  ).toBeVisible();
  await expect(
    navigationDialog.getByRole("link", { name: "Renewal Requests" }),
  ).toBeVisible();

  await navigationDialog
    .getByRole("link", { name: "Account Requests" })
    .click();
  await expect(page).toHaveURL(/\/admin\/account-requests/);
  await expect(navigationDialog).not.toBeVisible();

  await navigationTrigger.click();
  await expect(navigationDialog).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => document.body.style.overflow))
    .toBe("hidden");

  await page.setViewportSize({ width: 1024, height: 700 });
  await expect(navigationDialog).not.toBeVisible();
  await expect
    .poll(() =>
      navigationDialogElement.evaluate((element) => element.matches(":modal")),
    )
    .toBe(false);
  await expect
    .poll(() => page.evaluate(() => document.body.style.overflow))
    .not.toBe("hidden");
  await expect(page.locator(".admin-sidebar")).toBeVisible();
  await expect(navigationTrigger).toBeHidden();

  const sidebarGeometry = await page
    .locator(".admin-sidebar")
    .evaluate((element) => ({
      top: element.getBoundingClientRect().top,
      height: element.getBoundingClientRect().height,
      viewportHeight: window.innerHeight,
    }));
  expect(Math.abs(sidebarGeometry.top)).toBeLessThanOrEqual(1);
  expect(
    Math.abs(sidebarGeometry.height - sidebarGeometry.viewportHeight),
  ).toBeLessThanOrEqual(1);
});
