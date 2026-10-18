import { test, expect, Page } from '@playwright/test';

/**
 * Journeys that need no running backend: the browser's network is answered by the test, so the refusals
 * a real stack rarely produces on demand (expired session, throttling, business refusals) can be driven
 * on purpose. The real-stack journeys are in login.spec.ts and place-order.spec.ts.
 *
 * Run: npx playwright test journeys-mocked   (E2E_BASE_URL defaults to http://localhost:4200)
 */
// These run against a cold `ng serve`, whose first load of each lazy route chunk can take several
// seconds, so give each test more than the 30s default rather than letting a slow chunk load flake it.
test.describe.configure({ timeout: 60_000 });

const json = (status: number, body: unknown) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page: Page) {
  await page.addInitScript(() => sessionStorage.setItem('tokens', JSON.stringify({ accessToken: 'a', refreshToken: 'r' })));
  await page.route(/\/auth\/me$/, (r) => r.fulfill(json(200, { id: 'u-1', username: 'priya', accountId: 4, roles: ['CUSTOMER'] })));
  await page.route(/\/api\/v1\/accounts\//, (r) => r.fulfill(json(200, { accountId: 4, cashBalance: 1000, positions: [] })));
  await page.route(/\/positions/, (r) => r.fulfill(json(200, [])));
  await page.route(/\/orders(\?.*)?$/, (r) => (r.request().method() === 'GET' ? r.fulfill(json(200, [])) : r.fallback()));
  // No cached quotes, and no instrument catalogue: the symbol field falls back to free text with
  // the contract's format check, which is what these tests exercise, and the order flow does not
  // depend on the real clock or the real market's trading hours.
  await page.route(/\/api\/v1\/market\/instruments$/, (r) => r.fulfill(json(503, {})));
  await page.route(/\/api\/v1\/market\/quotes$/, (r) => r.fulfill(json(200, [])));
  // The app shell polls this on every page; left unmocked it 401s, which triggers silent
  // renewal, which also fails against the fake tokens here and tears down the whole session.
  await page.route(/\/api\/v1\/notifications\//, (r) => r.fulfill(json(200, { notifications: [], unreadCount: 0 })));
  // The watchlists page reads these on load; same 401-cascade risk as notifications above.
  await page.route(/\/api\/v1\/market\/fx-rates$/, (r) => r.fulfill(json(200, { baseCurrency: 'INR', currency: 'USD', midRate: 80, buyRate: 80.4, sellRate: 79.6, marginPercent: 0.5, rateDate: '2026-10-08' })));
  await page.route(/\/api\/v1\/market\/freshness$/, (r) => r.fulfill(json(200, { lastUpdatedOn: null, nextUpdateAt: null, pollIntervalSeconds: 300 })));
  await page.route(/\/api\/v1\/watchlists\/presets$/, (r) => r.fulfill(json(200, [])));
  // The shell reads the saved preferences (for the avatar) on every page; same 401-cascade risk as above.
  // Tests that exercise preferences register their own route afterwards, and the later route wins.
  await page.route(/\/api\/v1\/preferences\/4$/, (r) => r.fulfill(json(200, {
    accountId: 4, defaultAccountId: 4, notificationChannel: 'IN_APP', contactEmail: null, contactPhone: null,
    displayCurrency: 'INR', theme: 'SYSTEM', updatedOn: null
  })));
  // The portfolio page's value chart reads the history; an empty history just shows the chart's empty state.
  await page.route(/\/api\/v1\/portfolio\/4\/history/, (r) => r.fulfill(json(200, [])));
  // The ticket's price-trend line reads the tick history for the typed symbol; an empty list shows its empty state.
  await page.route(/\/api\/v1\/market\/history\//, (r) => r.fulfill(json(200, [])));
}

async function fillTicket(page: Page) {
  await page.goto('/trade');
  await page.fill('[data-testid="ticket-symbol"]', 'TCS.NS');
  await page.fill('[data-testid="ticket-quantity"]', '1');
  await page.fill('[data-testid="ticket-price"]', '10.00');
}

test.describe('Journeys with a stubbed backend', () => {
  test('a visitor without a session is sent to sign-in from every protected page', async ({ page }) => {
    for (const path of ['/dashboard', '/trade', '/blotter']) {
      await page.goto(path);
      await page.waitForURL('**/sign-in**');
      await expect(page.locator('[data-testid="signin-submit"]')).toBeVisible();
    }
  });

  test('an unknown address ends on the dashboard for a signed-in user', async ({ page }) => {
    await signedIn(page);
    await page.goto('/no/such/page');
    await page.waitForURL('**/dashboard');
  });

  test('a signed-in user is kept off the sign-in page', async ({ page }) => {
    await signedIn(page);
    await page.goto('/sign-in');
    await page.waitForURL('**/dashboard');
  });

  test('a 401 from a protected call ends the session and explains why on sign-in', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/v1\/accounts\//, (r) => r.fulfill(json(401, { errorCode: 'AUTH-401', message: 'Unauthorised' })));
    await page.route(/\/auth\/me$/, (r) => r.fulfill(json(401, { errorCode: 'AUTH-401', message: 'Unauthorised' })));
    // The access token is expired; the refresh token is refused too, so silent renewal cannot save the
    // session and the user is sent to sign-in. Stubbed so the outcome does not depend on a running stack.
    await page.route(/\/auth\/refresh$/, (r) => r.fulfill(json(401, { errorCode: 'AUTH-401', message: 'Unauthorised' })));
    await page.goto('/dashboard');
    await page.waitForURL('**/sign-in**');
    await expect(page.locator('[data-testid="signin-error"]')).toContainText('Your session has expired');
    expect(await page.evaluate(() => sessionStorage.getItem('tokens'))).toBeNull();
  });

  test('too many sign-in attempts (429) shows a wait message, not a credentials error', async ({ page }) => {
    await page.route(/\/auth\/login$/, (r) => r.fulfill(json(429, { message: 'ThrottlerException: Too Many Requests' })));
    await page.goto('/sign-in');
    await page.fill('[data-testid="signin-username"]', 'priya');
    await page.fill('[data-testid="signin-password"]', 'whatever');
    await page.click('[data-testid="signin-submit"]');
    const alert = page.locator('[data-testid="signin-error"]');
    await expect(alert).toContainText(/too many|try again/i);
    await expect(alert).not.toContainText('Throttler');
    expect(page.url()).toContain('/sign-in');
  });

  for (const [code, text] of [
    ['ORD-400', 'not enough cash'],
    ['ORD-409', 'already been placed'],
    ['INS-404', 'cannot be traded'],
    ['ACC-403', 'not active'],
  ] as const) {
    test(`a ${code} refusal is shown in plain words and no order is reported placed`, async ({ page }) => {
      await signedIn(page);
      await page.route(/\/api\/v1\/orders$/, (r) =>
        r.request().method() === 'POST' ? r.fulfill(json(code === 'ORD-409' ? 409 : code === 'ACC-403' ? 403 : code === 'INS-404' ? 404 : 400, { errorCode: code, message: 'internal detail' })) : r.fallback());
      await fillTicket(page);
      await page.click('[data-testid="ticket-submit"]');
      await page.click('[data-testid="ticket-confirm"]');
      await expect(page.locator('[data-testid="ticket-error"]')).toContainText(text);
      await expect(page.locator('[data-testid="ticket-error"]')).not.toContainText('internal detail');
      await expect(page.locator('[data-testid="ticket-success"]')).toHaveCount(0);
    });
  }

  test('leaving a half-filled order asks first, and Cancel keeps the user on the form', async ({ page }) => {
    await signedIn(page);
    await fillTicket(page);
    page.once('dialog', (d) => d.dismiss());
    await page.click('[data-testid="nav-dashboard"]');
    expect(page.url()).toContain('/trade');
  });

  test('each page sets its own title', async ({ page }) => {
    await signedIn(page);
    await page.goto('/blotter');
    await expect(page).toHaveTitle(/Blotter/);
    await page.goto('/trade');
    await expect(page).toHaveTitle(/New order/);
  });

  test('a NEW order can be cancelled from the blotter after a confirmation', async ({ page }) => {
    await signedIn(page);
    const id = '6f2b1c2a-6a1e-4a4f-9c0d-2f7a1b3c4d5e';
    let cancelled = false;
    let deleted = '';
    await page.route(/\/api\/v1\/accounts\/4\/orders/, (r) =>
      r.fulfill(json(200, [{ orderId: 'ORD-' + id, accountId: 4, symbol: 'TCS.NS', side: 'BUY', quantity: 1, price: 10, status: cancelled ? 'CANCELLED' : 'NEW', createdOn: '2026-01-01T00:00:00Z' }])));
    await page.route(/\/api\/v1\/orders\/[0-9a-f-]+$/, (r) => {
      deleted = r.request().url();
      cancelled = true;
      return r.fulfill(json(200, { orderId: 'ORD-' + id, status: 'CANCELLED', message: 'Order cancelled', symbol: 'TCS.NS', side: 'BUY', quantity: 1, price: 10 }));
    });
    await page.goto('/blotter');
    await page.click('[data-testid="blotter-cancel"]');
    // The confirmation is an in-page dialog now, not the browser's native prompt.
    await page.click('[data-testid="blotter-cancel-dialog-confirm"]');
    await expect(page.locator('[data-testid="blotter-status"]')).toContainText('CANCELLED');
    await expect(page.locator('[data-testid="blotter-cancel"]')).toHaveCount(0);
    expect(deleted.endsWith('/' + id)).toBe(true);
  });

  test('placing an order needs a review and a confirmation; nothing is sent before it', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/v1\/accounts\/4\/balance/, (r) => r.fulfill(json(200, { accountId: 4, cashBalance: 1000, currency: 'INR', asOf: '2026-01-01T00:00:00Z' })));
    let posts = 0;
    await page.route(/\/api\/v1\/orders$/, (r) => {
      if (r.request().method() !== 'POST') return r.fallback();
      posts++;
      return r.fulfill(json(201, { orderId: 'ORD-1', status: 'NEW', message: 'Accepted', symbol: 'TCS.NS', side: 'BUY', quantity: 1, price: 10 }));
    });
    await fillTicket(page);
    await expect(page.locator('[data-testid="ticket-cash"]')).toContainText('₹1,000.00');
    await expect(page.locator('[data-testid="ticket-estimate"]')).toContainText('10.00');
    await page.click('[data-testid="ticket-submit"]');
    await expect(page.locator('[data-testid="ticket-review"]')).toBeVisible();
    expect(posts).toBe(0);
    await page.click('[data-testid="ticket-edit"]');
    await expect(page.locator('[data-testid="ticket-review"]')).toHaveCount(0);
    await page.click('[data-testid="ticket-submit"]');
    await page.click('[data-testid="ticket-confirm"]');
    await expect(page.locator('[data-testid="ticket-success"]')).toContainText('ORD-1');
    expect(posts).toBe(1);
  });

  test('an expired access token is renewed silently and the order the user typed is not lost', async ({ page }) => {
    await signedIn(page);
    let refreshes = 0;
    let orderCalls = 0;
    await page.route(/\/auth\/refresh$/, (r) => {
      refreshes++;
      return r.fulfill(json(200, { accessToken: 'fresh', refreshToken: 'r2', tokenType: 'Bearer', expiresIn: 900 }));
    });
    await page.route(/\/api\/v1\/orders$/, (r) => {
      if (r.request().method() !== 'POST') return r.fallback();
      orderCalls++;
      const ok = r.request().headers()['authorization'] === 'Bearer fresh';
      return ok
        ? r.fulfill(json(201, { orderId: 'ORD-2', status: 'NEW', message: 'Accepted', symbol: 'TCS.NS', side: 'BUY', quantity: 1, price: 10 }))
        : r.fulfill(json(401, { errorCode: 'AUTH-401', message: 'Unauthorised' }));
    });
    await fillTicket(page);
    await page.click('[data-testid="ticket-submit"]');
    await page.click('[data-testid="ticket-confirm"]');
    await expect(page.locator('[data-testid="ticket-success"]')).toContainText('ORD-2');
    expect(refreshes).toBe(1);
    expect(orderCalls).toBe(2);
    expect(page.url()).toContain('/trade');
  });

  test('when the refresh token is refused too, the user is sent to sign-in', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/auth\/refresh$/, (r) => r.fulfill(json(401, { errorCode: 'AUTH-401', message: 'Unauthorised' })));
    await page.route(/\/api\/v1\/accounts\//, (r) => r.fulfill(json(401, { errorCode: 'AUTH-401', message: 'Unauthorised' })));
    await page.goto('/dashboard');
    await page.waitForURL('**/sign-in**');
    await expect(page.locator('[data-testid="signin-error"]')).toContainText('session has expired');
  });

  test('the password can be shown and hidden on sign-in', async ({ page }) => {
    await page.goto('/sign-in');
    const input = page.locator('[data-testid="signin-password"]');
    await expect(input).toHaveAttribute('type', 'password');
    await page.click('[data-testid="signin-toggle-password"]');
    await expect(input).toHaveAttribute('type', 'text');
    await page.click('[data-testid="signin-toggle-password"]');
    await expect(input).toHaveAttribute('type', 'password');
  });

  test('preferences load, and saving the display theme switches the UI theme', async ({ page }) => {
    await signedIn(page);
    // A display currency that differs from the form's default (INR), so seeing it proves the saved preferences arrived.
    let saved = { accountId: 4, defaultAccountId: 4, notificationChannel: 'IN_APP', contactEmail: null, contactPhone: null, displayCurrency: 'USD', theme: 'SYSTEM', updatedOn: null };
    await page.route(/\/api\/v1\/preferences\/4$/, (r) => r.fulfill(json(200, saved)));
    await page.route(/\/api\/v1\/preferences\/4\/display$/, async (r) => {
      saved = { ...saved, ...JSON.parse(r.request().postData() || '{}'), updatedOn: '2026-10-01T00:00:00Z' };
      return r.fulfill(json(200, saved));
    });
    await page.route(/\/api\/v1\/preferences\/4\/notifications$/, async (r) => {
      saved = { ...saved, ...JSON.parse(r.request().postData() || '{}'), updatedOn: '2026-10-01T00:00:00Z' };
      return r.fulfill(json(200, saved));
    });

    await page.goto('/preferences');
    await expect(page.locator('[data-testid="preferences-channel"]')).toHaveValue('IN_APP');
    // Wait for the saved preferences to fill the form: a late load would otherwise overwrite the change below.
    await expect(page.locator('[data-testid="preferences-currency"]')).toHaveValue('USD');

    await page.selectOption('[data-testid="preferences-theme"]', 'DARK');
    await page.click('[data-testid="preferences-save-display"]');
    await expect(page.locator('[data-testid="preferences-saved"]')).toContainText('Display settings saved');
    await expect(page.locator('html')).toHaveClass(/dark/);
  });

  test('choosing the EMAIL channel reveals the email field and saves it', async ({ page }) => {
    await signedIn(page);
    let saved = { accountId: 4, defaultAccountId: 4, notificationChannel: 'IN_APP', contactEmail: null, contactPhone: null, displayCurrency: 'USD', theme: 'SYSTEM', updatedOn: null };
    await page.route(/\/api\/v1\/preferences\/4$/, (r) => r.fulfill(json(200, saved)));
    let sentChannel = '';
    await page.route(/\/api\/v1\/preferences\/4\/notifications$/, async (r) => {
      const body = JSON.parse(r.request().postData() || '{}');
      sentChannel = body.notificationChannel;
      saved = { ...saved, ...body, updatedOn: '2026-10-01T00:00:00Z' };
      return r.fulfill(json(200, saved));
    });

    await page.goto('/preferences');
    // Wait for the saved preferences to fill the form: a late load would otherwise reset the channel chosen below.
    await expect(page.locator('[data-testid="preferences-currency"]')).toHaveValue('USD');
    await expect(page.locator('[data-testid="preferences-email"]')).toHaveCount(0);
    await page.selectOption('[data-testid="preferences-channel"]', 'EMAIL');
    await page.fill('[data-testid="preferences-email"]', 'me@example.com');
    await page.click('[data-testid="preferences-save-notifications"]');
    await expect(page.locator('[data-testid="preferences-saved"]')).toContainText('Notification settings saved');
    expect(sentChannel).toBe('EMAIL');
  });

  test('the inbox shows notifications and the sidebar badge shows the unread count', async ({ page }) => {
    await signedIn(page);
    const unread = { id: '00000000-0000-0000-0000-000000000001', accountId: 4, type: 'ORDER_FILLED', channel: 'EMAIL', subject: 'Order filled: TCS.NS', message: 'Your BUY order for 10 TCS.NS was filled at 3425.00.', deliveryState: 'SENT', readOn: null, createdOn: '2026-10-01T09:00:00Z' };
    let state = { notifications: [unread], unreadCount: 1 };
    await page.route(/\/api\/v1\/notifications\/4$/, (r) => r.fulfill(json(200, state)));
    await page.route(/\/api\/v1\/notifications\/4\/read$/, (r) => {
      state = { notifications: [{ ...unread, readOn: '2026-10-01T10:00:00Z' }], unreadCount: 0 };
      return r.fulfill(json(200, state));
    });

    await page.goto('/notifications');
    await expect(page.locator('[data-testid="notifications-row"]')).toHaveCount(1);
    await expect(page.locator('[data-testid="nav-notifications-badge"]')).toHaveText('1');
    await expect(page.locator('[data-testid="notifications-unread-dot"]')).toBeVisible();
  });

  test('marking all read clears the unread dot and the sidebar badge', async ({ page }) => {
    await signedIn(page);
    const unread = { id: '00000000-0000-0000-0000-000000000002', accountId: 4, type: 'ORDER_REJECTED', channel: 'IN_APP', subject: 'Order rejected: MSFT', message: 'Your BUY order for 2 MSFT was rejected (INSUFFICIENT_FUNDS).', deliveryState: 'SENT', readOn: null, createdOn: '2026-10-01T09:00:00Z' };
    let state = { notifications: [unread], unreadCount: 1 };
    await page.route(/\/api\/v1\/notifications\/4$/, (r) => r.fulfill(json(200, state)));
    await page.route(/\/api\/v1\/notifications\/4\/read$/, (r) => {
      state = { notifications: [{ ...unread, readOn: '2026-10-01T10:00:00Z' }], unreadCount: 0 };
      return r.fulfill(json(200, state));
    });

    await page.goto('/notifications');
    await expect(page.locator('[data-testid="nav-notifications-badge"]')).toHaveText('1');
    await page.click('[data-testid="notifications-mark-read"]');
    await expect(page.locator('[data-testid="nav-notifications-badge"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="notifications-unread-dot"]')).toHaveCount(0);
  });

  test('the dashboard shows the market board and a sparkline on click', async ({ page }) => {
    await signedIn(page);
    const q = (symbol, over = {}) => ({ symbol, lastPrice: 3500, bid: null, ask: null, currency: 'INR', change: 10, changePercent: 0.5, previousClose: 3490, marketState: 'open', stale: false, quoteAsOf: '2026-10-01T09:00:00Z', ...over });
    await page.route(/\/api\/v1\/market\/instruments$/, (r) => r.fulfill(json(200, [{ symbol: 'TCS.NS', name: 'Tata', assetClass: 'EQUITY', currency: 'INR' }])));
    await page.route(/\/api\/v1\/market\/quotes$/, (r) => r.fulfill(json(200, [q('TCS.NS')])));
    await page.route(/\/api\/v1\/market\/history\/[^/]+$/, (r) => r.fulfill(json(200, [q('TCS.NS', { lastPrice: 3505 }), q('TCS.NS', { lastPrice: 3500 })])));
    await page.route(/\/api\/v1\/watchlists$/, (r) => r.fulfill(json(200, [])));
    await page.route(/\/api\/v1\/alerts$/, (r) => r.fulfill(json(200, [])));
    await page.route(/\/api\/v1\/portfolio\/4$/, (r) => r.fulfill(json(200, { accountId: 4, baseCurrency: 'INR', cashBalance: 1000, marketValue: 0, costBasis: 0, unrealisedPnl: 0, unrealisedPnlPercent: null, realisedPnl: 0, totalValue: 1000, positionCount: 0, partial: false, asOf: '2026-10-01T09:00:00Z' })));

    await page.goto('/dashboard');
    await expect(page.locator('[data-testid="market-row"]')).toHaveCount(1);
    await page.click('[data-testid="market-row"]');
    await expect(page.locator('[data-testid="sparkline"]')).toBeVisible();
  });

  test('a price alert can be created and cancelled', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/v1\/market\/instruments$/, (r) => r.fulfill(json(200, [{ symbol: 'TCS.NS', name: 'Tata', assetClass: 'EQUITY', currency: 'INR' }])));
    await page.route(/\/api\/v1\/market\/quotes$/, (r) => r.fulfill(json(200, [])));
    await page.route(/\/api\/v1\/watchlists$/, (r) => r.fulfill(json(200, [])));
    let alerts = [];
    await page.route(/\/api\/v1\/alerts$/, (r) => {
      if (r.request().method() === 'POST') {
        alerts = [{ id: 9, symbol: 'TCS.NS', direction: 'ABOVE', targetPrice: 3700, status: 'ACTIVE', createdOn: null, triggeredOn: null, triggeredPrice: null }];
        return r.fulfill(json(201, alerts[0]));
      }
      return r.fulfill(json(200, alerts));
    });
    await page.route(/\/api\/v1\/alerts\/9$/, (r) => {
      alerts = [{ ...alerts[0], status: 'CANCELLED' }];
      return r.fulfill(json(200, alerts[0]));
    });

    await page.goto('/watchlists');
    await page.selectOption('[data-testid="alert-symbol"]', 'TCS.NS');
    await page.fill('[data-testid="alert-target"]', '3700');
    await page.click('[data-testid="alert-create"]');
    await expect(page.locator('[data-testid="alert-row"]')).toHaveCount(1);
    await expect(page.locator('[data-testid="alert-status"]')).toHaveText('ACTIVE');
    await page.click('[data-testid="alert-cancel"]');
    await expect(page.locator('[data-testid="alert-status"]')).toHaveText('CANCELLED');
  });

  test('the portfolio prices holdings, totals them in rupees, and charts them', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/v1\/portfolio\/4$/, (r) => r.fulfill(json(200, {
      accountId: 4, baseCurrency: 'INR', cashBalance: 1000, marketValue: 35000, costBasis: 100400, unrealisedPnl: 1000,
      unrealisedPnlPercent: 2.94, realisedPnl: 500, totalValue: 36000, positionCount: 2, partial: true, asOf: '2026-10-01T09:00:00Z'
    })));
    await page.route(/\/api\/v1\/portfolio\/4\/positions$/, (r) => r.fulfill(json(200, [
      { symbol: 'TCS.NS', quantity: 10, averageCost: 3400, costBasis: 34000, lastPrice: 3500, marketValue: 35000, unrealisedPnl: 1000, unrealisedPnlPercent: 2.94, currency: 'INR', priceAsOf: '2026-10-01T09:00:00Z', stale: false },
      { symbol: 'MSFT', quantity: 2, averageCost: 400, costBasis: 800, lastPrice: null, marketValue: null, unrealisedPnl: null, unrealisedPnlPercent: null, currency: 'USD', priceAsOf: null, stale: true }
    ])));
    await page.route(/\/api\/v1\/portfolio\/4\/pnl/, (r) => r.fulfill(json(200, {
      accountId: 4, baseCurrency: 'INR', realisedPnl: 500, unrealisedPnl: 1000, totalPnl: 1500, asOf: '2026-10-01T09:00:00Z'
    })));

    await page.goto('/portfolio');
    await expect(page.locator('[data-testid="position-row"]')).toHaveCount(2);
    await expect(page.locator('[data-testid="position-stale"]')).toBeVisible();
    await expect(page.locator('[data-testid="portfolio-partial"]')).toBeVisible();
    await expect(page.locator('[data-testid="allocation-chart"]')).toBeVisible();
    await expect(page.locator('[data-testid="allocation-donut"]')).toBeVisible();
    await expect(page.locator('[data-testid="portfolio-currency-card"]').first()).toBeVisible();
  });

  test('the portfolio shows a clear message when nothing can be priced (503)', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/v1\/portfolio\/4(\/positions|\/pnl|\/history.*)?$/, (r) => r.fulfill(json(503, { errorCode: 'MKT-503', message: 'Pricing unavailable' })));

    await page.goto('/portfolio');
    await expect(page.locator('[data-testid="portfolio-error"]')).toContainText('temporarily unavailable');
  });

  test('the public landing page is shown at the root for a visitor, with a way into the app', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="landing-cta"]')).toBeVisible();
    await expect(page.locator('[data-testid="landing-signin"]')).toBeVisible();
    await page.locator('[data-testid="landing-feature"]').first().waitFor();
    await page.click('[data-testid="landing-signin"]');
    await page.waitForURL('**/sign-in**');
    await expect(page.locator('[data-testid="signin-submit"]')).toBeVisible();
  });
});
