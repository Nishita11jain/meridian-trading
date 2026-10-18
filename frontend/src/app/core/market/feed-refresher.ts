import { Observable } from 'rxjs';

import { MarketFreshnessResponse } from '../../clients/watchlists-client/model/models';

/** Read this long after a batch is due, to give the poller time to store it. */
export const FEED_GRACE_MS = 15_000;
/** When the next batch is overdue (the poller is late or down), try again after this long. */
export const FEED_RETRY_MS = 60_000;

/**
 * How long to wait before the next read. Prices arrive in batches (every 5 minutes by default), so a
 * page reads once just after each batch is due rather than on a fixed timer: never more than a few
 * seconds behind, and one read per batch instead of five.
 */
export function nextRefreshDelay(freshness: MarketFreshnessResponse | null, nowMs: number): number {
  const next = freshness?.nextUpdateAt ? Date.parse(freshness.nextUpdateAt) : NaN;
  if (Number.isNaN(next)) {
    return FEED_RETRY_MS;
  }
  const untilDue = next - nowMs;
  if (untilDue <= 0) {
    return FEED_RETRY_MS;
  }
  // Never wait longer than one interval plus the grace, whatever the clock says.
  const cap = (freshness?.pollIntervalSeconds ?? 300) * 1000 + FEED_GRACE_MS;
  return Math.min(untilDue + FEED_GRACE_MS, cap);
}

/**
 * Refreshes a page in step with the market feed: calls {@code refresh} now, then once just after each
 * new batch of prices is due. Pauses while the browser tab is hidden and refreshes as soon as it is
 * shown again. Reads only our own API (the freshness route and whatever {@code refresh} reads).
 */
export class FeedRefresher {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private stopped = false;
  private readonly onVisibility = () => {
    if (document.hidden) {
      this.clear();
    } else if (!this.stopped) {
      this.tick();
    }
  };

  constructor(
    private readonly freshness: () => Observable<MarketFreshnessResponse>,
    private readonly refresh: () => void
  ) {}

  start(): void {
    this.stopped = false;
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', this.onVisibility);
    }
    this.tick();
  }

  stop(): void {
    this.stopped = true;
    this.clear();
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.onVisibility);
    }
  }

  private tick(): void {
    this.clear();
    this.refresh();
    this.freshness().subscribe({
      next: (f) => this.schedule(nextRefreshDelay(f, Date.now())),
      error: () => this.schedule(FEED_RETRY_MS)
    });
  }

  private schedule(delay: number): void {
    if (this.stopped) {
      return;
    }
    this.clear();
    this.timer = setTimeout(() => this.tick(), delay);
  }

  private clear(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
