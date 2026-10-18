import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

// Import from generated client modules (not the barrel)
import { OrdersService as GeneratedOrdersService } from '../../clients/trade-client/api/orders.service';
import { AccountsService as GeneratedAccountsService } from '../../clients/trade-client/api/accounts.service';
import {
  PlaceOrderRequest,
  OrderResponse,
  OrderHistoryEntry,
  AccountResponse,
  BalanceResponse,
  PositionResponse
} from '../../clients/trade-client/model/models';

/**
 * Wrapper service around the generated Trade API client.
 * 
 * Responsibilities:
 * - Provide order and account operations
 * - Handle error mapping and display
 * - Simplify the generated client interface
 * 
 * Generated clients are in src/app/clients/trade-client; this service wraps them.
 * Do not edit the generated clients. Add app-specific logic here.
 */
@Injectable({
  providedIn: 'root'
})
export class OrderService {
  constructor(
    private generatedOrdersClient: GeneratedOrdersService,
    private generatedAccountsClient: GeneratedAccountsService
  ) {}

  /**
   * Place a new order.
   * 
   * Business rules enforced by Trade API:
   * 1. Account must exist
   * 2. Account must be ACTIVE
   * 3. Instrument must exist and be tradable
   * 4. Quantity must be > 0
   * 5. Price must be > 0
   * 6. BUY: cash balance must be at least quantity × price
   * 7. SELL: held quantity must be at least order quantity
   * 8. idempotencyKey must be unique
   * 
   * @param accountId Numeric trading account ID (from JWT token)
   * @param symbol Instrument symbol (e.g., "AAPL")
   * @param side "BUY" or "SELL"
   * @param quantity Whole number > 0
   * @param price Limit price > 0 with at most 2 decimal places
   * @param idempotencyKey Unique identifier for this order request (UUID recommended)
   * @returns Observable<OrderResponse> with orderId and status
   */
  placeOrder(
    accountId: number,
    symbol: string,
    side: 'BUY' | 'SELL',
    quantity: number,
    price: number,
    idempotencyKey: string
  ): Observable<OrderResponse> {
    const request: PlaceOrderRequest = {
      accountId,
      symbol,
      side,
      quantity,
      price,
      idempotencyKey
    };
    return this.generatedOrdersClient.placeOrder(request);
  }

  /**
   * Cancel an existing order.
   * Only orders with status NEW can be cancelled.
   * 
   * @param orderId UUID of the order to cancel (string, not accountId)
   * @returns Observable<OrderResponse>
   */
  cancelOrder(orderId: string): Observable<OrderResponse> {
    return this.generatedOrdersClient.cancelOrder(orderId);
  }

  /**
   * Get order history for an account.
   * Returns all orders (including rejected and cancelled), newest first.
   * 
   * @param accountId Numeric trading account ID
   * @returns Observable<OrderHistoryEntry[]>
   */
  getOrderHistory(accountId: number): Observable<OrderHistoryEntry[]> {
    return this.generatedAccountsClient.getOrders(accountId);
  }

  /**
   * Get account details (name, status, created date).
   * 
   * @param accountId Numeric trading account ID
   * @returns Observable<AccountResponse>
   */
  getAccount(accountId: number): Observable<AccountResponse> {
    return this.generatedAccountsClient.getAccount(accountId);
  }

  /**
   * Get cash balance for an account.
   * 
   * @param accountId Numeric trading account ID
   * @returns Observable<BalanceResponse> with balance in currency
   */
  getBalance(accountId: number): Observable<BalanceResponse> {
    return this.generatedAccountsClient.getBalance(accountId);
  }

  /**
   * Get open positions (holdings) for an account.
   * 
   * @param accountId Numeric trading account ID
   * @returns Observable<PositionResponse[]> with symbol, quantity, average cost
   */
  getPositions(accountId: number): Observable<PositionResponse[]> {
    return this.generatedAccountsClient.getPositions(accountId);
  }
}
