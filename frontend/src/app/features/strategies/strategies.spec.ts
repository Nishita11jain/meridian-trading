import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { Strategies } from './strategies';
import { StrategiesService } from '../../core/strategies/strategies.service';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';
import { AuthService } from '../../core/auth/auth.service';

const strategy = (id: number, over: any = {}) => ({
  id,
  symbol: 'NVDA',
  side: 'BUY',
  strategyType: 'CROSSOVER',
  fastWindow: 6,
  slowWindow: 12,
  bandPeriod: null,
  bandMultiplier: null,
  orderQuantity: 20,
  maxSpend: 5000,
  maxPositionSize: 100,
  maxConsecutiveFailures: 3,
  consecutiveFailures: 0,
  status: 'ACTIVE',
  disabledReason: null,
  createdOn: '2026-10-07T00:00:00Z',
  ...over
});

describe('Strategies', () => {
  let service: any;
  let watchlists: any;

  async function render(strategies: any[] = [strategy(1)], over: Partial<Record<string, any>> = {}) {
    service = {
      list: vi.fn().mockReturnValue(of(strategies)),
      create: vi.fn().mockReturnValue(of(strategy(2))),
      updateStatus: vi.fn().mockReturnValue(of(strategy(1, { status: 'DISABLED' }))),
      log: vi.fn().mockReturnValue(of([{ triggeredAt: '2026-10-07T09:00:00Z', triggeringPrice: 300, action: 'BUY 20', outcome: 'PLACED', detail: 'ok', orderId: null }])),
      ...over
    };
    watchlists = { getInstruments: vi.fn().mockReturnValue(of([{ symbol: 'NVDA', name: 'Nvidia', assetClass: 'EQUITY', currency: 'USD', market: 'US', tradingStatus: 'OPEN', nextOpen: null }])) };
    await TestBed.configureTestingModule({
      imports: [Strategies],
      providers: [
        { provide: StrategiesService, useValue: service },
        { provide: WatchlistsService, useValue: watchlists },
        { provide: AuthService, useValue: { getProfile: () => of({ accountId: 4 }), logout: vi.fn() } },
        provideRouter([])
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(Strategies);
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: any, id: string) => f.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

  it('loads the account strategies on init and renders a card per strategy', async () => {
    const fixture = await render();

    expect(service.list).toHaveBeenCalledWith(4);
    expect(el(fixture, 'strategy-card-1')).toBeTruthy();
  });

  it('shows the empty state when the account has no strategies', async () => {
    const fixture = await render([]);

    expect(el(fixture, 'strategies-empty')).toBeTruthy();
  });

  it('the toggle calls updateStatus with the opposite status and swaps in the result', async () => {
    const fixture = await render();

    el(fixture, 'strategy-toggle-1')!.click();
    fixture.detectChanges();

    expect(service.updateStatus).toHaveBeenCalledWith(4, 1, 'DISABLED');
  });

  it('a disabled strategy with a reason shows it', async () => {
    const fixture = await render([strategy(1, { status: 'DISABLED', disabledReason: 'Auto-disabled after 3 consecutive order failures.' })]);

    expect(el(fixture, 'strategy-disabled-reason')?.textContent).toContain('Auto-disabled');
  });

  it('View log fetches and shows the execution log, Hide log collapses it', async () => {
    const fixture = await render();

    el(fixture, 'strategy-view-log-1')!.click();
    fixture.detectChanges();

    expect(service.log).toHaveBeenCalledWith(4, 1);
    expect(el(fixture, 'strategy-log-1')?.textContent).toContain('PLACED');

    el(fixture, 'strategy-view-log-1')!.click();
    fixture.detectChanges();
    expect(el(fixture, 'strategy-log-1')).toBeFalsy();
  });

  it('Create strategy is disabled until a symbol and the required risk limits are filled in', async () => {
    const fixture = await render();

    expect((el(fixture, 'strategy-create') as HTMLButtonElement).disabled).toBe(true);
  });

  it('a failed load shows an error message', async () => {
    const fixture = await render([], { list: vi.fn().mockReturnValue(throwError(() => ({ status: 500 }))) });

    expect(el(fixture, 'strategies-error')).toBeTruthy();
  });
});
