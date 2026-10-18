import { defineConfig, devices } from '@playwright/test';
import { loadE2EEnv } from './e2e/load-e2e-env';

loadE2EEnv();

const baseURL = process.env['E2E_BASE_URL'] || 'http://localhost:4200';

export default defineConfig({
  globalSetup: require.resolve('./e2e/global-setup.ts'),
  fullyParallel: false,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 2 : 0,
  workers: 1,
  reporter: 'html',
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  projects: [
    {
      name: 'manual',
      testDir: './e2e-manual',
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
