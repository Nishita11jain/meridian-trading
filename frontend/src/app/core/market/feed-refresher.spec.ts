import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { FEED_GRACE_MS, FEED_RETRY_MS, FeedRefresher, nextRefreshDelay } from './feed-refresher';

const NOW = Date.parse('2026-10-02T05:00:00Z');
const due = (inMs: number) => ({
  lastUpdatedOn: new Date(NOW + inMs - 300_000).toISOString(),
  nextUpdateAt: new Date(NOW + inMs).toISOString(),
  pollIntervalSeconds: 300
});

describe('nextRefreshDelay', () => {
  it('waits until the next batch is due, plus a little grace for the poller', () => {
    expect(nextRefreshDelay(due(120_000), NOW)).toBe(120_000 + FEED_GRACE_MS);
  });

  it('retries in a minute when the batch is overdue (the poller is late or down)', () => {
    expect(nextRefreshDelay(due(-30_000), NOW)).toBe(FEED_RETRY_MS);
  });

  it('retries in a minute before any price has been stored', () => {
    expect(nextRefreshDelay({ lastUpdatedOn: null, nextUpdateAt: null, pollIntervalSeconds: 300 }, NOW)).toBe(FEED_RETRY_MS);
    expect(nextRefreshDelay(null, NOW)).toBe(FEED_RETRY_MS);
  });

  it('never waits more than one interval plus the grace, even if the clock is off', () => {
    expect(nextRefreshDelay(due(3_600_000), NOW)).toBe(300_000 + FEED_GRACE_MS);
  });
});

describe('FeedRefresher', () => {
  afterEach(() => vi.useRealTimers());

  it('refreshes now, then once just after each batch is due', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const refresh = vi.fn();
    const freshness = vi.fn().mockImplementation(() => of(due(Date.parse('2026-10-02T05:02:00Z') - Date.now())));
    const refresher = new FeedRefresher(freshness, refresh);

    refresher.start();
    expect(refresh).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(120_000 + FEED_GRACE_MS - 1);
    expect(refresh).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(1);
    expect(refresh).toHaveBeenCalledTimes(2);
    refresher.stop();
  });

  it('retries in a minute when the timing cannot be read', () => {
    vi.useFakeTimers();
    const refresh = vi.fn();
    const refresher = new FeedRefresher(() => throwError(() => ({ status: 0 })), refresh);

    refresher.start();
    vi.advanceTimersByTime(FEED_RETRY_MS);

    expect(refresh).toHaveBeenCalledTimes(2);
    refresher.stop();
  });

  it('stops for good when stopped', () => {
    vi.useFakeTimers();
    const refresh = vi.fn();
    const refresher = new FeedRefresher(() => of(due(10_000)), refresh);

    refresher.start();
    refresher.stop();
    vi.advanceTimersByTime(3_600_000);

    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('pauses while the tab is hidden and refreshes as soon as it is shown again', () => {
    vi.useFakeTimers();
    const refresh = vi.fn();
    const refresher = new FeedRefresher(() => of(due(10_000)), refresh);
    const hidden = vi.spyOn(document, 'hidden', 'get');

    refresher.start();
    hidden.mockReturnValue(true);
    document.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(600_000);
    expect(refresh).toHaveBeenCalledTimes(1);

    hidden.mockReturnValue(false);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(refresh).toHaveBeenCalledTimes(2);

    refresher.stop();
    hidden.mockRestore();
  });
});
