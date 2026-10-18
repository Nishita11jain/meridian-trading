import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { OrderService } from './order.service';
import { OrdersService as GeneratedOrdersService } from '../../clients/trade-client/api/orders.service';
import { AccountsService as GeneratedAccountsService } from '../../clients/trade-client/api/accounts.service';

describe('OrderService', () => {
  let service: OrderService;
  let orders: { placeOrder: ReturnType<typeof vi.fn>; cancelOrder: ReturnType<typeof vi.fn> };
  let accounts: {
    getOrders: ReturnType<typeof vi.fn>;
    getAccount: ReturnType<typeof vi.fn>;
    getBalance: ReturnType<typeof vi.fn>;
    getPositions: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    orders = { placeOrder: vi.fn().mockReturnValue(of({})), cancelOrder: vi.fn().mockReturnValue(of({})) };
    accounts = {
      getOrders: vi.fn().mockReturnValue(of([])),
      getAccount: vi.fn().mockReturnValue(of({})),
      getBalance: vi.fn().mockReturnValue(of({})),
      getPositions: vi.fn().mockReturnValue(of([]))
    };

    TestBed.configureTestingModule({
      providers: [
        OrderService,
        { provide: GeneratedOrdersService, useValue: orders },
        { provide: GeneratedAccountsService, useValue: accounts }
      ]
    });

    service = TestBed.inject(OrderService);
  });

  it('places an order with every field of the contract request, including the idempotency key', () => {
    service.placeOrder(42, 'AAPL', 'BUY', 10, 25.5, 'key-12345678').subscribe();

    expect(orders.placeOrder).toHaveBeenCalledWith({
      accountId: 42,
      symbol: 'AAPL',
      side: 'BUY',
      quantity: 10,
      price: 25.5,
      idempotencyKey: 'key-12345678'
    });
  });

  it('reads order history for the account it is given', () => {
    service.getOrderHistory(42).subscribe();

    expect(accounts.getOrders).toHaveBeenCalledWith(42);
  });

  it('reads the account, balance and positions for the account it is given', () => {
    service.getAccount(42).subscribe();
    service.getBalance(42).subscribe();
    service.getPositions(42).subscribe();

    expect(accounts.getAccount).toHaveBeenCalledWith(42);
    expect(accounts.getBalance).toHaveBeenCalledWith(42);
    expect(accounts.getPositions).toHaveBeenCalledWith(42);
  });

  it('cancels an order by its id', () => {
    service.cancelOrder('6f2b1c2a-6a1e-4a4f-9c0d-2f7a1b3c4d5e').subscribe();

    expect(orders.cancelOrder).toHaveBeenCalledWith('6f2b1c2a-6a1e-4a4f-9c0d-2f7a1b3c4d5e');
  });
});
