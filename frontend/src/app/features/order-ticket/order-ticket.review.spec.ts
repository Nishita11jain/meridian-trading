import { DisplayCurrencyService } from '../../core/currency/display-currency.service';
import { displayCurrencyStub } from '../../core/currency/testing/display-currency.stub';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { OrderService } from '../../core/orders/order.service';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';
import { OrderTicket } from './order-ticket';

const balance = { accountId: 42, cashBalance: 1000, currency: 'INR', asOf: '2026-01-01T00:00:00Z' };
// TCS.NS is held (50) and has a cached market price of 10.00; MSFT is neither held nor priced.
const positions = [{ accountId: 42, symbol: 'TCS.NS', quantity: 50, averageCost: 9 }];
const cachedQuotes = [{ symbol: 'TCS.NS', lastPrice: 10, currency: 'INR', stale: false, quoteAsOf: '2026-10-01T09:00:00Z' }];
const catalogue = [
  { symbol: 'TCS.NS', name: 'Tata Consultancy Services Limited', assetClass: 'EQUITY', currency: 'INR' },
  { symbol: 'TATAMOTORS.NS', name: 'Tata Motors Limited', assetClass: 'EQUITY', currency: 'INR' },
  { symbol: 'MSFT', name: 'Microsoft Corporation', assetClass: 'EQUITY', currency: 'USD' },
  { symbol: 'ICICIBANK.NS', name: 'ICICI Bank Limited', assetClass: 'EQUITY', currency: 'INR' }
];

describe('OrderTicket: cash, estimate and review step', () => {
  let orders: { placeOrder: ReturnType<typeof vi.fn>; getBalance: ReturnType<typeof vi.fn>; getPositions: ReturnType<typeof vi.fn>; getOrderHistory: ReturnType<typeof vi.fn> };
  let quotes: { getQuotes: ReturnType<typeof vi.fn>; getInstruments: ReturnType<typeof vi.fn>; getHistory: ReturnType<typeof vi.fn>; getFxRates: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    orders = {
      placeOrder: vi.fn().mockReturnValue(of({ orderId: 'ORD-1', status: 'NEW', message: 'Accepted' })),
      getBalance: vi.fn().mockReturnValue(of(balance)),
      getPositions: vi.fn().mockReturnValue(of(positions)),
      getOrderHistory: vi.fn().mockReturnValue(of([]))
    };
    quotes = { getQuotes: vi.fn().mockReturnValue(of(cachedQuotes)), getInstruments: vi.fn().mockReturnValue(of(catalogue)), getHistory: vi.fn().mockReturnValue(of([])), getFxRates: vi.fn().mockReturnValue(of({ baseCurrency: 'INR', currency: 'USD', midRate: 80, buyRate: 80.4, sellRate: 79.6, marginPercent: 0.5, rateDate: '2026-10-08' })) };
    await TestBed.configureTestingModule({
      imports: [OrderTicket],
      providers: [
        { provide: DisplayCurrencyService, useValue: displayCurrencyStub() },
        { provide: AuthService, useValue: { getProfile: () => of({ accountId: 42 }), logout: vi.fn() } },
        { provide: OrderService, useValue: orders },
        { provide: WatchlistsService, useValue: quotes },
        provideRouter([])
      ]
    }).compileComponents();
  });

  function render() {
    const fixture = TestBed.createComponent(OrderTicket);
    fixture.detectChanges();
    return fixture;
  }
  const el = (fixture: any, id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  function fill(fixture: any, side: 'BUY' | 'SELL', quantity: string, price: string) {
    fixture.componentInstance.ticketForm.setValue({ symbol: 'TCS.NS', side, quantity, price });
    fixture.detectChanges();
  }

  it('shows the available cash with its currency', () => {
    const fixture = render();

    expect(orders.getBalance).toHaveBeenCalledWith(42);
    expect(el(fixture, 'ticket-cash')?.textContent).toContain('₹1,000.00');
  });

  it('still works, without a cash line, when the balance cannot be read', () => {
    orders.getBalance.mockReturnValue(throwError(() => ({ status: 500 })));

    const fixture = render();

    expect(el(fixture, 'ticket-cash')).toBeNull();
    fill(fixture, 'BUY', '1', '10');
    fixture.componentInstance.submitOrder();
    fixture.detectChanges();
    expect(el(fixture, 'ticket-review')).toBeTruthy();
  });

  it('shows quantity x price as the estimated cost of a BUY, and as proceeds for a SELL', () => {
    const fixture = render();

    fill(fixture, 'BUY', '3', '10.50');
    expect(el(fixture, 'ticket-estimate')?.textContent).toContain('Maximum cost');
    expect(el(fixture, 'ticket-estimate')?.textContent).toContain('31.50');

    fill(fixture, 'SELL', '3', '10.50');
    expect(el(fixture, 'ticket-estimate')?.textContent).toContain('Minimum proceeds');
  });

  it('shows no estimate for a quantity or price that is not valid', () => {
    const fixture = render();

    for (const [q, p] of [['0', '10'], ['1.5', '10'], ['1', '0'], ['1', '10.123'], ['', '']]) {
      fill(fixture, 'BUY', q, p);
      expect(el(fixture, 'ticket-estimate'), `${q} x ${p}`).toBeNull();
    }
  });

  it('submitting shows a summary and sends nothing yet', () => {
    const fixture = render();
    fill(fixture, 'BUY', '2', '10.00');

    fixture.componentInstance.submitOrder();
    fixture.detectChanges();

    expect(orders.placeOrder).not.toHaveBeenCalled();
    const summary = el(fixture, 'ticket-review-summary')?.textContent ?? '';
    expect(summary).toContain('BUY');
    expect(summary).toContain('TCS.NS');
    expect(summary).toContain('20.00');
    expect(summary).toContain('₹');
  });

  it('an invalid ticket shows no summary', () => {
    const fixture = render();
    fill(fixture, 'BUY', '0', '10.00');

    fixture.componentInstance.submitOrder();
    fixture.detectChanges();

    expect(el(fixture, 'ticket-review')).toBeNull();
  });

  it('Confirm sends the order exactly as summarised, then closes the summary', () => {
    const fixture = render();
    fill(fixture, 'SELL', '2', '10.00');
    fixture.componentInstance.submitOrder();
    fixture.detectChanges();

    el(fixture, 'ticket-confirm')!.click();
    fixture.detectChanges();

    expect(orders.placeOrder).toHaveBeenCalledTimes(1);
    expect(orders.placeOrder.mock.calls[0].slice(0, 5)).toEqual([42, 'TCS.NS', 'SELL', 2, 10]);
    expect(el(fixture, 'ticket-review')).toBeNull();
    expect(el(fixture, 'ticket-success')?.textContent).toContain('Order ORD-1 is NEW');
    // Read at load, again when the order is reviewed, and again after the fill.
    expect(orders.getBalance).toHaveBeenCalledTimes(3);
  });

  it('Edit closes the summary without sending anything', () => {
    const fixture = render();
    fill(fixture, 'BUY', '2', '10.00');
    fixture.componentInstance.submitOrder();
    fixture.detectChanges();

    el(fixture, 'ticket-edit')!.click();
    fixture.detectChanges();

    expect(el(fixture, 'ticket-review')).toBeNull();
    expect(orders.placeOrder).not.toHaveBeenCalled();
  });

  it('changing any field closes the summary, so what was confirmed is what is sent', () => {
    const fixture = render();
    fill(fixture, 'BUY', '2', '10.00');
    fixture.componentInstance.submitOrder();
    fixture.detectChanges();

    fixture.componentInstance.ticketForm.controls.quantity.setValue('200');
    fixture.detectChanges();

    expect(el(fixture, 'ticket-review')).toBeNull();
    fixture.componentInstance.confirmOrder();
    expect(orders.placeOrder).not.toHaveBeenCalled();
  });

  it('confirming with no summary open sends nothing', () => {
    const fixture = render();
    fill(fixture, 'BUY', '2', '10.00');

    fixture.componentInstance.confirmOrder();

    expect(orders.placeOrder).not.toHaveBeenCalled();
  });

  it('blocks a BUY that costs more than the cash: the shortfall is shown, Review is disabled, nothing is sent', () => {
    const fixture = render();
    fill(fixture, 'BUY', '200', '10.00'); // 2,000 against 1,000 cash

    expect(el(fixture, 'ticket-block')?.textContent).toContain('Not enough cash');
    expect(el(fixture, 'ticket-block')?.textContent).toContain('₹2,000.00');
    expect(el(fixture, 'ticket-block')?.textContent).toContain('₹1,000.00');
    expect((el(fixture, 'ticket-submit') as HTMLButtonElement).disabled).toBe(true);

    // Even called directly, a blocked order opens no dialog and cannot be sent.
    fixture.componentInstance.submitOrder();
    fixture.detectChanges();
    expect(el(fixture, 'ticket-review')).toBeNull();
    fixture.componentInstance.confirmOrder();
    expect(orders.placeOrder).not.toHaveBeenCalled();
  });

  describe('the review dialog', () => {
    function openReview(fixture: any, side: 'BUY' | 'SELL', quantity: string, price: string) {
      fill(fixture, side, quantity, price);
      fixture.componentInstance.submitOrder();
      fixture.detectChanges();
    }

    it('is a modal dialog named by its title, which states the order', () => {
      const fixture = render();
      openReview(fixture, 'BUY', '2', '10.00');

      const dialog = el(fixture, 'ticket-review')!;
      expect(dialog.getAttribute('role')).toBe('dialog');
      expect(dialog.getAttribute('aria-modal')).toBe('true');
      expect(dialog.getAttribute('aria-labelledby')).toBe('ticket-review-title');
      expect(fixture.nativeElement.querySelector('#ticket-review-title').textContent).toContain('Buy 2 TCS.NS');
    });

    it('shows the cash before and after a buy', () => {
      const fixture = render();
      openReview(fixture, 'BUY', '2', '10.00'); // 1,000 - 20

      expect(el(fixture, 'ticket-review-cash')?.textContent).toContain('1,000.00');
      expect(el(fixture, 'ticket-review-cash')?.textContent).toContain('980.00');
    });

    it('shows the holding before and after a sell, and labels the button for the side', () => {
      const fixture = render();
      openReview(fixture, 'SELL', '20', '10.00'); // 50 held

      expect(el(fixture, 'ticket-review-holding')?.textContent).toContain('50');
      expect(el(fixture, 'ticket-review-holding')?.textContent).toContain('30');
      expect(el(fixture, 'ticket-confirm')?.textContent).toContain('Place sell order');
    });

    it('puts the currency symbol inside the limit price box: ₹ for a rupee share', () => {
      const fixture = render();
      fill(fixture, 'BUY', '1', '10.00');

      expect(el(fixture, 'ticket-price-symbol')?.textContent?.trim()).toBe('₹');
    });

    describe('a dollar instrument against rupee cash', () => {
      beforeEach(() => {
        quotes.getQuotes.mockReturnValue(of([{ symbol: 'MSFT', lastPrice: 10, currency: 'USD', stale: false, quoteAsOf: '2026-10-01T09:00:00Z' }]));
      });
      function fillDollar(fixture: any, side: 'BUY' | 'SELL', quantity: string, price: string) {
        fixture.componentInstance.ticketForm.setValue({ symbol: 'MSFT', side, quantity, price });
        fixture.detectChanges();
      }

      it('shows $ inside the limit price box for a dollar share', () => {
        const fixture = render();
        fillDollar(fixture, 'BUY', '1', '10.00');

        expect(el(fixture, 'ticket-price-symbol')?.textContent?.trim()).toBe('$');
      });

      it('shows the dollar amount and what it costs in rupees at the buy rate', () => {
        const fixture = render();
        fillDollar(fixture, 'BUY', '1', '10.00');

        expect(el(fixture, 'ticket-estimate')?.textContent).toContain('$10.00');
        expect(el(fixture, 'ticket-estimate')?.textContent).toContain('10.00');
        // 10 dollars x 80.4 (the 80 mid rate plus the 0.5% margin) = 804 rupees.
        expect(el(fixture, 'ticket-estimate-base')?.textContent).toContain('₹804.00');
        expect(el(fixture, 'ticket-estimate-base')?.textContent).toContain('₹80.40');
        expect(fixture.componentInstance.cashAfter()).toBe(196);
      });

      it('uses the lower sell rate for a sell', () => {
        const fixture = render();
        fillDollar(fixture, 'SELL', '1', '10.00');

        expect(el(fixture, 'ticket-estimate-base')?.textContent).toContain('₹796.00');
      });

      it('blocks a buy that fits in dollars but not in rupees', () => {
        const fixture = render();
        fillDollar(fixture, 'BUY', '2', '10.00');   // 20 dollars, but 1,608 rupees against 1,000 in cash

        expect(el(fixture, 'ticket-block')?.textContent).toContain('Not enough cash');
        expect(el(fixture, 'ticket-block')?.textContent).toContain('₹1,608.00');
      });

      it('works out the largest quantity from the rupee cost', () => {
        const fixture = render();
        fillDollar(fixture, 'BUY', '1', '10.00');

        // floor(1,000 / (10 x 80.4)) = 1
        expect(fixture.componentInstance.maxQuantity()).toBe(1);
      });

      it('carries the conversion into the review dialog', () => {
        const fixture = render();
        fillDollar(fixture, 'BUY', '1', '10.00');
        fixture.componentInstance.submitOrder();
        fixture.detectChanges();

        expect(el(fixture, 'ticket-review-converted')?.textContent).toContain('₹804.00');
        expect(el(fixture, 'ticket-review-cash')?.textContent).toContain('196.00');
      });

      it('cannot place a dollar order while the rates are unavailable', () => {
        quotes.getFxRates.mockReturnValue(throwError(() => ({ status: 503 })));
        const fixture = render();
        fillDollar(fixture, 'BUY', '1', '10.00');

        expect(el(fixture, 'ticket-block')?.textContent).toContain('No exchange rate');
      });

      it('leaves a rupee instrument alone', () => {
        const fixture = render();
        fill(fixture, 'BUY', '2', '10.00');

        expect(el(fixture, 'ticket-estimate-base')).toBeNull();
        expect(el(fixture, 'ticket-block')).toBeNull();
      });
    });

    it('closes on Escape and keeps what was typed', () => {
      const fixture = render();
      openReview(fixture, 'BUY', '2', '10.00');

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      fixture.detectChanges();

      expect(el(fixture, 'ticket-review')).toBeNull();
      expect(fixture.componentInstance.ticketForm.controls.quantity.value).toBe('2');
      expect(orders.placeOrder).not.toHaveBeenCalled();
    });

    it('closes on a click on the backdrop, but not on a click inside the dialog', () => {
      const fixture = render();
      openReview(fixture, 'BUY', '2', '10.00');

      el(fixture, 'ticket-review')!.click();
      fixture.detectChanges();
      expect(el(fixture, 'ticket-review')).toBeTruthy();

      el(fixture, 'ticket-review-backdrop')!.click();
      fixture.detectChanges();
      expect(el(fixture, 'ticket-review')).toBeNull();
    });

    it('cannot place an order that became unaffordable when the cash was re-read on opening', () => {
      orders.getBalance
        .mockReturnValueOnce(of(balance))                    // on load: 1,000
        .mockReturnValueOnce(of({ ...balance, cashBalance: 5 })); // re-read on Review: 5
      const fixture = render();
      openReview(fixture, 'BUY', '2', '10.00'); // 20

      expect(el(fixture, 'ticket-review-blocked')?.textContent).toContain('Not enough cash');
      expect((el(fixture, 'ticket-confirm') as HTMLButtonElement).disabled).toBe(true);
      fixture.componentInstance.confirmOrder();
      expect(orders.placeOrder).not.toHaveBeenCalled();
    });

    it('repeats the price warning in the dialog', () => {
      const fixture = render();
      openReview(fixture, 'BUY', '1', '5.00'); // 50% below the 10.00 market

      expect(el(fixture, 'ticket-review-price-warning')?.textContent).toContain('below the market price');
    });
  });

  it('does not block a BUY within the cash', () => {
    const fixture = render();
    fill(fixture, 'BUY', '100', '10.00'); // exactly the 1,000 available

    fixture.componentInstance.submitOrder();
    fixture.detectChanges();

    expect(el(fixture, 'ticket-block')).toBeNull();
    expect((el(fixture, 'ticket-confirm') as HTMLButtonElement).disabled).toBe(false);
  });

  it('blocks a SELL of more shares than are held, and allows one within the holding', () => {
    const fixture = render();

    fill(fixture, 'SELL', '60', '10.00'); // 50 held
    expect(el(fixture, 'ticket-block')?.textContent).toContain('you hold 50 TCS.NS');

    fill(fixture, 'SELL', '50', '10.00');
    expect(el(fixture, 'ticket-block')).toBeNull();
  });

  it('blocks a SELL of a symbol that is not held at all', () => {
    const fixture = render();
    fixture.componentInstance.ticketForm.setValue({ symbol: 'MSFT', side: 'SELL', quantity: '1', price: '400.00' });
    fixture.detectChanges();

    expect(el(fixture, 'ticket-block')?.textContent).toContain('you hold 0 MSFT');
  });

  it('does not block a BUY when the cash could not be read: the server decides then', () => {
    orders.getBalance.mockReturnValue(throwError(() => ({ status: 500 })));
    const fixture = render();

    fill(fixture, 'BUY', '100000', '10.00');

    expect(el(fixture, 'ticket-block')).toBeNull();
  });

  it('does not block a SELL when the holdings could not be read: the server decides then', () => {
    orders.getPositions.mockReturnValue(throwError(() => ({ status: 500 })));
    const fixture = render();

    fill(fixture, 'SELL', '100000', '10.00');

    expect(el(fixture, 'ticket-block')).toBeNull();
  });

  it('shows the cached market price for the symbol', () => {
    const fixture = render();
    fill(fixture, 'BUY', '1', '10.00');

    expect(el(fixture, 'ticket-market-price')?.textContent).toContain('₹');
    expect(el(fixture, 'ticket-market-price')?.textContent).toContain('10.00');
  });

  it('says there is no recent price for a symbol the poller does not track, and does not check the price', () => {
    const fixture = render();
    fixture.componentInstance.ticketForm.setValue({ symbol: 'MSFT', side: 'BUY', quantity: '1', price: '1.00' });
    fixture.detectChanges();

    expect(el(fixture, 'ticket-market-price')).toBeNull();
    expect(el(fixture, 'ticket-no-price')?.textContent).toContain('MSFT');
    expect(el(fixture, 'ticket-price-warning')).toBeNull();
  });

  it('warns when a BUY limit is far below the market price, because it will not fill', () => {
    const fixture = render();
    fill(fixture, 'BUY', '1', '5.00'); // 50% below the 10.00 market

    expect(el(fixture, 'ticket-price-warning')?.textContent).toContain('50% below the market price');
    expect(el(fixture, 'ticket-price-warning')?.textContent).toContain('most likely be rejected');
  });

  it('warns when a BUY limit is far above the market price, because it reserves far more cash than needed', () => {
    const fixture = render();
    fill(fixture, 'BUY', '1', '50.00'); // 400% above

    expect(el(fixture, 'ticket-price-warning')?.textContent).toContain('above the market price');
    expect(el(fixture, 'ticket-price-warning')?.textContent).toContain('reserved');
  });

  it('warns when a SELL limit is far above the market price', () => {
    const fixture = render();
    fill(fixture, 'SELL', '1', '20.00');

    expect(el(fixture, 'ticket-price-warning')?.textContent).toContain('A sell only fills at or above your limit');
  });

  it('does not warn for a limit within 10% of the market price', () => {
    const fixture = render();
    fill(fixture, 'BUY', '1', '10.90');

    expect(el(fixture, 'ticket-price-warning')).toBeNull();
  });

  it('the price warning is a warning, not a block: the order can still be confirmed', () => {
    const fixture = render();
    fill(fixture, 'BUY', '1', '5.00');
    fixture.componentInstance.submitOrder();
    fixture.detectChanges();

    el(fixture, 'ticket-confirm')!.click();

    expect(orders.placeOrder).toHaveBeenCalledTimes(1);
  });

  it('offers the largest affordable quantity for a BUY, and fills it in on request', () => {
    const fixture = render();
    fill(fixture, 'BUY', '1', '30.00'); // floor(1000 / 30) = 33

    expect(el(fixture, 'ticket-max')?.textContent).toContain('Max 33');
    el(fixture, 'ticket-use-max')!.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.ticketForm.controls.quantity.value).toBe('33');
    expect(el(fixture, 'ticket-block')).toBeNull();
  });

  it('offers the holding as the largest quantity for a SELL', () => {
    const fixture = render();
    fill(fixture, 'SELL', '1', '10.00');

    expect(el(fixture, 'ticket-max')?.textContent).toContain('Max 50');
  });

  it('re-reads cash, holdings and prices when the order is reviewed, so the checks are current', () => {
    const fixture = render();
    fill(fixture, 'BUY', '1', '10.00');
    const balanceReads = orders.getBalance.mock.calls.length;
    const holdingReads = orders.getPositions.mock.calls.length;
    const quoteReads = quotes.getQuotes.mock.calls.length;

    fixture.componentInstance.submitOrder();

    expect(orders.getBalance.mock.calls.length).toBe(balanceReads + 1);
    expect(orders.getPositions.mock.calls.length).toBe(holdingReads + 1);
    expect(quotes.getQuotes.mock.calls.length).toBe(quoteReads + 1);
  });

  it('a refusal closes the summary and shows the reason', () => {
    orders.placeOrder.mockReturnValue(throwError(() => ({ status: 400, error: { errorCode: 'ORD-400' } })));
    const fixture = render();
    fill(fixture, 'BUY', '2', '10.00');
    fixture.componentInstance.submitOrder();
    fixture.detectChanges();

    el(fixture, 'ticket-confirm')!.click();
    fixture.detectChanges();

    expect(el(fixture, 'ticket-review')).toBeNull();
    expect(el(fixture, 'ticket-error')?.textContent).toContain('not enough cash');
  });

  describe('the instrument picker', () => {
    function type(fixture: any, text: string) {
      const input = el(fixture, 'ticket-symbol') as HTMLInputElement;
      input.dispatchEvent(new Event('focus'));
      input.value = text;
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      return input;
    }
    const options = (fixture: any) =>
      [...fixture.nativeElement.querySelectorAll('[data-testid="ticket-symbol-option"]')].map((o: any) => o.textContent);

    it('is a combobox over the catalogue, loaded from our own API', () => {
      const fixture = render();

      const input = el(fixture, 'ticket-symbol')!;
      expect(quotes.getInstruments).toHaveBeenCalled();
      expect(input.getAttribute('role')).toBe('combobox');
      expect(input.getAttribute('aria-controls')).toBe('ticket-symbol-listbox');
    });

    it('typing part of a symbol lists the matching instruments, symbol matches first', () => {
      const fixture = render();
      type(fixture, 'tcs');

      expect(options(fixture)[0]).toContain('TCS.NS');
      expect(options(fixture).some((o: string) => o.includes('MSFT'))).toBe(false);
    });

    it('typing part of a company name finds its instruments too', () => {
      const fixture = render();
      type(fixture, 'tata');

      const listed = options(fixture);
      expect(listed.some((o: string) => o.includes('TCS.NS'))).toBe(true);
      expect(listed.some((o: string) => o.includes('TATAMOTORS.NS'))).toBe(true);
    });

    it('each option shows the name, the currency and the cached price where there is one', () => {
      const fixture = render();
      type(fixture, 'tcs');

      expect(options(fixture)[0]).toContain('Tata Consultancy Services Limited');
      expect(options(fixture)[0]).toContain('INR');
      expect(options(fixture)[0]).toContain('10.00');
    });

    it('clicking an option selects it and closes the list', () => {
      const fixture = render();
      type(fixture, 'micro');

      (fixture.nativeElement.querySelector('[data-testid="ticket-symbol-option"]') as HTMLElement).click();
      fixture.detectChanges();

      expect(fixture.componentInstance.ticketForm.controls.symbol.value).toBe('MSFT');
      expect(el(fixture, 'ticket-symbol-listbox')).toBeNull();
      expect(el(fixture, 'ticket-instrument-name')?.textContent).toContain('Microsoft Corporation');
    });

    it('arrow keys move through the options and Enter picks one without submitting the form', () => {
      const fixture = render();
      const input = type(fixture, 'tata');

      const down = new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true });
      input.dispatchEvent(down);
      fixture.detectChanges();
      expect(input.getAttribute('aria-activedescendant')).toBe('ticket-symbol-option-0');

      const enter = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });
      input.dispatchEvent(enter);
      fixture.detectChanges();

      expect(enter.defaultPrevented).toBe(true);
      expect(['TCS.NS', 'TATAMOTORS.NS']).toContain(fixture.componentInstance.ticketForm.controls.symbol.value);
      expect(fixture.componentInstance.reviewing()).toBe(false);
    });

    it('Escape closes the list', () => {
      const fixture = render();
      const input = type(fixture, 'tcs');
      expect(el(fixture, 'ticket-symbol-listbox')).toBeTruthy();

      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      fixture.detectChanges();

      expect(el(fixture, 'ticket-symbol-listbox')).toBeNull();
    });

    it('says when nothing matches', () => {
      const fixture = render();
      type(fixture, 'zzz');

      expect(el(fixture, 'ticket-symbol-none')?.textContent).toContain('ZZZ');
    });

    it('a symbol that is not in the catalogue cannot be submitted, even in the right format', () => {
      const fixture = render();
      fixture.componentInstance.ticketForm.setValue({ symbol: 'NOPE', side: 'BUY', quantity: '1', price: '10.00' });
      fixture.componentInstance.submitOrder();
      fixture.detectChanges();

      expect(fixture.componentInstance.ticketForm.controls.symbol.errors).toEqual({ unknownInstrument: true });
      expect(fixture.nativeElement.querySelector('#ticket-symbol-hint').textContent).toContain('Choose an instrument from the list');
      expect(el(fixture, 'ticket-review')).toBeNull();
    });

    it('a listed symbol typed in lower case is accepted', () => {
      const fixture = render();
      fixture.componentInstance.ticketForm.setValue({ symbol: 'tcs.ns', side: 'BUY', quantity: '1', price: '10.00' });

      expect(fixture.componentInstance.ticketForm.controls.symbol.valid).toBe(true);
    });

    it('falls back to free text with the format check when the catalogue cannot be read', () => {
      quotes.getInstruments.mockReturnValue(throwError(() => ({ status: 503 })));
      const fixture = render();
      type(fixture, 'tcs');

      expect(el(fixture, 'ticket-symbol-listbox')).toBeNull();
      fixture.componentInstance.ticketForm.setValue({ symbol: 'ANYCO', side: 'BUY', quantity: '1', price: '10.00' });
      expect(fixture.componentInstance.ticketForm.controls.symbol.valid).toBe(true);
      fixture.componentInstance.ticketForm.setValue({ symbol: 'BAD!!', side: 'BUY', quantity: '1', price: '10.00' });
      expect(fixture.componentInstance.ticketForm.controls.symbol.valid).toBe(false);
    });
  });

  describe('a closed market', () => {
    const closedCatalogue = catalogue.map((c) => c.symbol === 'TCS.NS'
      ? { ...c, market: 'NSE', tradingStatus: 'CLOSED', nextOpen: '2026-10-05T03:45:00Z' }
      : { ...c, market: 'NSE', tradingStatus: 'OPEN', nextOpen: null });

    it('does not block the order: it says it will be queued and when the market opens, and Review is enabled', () => {
      quotes.getInstruments.mockReturnValue(of(closedCatalogue));
      const fixture = render();
      fill(fixture, 'BUY', '1', '10.00');

      expect(el(fixture, 'ticket-block')).toBeNull();
      expect(el(fixture, 'ticket-queued')?.textContent).toContain('TCS.NS trades on the NSE, which is closed now. Your order will be queued');
      expect((el(fixture, 'ticket-submit') as HTMLButtonElement).disabled).toBe(false);
      fixture.componentInstance.submitOrder();
      fixture.componentInstance.confirmOrder();
      expect(orders.placeOrder).toHaveBeenCalled();
    });

    it('marks a closed instrument in the picker', () => {
      quotes.getInstruments.mockReturnValue(of(closedCatalogue));
      const fixture = render();
      const input = el(fixture, 'ticket-symbol') as HTMLInputElement;
      input.dispatchEvent(new Event('focus'));
      input.value = 'ta';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      const options = [...fixture.nativeElement.querySelectorAll('[data-testid="ticket-symbol-option"]')] as HTMLElement[];
      const tcs = options.find((o) => o.textContent!.includes('TCS.NS'))!;
      const motors = options.find((o) => o.textContent!.includes('TATAMOTORS.NS'))!;
      expect(tcs.querySelector('[data-testid="ticket-symbol-closed"]')).toBeTruthy();
      expect(motors.querySelector('[data-testid="ticket-symbol-closed"]')).toBeNull();
    });

    it('Review re-reads whether the market is open', () => {
      const fixture = render();
      fill(fixture, 'BUY', '1', '10.00');
      const before = quotes.getInstruments.mock.calls.length;

      fixture.componentInstance.submitOrder();

      expect(quotes.getInstruments.mock.calls.length).toBe(before + 1);
    });

    it('a refusal from the API (the market closed meanwhile) reads plainly', () => {
      orders.placeOrder.mockReturnValue(throwError(() => ({ status: 409, error: { errorCode: 'MKT-409', message: 'internal' } })));
      const fixture = render();
      fill(fixture, 'BUY', '1', '10.00');
      fixture.componentInstance.submitOrder();
      fixture.componentInstance.confirmOrder();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('That market is closed now, so the order was not placed.');
    });
  });

  describe('after an order is placed', () => {
    afterEach(() => vi.useRealTimers());

    function place(fixture: any) {
      fill(fixture, 'BUY', '2', '10.00');
      fixture.componentInstance.submitOrder();
      fixture.componentInstance.confirmOrder();
      fixture.detectChanges();
    }
    const history = (status: string, over: any = {}) =>
      of([{ orderId: 'ORD-1', accountId: 42, symbol: 'TCS.NS', side: 'BUY', quantity: 2, price: 10, status, createdOn: '2026-10-02T05:00:00Z', ...over }]);

    it('follows the order until it fills, then re-reads cash and holdings and says so', () => {
      vi.useFakeTimers();
      orders.getOrderHistory
        .mockReturnValueOnce(history('NEW'))
        .mockReturnValue(history('FILLED', { executedPrice: 9.95 }));
      const fixture = render();
      place(fixture);
      const balanceCalls = orders.getBalance.mock.calls.length;
      const positionCalls = orders.getPositions.mock.calls.length;

      vi.advanceTimersByTime(2000); // still NEW: nothing re-read yet
      expect(orders.getPositions.mock.calls.length).toBe(positionCalls);

      vi.advanceTimersByTime(2000); // FILLED
      fixture.detectChanges();
      expect(orders.getBalance.mock.calls.length).toBe(balanceCalls + 1);
      expect(orders.getPositions.mock.calls.length).toBe(positionCalls + 1);
      expect(el(fixture, 'ticket-success')?.textContent).toContain('Order ORD-1 FILLED at 9.95');

      vi.advanceTimersByTime(20000); // and then it stops asking
      expect(orders.getOrderHistory).toHaveBeenCalledTimes(2);
    });

    it('says why when the order is rejected, and that no cash moved', () => {
      vi.useFakeTimers();
      orders.getOrderHistory.mockReturnValue(history('REJECTED', { rejectionReason: 'PRICE_NOT_MET' }));
      const fixture = render();
      place(fixture);

      vi.advanceTimersByTime(2000);
      fixture.detectChanges();

      expect(el(fixture, 'ticket-success')?.textContent).toContain('REJECTED: Price not met');
      expect(el(fixture, 'ticket-success')?.textContent).toContain('No cash moved');
    });

    it('gives up after 30 seconds, re-reading once, if the order has still not settled', () => {
      vi.useFakeTimers();
      orders.getOrderHistory.mockReturnValue(history('NEW'));
      const fixture = render();
      place(fixture);
      const positionCalls = orders.getPositions.mock.calls.length;

      vi.advanceTimersByTime(60000);

      expect(orders.getOrderHistory).toHaveBeenCalledTimes(15);
      expect(orders.getPositions.mock.calls.length).toBe(positionCalls + 1);
    });
  });
});
