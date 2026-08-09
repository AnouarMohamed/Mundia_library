import { expect, test, type Page } from "@playwright/test";

const user = {
  email: process.env.E2E_USER_EMAIL,
  password: process.env.E2E_USER_PASSWORD,
};

const signIn = async (page: Page) => {
  if (!user.email || !user.password) throw new Error("Missing E2E credentials");

  await page.goto("/sign-in");
  await page.getByLabel(/email/i).fill(user.email);
  await page.getByLabel(/password/i).fill(user.password);
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
  testInfo.setTimeout(60_000);
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
  await expect(page.getByText("Algorithms", { exact: true }).first()).toBeVisible();

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
  await expect(page.getByRole("dialog", { name: "Account" })).toBeVisible();
  await expect(page.getByText("Request Admin Access")).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "Account" })).not.toBeVisible();
  await expect(accountMenuTrigger).toBeFocused();
});
