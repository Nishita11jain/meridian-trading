import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
vi.mock('apexcharts', () => ({ default: class { render() { return Promise.resolve(); } destroy() {} } }));

import { MarketBoard } from './market-board';
import { FEED_GRACE_MS } from '../../core/market/feed-refresher';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';

const quote = (symbol: string, over: any = {}) => ({
  symbol, lastPrice: 3500, bid: null, ask: null, currency: 'INR', change: 10, changePercent: 0.5,
  previousClose: 3490, marketState: 'unknown', stale: false, quoteAsOf: '2026-10-01T09:00:00Z', ...over
});

const instrument = (symbol: string, tradingStatus: 'OPEN' | 'CLOSED', over: any = {}) => ({
  symbol, name: symbol, assetClass: 'EQUITY', currency: 'INR', market: 'NSE', tradingStatus,
  nextOpen: tradingStatus === 'CLOSED' ? '2026-10-05T03:45:00Z' : null, ...over
});

describe('MarketBoard', () => {
  let service: { getQuotes: ReturnType<typeof vi.fn>; getHistory: ReturnType<typeof vi.fn>; getInstruments: ReturnType<typeof vi.fn>; getFreshness: ReturnType<typeof vi.fn> };

  afterEach(() => vi.useRealTimers());

  async function render(quotes: any[] = [quote('TCS.NS'), quote('MSFT', { currency: 'USD', changePercent: -0.02 })],
                        history: any = of([quote('TCS.NS', { lastPrice: 3510 }), quote('TCS.NS', { lastPrice: 3500 })]),
                        instruments: any[] = [instrument('TCS.NS', 'OPEN'), instrument('MSFT', 'OPEN', { market: 'US market' })]) {
    service = {
      getQuotes: vi.fn().mockReturnValue(Array.isArray(quotes) ? of(quotes) : quotes),
      getHistory: vi.fn().mockReturnValue(history),
      getInstruments: vi.fn().mockReturnValue(of(instruments)),
      // The next batch is due 2 minutes from now.
      getFreshness: vi.fn().mockImplementation(() => of({
        lastUpdatedOn: new Date(Date.now() - 180_000).toISOString(),
        nextUpdateAt: new Date(Date.now() + 120_000).toISOString(),
        pollIntervalSeconds: 300
      }))
    };
    await TestBed.configureTestingModule({
      imports: [MarketBoard],
      providers: [{ provide: WatchlistsService, useValue: service }, provideRouter([])]
    }).compileComponents();
    const fixture = TestBed.createComponent(MarketBoard);
    fixture.detectChanges();
    return fixture;
  }
  const el = (f: any, id: string) => f.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  const all = (f: any, id: string) => [...f.nativeElement.querySelectorAll(`[data-testid="${id}"]`)] as HTMLElement[];

  it('lists a row per cached price with its currency, change and time', async () => {
    const fixture = await render();

    const rows = all(fixture, 'market-row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('TCS.NS');
    expect(rows[0].textContent).toContain('₹3,500.00');
    expect(all(fixture, 'market-change')[0].textContent).toContain('+0.50%');
    expect(all(fixture, 'market-change')[1].textContent).toContain('-0.02%');
  });

  it('opens the first symbol\'s trend straight away', async () => {
    const fixture = await render();

    expect(service.getHistory).toHaveBeenCalledWith('TCS.NS');
    expect(el(fixture, 'sparkline')).toBeTruthy();
    expect(el(fixture, 'market-trend')?.textContent).toContain('TCS.NS');
  });

  it('choosing another symbol loads its trend and marks the row chosen', async () => {
    const fixture = await render();

    (all(fixture, 'market-select')[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(service.getHistory).toHaveBeenCalledWith('MSFT');
    expect(all(fixture, 'market-select')[1].getAttribute('aria-pressed')).toBe('true');
    expect(el(fixture, 'market-trend')?.textContent).toContain('MSFT');
  });

  it('colours the trend by direction: up when the latest price is higher', async () => {
    const fixture = await render();

    expect(fixture.componentInstance.trendColor()).toBe('var(--up)');
  });

  it('says when a symbol has no history yet', async () => {
    const fixture = await render(undefined, of([]));

    expect(el(fixture, 'sparkline-empty')?.textContent).toContain('No history yet for TCS.NS');
  });

  it('shows Open or Closed from the API\'s trading status, not the feed\'s market state', async () => {
    const fixture = await render(
      [quote('TCS.NS', { marketState: 'unknown' }), quote('FX:GBPUSD', { marketState: 'open' })],
      undefined,
      [instrument('TCS.NS', 'CLOSED'), instrument('FX:GBPUSD', 'OPEN', { market: 'FX market' })]);

    const states = all(fixture, 'market-state').map((s) => s.textContent!.trim());
    expect(states).toEqual(['Closed', 'Open']);
  });

  it('a closed market offers to queue the trade, saying when it opens; open markets keep Trade', async () => {
    const fixture = await render(undefined, undefined, [instrument('TCS.NS', 'CLOSED'), instrument('MSFT', 'OPEN')]);

    const queue = el(fixture, 'market-trade-closed') as HTMLAnchorElement;
    expect(queue.getAttribute('href')).toBe('/trade?symbol=TCS.NS');
    expect(queue.getAttribute('title')).toContain('TCS.NS trades on the NSE, which is closed now. It opens');
    expect(all(fixture, 'market-trade').map((a) => a.getAttribute('href'))).toEqual(['/trade?symbol=MSFT']);
    // The trend panel (TCS.NS is selected first) says it is closed and offers to queue, not a plain trade.
    expect(el(fixture, 'market-trend-closed')?.textContent).toContain('which is closed now');
    const trendQueue = el(fixture, 'market-trend-trade-closed') as HTMLAnchorElement;
    expect(trendQueue.textContent).toContain('Queue a trade for TCS.NS');
    expect(el(fixture, 'market-trend-trade')).toBeNull();
  });

  it('without the trading status it shows no tag and keeps Trade: the API still refuses a closed market', async () => {
    service = undefined as any;
    const fixture = await render(undefined, undefined, []);

    expect(all(fixture, 'market-state').length).toBe(0);
    expect(all(fixture, 'market-trade').length).toBe(2);
  });

  it('shows a stale tag on a stale price', async () => {
    const fixture = await render([quote('TCS.NS', { stale: true })]);

    expect(el(fixture, 'market-stale')).toBeTruthy();
  });

  it('Trade opens the order ticket with that instrument chosen', async () => {
    const fixture = await render();

    expect(all(fixture, 'market-trade')[1].getAttribute('href')).toBe('/trade?symbol=MSFT');
    expect(el(fixture, 'market-trend-trade')?.getAttribute('href')).toBe('/trade?symbol=TCS.NS');
  });

  it('shows the empty state when there are no prices yet', async () => {
    const fixture = await render([]);

    expect(el(fixture, 'market-empty')).toBeTruthy();
    expect(el(fixture, 'market-table')).toBeNull();
  });

  it('says so when the prices cannot be loaded', async () => {
    const fixture = await render(throwError(() => ({ status: 0 })) as any);

    expect(el(fixture, 'market-error')?.textContent).toContain('could not be loaded');
  });

  it('re-reads the prices just after the next batch is due, not on a fixed timer, and stops when it leaves', async () => {
    vi.useFakeTimers();
    const fixture = await render();
    expect(service.getQuotes).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(120_000); // the batch is due, but give the poller its grace
    expect(service.getQuotes).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(FEED_GRACE_MS);
    expect(service.getQuotes).toHaveBeenCalledTimes(2);

    fixture.destroy();
    vi.advanceTimersByTime(600_000);
    expect(service.getQuotes).toHaveBeenCalledTimes(2);
  });

  it('says how many price updates the line covers, and since when', async () => {
    const fixture = await render();

    expect(el(fixture, 'sparkline-caption')?.textContent).toContain('2 price updates since');
  });

  it('a level price is drawn across the middle and said in words, not left looking empty', async () => {
    const same = (at: string) => quote('TCS.NS', { lastPrice: 2075, quoteAsOf: at });
    const fixture = await render(undefined, of([same('2026-10-01T09:45:00Z'), same('2026-10-01T09:30:00Z')]));

    expect(fixture.componentInstance.sparklinePoints()).toBe('0,14 100,14');
    expect(el(fixture, 'sparkline-flat')?.textContent).toContain('No price change across the last 2 updates');
    expect(fixture.componentInstance.trendColor()).toBe('var(--muted)');
  });

  it('with only one price it says so, and when the trend will appear', async () => {
    const fixture = await render(undefined, of([quote('TCS.NS', { quoteAsOf: '2026-10-01T09:45:00Z' })]));

    expect(el(fixture, 'sparkline')).toBeNull();
    expect(el(fixture, 'sparkline-single')?.textContent).toContain('Only one price so far');
  });

});
