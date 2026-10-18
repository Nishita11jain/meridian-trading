import { describe, expect, it } from 'vitest';

import { closedMessage, isClosed, opensAt } from './trading-status';

describe('trading status wording', () => {
  it('is closed only when the API says CLOSED', () => {
    expect(isClosed({ tradingStatus: 'CLOSED' } as any)).toBe(true);
    expect(isClosed({ tradingStatus: 'OPEN' } as any)).toBe(false);
    expect(isClosed(null)).toBe(false);
  });

  it('says which market is closed and when it opens', () => {
    const text = closedMessage({ symbol: 'TCS.NS', market: 'NSE', nextOpen: '2026-10-05T03:45:00Z' });

    expect(text).toMatch(/^TCS\.NS trades on the NSE, which is closed now\. It opens Mon 5 Oct, \d{2}:\d{2}\.$/);
  });

  it('leaves out the time when there is none', () => {
    expect(closedMessage({ symbol: 'X', market: 'US market', nextOpen: null })).toBe('X trades on the US market, which is closed now.');
    expect(opensAt('not a date')).toBe('');
  });
});
