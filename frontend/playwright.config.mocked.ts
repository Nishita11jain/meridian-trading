import { defineConfig, devices } from '@playwright/test';
import { loadE2EEnv } from './e2e/load-e2e-env';

loadE2EEnv();

const baseURL = process.env['E2E_BASE_URL'] || 'http://localhost:4200';

export default defineConfig({
  // Mocked suite stubs network calls on purpose; no backend reachability precheck.
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
  projects: [
    {
      name: 'mocked',
      testDir: './e2e-mocked',
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
