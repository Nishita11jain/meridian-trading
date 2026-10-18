import { DisplayCurrencyService } from '../../core/currency/display-currency.service';
import { displayCurrencyStub } from '../../core/currency/testing/display-currency.stub';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { OrderTicket } from './order-ticket';
import { AuthService } from '../../core/auth/auth.service';
import { OrderService } from '../../core/orders/order.service';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';
import { LeavePageConfirmService } from '../../core/guards/leave-page-confirm.service';

describe('OrderTicket', () => {
  let mockAuthService: any;
  let mockOrderService: any;

  beforeEach(async () => {
    mockAuthService = {
      logout: vi.fn(),
      getProfile: vi.fn().mockReturnValue(of({ accountId: 42 }))
    };

    mockOrderService = {
      placeOrder: vi.fn(),
      getBalance: vi.fn().mockReturnValue(of({ accountId: 42, cashBalance: 1000, currency: 'INR', asOf: '2026-01-01T00:00:00Z' })),
      // Enough AAPL held that the SELL tests here are not blocked; blocking has its own tests.
      getPositions: vi.fn().mockReturnValue(of([{ accountId: 42, symbol: 'AAPL', quantity: 1000, averageCost: 10 }]))
    };

    await TestBed.configureTestingModule({
      imports: [OrderTicket],
      providers: [
        { provide: DisplayCurrencyService, useValue: displayCurrencyStub() },
        { provide: AuthService, useValue: mockAuthService },
        { provide: OrderService, useValue: mockOrderService },
        // No cached quotes: these tests are about the order flow, not the price check.
        // No cached quotes, and no instrument catalogue: the symbol field falls back to free text with
        // the contract's format check, which is what the symbol tests in this file exercise.
        { provide: WatchlistsService, useValue: {
          getQuotes: vi.fn().mockReturnValue(of([])),
          getFxRates: vi.fn().mockReturnValue(of({ baseCurrency: 'INR', currency: 'USD', midRate: 80, buyRate: 80.4, sellRate: 79.6, marginPercent: 0.5, rateDate: '2026-10-08' })),
          getHistory: vi.fn().mockReturnValue(of([])),
          getInstruments: vi.fn().mockReturnValue(throwError(() => ({ status: 503 })))
        } },
        provideRouter([])
      ]
    }).compileComponents();
  });

  function createComponent() {
    const fixture = TestBed.createComponent(OrderTicket);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  it('shows the account from the session, and offers no field to type one', () => {
    const { fixture } = createComponent();

    expect(fixture.nativeElement.querySelector('[data-testid="ticket-account-badge"]').textContent).toContain('42');
    expect(fixture.nativeElement.querySelector('[data-testid="ticket-account"]')).toBeNull();
  });

  it('a valid order submits and shows the returned status', () => {
    const { component } = createComponent();

    mockOrderService.placeOrder.mockReturnValue(
      of({
        orderId: 'ORD-123',
        status: 'NEW',
        message: 'Accepted and queued',
        symbol: 'AAPL',
        side: 'BUY',
        quantity: 10,
        price: 25.5
      })
    );

    component.ticketForm.setValue({
      symbol: 'AAPL',
      side: 'BUY',
      quantity: '10',
      price: '25.50'
    });

    component.submitOrder();
    component.confirmOrder();

    expect(mockOrderService.placeOrder).toHaveBeenCalledTimes(1);
    expect(component.outcomeMessage()).toContain('is NEW');
    expect(component.errorMessage()).toBe('');
    expect(component.ticketForm.getRawValue()).toEqual({ symbol: '', side: 'BUY', quantity: '', price: '' });
  });

  it('an invalid quantity is blocked before submission', () => {
    const { component } = createComponent();

    component.ticketForm.setValue({
      symbol: 'AAPL',
      side: 'BUY',
      quantity: '0',
      price: '25.50'
    });

    component.submitOrder();
    component.confirmOrder();

    expect(component.ticketForm.controls.quantity.invalid).toBe(true);
    expect(mockOrderService.placeOrder).not.toHaveBeenCalled();
  });

  it('a price with three decimal places is blocked before submission', () => {
    const { component } = createComponent();

    component.ticketForm.setValue({ symbol: 'AAPL', side: 'BUY', quantity: '10', price: '25.505' });
    component.submitOrder();
    component.confirmOrder();

    expect(component.ticketForm.controls.price.invalid).toBe(true);
    expect(mockOrderService.placeOrder).not.toHaveBeenCalled();
  });

  it('a symbol that does not match the contract shape is blocked before submission', () => {
    const { component } = createComponent();

    component.ticketForm.setValue({ symbol: 'AAPL!!', side: 'BUY', quantity: '10', price: '25.50' });
    component.submitOrder();
    component.confirmOrder();

    expect(component.ticketForm.controls.symbol.invalid).toBe(true);
    expect(mockOrderService.placeOrder).not.toHaveBeenCalled();
  });

  describe('client-side validation', () => {
    const validTicket = { symbol: 'AAPL', side: 'BUY' as const, quantity: '10', price: '25.50' };

    it.each(['0', '-1', '1.5', 'abc', ''])('blocks the quantity "%s" before submission', (quantity) => {
      const { component } = createComponent();
      component.ticketForm.setValue({ ...validTicket, quantity });

      component.submitOrder();
      component.confirmOrder();

      expect(component.ticketForm.controls.quantity.invalid).toBe(true);
      expect(mockOrderService.placeOrder).not.toHaveBeenCalled();
    });

    it.each(['0', '0.00', '-5', '25.505', 'abc', ''])('blocks the price "%s" before submission', (price) => {
      const { component } = createComponent();
      component.ticketForm.setValue({ ...validTicket, price });

      component.submitOrder();
      component.confirmOrder();

      expect(component.ticketForm.controls.price.invalid).toBe(true);
      expect(mockOrderService.placeOrder).not.toHaveBeenCalled();
    });

    it.each(['AAPL!!', 'A B', 'FX:EUR', 'X:', '.NS', 'AAPL..NS', 'A'.repeat(21), ''])(
      'blocks the symbol "%s" before submission',
      (symbol) => {
        const { component } = createComponent();
        component.ticketForm.setValue({ ...validTicket, symbol });

        component.submitOrder();
        component.confirmOrder();

        expect(component.ticketForm.controls.symbol.invalid).toBe(true);
        expect(mockOrderService.placeOrder).not.toHaveBeenCalled();
      }
    );

    it.each(['AAPL', 'INFY.NS', 'RELIANCE.BO', 'FX:EURUSD', 'X:BTC'])('accepts the symbol "%s", which has a shape the contract allows', (symbol) => {
      const { component } = createComponent();
      component.ticketForm.setValue({ ...validTicket, symbol });

      expect(component.ticketForm.controls.symbol.valid).toBe(true);
    });

    it('accepts a price of one cent and a price with one decimal place', () => {
      const { component } = createComponent();

      for (const price of ['0.01', '25.5', '100']) {
        component.ticketForm.setValue({ ...validTicket, price });
        expect(component.ticketForm.controls.price.valid).toBe(true);
      }
    });
  });

  describe('what is sent for a valid ticket', () => {
    const accepted = of({ orderId: 'ORD-1', status: 'NEW', message: 'Accepted', symbol: 'AAPL', side: 'SELL', quantity: 5, price: 12.5 });

    it('a SELL order is sent with the side, the whole quantity, the price and a key', () => {
      const { component } = createComponent();
      mockOrderService.placeOrder.mockReturnValue(accepted);
      component.ticketForm.setValue({ symbol: 'AAPL', side: 'SELL', quantity: '5', price: '12.50' });

      component.submitOrder();
      component.confirmOrder();

      const [, symbol, side, quantity, price, key] = mockOrderService.placeOrder.mock.calls[0];
      expect(symbol).toBe('AAPL');
      expect(side).toBe('SELL');
      expect(quantity).toBe(5);
      expect(price).toBe(12.5);
      expect(typeof key).toBe('string');
      expect(key.length).toBeGreaterThanOrEqual(8);
    });

    it('the account sent is the one the session may trade, never one typed into the form', () => {
      const { component } = createComponent();
      mockOrderService.placeOrder.mockReturnValue(accepted);
      component.ticketForm.setValue({ symbol: 'AAPL', side: 'BUY', quantity: '5', price: '12.50' });

      component.submitOrder();
      component.confirmOrder();

      expect(mockOrderService.placeOrder.mock.calls[0][0]).toBe(42);
      expect('account' in component.ticketForm.controls).toBe(false);
      expect('accountId' in component.ticketForm.controls).toBe(false);
    });
  });

  describe('when the trading account cannot be loaded', () => {
    it('shows a readable message instead of a blank form', () => {
      mockAuthService.getProfile.mockReturnValue(throwError(() => ({ status: 0 })));

      const { component } = createComponent();

      expect(component.errorMessage()).toContain('could not reach the service');
    });

    it('refuses to submit and tells the user to sign in again', () => {
      mockAuthService.getProfile.mockReturnValue(throwError(() => ({ status: 0 })));
      const { component } = createComponent();
      component.ticketForm.setValue({ symbol: 'AAPL', side: 'BUY', quantity: '10', price: '25.50' });

      component.submitOrder();
      component.confirmOrder();

      expect(mockOrderService.placeOrder).not.toHaveBeenCalled();
      expect(component.errorMessage()).toContain('Could not determine the trading account');
    });

    it('shows no account number', () => {
      mockAuthService.getProfile.mockReturnValue(throwError(() => ({ status: 0 })));

      const { fixture } = createComponent();

      expect(fixture.nativeElement.querySelector('[data-testid="ticket-account-badge"]').textContent.trim()).toBe('Account');
    });
  });

  describe('unsaved changes', () => {
    const accepted = of({ orderId: 'ORD-1', status: 'NEW', message: 'Accepted', symbol: 'AAPL', side: 'BUY', quantity: 10, price: 25.5 });

    /** What the user's own typing does: the form is edited, so it becomes dirty. */
    function typeIntoTicket(component: OrderTicket, value = { symbol: 'AAPL', side: 'BUY' as const, quantity: '10', price: '25.50' }) {
      component.ticketForm.setValue(value);
      component.ticketForm.markAsDirty();
    }

    function leaveTheTab(): Event {
      const event = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(event);
      return event;
    }

    it('has nothing to lose before the user types anything', () => {
      const { component } = createComponent();

      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('has something to lose once an order has been typed and not sent', () => {
      const { component } = createComponent();

      typeIntoTicket(component);

      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('has nothing to lose if the user typed and then cleared every field', () => {
      const { component } = createComponent();

      typeIntoTicket(component, { symbol: '', side: 'BUY', quantity: '', price: '' });

      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('has nothing to lose after the order is placed, and counts again as soon as the user edits', () => {
      const { component } = createComponent();
      mockOrderService.placeOrder.mockReturnValue(accepted);
      typeIntoTicket(component);

      component.submitOrder();
      component.confirmOrder();
      expect(component.hasUnsavedChanges()).toBe(false);

      component.ticketForm.controls.quantity.setValue('11');
      component.ticketForm.markAsDirty();
      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('still has something to lose when the order was refused, because it was never placed', () => {
      const { component } = createComponent();
      mockOrderService.placeOrder.mockReturnValue(throwError(() => ({ status: 400, error: { errorCode: 'ORD-400' } })));
      typeIntoTicket(component);

      component.submitOrder();
      component.confirmOrder();

      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('asks nothing while the order is being sent', () => {
      const { component } = createComponent();
      typeIntoTicket(component);
      component.submitting.set(true);

      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('makes the browser ask before the tab is closed or reloaded when there is unsent input', () => {
      const { component } = createComponent();
      typeIntoTicket(component);

      expect(leaveTheTab().defaultPrevented).toBe(true);
    });

    it('lets the tab close or reload quietly when there is nothing to lose', () => {
      createComponent();

      expect(leaveTheTab().defaultPrevented).toBe(false);
    });

    it('lets one confirmed custom reload pass without the native browser prompt', () => {
      const { component } = createComponent();
      typeIntoTicket(component);
      TestBed.inject(LeavePageConfirmService).allowNextBeforeUnload();

      expect(leaveTheTab().defaultPrevented).toBe(false);
      expect(leaveTheTab().defaultPrevented).toBe(true);
    });
  });

  describe('idempotency key', () => {
    const ticket = { symbol: 'AAPL', side: 'BUY' as const, quantity: '10', price: '25.50' };
    const accepted = of({ orderId: 'ORD-1', status: 'NEW', message: 'Accepted', symbol: 'AAPL', side: 'BUY', quantity: 10, price: 25.5 });

    it('a retry after no response reuses the same key, so a second order cannot be placed', () => {
      const { component } = createComponent();
      mockOrderService.placeOrder
        .mockReturnValueOnce(throwError(() => ({ status: 0 })))
        .mockReturnValueOnce(accepted);
      component.ticketForm.setValue(ticket);

      component.submitOrder();
      component.confirmOrder();
      component.submitOrder();
      component.confirmOrder();

      const firstKey = mockOrderService.placeOrder.mock.calls[0][5];
      const secondKey = mockOrderService.placeOrder.mock.calls[1][5];
      expect(secondKey).toBe(firstKey);
    });

    it('a new key is used after the API refused the order with an error code', () => {
      const { component } = createComponent();
      mockOrderService.placeOrder
        .mockReturnValueOnce(throwError(() => ({ status: 400, error: { errorCode: 'ORD-400' } })))
        .mockReturnValueOnce(accepted);
      component.ticketForm.setValue(ticket);

      component.submitOrder();
      component.confirmOrder();
      component.submitOrder();
      component.confirmOrder();

      const firstKey = mockOrderService.placeOrder.mock.calls[0][5];
      const secondKey = mockOrderService.placeOrder.mock.calls[1][5];
      expect(secondKey).not.toBe(firstKey);
    });

    it('a new key is used for the next order after a success', () => {
      const { component } = createComponent();
      mockOrderService.placeOrder.mockReturnValue(accepted);
      component.ticketForm.setValue(ticket);

      component.submitOrder();
      component.confirmOrder();
      component.ticketForm.setValue(ticket);
      component.submitOrder();
      component.confirmOrder();

      const firstKey = mockOrderService.placeOrder.mock.calls[0][5];
      const secondKey = mockOrderService.placeOrder.mock.calls[1][5];
      expect(secondKey).not.toBe(firstKey);
    });

    it('a new key is used when the ticket changes after a failed attempt', () => {
      const { component } = createComponent();
      mockOrderService.placeOrder
        .mockReturnValueOnce(throwError(() => ({ status: 503 })))
        .mockReturnValueOnce(accepted);
      component.ticketForm.setValue(ticket);
      component.submitOrder();
      component.confirmOrder();

      component.ticketForm.setValue({ ...ticket, quantity: '11' });
      component.submitOrder();
      component.confirmOrder();

      const firstKey = mockOrderService.placeOrder.mock.calls[0][5];
      const secondKey = mockOrderService.placeOrder.mock.calls[1][5];
      expect(secondKey).not.toBe(firstKey);
    });
  });

  it('the rejection message is announced to assistive technology', () => {
    const { fixture, component } = createComponent();
    mockOrderService.placeOrder.mockReturnValue(throwError(() => ({ status: 400, error: { errorCode: 'ORD-400' } })));
    component.ticketForm.setValue({ symbol: 'AAPL', side: 'BUY', quantity: '10', price: '25.50' });

    component.submitOrder();
    component.confirmOrder();
    fixture.detectChanges();

    const box = fixture.nativeElement.querySelector('[data-testid="ticket-error"]');
    expect(box.getAttribute('role')).toBe('alert');
  });

  it('a business-rule rejection shows the mapped message', () => {
    const { component } = createComponent();

    mockOrderService.placeOrder.mockReturnValue(
      throwError(() => ({
        error: { errorCode: 'ORD-400', message: 'Insufficient cash balance' },
        status: 400
      }))
    );

    component.ticketForm.setValue({
      symbol: 'AAPL',
      side: 'BUY',
      // Affordable against the cash this page read (2 x 250 = 500 of 1,000), so the ticket sends it;
      // the server still refuses -- the cash changed after the page loaded -- and that refusal is shown.
      quantity: '2',
      price: '250.00'
    });

    component.submitOrder();
    component.confirmOrder();

    expect(component.errorMessage()).toContain('There is not enough cash for this order');
    expect(component.outcomeMessage()).toBe('');
  });
});
