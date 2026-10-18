import { test, expect, Page } from '@playwright/test';

/**
 * SEC2-688: E2E test for sign-in flow
 * 
 * Tests that a user can sign in with correct credentials and reach the dashboard.
 * Requires E2E_USERNAME and E2E_PASSWORD environment variables.
 * 
 * Run with: E2E_USERNAME=testuser E2E_PASSWORD=testpass npx playwright test
 */

test.describe('Sign-in Flow', () => {
  const username = process.env['E2E_USERNAME'] || '';
  const password = process.env['E2E_PASSWORD'] || '';

  test.skip(!username || !password, 'E2E_USERNAME and E2E_PASSWORD must be set');
  test.setTimeout(180000);

  async function pause(page: Page, ms = 800): Promise<void> {
    await page.waitForTimeout(ms);
  }

  async function fillAndPause(page: Page, selector: string, value: string): Promise<void> {
    await page.fill(selector, value);
    await pause(page);
  }

  test('one login, then sign-in and guard cases sequentially with visible pauses', async ({ page }) => {
    // Case 1: empty submit is blocked on sign-in.
    await page.goto('/sign-in');
    await page.waitForURL('**/sign-in**');
    await pause(page, 1200);
    await page.click('[data-testid="signin-submit"]');
    await pause(page);
    await expect(page.locator('[data-testid="signin-error"]')).toContainText('Enter your username and password');
    expect(page.url()).toContain('/sign-in');
    await pause(page, 1400);

    // Case 2: invalid credentials show refusal and clear password.
    await fillAndPause(page, '[data-testid="signin-username"]', username);
    await fillAndPause(page, '[data-testid="signin-password"]', 'wrong-password');
    await page.click('[data-testid="signin-submit"]');
    await pause(page);
    const errorAlert = page.locator('[data-testid="signin-error"]');
    await expect(errorAlert).toBeVisible();
    await expect(errorAlert).toContainText('Sign-in was refused');
    await expect(page.locator('[data-testid="signin-password"]')).toHaveValue('');
    expect(page.url()).toContain('/sign-in');
    await pause(page, 1400);

    // Case 3: signed-out guard keeps returnUrl, then successful login once lands on target.
    await page.goto('/blotter');
    await page.waitForURL('**/sign-in**');
    expect(page.url()).toContain('/sign-in');
    expect(page.url()).toContain('returnUrl');
    expect(decodeURIComponent(page.url())).toContain('/blotter');
    await pause(page, 1200);
    await fillAndPause(page, '[data-testid="signin-username"]', username);
    await fillAndPause(page, '[data-testid="signin-password"]', password);
    await page.click('[data-testid="signin-submit"]');
    await page.waitForURL('**/blotter');
    expect(page.url()).toContain('/blotter');
    await pause(page, 1400);

    // Case 4: signed-in user is kept off sign-in and unsafe returnUrl is ignored.
    await page.goto('/sign-in');
    await page.waitForURL('**/dashboard');
    await expect(page.locator('[data-testid="signin-username"]')).toHaveCount(0);
    await pause(page, 1200);
    await page.goto('/sign-in?returnUrl=%2Fblotter');
    await page.waitForURL('**/blotter');
    await pause(page, 1200);
    await page.goto('/sign-in?returnUrl=https%3A%2F%2Fevil.example%2Fphish');
    await page.waitForURL('**/dashboard');
    expect(new URL(page.url()).hostname).not.toBe('evil.example');
    await pause(page, 1400);

    // Case 5: sign out from signed-in area.
    await page.click('[data-testid="signout"]');
    await page.waitForURL('**/sign-in');
    expect(page.url()).toContain('/sign-in');
    await pause(page, 1500);
  });
});
