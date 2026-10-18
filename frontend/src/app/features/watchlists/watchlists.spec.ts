import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Watchlists } from './watchlists';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';
import { AuthService } from '../../core/auth/auth.service';
import { OrderService } from '../../core/orders/order.service';
import { FEED_GRACE_MS } from '../../core/market/feed-refresher';

const quote = (symbol: string, over: any = {}) => ({
  symbol, lastPrice: 3500, bid: null, ask: null, currency: 'INR', change: 10, changePercent: 0.5,
  previousClose: 3490, marketState: 'unknown', stale: false, quoteAsOf: '2026-10-01T09:00:00Z', ...over
});
const instrument = (symbol: string, tradingStatus = 'OPEN', over: any = {}) => ({
  symbol, name: symbol + ' Limited', assetClass: 'EQUITY', currency: 'INR', market: 'NSE', tradingStatus,
  nextOpen: tradingStatus === 'CLOSED' ? '2026-10-05T03:45:00Z' : null, ...over
});
const list = (id: number, name: string, items: any[]) => ({ id, name, createdOn: null, items });

describe('Watchlists', () => {
  let service: any;
  let orders: any;

  afterEach(() => vi.useRealTimers());
  beforeEach(() => { try { localStorage.clear(); } catch { /* none */ } });

  async function render(over: Partial<Record<string, any>> = {}) {
    service = {
      getPresets: vi.fn().mockReturnValue(of([
        { key: 'NSE', name: 'NSE stocks', symbols: ['TCS.NS', 'ICICIBANK.NS'] },
        { key: 'US', name: 'US stocks', symbols: ['MSFT'] }
      ])),
      createFromPreset: vi.fn().mockReturnValue(of(list(7, 'NSE stocks', [{ symbol: 'TCS.NS', quote: null, group: null }]))),
      setInstrumentFlag: vi.fn().mockImplementation((id: number, symbol: string, flag: string | null) =>
        of(list(id, 'Tech', [
          { symbol: 'TCS.NS', quote: quote('TCS.NS', { lastPrice: 2075, changePercent: 1.19 }), flag: symbol === 'TCS.NS' ? flag : null },
          { symbol: 'MSFT', quote: quote('MSFT', { lastPrice: 512.8, changePercent: -0.02, currency: 'USD' }), flag: symbol === 'MSFT' ? flag : null },
          { symbol: 'ICICIBANK.NS', quote: quote('ICICIBANK.NS', { lastPrice: 1310.6, changePercent: -0.84, stale: true }), flag: symbol === 'ICICIBANK.NS' ? flag : null }
        ]))),
      getInstruments: vi.fn().mockReturnValue(of([instrument('TCS.NS'), instrument('MSFT', 'CLOSED', { market: 'US market', currency: 'USD' }), instrument('ICICIBANK.NS')])),
      getQuotes: vi.fn().mockReturnValue(of([])),
      getFreshness: vi.fn().mockImplementation(() => of({
        lastUpdatedOn: new Date(Date.now() - 240_000).toISOString(),
        nextUpdateAt: new Date(Date.now() + 60_000).toISOString(),
        pollIntervalSeconds: 300
      })),
      getWatchlists: vi.fn().mockReturnValue(of([
        list(1, 'Tech', [
          { symbol: 'TCS.NS', quote: quote('TCS.NS', { lastPrice: 2075, changePercent: 1.19 }) },
          { symbol: 'MSFT', quote: quote('MSFT', { lastPrice: 512.8, changePercent: -0.02, currency: 'USD' }) },
          { symbol: 'ICICIBANK.NS', quote: quote('ICICIBANK.NS', { lastPrice: 1310.6, changePercent: -0.84, stale: true }) }
        ]),
        list(2, 'Banks', [])
      ])),
      getAlerts: vi.fn().mockReturnValue(of([{ id: 9, symbol: 'TCS.NS', direction: 'ABOVE', targetPrice: 3600, status: 'ACTIVE', createdOn: null, triggeredOn: null, triggeredPrice: null }])),
      createWatchlist: vi.fn().mockReturnValue(of(list(3, 'New', []))),
      renameWatchlist: vi.fn().mockReturnValue(of(list(1, 'Tech giants', []))),
      deleteWatchlist: vi.fn().mockReturnValue(of(null)),
      reorderInstruments: vi.fn().mockImplementation((id: number, symbols: string[]) =>
        of(list(id, 'Tech', symbols.map((s) => ({ symbol: s, quote: quote(s) }))))),
      addInstrument: vi.fn().mockReturnValue(of(list(1, 'Tech', []))),
      removeInstrument: vi.fn().mockReturnValue(of(list(1, 'Tech', []))),
      createAlert: vi.fn().mockReturnValue(of({ id: 10, symbol: 'TCS.NS', direction: 'ABOVE', targetPrice: 3700, status: 'ACTIVE' })),
      cancelAlert: vi.fn().mockReturnValue(of({ id: 9, symbol: 'TCS.NS', direction: 'ABOVE', targetPrice: 3600, status: 'CANCELLED' })),
      ...over
    };
    orders = { getPositions: vi.fn().mockReturnValue(of([{ accountId: 4, symbol: 'TCS.NS', quantity: 150, averageCost: 3443.33 }])) };
    await TestBed.configureTestingModule({
      imports: [Watchlists],
      providers: [
        { provide: WatchlistsService, useValue: service },
        { provide: OrderService, useValue: orders },
        { provide: AuthService, useValue: { getProfile: () => of({ accountId: 4 }), logout: vi.fn() } },
        provideRouter([])
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(Watchlists);
    fixture.detectChanges();
    return fixture;
  }
  const el = (f: any, id: string) => f.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  const all = (f: any, id: string) => [...f.nativeElement.querySelectorAll(`[data-testid="${id}"]`)] as HTMLElement[];
  const symbols = (f: any) => all(f, 'watchlist-item').map((r) => r.querySelector('td:nth-child(2) .font-extrabold')!.textContent!.trim());

  it('shows a tab per list and the first list as a table', async () => {
    const fixture = await render();

    expect(all(fixture, 'watchlist-tab').map((t) => t.textContent!.trim())).toEqual(['Tech 3', 'Banks 0']);
    expect(el(fixture, 'watchlist-title')?.textContent).toContain('Tech');
    expect(symbols(fixture)).toEqual(['TCS.NS', 'MSFT', 'ICICIBANK.NS']);
    expect(service.getQuotes).not.toHaveBeenCalled(); // the market board is on the dashboard
  });

  it('each row shows price, change in colour, market state, stale, holding and its alert', async () => {
    const fixture = await render();
    const [tcs, msft, icici] = all(fixture, 'watchlist-item');

    expect(tcs.querySelector('[data-testid="watchlist-price"]')!.textContent).toContain('2,075.00');
    expect((tcs.querySelector('[data-testid="watchlist-change"]') as HTMLElement).style.color).toBe('var(--up)');
    expect((msft.querySelector('[data-testid="watchlist-change"]') as HTMLElement).style.color).toBe('var(--down)');
    expect(tcs.querySelector('[data-testid="watchlist-state"]')!.textContent).toContain('Open');
    expect(msft.querySelector('[data-testid="watchlist-state"]')!.textContent).toContain('Closed');
    expect(icici.querySelector('[data-testid="watchlist-stale"]')).toBeTruthy();
    expect(tcs.querySelector('[data-testid="watchlist-held"]')!.textContent).toContain('Held 150');
    expect(tcs.querySelector('[data-testid="watchlist-alert"]')!.textContent).toContain('≥ 3,600.00');
  });

  it('Buy and Sell open the ticket with the instrument and side chosen; Sell only for a holding', async () => {
    const fixture = await render();
    const [tcs, , icici] = all(fixture, 'watchlist-item');

    expect(tcs.querySelector('[data-testid="watchlist-buy"]')!.getAttribute('href')).toBe('/trade?symbol=TCS.NS&side=BUY');
    expect(tcs.querySelector('[data-testid="watchlist-sell"]')!.getAttribute('href')).toBe('/trade?symbol=TCS.NS&side=SELL');
    expect(icici.querySelector('[data-testid="watchlist-sell"]')).toBeNull();
  });

  it('a closed market offers no Buy or Sell, and says when it opens', async () => {
    const fixture = await render();
    const msft = all(fixture, 'watchlist-item')[1];

    expect(msft.querySelector('[data-testid="watchlist-buy"]')).toBeNull();
    const closed = msft.querySelector('[data-testid="watchlist-closed"]') as HTMLButtonElement;
    expect(closed.disabled).toBe(true);
    expect(closed.getAttribute('title')).toContain('MSFT trades on the US market, which is closed now');
  });

  it('sorts by a column header, biggest change first, then back to your own order', async () => {
    const fixture = await render();

    el(fixture, 'watchlist-sort-change')!.click();
    fixture.detectChanges();
    expect(symbols(fixture)).toEqual(['TCS.NS', 'MSFT', 'ICICIBANK.NS']);
    el(fixture, 'watchlist-sort-change')!.click();
    fixture.detectChanges();
    expect(symbols(fixture)).toEqual(['ICICIBANK.NS', 'MSFT', 'TCS.NS']);

    el(fixture, 'watchlist-sort-symbol')!.click();
    fixture.detectChanges();
    expect(symbols(fixture)).toEqual(['ICICIBANK.NS', 'MSFT', 'TCS.NS']);
    expect((all(fixture, 'watchlist-drag')[0] as HTMLButtonElement).disabled).toBe(true);

    el(fixture, 'watchlist-sort-clear')!.click();
    fixture.detectChanges();
    expect(symbols(fixture)).toEqual(['TCS.NS', 'MSFT', 'ICICIBANK.NS']);
  });

  it('Alt+Down on the handle moves a row and saves the new order', async () => {
    const fixture = await render();

    all(fixture, 'watchlist-drag')[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true, cancelable: true }));
    fixture.detectChanges();

    expect(symbols(fixture)).toEqual(['MSFT', 'TCS.NS', 'ICICIBANK.NS']);
    expect(service.reorderInstruments).toHaveBeenCalledWith(1, ['MSFT', 'TCS.NS', 'ICICIBANK.NS']);
  });

  it('dropping a dragged row onto another moves it there', async () => {
    const fixture = await render();
    const component = fixture.componentInstance;

    component.dragging.set('ICICIBANK.NS');
    component.onDrop(new Event('drop') as DragEvent, 'TCS.NS');
    fixture.detectChanges();

    expect(service.reorderInstruments).toHaveBeenCalledWith(1, ['ICICIBANK.NS', 'TCS.NS', 'MSFT']);
  });

  it('a list can be renamed', async () => {
    const fixture = await render();

    el(fixture, 'watchlist-rename')!.click();
    fixture.detectChanges();
    const input = el(fixture, 'watchlist-rename-input') as HTMLInputElement;
    input.value = 'Tech giants';
    el(fixture, 'watchlist-rename-save')!.click();
    fixture.detectChanges();

    expect(service.renameWatchlist).toHaveBeenCalledWith(1, 'Tech giants');
    expect(all(fixture, 'watchlist-tab')[0].textContent).toContain('Tech giants');
  });

  it('deleting asks first, then removes the list', async () => {
    const fixture = await render();

    el(fixture, 'watchlist-delete')!.click();
    fixture.detectChanges();
    expect(service.deleteWatchlist).not.toHaveBeenCalled();
    el(fixture, 'watchlist-delete-confirm')!.click();
    fixture.detectChanges();

    expect(service.deleteWatchlist).toHaveBeenCalledWith(1);
    expect(all(fixture, 'watchlist-tab').map((t) => t.textContent!.trim())).toEqual(['Banks 0']);
  });

  it('adds an instrument by searching, leaving out ones already on the list', async () => {
    const fixture = await render();
    el(fixture, 'watchlist-tab')!.parentElement!.querySelectorAll('button')[1].dispatchEvent(new Event('click'));
    fixture.detectChanges(); // the empty "Banks" list
    expect(el(fixture, 'watchlist-items-empty')).toBeTruthy();

    const input = el(fixture, 'instrument-search') as HTMLInputElement;
    input.value = 'tcs';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));

    expect(service.addInstrument).toHaveBeenCalledWith(2, { symbol: 'TCS.NS' });
  });

  it('Alert on a row fills the alert form with that instrument', async () => {
    const fixture = await render();

    all(fixture, 'watchlist-set-alert')[2].click();

    expect(fixture.componentInstance.alertForm.controls.symbol.value).toBe('ICICIBANK.NS');
  });

  it('creates a watchlist and opens it', async () => {
    const fixture = await render();

    fixture.componentInstance.watchlistForm.setValue({ name: 'New' });
    fixture.detectChanges();
    el(fixture, 'watchlist-create')!.click();
    fixture.detectChanges();

    expect(service.createWatchlist).toHaveBeenCalledWith({ name: 'New' });
    expect(el(fixture, 'watchlist-title')?.textContent).toContain('New');
  });

  it('creates an alert from the form, and refuses an invalid target', async () => {
    const fixture = await render();

    fixture.componentInstance.alertForm.setValue({ symbol: 'TCS.NS', direction: 'ABOVE', targetPrice: 'abc' });
    el(fixture, 'alert-create')!.click();
    expect(service.createAlert).not.toHaveBeenCalled();

    fixture.componentInstance.alertForm.setValue({ symbol: 'TCS.NS', direction: 'ABOVE', targetPrice: '3700' });
    fixture.detectChanges();
    el(fixture, 'alert-create')!.click();
    expect(service.createAlert).toHaveBeenCalledWith({ symbol: 'TCS.NS', direction: 'ABOVE', targetPrice: 3700 });
  });

  it('cancels an active alert', async () => {
    const fixture = await render();

    el(fixture, 'alert-cancel')!.click();

    expect(service.cancelAlert).toHaveBeenCalledWith(9);
  });

  it('refreshes just after the next batch is due, and highlights a price that changed', async () => {
    vi.useFakeTimers();
    const fixture = await render();
    expect(service.getWatchlists).toHaveBeenCalledTimes(1);
    service.getWatchlists.mockReturnValue(of([
      list(1, 'Tech', [{ symbol: 'TCS.NS', quote: quote('TCS.NS', { lastPrice: 2080, changePercent: 1.4 }) }])
    ]));

    vi.advanceTimersByTime(60_000 + FEED_GRACE_MS);
    fixture.detectChanges();

    expect(service.getWatchlists).toHaveBeenCalledTimes(2);
    expect(el(fixture, 'watchlist-item')!.classList.contains('lt-flash')).toBe(true);
    expect(el(fixture, 'watchlist-price')!.textContent).toContain('2,080.00');
  });

  it('shows a readable error when a load fails', async () => {
    const fixture = await render({ getWatchlists: vi.fn().mockReturnValue(throwError(() => ({ status: 401, error: { errorCode: 'AUTH-401' } }))) });

    expect(el(fixture, 'watchlists-error')).toBeTruthy();
  });

  // ---------------------------------------------------------------- groups, columns, ready-made lists

  const flagged = () => of([list(1, 'Tech', [
    { symbol: 'TCS.NS', quote: quote('TCS.NS'), flag: 'GREEN' },
    { symbol: 'MSFT', quote: quote('MSFT', { currency: 'USD' }), flag: null },
    { symbol: 'ICICIBANK.NS', quote: quote('ICICIBANK.NS'), flag: 'RED' }
  ])]);

  it('has no group headers or group column: one plain table', async () => {
    const fixture = await render();

    expect(fixture.nativeElement.textContent).not.toContain('Not grouped');
    expect(all(fixture, 'watchlist-group').length).toBe(0);
    expect(all(fixture, 'watchlist-group-select').length).toBe(0);
  });

  it('a flag shows as a coloured dot, and its button says so', async () => {
    const fixture = await render({ getWatchlists: vi.fn().mockReturnValue(flagged()) });
    const [tcs, msft] = all(fixture, 'watchlist-flag');

    expect(tcs.getAttribute('aria-label')).toBe('TCS.NS is flagged green. Change flag');
    expect((tcs.querySelector('span') as HTMLElement).style.background).toBe('var(--up)');
    expect(msft.getAttribute('aria-label')).toBe('Flag MSFT');
  });

  it('the dot opens a menu to flag a row red, amber or green, or remove the flag', async () => {
    const fixture = await render();

    all(fixture, 'watchlist-flag')[1].click(); // MSFT
    fixture.detectChanges();
    expect(el(fixture, 'watchlist-flag-menu')).toBeTruthy();
    el(fixture, 'watchlist-flag-AMBER')!.click();
    fixture.detectChanges();

    expect(service.setInstrumentFlag).toHaveBeenCalledWith(1, 'MSFT', 'AMBER');
    expect(el(fixture, 'watchlist-flag-menu')).toBeNull();
    expect(all(fixture, 'watchlist-flag')[1].getAttribute('aria-label')).toBe('MSFT is flagged amber. Change flag');

    all(fixture, 'watchlist-flag')[1].click();
    fixture.detectChanges();
    el(fixture, 'watchlist-flag-none')!.click();
    expect(service.setInstrumentFlag).toHaveBeenCalledWith(1, 'MSFT', null);
  });

  it('the flag menu closes on Escape and on a click elsewhere', async () => {
    const fixture = await render();

    all(fixture, 'watchlist-flag')[0].click();
    fixture.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(el(fixture, 'watchlist-flag-menu')).toBeNull();

    all(fixture, 'watchlist-flag')[0].click();
    fixture.detectChanges();
    document.body.click();
    fixture.detectChanges();
    expect(el(fixture, 'watchlist-flag-menu')).toBeNull();
  });

  it('"Flagged only" shows just the flagged rows, and reordering waits for the whole list', async () => {
    const fixture = await render({ getWatchlists: vi.fn().mockReturnValue(flagged()) });

    el(fixture, 'watchlist-flagged-only')!.click();
    fixture.detectChanges();

    expect(symbols(fixture)).toEqual(['TCS.NS', 'ICICIBANK.NS']);
    expect(el(fixture, 'watchlist-flagged-only')!.getAttribute('aria-pressed')).toBe('true');
    expect((all(fixture, 'watchlist-drag')[0] as HTMLButtonElement).disabled).toBe(true);
  });

  it('"Flagged only" on a list with no flags says how to flag', async () => {
    const fixture = await render();

    el(fixture, 'watchlist-flagged-only')!.click();
    fixture.detectChanges();

    expect(el(fixture, 'watchlist-flagged-empty')?.textContent).toContain('Click the dot beside a symbol');
  });

  it('a price that moved since the last visit says by how much', async () => {
    localStorage.setItem('lt.watchlist.seen', JSON.stringify({ 'TCS.NS': 2000, 'MSFT': 512.8 }));
    const fixture = await render();
    const [tcs, msft] = all(fixture, 'watchlist-item');

    expect(tcs.querySelector('[data-testid="watchlist-since"]')!.textContent).toContain('▲ +3.75% since last visit');
    expect(msft.querySelector('[data-testid="watchlist-since"]')).toBeNull(); // unchanged
    expect(el(fixture, 'watchlist-moved')?.textContent).toContain('1 price has moved since your last visit');
  });

  it('a first visit shows no notes, and remembers the prices for next time', async () => {
    const fixture = await render();

    expect(all(fixture, 'watchlist-since').length).toBe(0);
    expect(JSON.parse(localStorage.getItem('lt.watchlist.seen')!)['TCS.NS']).toBe(2075);
  });

  it('Mark as seen clears the notes', async () => {
    localStorage.setItem('lt.watchlist.seen', JSON.stringify({ 'TCS.NS': 2000 }));
    const fixture = await render();

    el(fixture, 'watchlist-mark-seen')!.click();
    fixture.detectChanges();

    expect(all(fixture, 'watchlist-since').length).toBe(0);
    expect(el(fixture, 'watchlist-moved')).toBeNull();
  });

  it('columns can be chosen, and the choice is remembered in this browser', async () => {
    const fixture = await render();
    expect(el(fixture, 'watchlist-bid')).toBeNull();

    el(fixture, 'watchlist-columns')!.click();
    fixture.detectChanges();
    el(fixture, 'watchlist-column-bid')!.click();
    el(fixture, 'watchlist-column-alert')!.click();
    fixture.detectChanges();

    expect(el(fixture, 'watchlist-bid')).toBeTruthy();
    expect(el(fixture, 'watchlist-alert')).toBeNull();
    expect(JSON.parse(localStorage.getItem('lt.watchlist.columns')!)).toEqual(['changePercent', 'bid', 'asOf']);
  });

  it('starts with the saved column choice', async () => {
    localStorage.setItem('lt.watchlist.columns', JSON.stringify(['previousClose', 'nonsense']));
    const fixture = await render();

    expect(el(fixture, 'watchlist-prev-close')).toBeTruthy();
    expect(el(fixture, 'watchlist-change')).toBeNull();
  });

  it('starts a new list from a ready-made one and opens it', async () => {
    const fixture = await render();
    const select = el(fixture, 'watchlist-preset') as HTMLSelectElement;
    expect([...select.options].map((o) => o.textContent!.trim())).toContain('NSE stocks (2)');

    select.value = 'NSE';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(service.createFromPreset).toHaveBeenCalledWith('NSE');
    expect(el(fixture, 'watchlist-title')?.textContent).toContain('NSE stocks');
  });
});
