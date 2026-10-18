import { test, expect, Page } from '@playwright/test';

/**
 * SEC2-694: E2E test for placing an order
 *
 * Tests the order ticket: the account renders read-only, an invalid order is
 * rejected before it ever reaches the wire, and a valid order shows whatever
 * outcome the API actually returned.
 *
 * Requires E2E_USERNAME, E2E_PASSWORD, E2E_ACCOUNT_ID and E2E_SYMBOL
 * environment variables.
 *
 * Run with:
 *   E2E_USERNAME=... E2E_PASSWORD=... E2E_ACCOUNT_ID=1 E2E_SYMBOL=TCS.NS npx playwright test place-order.spec.ts
 */

const username = process.env['E2E_USERNAME'] || '';
const password = process.env['E2E_PASSWORD'] || '';
const accountId = process.env['E2E_ACCOUNT_ID'] || '';
const symbol = process.env['E2E_SYMBOL'] || '';

test.describe('Place an order', () => {
  test.skip(
    !username || !password || !accountId || !symbol,
    'E2E_USERNAME, E2E_PASSWORD, E2E_ACCOUNT_ID and E2E_SYMBOL must be set'
  );

  test.setTimeout(180000);

  async function pause(page: Page, ms = 800): Promise<void> {
    await page.waitForTimeout(ms);
  }

  async function fillAndPause(page: Page, selector: string, value: string): Promise<void> {
    await page.fill(selector, value);
    await pause(page);
  }

  async function signIn(page: Page): Promise<void> {
    await page.goto('/sign-in');
    await fillAndPause(page, '[data-testid="signin-username"]', username);
    await fillAndPause(page, '[data-testid="signin-password"]', password);
    await page.click('[data-testid="signin-submit"]');
    await page.waitForURL('**/dashboard');
    await pause(page, 1200);
    // The order ticket lives on its own page, separate from the dashboard.
    await page.click('[data-testid="nav-trade"]');
    await page.waitForURL('**/trade');
    await pause(page, 1200);
  }

  async function resetToTrade(page: Page): Promise<void> {
    if (!/\/trade/.test(page.url())) {
      await page.click('[data-testid="nav-trade"]');
      await page.waitForURL('**/trade');
    }
    await fillAndPause(page, '[data-testid="ticket-symbol"]', '');
    await fillAndPause(page, '[data-testid="ticket-quantity"]', '');
    await fillAndPause(page, '[data-testid="ticket-price"]', '');
  }

  test('one login, then run order-ticket cases sequentially with visible pauses', async ({ page }) => {
    await signIn(page);

    // Case 1: account is read-only and tied to signed-in session.
    // The account comes from the session and is shown, never typed: there is no account field to
    // edit. The badge shows the customer-facing identifier (e.g. "ETP-4105"), resolved server-side
    // from the numeric account claim in the token — there's no way to derive that string from
    // E2E_ACCOUNT_ID here, so this checks that a real value rendered, not exact equality.
    await expect(page.locator('[data-testid="ticket-account-badge"]')).not.toHaveText('Account');
    await expect(page.locator('[data-testid="ticket-account"]')).toHaveCount(0);
    await pause(page, 1500);

    // Case 2: unsaved ticket confirmation flow.
    await fillAndPause(page, '[data-testid="ticket-symbol"]', symbol);

    // Answer each "leave this page?" prompt as the test says, and keep what was asked.
    const asked: string[] = [];
    let answer: 'accept' | 'dismiss' = 'dismiss';
    page.on('dialog', async (dialog) => {
      asked.push(dialog.message());
      await (answer === 'accept' ? dialog.accept() : dialog.dismiss());
    });

    // Back to the dashboard, then choose Stay: still on the ticket, with the symbol still typed.
    await page.goBack();
    await expect.poll(() => asked.length).toBe(1);
    expect(asked[0]).toContain('not been placed');
    await expect(page).toHaveURL(/\/trade/);
    await expect(page.locator('[data-testid="ticket-symbol"]')).toHaveValue(symbol);
    await pause(page, 1500);

    // Back again, this time choose Leave: the dashboard (the browser history was not disturbed).
    answer = 'accept';
    await page.goBack();
    await page.waitForURL('**/dashboard');
    await expect.poll(() => asked.length).toBe(2);
    await pause(page, 1500);

    // Return to trade once, then continue remaining cases in the same login session.
    await resetToTrade(page);
    await pause(page, 1500);

    // Case 3: invalid order blocked client-side.
    // Every POST to the orders API is recorded, so "nothing was sent" is proven by an
    // actual request count rather than by the absence of a response panel.
    const orderRequests: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().includes('/api/v1/orders')) {
        orderRequests.push(request.url());
      }
    });

    await fillAndPause(page, '[data-testid="ticket-symbol"]', symbol);
    // Zero is not a whole number greater than zero: caught client-side,
    // before any request is sent.
    await fillAndPause(page, '[data-testid="ticket-quantity"]', '0');
    await fillAndPause(page, '[data-testid="ticket-price"]', '10.00');
    await page.click('[data-testid="ticket-submit"]');
    await pause(page);

    // The form's own validation hint appears...
    const quantityField = page.locator('[data-testid="ticket-quantity"]');
    await expect(quantityField).toBeVisible();
    const hint = page.locator('text=Quantity must be a whole number greater than zero.');
    await expect(hint).toBeVisible();

    // ...and nothing was actually sent to the API: no outcome of either kind renders.
    await expect(page.locator('[data-testid="ticket-success"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="ticket-error"]')).toHaveCount(0);

    // Give a wrongly-sent request time to show up before asserting its absence.
    await page.waitForTimeout(1500);
    expect(orderRequests, 'an invalid order must never reach the API').toHaveLength(0);
    await pause(page, 1500);

    // Case 4: valid order submits from same session.
    await fillAndPause(page, '[data-testid="ticket-quantity"]', '1');
    await fillAndPause(page, '[data-testid="ticket-price"]', '1.00');
    await pause(page, 1200);

    await page.click('[data-testid="ticket-submit"]');
    await pause(page, 700);
    await page.click('[data-testid="ticket-confirm"]');

    const success = page.locator('[data-testid="ticket-success"]');
    const error = page.locator('[data-testid="ticket-error"]');

    // The API's own order-publish step has up to a 5s internal timeout
    // before it answers, so give this a real margin above that rather than
    // racing it with the default assertion timeout.
    await expect(success.or(error)).toBeVisible({ timeout: 10000 });

    // A valid, affordable order at this price must be accepted — an error here is a real
    // failure, not an acceptable alternative outcome, so fail with the server's own message
    // rather than silently passing on the error branch.
    if (await error.isVisible()) {
      throw new Error(`Expected the order to be accepted, but it was refused: ${(await error.innerText()).trim()}`);
    }

    // Never assume FILLED: the executor resolves NEW asynchronously, after this assertion runs.
    await expect(success).toContainText(/NEW|FILLED|REJECTED/);
    await pause(page, 1500);
  });
});
