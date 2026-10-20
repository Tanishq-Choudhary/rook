import { expect, test, type Page } from "@playwright/test";

const KEY = "test-key";

async function signIn(page: Page, key = KEY) {
  await page.goto("/login");
  await page.getByLabel("API key").fill(key);
  await page.getByRole("button", { name: "Sign in" }).click();
  if (key === KEY) {
    await page.waitForURL((url) => !url.pathname.startsWith("/login"));
  }
}

test("everything redirects to the sign in page without a key", async ({ page }) => {
  await page.goto("/jobs");
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole("heading", { name: "Rook" })).toBeVisible();
});

test("a wrong key is refused with a reason and stays on the page", async ({ page }) => {
  await signIn(page, "nope");
  await expect(page.locator("#login-error")).toHaveText("That key was not accepted");
  await expect(page).toHaveURL(/\/login$/);
});

test("signing in shows the overview with counts and recent jobs", async ({ page }) => {
  await signIn(page);
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
  await expect(page.getByTestId("total-AWAITING_APPROVAL")).toHaveText("1");
  await expect(page.getByTestId("total-FAILED")).toHaveText("1");
  await expect(page.getByRole("link", { name: "acme/shop" })).toBeVisible();
  const cookies = await page.context().cookies();
  const session = cookies.find((cookie) => cookie.name === "rook_key");
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe("Lax");
});

test("the live feed shows an event pushed by the server", async ({ page }) => {
  await signIn(page);
  await expect(page.getByText("Live", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Patch proposed" })).toBeVisible();
});

test("the jobs list can be filtered by state", async ({ page }) => {
  await signIn(page);
  await page.getByRole("link", { name: "Jobs" }).first().click();
  await expect(page.getByRole("row")).toHaveCount(3);
  await page.getByLabel("State").selectOption("FAILED");
  await page.getByRole("button", { name: "Filter" }).click();
  await expect(page.getByRole("row")).toHaveCount(2);
  await expect(page.getByRole("link", { name: "acme/blog" })).toBeVisible();
});

test("a job shows its diff, verification and policy decision", async ({ page }) => {
  await signIn(page);
  await page.goto("/jobs/11111111-1111-4111-8111-111111111111");
  await expect(page.getByRole("heading", { name: "acme/shop" })).toBeVisible();
  const diff = page.getByRole("table", { name: "Changes in src/main/java/com/shop/Service.java" });
  await expect(diff.locator("tr[data-kind='del']")).toContainText("client.charge(5)");
  await expect(diff.locator("tr[data-kind='add']")).toContainText("client.createCharge(5)");
  await expect(page.getByText("Passed")).toBeVisible();
  await expect(page.getByRole("list", { name: "Verification gates" }).getByRole("listitem")).toHaveCount(2);
  await expect(page.getByText("Policy outcome:")).toContainText("Require approval");
  await expect(page.getByRole("img", { name: "Which upstream changes reach which files" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Job timeline" }).getByRole("listitem")).toHaveCount(2);
});

test("a reviewer approves a waiting job and the page shows the new state", async ({ page }) => {
  await signIn(page);
  await page.goto("/jobs/11111111-1111-4111-8111-111111111111");
  await page.getByLabel("Review note").fill("looks right");
  await page.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByText("Publishing", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: /Pull request #12/ })).toBeVisible();
});

test("an action the server refuses is explained on the page", async ({ page }) => {
  await signIn(page);
  await page.goto("/jobs/22222222-2222-4222-8222-222222222222");
  await page.getByRole("button", { name: "Retry from the start" }).click();
  await expect(page.getByText("Pending", { exact: true })).toBeVisible();
  await page.goto("/jobs/00000000-0000-4000-8000-000000000000");
  await expect(page.getByText("This page could not be found")).toBeVisible();
});

test("changes, repositories, usage, policies and audit render their data", async ({ page }) => {
  await signIn(page);
  await page.goto("/changes");
  await expect(page.getByRole("link", { name: "acme-sdk 1.0.0 to 2.0.0" })).toBeVisible();
  await page.getByRole("link", { name: "acme-sdk 1.0.0 to 2.0.0" }).click();
  await expect(page.getByText("charge renamed to createCharge")).toBeVisible();
  await expect(page.getByText("Use createCharge.")).toBeVisible();
  await page.goto("/repositories");
  await expect(page.getByRole("cell", { name: "acme-sdk" })).toBeVisible();
  await page.goto("/usage");
  await expect(page.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "2");
  await expect(page.getByText("$1.20")).toBeVisible();
  await page.goto("/policies");
  await expect(page.getByLabel("Most files a patch may change")).toHaveValue("10");
  await page.goto("/audit");
  await expect(page.getByRole("status")).toContainText("The hash chain is intact");
  await expect(page.getByRole("cell", { name: "IMPACT_ANALYZED" })).toBeVisible();
});

test("the evaluation page shows the committed results", async ({ page }) => {
  await signIn(page);
  await page.goto("/evaluation");
  const table = page.getByRole("table", { name: "Strategy results" });
  await expect(table.getByRole("row", { name: /codemod-only/ })).toContainText("71.4%");
  await expect(table.getByRole("row", { name: /agent/ })).toContainText(/100(\.0)?%/);
  await expect(table.getByRole("row", { name: /llm-only/ })).toContainText("98.2%");
  await expect(page.getByText(/Not run:/)).toHaveCount(0);
});

test("signing out drops the session", async ({ page }) => {
  await signIn(page);
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto("/jobs");
  await expect(page).toHaveURL(/\/login$/);
});

test("the page follows the colour scheme of the system", async ({ page }) => {
  await signIn(page);
  await page.emulateMedia({ colorScheme: "light" });
  const light = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  await page.emulateMedia({ colorScheme: "dark" });
  const dark = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(light).not.toBe(dark);
});

test("the main navigation is a labelled landmark and every page has one h1", async ({ page }) => {
  await signIn(page);
  await expect(page.getByRole("navigation", { name: "Main" })).toBeVisible();
  for (const path of ["/", "/jobs", "/changes", "/repositories", "/policies", "/usage", "/audit", "/evaluation"]) {
    await page.goto(path);
    await expect(page.getByRole("heading", { level: 1 })).toHaveCount(1);
  }
});
