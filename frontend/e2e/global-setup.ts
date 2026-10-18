/**
 * Fail fast, before any browser opens, if the real backend this suite depends on isn't
 * reachable. E2E_TRADE_API and E2E_AUTH_API are declared as required variables for this
 * suite; this is what actually reads them, rather than leaving them as names nobody checks.
 *
 * A reachable service can still answer 401/403/404 here — that's fine, it proves the host
 * is up and listening. Only a network failure (refused connection, DNS, timeout) fails setup.
 */
async function checkReachable(label: string, baseUrl: string): Promise<void> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);

  try {
    await fetch(baseUrl, { signal: controller.signal });
  } catch (err) {
    throw new Error(
      `${label} (${baseUrl}) is not reachable: ${(err as Error).message}. ` +
      `Start it before running the e2e suite, or correct the environment variable.`
    );
  } finally {
    clearTimeout(timeout);
  }
}

export default async function globalSetup(): Promise<void> {
  // The "mocked" project stubs every network call on purpose and needs no real backend —
  // running this check for it would fail a suite that is deliberately backend-free.
  if (process.argv.some((arg) => arg.includes('mocked'))) {
    return;
  }

  const tradeApi = process.env['E2E_TRADE_API'] || 'http://localhost:8081';
  const authApi = process.env['E2E_AUTH_API'] || 'http://localhost:3000';

  await checkReachable('E2E_TRADE_API', tradeApi);
  await checkReachable('E2E_AUTH_API', authApi);
}
