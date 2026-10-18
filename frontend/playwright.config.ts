import { defineConfig, devices } from '@playwright/test';
import { loadE2EEnv } from './e2e/load-e2e-env';

/**
 * SEC2-688 / SEC2-694: Playwright E2E configuration
 *
 * Requires environment variables:
 * - E2E_BASE_URL: The base URL of the trading UI (e.g., http://localhost:4200)
 * - E2E_USERNAME: Username for test login
 * - E2E_PASSWORD: Password for test login
 * - E2E_ACCOUNT_ID: Numeric trading account the above login may trade (place-order.spec.ts)
 * - E2E_SYMBOL: A tradable instrument symbol (place-order.spec.ts)
 *
 * To run: npx playwright test
 * With custom base URL: E2E_BASE_URL=http://localhost:4200 npx playwright test
 */

const DEFAULT_E2E_ACCOUNT_ID = '5';
const DEFAULT_E2E_SYMBOL = 'TCS.NS';

loadE2EEnv();

process.env['E2E_ACCOUNT_ID'] = process.env['E2E_ACCOUNT_ID'] || DEFAULT_E2E_ACCOUNT_ID;
process.env['E2E_SYMBOL'] = process.env['E2E_SYMBOL'] || DEFAULT_E2E_SYMBOL;

const baseURL = process.env['E2E_BASE_URL'] || 'http://localhost:4200';

// Validate required environment variables
if (!process.env['E2E_USERNAME'] || !process.env['E2E_PASSWORD']) {
  console.warn('Warning: E2E_USERNAME and E2E_PASSWORD environment variables not set.');
  console.warn('E2E tests that require authentication will fail.');
}
if (!process.env['E2E_ACCOUNT_ID'] || !process.env['E2E_SYMBOL']) {
  console.warn('Warning: E2E_ACCOUNT_ID and E2E_SYMBOL environment variables not set.');
  console.warn('place-order.spec.ts will be skipped.');
}

export default defineConfig({
  globalSetup: require.resolve('./e2e/global-setup.ts'),
  fullyParallel: false,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 2 : 0,
  workers: process.env['CI'] ? 1 : 1,
  reporter: 'html',
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },

  // The default config is the assessed real-stack run only.
  projects: [
    {
      name: 'chromium',
      testDir: './e2e',
      use: { ...devices['Desktop Chrome'] }
    }
  ],

  webServer: process.env['CI']
    ? undefined
    : {
        command: 'npm start',
        url: baseURL,
        reuseExistingServer: !process.env['CI'],
        timeout: 120000
      }
});
