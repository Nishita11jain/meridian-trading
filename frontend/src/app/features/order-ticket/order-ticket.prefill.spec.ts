import { DisplayCurrencyService } from '../../core/currency/display-currency.service';
import { displayCurrencyStub } from '../../core/currency/testing/display-currency.stub';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { OrderService } from '../../core/orders/order.service';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';
import { OrderTicket } from './order-ticket';

/** "Trade" on the market board opens the ticket as /trade?symbol=..., with that instrument chosen. */
describe('OrderTicket: opened from the market board', () => {
  async function renderWith(query: Record<string, string>) {
    await TestBed.configureTestingModule({
      imports: [OrderTicket],
      providers: [
        { provide: DisplayCurrencyService, useValue: displayCurrencyStub() },
        { provide: AuthService, useValue: { getProfile: () => of({ accountId: 42 }), logout: vi.fn() } },
        { provide: OrderService, useValue: {
          getBalance: vi.fn().mockReturnValue(of({ accountId: 42, cashBalance: 1000, currency: 'INR', asOf: null })),
          getPositions: vi.fn().mockReturnValue(of([])),
          placeOrder: vi.fn()
        } },
        { provide: WatchlistsService, useValue: {
          getQuotes: vi.fn().mockReturnValue(of([])),
          getFxRates: vi.fn().mockReturnValue(of({ baseCurrency: 'INR', currency: 'USD', midRate: 80, buyRate: 80.4, sellRate: 79.6, marginPercent: 0.5, rateDate: '2026-10-08' })),
          getHistory: vi.fn().mockReturnValue(of([])),
          getInstruments: vi.fn().mockReturnValue(of([{ symbol: 'MSFT', name: 'Microsoft Corporation', assetClass: 'EQUITY', currency: 'USD' }]))
        } },
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(OrderTicket);
    fixture.detectChanges();
    return fixture;
  }

  it('chooses the instrument named in the address', async () => {
    const fixture = await renderWith({ symbol: 'msft' });

    expect(fixture.componentInstance.ticketForm.controls.symbol.value).toBe('MSFT');
    expect(fixture.componentInstance.ticketForm.controls.symbol.valid).toBe(true);
    // Choosing it for the user is not an edit of theirs: leaving asks nothing.
    expect(fixture.componentInstance.ticketForm.dirty).toBe(false);
  });

  it('ignores a value that is not a symbol', async () => {
    const fixture = await renderWith({ symbol: '<script>' });

    expect(fixture.componentInstance.ticketForm.controls.symbol.value).toBe('');
  });

  it('chooses the side too, from Buy or Sell on a watchlist row', async () => {
    const fixture = await renderWith({ symbol: 'MSFT', side: 'sell' });

    expect(fixture.componentInstance.ticketForm.controls.side.value).toBe('SELL');
  });

  it('ignores a side that is not BUY or SELL', async () => {
    const fixture = await renderWith({ symbol: 'MSFT', side: 'short' });

    expect(fixture.componentInstance.ticketForm.controls.side.value).toBe('BUY');
  });
});
