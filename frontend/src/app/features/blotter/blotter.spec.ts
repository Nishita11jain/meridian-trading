import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { Blotter } from './blotter';
import { AuthService } from '../../core/auth/auth.service';
import { OrderService } from '../../core/orders/order.service';
import { OrderHistoryEntry } from '../../clients/trade-client/model/orderHistoryEntry';

function entry(overrides: Partial<OrderHistoryEntry>): OrderHistoryEntry {
  return {
    orderId: 'ORD-1',
    accountId: 42,
    symbol: 'AAPL',
    side: 'BUY',
    quantity: 10,
    price: 100,
    status: 'FILLED',
    createdOn: '2026-01-01T00:00:00.000Z',
    ...overrides
  } as OrderHistoryEntry;
}

describe('Blotter', () => {
  let mockAuthService: any;
  let mockOrderService: any;

  beforeEach(async () => {
    mockAuthService = {
      getProfile: vi.fn().mockReturnValue(of({ accountId: 42 })),
      logout: vi.fn()
    };

    mockOrderService = {
      getOrderHistory: vi.fn()
    };

    await TestBed.configureTestingModule({
      imports: [Blotter],
      providers: [
        { provide: AuthService, useValue: mockAuthService },
        { provide: OrderService, useValue: mockOrderService },
        provideRouter([])
      ]
    }).compileComponents();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function createComponent() {
    const fixture = TestBed.createComponent(Blotter);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  it('renders order history newest first, including rejections', () => {
    mockOrderService.getOrderHistory.mockReturnValue(
      of([
        entry({ orderId: 'ORD-OLD', symbol: 'AAPL', status: 'FILLED', createdOn: '2026-01-01T00:00:00.000Z' }),
        entry({ orderId: 'ORD-NEW', symbol: 'INFY.NS', status: 'REJECTED', createdOn: '2026-01-02T00:00:00.000Z' })
      ])
    );

    const { fixture } = createComponent();

    const rows = fixture.nativeElement.querySelectorAll('[data-testid="blotter-row"]');
    expect(rows.length).toBe(2);
    // Newest first
    expect(rows[0].textContent).toContain('INFY.NS');
    expect(rows[1].textContent).toContain('AAPL');
    // Rejections are not hidden
    expect(rows[0].textContent).toContain('REJECTED');
  });

  it('an order at NEW is shown as still working', () => {
    mockOrderService.getOrderHistory.mockReturnValue(
      of([entry({ orderId: 'ORD-WORKING', status: 'NEW' })])
    );

    const { fixture } = createComponent();

    const badge = fixture.nativeElement.querySelector('[data-testid="blotter-status"]');
    const stillWorking = fixture.nativeElement.querySelector('[data-testid="blotter-still-working"]');
    expect(badge.textContent).toContain('NEW');
    expect(stillWorking).toBeTruthy();
    expect(stillWorking.textContent).toContain('still working');
  });

  it('the re-read stops when nothing is at NEW', () => {
    vi.useFakeTimers();

    mockOrderService.getOrderHistory
      .mockReturnValueOnce(of([entry({ orderId: 'ORD-1', status: 'NEW' })]))
      .mockReturnValueOnce(of([entry({ orderId: 'ORD-1', status: 'FILLED' })]));

    createComponent();

    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(1);

    // First poll: order resolves to FILLED, polling should stop.
    vi.advanceTimersByTime(4000);
    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(2);

    // No further polls once nothing is NEW.
    vi.advanceTimersByTime(4000 * 5);
    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(2);
  });

  it('stops polling after the maximum number of attempts even if still NEW', () => {
    vi.useFakeTimers();

    mockOrderService.getOrderHistory.mockReturnValue(
      of([entry({ orderId: 'ORD-STUCK', status: 'NEW' })])
    );

    createComponent();

    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(1);

    // Advance well past the 20-attempt cap at a 4s interval.
    vi.advanceTimersByTime(4000 * 25);

    // 1 initial + 20 polls = 21, then it must stop even though status is still NEW.
    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(21);

    vi.advanceTimersByTime(4000 * 5);
    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(21);
  });

  it('renders each of the four statuses as a badge that carries the word', () => {
    vi.useFakeTimers();
    mockOrderService.getOrderHistory.mockReturnValue(
      of([
        entry({ orderId: 'ORD-NEW', status: 'NEW', createdOn: '2026-01-04T00:00:00.000Z' }),
        entry({ orderId: 'ORD-FILLED', status: 'FILLED', createdOn: '2026-01-03T00:00:00.000Z' }),
        entry({ orderId: 'ORD-REJECTED', status: 'REJECTED', createdOn: '2026-01-02T00:00:00.000Z' }),
        entry({ orderId: 'ORD-CANCELLED', status: 'CANCELLED', createdOn: '2026-01-01T00:00:00.000Z' })
      ])
    );

    const { fixture } = createComponent();

    const badges = (Array.from(fixture.nativeElement.querySelectorAll('[data-testid="blotter-status"]')) as HTMLElement[])
      .map((badge) => badge.textContent?.trim());
    expect(badges).toEqual(['NEW', 'FILLED', 'REJECTED', 'CANCELLED']);
  });

  it('only an order at NEW is marked as still working', () => {
    vi.useFakeTimers();
    mockOrderService.getOrderHistory.mockReturnValue(
      of([
        entry({ orderId: 'ORD-NEW', status: 'NEW', createdOn: '2026-01-04T00:00:00.000Z' }),
        entry({ orderId: 'ORD-FILLED', status: 'FILLED', createdOn: '2026-01-03T00:00:00.000Z' }),
        entry({ orderId: 'ORD-REJECTED', status: 'REJECTED', createdOn: '2026-01-02T00:00:00.000Z' }),
        entry({ orderId: 'ORD-CANCELLED', status: 'CANCELLED', createdOn: '2026-01-01T00:00:00.000Z' })
      ])
    );

    const { fixture } = createComponent();

    expect(fixture.nativeElement.querySelectorAll('[data-testid="blotter-still-working"]').length).toBe(1);
  });

  it('says on screen that automatic refresh has stopped while an order is still working', () => {
    vi.useFakeTimers();
    mockOrderService.getOrderHistory.mockReturnValue(of([entry({ orderId: 'ORD-STUCK', status: 'NEW' })]));

    const { fixture } = createComponent();
    expect(fixture.nativeElement.querySelector('[data-testid="blotter-polling-stopped"]')).toBeFalsy();

    vi.advanceTimersByTime(4000 * 25);
    fixture.detectChanges();

    const notice = fixture.nativeElement.querySelector('[data-testid="blotter-polling-stopped"]');
    expect(notice).toBeTruthy();
    expect(notice.textContent).toContain('press Refresh');
    expect(fixture.nativeElement.querySelector('[data-testid="blotter-still-working"]')).toBeTruthy();
  });

  it('a manual refresh after polling gave up starts a fresh round of re-reads', () => {
    vi.useFakeTimers();
    mockOrderService.getOrderHistory.mockReturnValue(of([entry({ orderId: 'ORD-STUCK', status: 'NEW' })]));

    const { fixture } = createComponent();
    vi.advanceTimersByTime(4000 * 25);
    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(21);

    (fixture.nativeElement.querySelector('[data-testid="blotter-refresh"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(22);
    expect(fixture.nativeElement.querySelector('[data-testid="blotter-polling-stopped"]')).toBeFalsy();

    vi.advanceTimersByTime(4000 * 2);
    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(24);
  });

  it('the table has a caption and column headers for assistive technology', () => {
    mockOrderService.getOrderHistory.mockReturnValue(of([entry({})]));

    const { fixture } = createComponent();

    expect(fixture.nativeElement.querySelector('table caption')).toBeTruthy();
    const headers = Array.from(fixture.nativeElement.querySelectorAll('th')) as HTMLElement[];
    expect(headers.length).toBeGreaterThan(0);
    expect(headers.every((th) => th.getAttribute('scope') === 'col')).toBe(true);
  });

  it('empty history is handled gracefully', () => {
    mockOrderService.getOrderHistory.mockReturnValue(of([]));

    const { fixture } = createComponent();

    const empty = fixture.nativeElement.querySelector('[data-testid="blotter-empty"]');
    const table = fixture.nativeElement.querySelector('[data-testid="blotter-table"]');
    expect(empty).toBeTruthy();
    expect(table).toBeFalsy();
  });

  it('shows a readable message when the history call fails', () => {
    mockOrderService.getOrderHistory.mockReturnValue(
      throwError(() => ({ status: 0 }))
    );

    const { fixture } = createComponent();

    const error = fixture.nativeElement.querySelector('[data-testid="blotter-error"]');
    expect(error).toBeTruthy();
    expect(error.textContent).toContain('could not reach the service');
  });

  it('manual refresh re-fetches history', () => {
    mockOrderService.getOrderHistory.mockReturnValue(of([]));

    const { fixture } = createComponent();
    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(1);

    const button = fixture.nativeElement.querySelector('[data-testid="blotter-refresh"]') as HTMLButtonElement;
    button.click();

    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(2);
  });

  describe('search', () => {
    const rows = (fixture: any) => fixture.nativeElement.querySelectorAll('[data-testid="blotter-row"]').length;
    const type = (fixture: any, text: string) => {
      const input = fixture.nativeElement.querySelector('[data-testid="blotter-search"]') as HTMLInputElement;
      input.value = text;
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    };
    const regexOn = (fixture: any) => {
      (fixture.nativeElement.querySelector('[data-testid="blotter-search-regex"]') as HTMLButtonElement).click();
      fixture.detectChanges();
    };
    const list = () => [
      entry({ orderId: 'ORD-1', symbol: 'TCS.NS' }),
      entry({ orderId: 'ORD-2', symbol: 'INFY.NS' }),
      entry({ orderId: 'ORD-3', symbol: 'AAPL' })
    ];

    it('plain text finds a symbol, case-insensitively', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of(list()));
      const { fixture } = createComponent();

      type(fixture, 'infy');

      expect(rows(fixture)).toBe(1);
    });

    it('the .* toggle searches with a regular expression', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of(list()));
      const { fixture } = createComponent();

      regexOn(fixture);
      type(fixture, String.raw`\.NS$`);

      expect(rows(fixture)).toBe(2);
    });

    it('a half-typed pattern keeps every row and says what is wrong', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of(list()));
      const { fixture } = createComponent();

      regexOn(fixture);
      type(fixture, '(TCS');

      expect(rows(fixture)).toBe(3);
      expect(fixture.nativeElement.querySelector('[data-testid="blotter-search-error"]')?.textContent).toContain('Not a valid pattern');
    });
  });

  describe('cancelling a working order', () => {
    const working = () => entry({ orderId: 'ORD-6f2b1c2a-6a1e-4a4f-9c0d-2f7a1b3c4d5e', status: 'NEW' });
    const cancelButton = (fixture: any) => fixture.nativeElement.querySelector('[data-testid="blotter-cancel"]') as HTMLButtonElement | null;
    const dialog = (fixture: any) => fixture.nativeElement.querySelector('[data-testid="blotter-cancel-dialog"]') as HTMLElement | null;
    /** Opens the in-page dialog from the row, then answers it. */
    const answer = (fixture: any, choice: 'confirm' | 'keep') => {
      cancelButton(fixture)!.click();
      fixture.detectChanges();
      (fixture.nativeElement.querySelector(`[data-testid="blotter-cancel-dialog-${choice}"]`) as HTMLButtonElement).click();
      fixture.detectChanges();
    };

    afterEach(() => vi.restoreAllMocks());

    it('offers Cancel only on orders that are still NEW', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of([working(), entry({ orderId: 'ORD-2', status: 'FILLED' }), entry({ orderId: 'ORD-3', status: 'REJECTED' }), entry({ orderId: 'ORD-4', status: 'CANCELLED' })]));

      const { fixture } = createComponent();

      expect(fixture.nativeElement.querySelectorAll('[data-testid="blotter-cancel"]').length).toBe(1);
    });

    it('asks first, sends the bare UUID, and re-reads the list', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of([working()]));
      mockOrderService.cancelOrder = vi.fn().mockReturnValue(of({ status: 'CANCELLED' }));
      const { fixture } = createComponent();

      cancelButton(fixture)!.click();
      fixture.detectChanges();

      // Nothing is sent until the dialog is answered, and the dialog names the order.
      expect(mockOrderService.cancelOrder).not.toHaveBeenCalled();
      expect(dialog(fixture)?.textContent).toContain('AAPL');

      (fixture.nativeElement.querySelector('[data-testid="blotter-cancel-dialog-confirm"]') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(dialog(fixture)).toBeNull();
      expect(mockOrderService.cancelOrder).toHaveBeenCalledWith('6f2b1c2a-6a1e-4a4f-9c0d-2f7a1b3c4d5e');
      expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(2);
    });

    it('sends nothing when the user declines the confirmation', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of([working()]));
      mockOrderService.cancelOrder = vi.fn();
      const { fixture } = createComponent();

      answer(fixture, 'keep');

      expect(mockOrderService.cancelOrder).not.toHaveBeenCalled();
      expect(dialog(fixture)).toBeNull();
    });

    it('never uses the browser native confirm prompt', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of([working()]));
      mockOrderService.cancelOrder = vi.fn().mockReturnValue(of({ status: 'CANCELLED' }));
      const confirmSpy = vi.spyOn(window, 'confirm');
      const { fixture } = createComponent();

      answer(fixture, 'confirm');

      expect(confirmSpy).not.toHaveBeenCalled();
      expect(mockOrderService.cancelOrder).toHaveBeenCalledTimes(1);
    });

    it('explains a refusal (ORD-409, already filled) and keeps showing it after the list is re-read', () => {
      mockOrderService.getOrderHistory
        .mockReturnValueOnce(of([working()]))
        .mockReturnValueOnce(of([{ ...working(), status: 'FILLED' }]));
      mockOrderService.cancelOrder = vi.fn().mockReturnValue(throwError(() => ({ status: 409, error: { errorCode: 'ORD-409' } })));
      const { fixture } = createComponent();

      answer(fixture, 'confirm');

      const alert = fixture.nativeElement.querySelector('[data-testid="blotter-error"]');
      expect(alert?.textContent).toContain('already been placed');
      expect(fixture.nativeElement.querySelector('[data-testid="blotter-status"]').textContent).toContain('FILLED');
      expect(cancelButton(fixture)).toBeNull();
    });
  });

  it('does not poll while the tab is hidden, and does not use up attempts doing so', () => {
    vi.useFakeTimers();
    mockOrderService.getOrderHistory.mockReturnValue(of([entry({ status: 'NEW' })]));
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    createComponent();

    vi.advanceTimersByTime(4000 * 30);
    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(1);

    hidden.mockReturnValue(false);
    vi.advanceTimersByTime(4000);
    expect(mockOrderService.getOrderHistory).toHaveBeenCalledTimes(2);
    hidden.mockRestore();
  });

  describe('rejection reasons', () => {
    const rejected = (rejectionReason: string | null) =>
      entry({ orderId: 'ORD-R', status: 'REJECTED', rejectionReason } as Partial<OrderHistoryEntry>);

    it('says in plain words why an order was rejected for not enough cash', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of([rejected('INSUFFICIENT_FUNDS')]));

      const { fixture } = createComponent();

      expect(fixture.nativeElement.querySelector('[data-testid="blotter-reason"]').textContent).toContain('Not enough cash');
    });

    it('explains a price-not-met rejection', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of([rejected('PRICE_NOT_MET')]));

      const { fixture } = createComponent();

      expect(fixture.nativeElement.querySelector('[data-testid="blotter-reason"]').textContent).toContain('Price not met');
    });

    it('shows an older free-text reason as it is, and says so when none was recorded', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of([
        rejected('buy limit below ask'),
        { ...rejected(null), orderId: 'ORD-R2' } as OrderHistoryEntry
      ]));

      const { fixture } = createComponent();
      const reasons = [...fixture.nativeElement.querySelectorAll('[data-testid="blotter-reason"]')].map((n: any) => n.textContent.trim());

      expect(reasons).toContain('buy limit below ask');
      expect(reasons).toContain('Reason not recorded');
    });

    it('shows no reason on an order that was not rejected', () => {
      mockOrderService.getOrderHistory.mockReturnValue(of([entry({ status: 'FILLED' })]));

      const { fixture } = createComponent();

      expect(fixture.nativeElement.querySelector('[data-testid="blotter-reason"]')).toBeNull();
    });
  });
});
