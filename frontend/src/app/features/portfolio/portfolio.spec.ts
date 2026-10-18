import { DisplayCurrencyService } from '../../core/currency/display-currency.service';
import { displayCurrencyStub } from '../../core/currency/testing/display-currency.stub';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
vi.mock('apexcharts', () => ({ default: class { render() { return Promise.resolve(); } destroy() {} updateOptions() {} } }));

// The value chart draws on a canvas, which the test browser lacks; its own spec covers it.

import { Portfolio } from './portfolio';
import { AuthService } from '../../core/auth/auth.service';
import { PortfolioService } from '../../core/portfolio/portfolio.service';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';

const summary = {
  accountId: 4, baseCurrency: 'INR', cashBalance: 1000, marketValue: 36279.68, costBasis: 35267.52,
  unrealisedPnl: 1012.16, unrealisedPnlPercent: 2.87, realisedPnl: 500, totalValue: 37279.68,
  positionCount: 2, partial: false, asOf: null
};
const position = (over: any = {}) => ({
  symbol: 'TCS.NS', quantity: 10, averageCost: 3400, costBasis: 34000, lastPrice: 3500, marketValue: 35000,
  unrealisedPnl: 1000, unrealisedPnlPercent: 2.94, currency: 'INR', priceAsOf: '2026-10-01T09:00:00Z', stale: false, ...over
});

describe('Portfolio', () => {
  let service: any;
  let market: any;

  async function render(over: Partial<Record<string, any>> = {}, quotes: any[] = [{ symbol: 'TCS.NS', change: 24.4 }]) {
    service = {
      summary: vi.fn().mockReturnValue(of(summary)),
      positions: vi.fn().mockReturnValue(of([position(), position({ symbol: 'FX:GBPUSD', quantity: 10, averageCost: 1.32, costBasis: 13.2, lastPrice: 1.326, marketValue: 13.26, unrealisedPnl: 0.06, unrealisedPnlPercent: 0.45, currency: 'USD' })])),
      pnl: vi.fn().mockReturnValue(of([])),
      history: vi.fn().mockReturnValue(of([])),
      ...over
    };
    market = { getQuotes: vi.fn().mockReturnValue(of(quotes)) };
    await TestBed.configureTestingModule({
      imports: [Portfolio],
      providers: [
        { provide: DisplayCurrencyService, useValue: displayCurrencyStub() },
        { provide: AuthService, useValue: { getProfile: () => of({ accountId: 4 }), logout: vi.fn() } },
        { provide: PortfolioService, useValue: service },
        { provide: WatchlistsService, useValue: market },
        provideRouter([])
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(Portfolio);
    fixture.detectChanges();
    return fixture;
  }
  const el = (f: any, id: string) => f.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  const all = (f: any, id: string) => [...f.nativeElement.querySelectorAll(`[data-testid="${id}"]`)] as HTMLElement[];

  it('shows cash and one summary line in rupees: current value, invested, total and today\'s P&L', async () => {
    const fixture = await render();

    expect(el(fixture, 'portfolio-cash')?.textContent).toContain('1,000.00');
    const cards = all(fixture, 'portfolio-currency-card');
    expect(cards.length).toBe(1);
    const inr = cards[0];
    expect(inr.textContent).toContain('INR');
    expect(inr.querySelector('[data-testid="portfolio-current"]')!.textContent).toContain('₹36,279.68');
    expect(inr.querySelector('[data-testid="portfolio-invested"]')!.textContent).toContain('₹35,267.52');
    expect(inr.querySelector('[data-testid="portfolio-total-pnl"]')!.textContent).toContain('+₹1,012.16');
    expect(inr.querySelector('[data-testid="portfolio-total-pnl"]')!.textContent).toContain('(+2.87%)');
    expect(inr.querySelector('[data-testid="portfolio-realised"]')!.textContent).toContain('Realised +₹500.00');
    // Today: 24.40 per share x 10 shares, from the rupee holding only.
    expect(inr.querySelector('[data-testid="portfolio-today"]')!.textContent).toContain('+₹244.00');
  });

  it('shows one allocation donut per currency, with the larger currency first and a tab for each', async () => {
    const fixture = await render();

    expect(el(fixture, 'allocation-chart')).toBeTruthy();
    expect(all(fixture, 'allocation-currency').map((b) => b.textContent!.trim())).toEqual(['INR', 'USD']);
    expect(all(fixture, 'allocation-legend-item').map((i) => i.textContent!.replace(/\s+/g, ' ').trim())).toEqual(['TCS.NS 100.0% · ₹35,000.00']);

    all(fixture, 'allocation-currency')[1].click();
    fixture.detectChanges();
    expect(all(fixture, 'allocation-legend-item')[0].textContent).toContain('FX:GBPUSD');
  });

  it('every holding gets its own slice and legend line: none is grouped into "Other"', async () => {
    const many = ['A', 'B', 'C', 'D', 'E', 'F', 'G'].map((s, i) => position({ symbol: s, marketValue: (7 - i) * 100 }));
    const fixture = await render({ positions: vi.fn().mockReturnValue(of(many)) });

    const legend = all(fixture, 'allocation-legend-item').map((i) => i.textContent!.trim());
    expect(legend.length).toBe(7);
    expect(legend[0]).toContain('A');
    expect(legend[6]).toContain('G');
    expect(legend.some((l) => l.includes('Other'))).toBe(false);
  });

  it('the slices add up to the whole ring, and the donut is described for a screen reader', async () => {
    const two = [position({ symbol: 'A', marketValue: 300 }), position({ symbol: 'B', marketValue: 100 })];
    const fixture = await render({ positions: vi.fn().mockReturnValue(of(two)) });

    const slices = fixture.componentInstance.slices();
    expect(slices.map((s) => Math.round(s.share))).toEqual([75, 25]);
    expect(el(fixture, 'allocation-donut')?.getAttribute('aria-label')).toBe('Allocation in INR: A 75.0%, B 25.0%');
  });

  it('pointing at a slice shows its share in the centre', async () => {
    const two = [position({ symbol: 'A', marketValue: 300 }), position({ symbol: 'B', marketValue: 100 })];
    const fixture = await render({ positions: vi.fn().mockReturnValue(of(two)) });

    all(fixture, 'allocation-legend-item')[1].dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();

    expect(el(fixture, 'allocation-centre')?.textContent).toContain('25%');
  });

  it('the holdings table shows each holding\'s P&L and its share of its currency', async () => {
    const fixture = await render();
    const [tcs, fx] = all(fixture, 'position-row');

    expect(tcs.querySelector('[data-testid="position-pnl"]')!.textContent).toContain('+₹1,000.00');
    expect(tcs.querySelector('[data-testid="position-pnl"]')!.textContent).toContain('+2.94%');
    expect(tcs.querySelector('[data-testid="position-allocation"]')!.textContent).toContain('100.0%');
    expect(fx.querySelector('[data-testid="position-allocation"]')!.textContent).toContain('100.0%');
  });

  it('the old bar charts are gone', async () => {
    const fixture = await render();

    expect(el(fixture, 'pnl-chart')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Each line versus its basis');
  });

  it('marks a stale position and flags the summary partial', async () => {
    const fixture = await render({
      summary: vi.fn().mockReturnValue(of({ ...summary, partial: true })),
      positions: vi.fn().mockReturnValue(of([position({ stale: true, lastPrice: null, marketValue: null, unrealisedPnl: null, unrealisedPnlPercent: null })]))
    });

    expect(el(fixture, 'position-stale')).toBeTruthy();
    expect(el(fixture, 'portfolio-partial')).toBeTruthy();
    expect(el(fixture, 'position-pnl')?.textContent).toContain('No price yet');
  });

  it('shows the empty state when nothing is held', async () => {
    const fixture = await render({ positions: vi.fn().mockReturnValue(of([])) });

    expect(el(fixture, 'portfolio-empty')).toBeTruthy();
    expect(el(fixture, 'allocation-chart')).toBeNull();
  });

  it('shows a clear message when pricing is unavailable (503)', async () => {
    const fixture = await render({ summary: vi.fn().mockReturnValue(throwError(() => ({ status: 503 }))) });

    expect(el(fixture, 'portfolio-error')?.textContent).toContain('temporarily unavailable');
  });

  it('works without the session changes: today shows "—" and nothing else is affected', async () => {
    const fixture = await render({}, []);
    market.getQuotes.mockReturnValue(throwError(() => ({ status: 0 })));

    expect(all(fixture, 'portfolio-today').every((t) => t.textContent!.includes('—'))).toBe(true);
    expect(el(fixture, 'portfolio-current')?.textContent).toContain('₹36,279.68');
  });

  it('shows the portfolio value chart for the account', async () => {
    const fixture = await render();

    expect(el(fixture, 'portfolio-chart')).toBeTruthy();
    expect(service.history).toHaveBeenCalledWith(4, 'ALL');
  });
});
