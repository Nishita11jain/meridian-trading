import { DisplayCurrencyService } from '../../core/currency/display-currency.service';
import { displayCurrencyStub } from '../../core/currency/testing/display-currency.stub';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { Dashboard } from './dashboard';
import { AuthService } from '../../core/auth/auth.service';
import { OrderService } from '../../core/orders/order.service';
import { PortfolioService } from '../../core/portfolio/portfolio.service';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';

const account = { id: 42, accountId: 'ETP-4105', holderName: 'Aditya Rao', cashBalance: 218500, status: 'ACTIVE', version: 1, lastUpdated: '2026-01-01T00:00:00Z' };

const priced = (symbol: string, marketValue: number | null, over: any = {}) => ({
  symbol, quantity: 10, averageCost: 100, costBasis: 1000, lastPrice: marketValue == null ? null : marketValue / 10,
  marketValue, unrealisedPnl: marketValue == null ? null : marketValue - 1000,
  unrealisedPnlPercent: marketValue == null ? null : ((marketValue - 1000) / 1000) * 100,
  currency: 'INR', priceAsOf: null, stale: false, ...over
});

const summary = {
  accountId: 42, baseCurrency: 'INR', cashBalance: 218500, marketValue: 3700, costBasis: 3600,
  unrealisedPnl: 100, unrealisedPnlPercent: 2.78, realisedPnl: 0, totalValue: 222200,
  positionCount: 2, partial: false, asOf: null
};

describe('Dashboard', () => {
  let orders: any;
  let portfolio: any;

  beforeEach(async () => {
    orders = {
      getAccount: vi.fn().mockReturnValue(of(account)),
      getBalance: vi.fn().mockReturnValue(of({ accountId: 42, cashBalance: 218500, currency: 'INR', asOf: '2026-01-01T00:00:00Z' }))
    };
    portfolio = {
      summary: vi.fn().mockReturnValue(of(summary)),
      positions: vi.fn().mockReturnValue(of([]))
    };

    await TestBed.configureTestingModule({
      imports: [Dashboard],
      providers: [
        { provide: DisplayCurrencyService, useValue: displayCurrencyStub() },
        { provide: AuthService, useValue: { logout: vi.fn(), getProfile: vi.fn().mockReturnValue(of({ accountId: 42 })) } },
        { provide: OrderService, useValue: orders },
        { provide: PortfolioService, useValue: portfolio },
        { provide: WatchlistsService, useValue: {
          getQuotes: vi.fn().mockReturnValue(of([])), getHistory: vi.fn().mockReturnValue(of([])), getInstruments: vi.fn().mockReturnValue(of([])),
          getFreshness: vi.fn().mockReturnValue(of({ lastUpdatedOn: null, nextUpdateAt: null, pollIntervalSeconds: 300 }))
        } },
        provideRouter([])
      ]
    }).compileComponents();
  });

  function render() {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    return fixture;
  }
  /** The cash balance starts hidden; press the eye to show it. */
  const revealCash = (f: any) => { el(f, 'dashboard-toggle-cash')!.click(); f.detectChanges(); return f; };
  const el = (f: any, id: string) => f.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  const all = (f: any, id: string) => [...f.nativeElement.querySelectorAll(`[data-testid="${id}"]`)] as HTMLElement[];

  it('renders account holder, status and cash balance', () => {
    const fixture = revealCash(render());

    expect(el(fixture, 'dashboard-holder')?.textContent).toContain('Aditya Rao');
    expect(el(fixture, 'dashboard-status')?.textContent).toContain('ACTIVE');
    expect(el(fixture, 'dashboard-cash')?.textContent).toContain('218,500.00');
  });

  it('hides the cash balance until the eye is pressed, and hides it again', () => {
    const fixture = render();
    expect(el(fixture, 'dashboard-cash')?.textContent).not.toContain('218,500.00');
    expect(el(fixture, 'dashboard-toggle-cash')?.getAttribute('aria-pressed')).toBe('false');
    revealCash(fixture);
    expect(el(fixture, 'dashboard-cash')?.textContent).toContain('218,500.00');
    revealCash(fixture);
    expect(el(fixture, 'dashboard-cash')?.textContent).not.toContain('218,500.00');
  });

  it('greets the account holder by name', () => {
    const fixture = render();

    const greeting = fixture.nativeElement.querySelector('.dash-hero__hello') as HTMLElement;
    expect(greeting.textContent!.replace(/\s+/g, ' ').trim()).toBe('Hi, Aditya Rao!');
  });

  it('labels the cash balance with its currency, and still shows it when the currency cannot be read', () => {
    expect(el(revealCash(render()), 'dashboard-cash')?.textContent).toContain('₹218,500.00');

    // Cash is always in rupees, so it keeps its symbol even when the balance's own currency cannot be read.
    orders.getBalance.mockReturnValue(throwError(() => ({ status: 500 })));
    const without = revealCash(render());
    expect(el(without, 'dashboard-cash')?.textContent).toContain('218,500.00');
  });

  it('shows market value and unrealised P&L in rupees, coloured by sign', () => {
    const fixture = render();

    const values = all(fixture, 'dashboard-market-value');
    expect(values.map((v) => v.textContent)).toEqual([expect.stringContaining('₹')]);
    expect(values[0].textContent).toContain('3,700.00');

    const pnl = all(fixture, 'dashboard-pnl');
    expect(pnl.length).toBe(1);
    expect(pnl[0].textContent).toContain('+₹100.00');
    expect(pnl[0].textContent).toContain('(+2.78%)');
    expect(pnl[0].style.color).toBe('var(--up)');
  });

  it('says when the valuation leaves out holdings that have no price yet', () => {
    portfolio.summary.mockReturnValue(of({ ...summary, partial: true }));
    const fixture = render();

    expect(el(fixture, 'dashboard-partial')).toBeTruthy();
  });

  it('includes the market board', () => {
    const fixture = render();

    expect(fixture.nativeElement.querySelector('app-market-board')).toBeTruthy();
  });

  it('lists the five largest holdings by market value, with a link to the full table', () => {
    portfolio.positions.mockReturnValue(of([
      priced('A', 1100), priced('B', 5000), priced('C', 900), priced('D', 3000),
      priced('E', 2000), priced('F', 4000), priced('ZERO', 100, { quantity: 0 })
    ]));
    const fixture = render();

    const rows = all(fixture, 'dashboard-holding-row');
    expect(rows.map((r) => r.querySelector('td .font-extrabold')!.textContent!.trim())).toEqual(['B', 'F', 'D', 'E', 'A']);
    expect(el(fixture, 'dashboard-holdings-more')?.textContent).toContain('Showing 5 of 6 holdings');
    expect(el(fixture, 'dashboard-view-portfolio')?.getAttribute('href')).toBe('/portfolio');
  });

  it('shows each holding\'s unrealised P&L in colour, and "No price yet" without a price', () => {
    portfolio.positions.mockReturnValue(of([priced('UP', 1500), priced('NOPRICE', null)]));
    const fixture = render();

    const pnl = all(fixture, 'dashboard-holding-pnl');
    expect(pnl[0].textContent).toContain('+₹500.00');
    expect(pnl[0].textContent).toContain('(+50.00%)');
    expect(pnl[0].style.color).toBe('var(--up)');
    expect(pnl[1].textContent).toContain('No price yet');
  });

  it('shows an empty state and a link to place the first order when there are no holdings', () => {
    const fixture = render();

    expect(el(fixture, 'dashboard-holdings-empty')).toBeTruthy();
    expect(el(fixture, 'dashboard-holdings-table')).toBeNull();
  });

  it('a valuation failure does not hide the account', () => {
    portfolio.summary.mockReturnValue(throwError(() => ({ status: 503 })));
    portfolio.positions.mockReturnValue(throwError(() => ({ status: 0 })));
    const fixture = render();

    expect(el(fixture, 'dashboard-holder')?.textContent).toContain('Aditya Rao');
    expect(el(fixture, 'dashboard-holdings-error')?.textContent).toContain('could not reach the service');
    expect(el(fixture, 'dashboard-error')).toBeNull();
  });

  it('shows a readable message when the account call fails', () => {
    orders.getAccount.mockReturnValue(throwError(() => ({ status: 0 })));
    const fixture = render();

    expect(el(fixture, 'dashboard-error')?.textContent).toContain('could not reach the service');
  });

  it('the summary tiles are plain cards: Open portfolio is the one way to the portfolio', () => {
    const fixture = render();

    for (const id of ['dashboard-tile-cash', 'dashboard-tile-value', 'dashboard-tile-pnl']) {
      expect(el(fixture, id)?.tagName).toBe('DIV');
      expect(el(fixture, id)?.getAttribute('href')).toBeNull();
    }
  });
});
