import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../core/auth/auth.service';
import { OrderService } from '../../core/orders/order.service';
import { messageFor } from '../../core/errors/error-messages';
import { rejectionReasonText } from '../../core/orders/rejection-reasons';
import { OrderHistoryEntry } from '../../clients/trade-client/model/orderHistoryEntry';
import { OrderStatus } from '../../clients/trade-client/model/orderStatus';
import { AppShell } from '../../shared/layout/app-shell';
import { SearchField } from '../../shared/search/search-field';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { createMatcher } from '../../core/search/text-matcher';

const POLL_INTERVAL_MS = 4000;
const MAX_POLL_ATTEMPTS = 20;

/** The status tabs: All, or one group of orders. Working is the NEW orders, which includes queued ones. */
type BlotterFilter = 'ALL' | 'WORKING' | 'FILLED' | 'REJECTED' | 'CANCELLED';

const FILTER_STATUS: Record<BlotterFilter, OrderStatus | null> = {
  ALL: null,
  WORKING: OrderStatus.New,
  FILLED: OrderStatus.Filled,
  REJECTED: OrderStatus.Rejected,
  CANCELLED: OrderStatus.Cancelled
};

const STATUS_BADGE: Record<OrderStatus, { bg: string; fg: string }> = {
  [OrderStatus.New]: { bg: 'var(--info-bg)', fg: 'var(--info)' },
  [OrderStatus.Filled]: { bg: 'var(--up-bg)', fg: 'var(--up)' },
  [OrderStatus.Rejected]: { bg: 'var(--down-bg)', fg: 'var(--down)' },
  [OrderStatus.Cancelled]: { bg: 'var(--surface-2)', fg: 'var(--muted)' }
};

/**
 * SEC2-693: Blotter (standalone, Angular 21 style)
 *
 * Every order for the account, newest first, rejections included, with a status badge
 * per row and a bounded re-read while anything is still NEW.
 */
@Component({
  selector: 'app-blotter',
  imports: [CommonModule, AppShell, SearchField, ConfirmDialog],
  template: `
    <app-shell title="Blotter">
      <section class="lt-card lt-card-pad lt-appear" aria-labelledby="blotter-title">
        <h2 id="blotter-title" class="sr-only">Orders</h2>

        <!-- One toolbar: status filter, search and refresh, with when the list was last read. -->
        <div class="lt-card-head" data-testid="blotter-toolbar">
          <div class="lt-seg" role="tablist" aria-label="Filter orders by status">
            <button type="button" *ngFor="let tab of tabs" role="tab"
              [attr.data-testid]="'blotter-tab-' + tab.key" [attr.aria-selected]="filter() === tab.key"
              (click)="filter.set(tab.key)">{{ tab.label }} <span class="lt-seg__count">{{ counts()[tab.key] }}</span></button>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <app-search-field testId="blotter-search" label="Search symbol or order ID" placeholder="Search symbol or order ID"
              regexPlaceholder="Regex, e.g. ^(TCS|INFY)" width="260px"
              [(query)]="query" [(regex)]="queryRegex"></app-search-field>
            <span class="text-[11px] tabular-nums" style="color: var(--muted)" *ngIf="lastUpdated() as at" data-testid="blotter-updated">
              Updated {{ at | date:'HH:mm:ss' }}
            </span>
            <button type="button" class="lt-tool" [class.is-spinning]="loading()" data-testid="blotter-refresh"
              [disabled]="loading()" [attr.aria-label]="loading() ? 'Refreshing orders' : 'Refresh orders'"
              [attr.title]="loading() ? 'Refreshing…' : 'Refresh'" (click)="onRefreshClick()">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M20 11a8 8 0 1 0-2.3 5.7" stroke-linecap="round"></path><path d="M20 4v7h-7" stroke-linecap="round" stroke-linejoin="round"></path>
              </svg>
            </button>
          </div>
        </div>

        <div class="lt-alert lt-alert-down mb-3" data-testid="blotter-error" role="alert" *ngIf="errorMessage()">{{ errorMessage() }}</div>

        <div class="lt-alert lt-alert-warn mb-3" data-testid="blotter-polling-stopped" role="status" *ngIf="autoRefreshStopped()">
          Some orders are still working. Automatic refresh has stopped, so press Refresh to check again.
        </div>

        <div class="lt-empty" data-testid="blotter-loading" *ngIf="!loaded() && !errorMessage()">
          <p>Loading your orders…</p>
        </div>

        <div class="lt-empty" data-testid="blotter-empty" *ngIf="loaded() && !errorMessage() && orders().length === 0">
          <p>No orders yet.</p>
        </div>

        <p class="lt-empty" data-testid="blotter-no-match" *ngIf="orders().length > 0 && visibleOrders().length === 0">No orders match.</p>

        <div class="overflow-x-auto" *ngIf="orders().length > 0 && visibleOrders().length > 0">
          <table class="lt-table" data-testid="blotter-table">
            <caption class="sr-only">Orders for your account, newest first</caption>
            <thead>
              <tr>
                <th scope="col">Symbol</th>
                <th scope="col">Side</th>
                <th scope="col" class="text-right">Qty</th>
                <th scope="col" class="text-right">Limit</th>
                <th scope="col" class="text-right">Executed</th>
                <th scope="col">Status</th>
                <th scope="col" class="text-right">Placed</th>
                <th scope="col" class="text-right"><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let order of visibleOrders(); trackBy: trackByOrder" data-testid="blotter-row">
                <td class="whitespace-nowrap">
                  <span class="font-extrabold">{{ order.symbol }}</span>
                  <span class="ml-2 text-[11px] tabular-nums" style="color: var(--muted)" [attr.title]="order.orderId">{{ shortId(order.orderId) }}</span>
                </td>
                <td>
                  <span class="lt-side" [class.lt-side-buy]="order.side === 'BUY'" [class.lt-side-sell]="order.side === 'SELL'">
                    <span aria-hidden="true">{{ order.side === 'BUY' ? '↗' : '↘' }}</span>{{ order.side }}
                  </span>
                </td>
                <td class="text-right tabular-nums">{{ order.quantity }}</td>
                <td class="text-right tabular-nums">{{ order.price | number:'1.2-2' }}</td>
                <td class="text-right tabular-nums" [style.color]="order.executedPrice != null ? null : 'var(--muted)'">
                  {{ order.executedPrice != null ? (order.executedPrice | number:'1.2-2') : '—' }}
                </td>
                <td>
                  <span class="lt-dot" [style.color]="statusClass(order.status).fg" data-testid="blotter-status">{{ order.status }}</span>
                  <span class="ml-2 text-[11px] italic" style="color: var(--warn)" data-testid="blotter-still-working" *ngIf="isWorking(order.status)">still working</span>
                  <span class="mt-0.5 block text-[11px]" style="color: var(--muted)" data-testid="blotter-reason"
                    *ngIf="order.status === 'REJECTED'">{{ reasonText(order.rejectionReason) }}</span>
                </td>
                <td class="whitespace-nowrap text-right text-[12px] tabular-nums" style="color: var(--muted)" [attr.title]="order.createdOn | date:'medium'">
                  {{ order.createdOn | date:'d MMM, HH:mm' }}
                </td>
                <td class="text-right">
                  <button type="button" class="lt-btn lt-btn-ghost lt-btn-sm" style="color: var(--down)"
                    data-testid="blotter-cancel" *ngIf="isWorking(order.status)"
                    [disabled]="cancellingId() === order.orderId"
                    [attr.aria-label]="'Cancel order ' + order.orderId"
                    (click)="onCancelClick(order)">{{ cancellingId() === order.orderId ? 'Cancelling…' : 'Cancel' }}</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      @if (confirmingCancel(); as order) {
        <app-confirm-dialog testId="blotter-cancel-dialog" eyebrow="Cancel order" tone="danger"
          [title]="'Cancel ' + order.side.toLowerCase() + ' ' + order.quantity + ' ' + order.symbol + '?'"
          [body]="'The order at ' + (order.price | number:'1.2-2') + ' is withdrawn and any reserved cash or shares are released. This cannot be undone.'"
          confirmLabel="Cancel order" cancelLabel="Keep order"
          (confirmed)="confirmCancel(order)" (dismissed)="confirmingCancel.set(null)"></app-confirm-dialog>
      }
    </app-shell>
  `
})
export class Blotter implements OnInit, OnDestroy {
  private readonly authService = inject(AuthService);
  private readonly orderService = inject(OrderService);

  private readonly accountId = signal<number | null>(null);
  readonly orders = signal<OrderHistoryEntry[]>([]);
  /** The status tab and the search text, both applied in the page: the blotter already holds every order. */
  readonly tabs: { key: BlotterFilter; label: string }[] = [
    { key: 'ALL', label: 'All' },
    { key: 'WORKING', label: 'Working' },
    { key: 'FILLED', label: 'Filled' },
    { key: 'REJECTED', label: 'Rejected' },
    { key: 'CANCELLED', label: 'Cancelled' }
  ];
  readonly filter = signal<BlotterFilter>('ALL');
  readonly query = signal('');
  /** True when the search box is in regular-expression mode (the .* toggle). */
  readonly queryRegex = signal(false);
  readonly counts = computed(() => {
    const all = this.orders();
    return {
      ALL: all.length,
      WORKING: all.filter((o) => o.status === OrderStatus.New).length,
      FILLED: all.filter((o) => o.status === OrderStatus.Filled).length,
      REJECTED: all.filter((o) => o.status === OrderStatus.Rejected).length,
      CANCELLED: all.filter((o) => o.status === OrderStatus.Cancelled).length
    };
  });
  readonly visibleOrders = computed(() => {
    const status = FILTER_STATUS[this.filter()];
    const matcher = createMatcher(this.query(), this.queryRegex());
    return this.orders().filter((o) =>
      (status === null || o.status === status) && matcher.test(o.symbol, o.orderId, o.side));
  });
  readonly loading = signal(false);
  // True once the first response (success or failure) has arrived, so the empty state
  // never renders while the real answer is still in flight.
  readonly loaded = signal(false);
  readonly errorMessage = signal('');
  // True once the bounded re-read has given up while an order is still at NEW.
  readonly autoRefreshStopped = signal(false);
  // The order whose cancel request is in flight, so its button cannot be pressed twice.
  readonly cancellingId = signal<string | null>(null);
  /** The order the cancel dialog is asking about; null while no dialog is open. */
  readonly confirmingCancel = signal<OrderHistoryEntry | null>(null);
  /** When the list was last read successfully, shown beside the refresh button. */
  readonly lastUpdated = signal<Date | null>(null);

  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private pollAttempts = 0;

  ngOnInit(): void {
    this.authService.getProfile().subscribe({
      next: (profile) => {
        this.accountId.set(profile.accountId);
        this.fetchHistory();
      },
      error: (err) => {
        this.errorMessage.set(messageFor(err));
      }
    });
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  onRefreshClick(): void {
    // A manual refresh starts a fresh bounded round of re-reads if anything is still at NEW.
    this.autoRefreshStopped.set(false);
    this.fetchHistory();
  }

  /** Asks first, in a dialog: cancelling cannot be undone. */
  onCancelClick(order: OrderHistoryEntry): void {
    if (this.cancellingId() === null) {
      this.confirmingCancel.set(order);
    }
  }

  /** Withdraws a NEW order once the dialog is confirmed. The API refuses (ORD-409) if it was filled meanwhile. */
  confirmCancel(order: OrderHistoryEntry): void {
    this.confirmingCancel.set(null);
    if (this.cancellingId() !== null) {
      return;
    }

    this.cancellingId.set(order.orderId);
    // The list shows the order id with an ORD- prefix; the cancel route takes the bare UUID.
    this.orderService.cancelOrder(order.orderId.replace(/^ORD-/, '')).subscribe({
      next: () => {
        this.cancellingId.set(null);
        this.autoRefreshStopped.set(false);
        this.fetchHistory();
      },
      error: (err) => {
        this.cancellingId.set(null);
        this.errorMessage.set(messageFor(err));
        // The refusal usually means the order changed state; show the truth.
        this.fetchHistory(true, true);
      }
    });
  }

  statusClass(status: OrderStatus): { bg: string; fg: string } {
    return STATUS_BADGE[status];
  }

  /**
   * A rejection reason in plain words. The API sends a code (INSUFFICIENT_FUNDS, PRICE_NOT_MET, ...);
   * rows written before reasons were recorded have none, and older executor rows hold free text,
   * which is shown as it is rather than hidden.
   */
  reasonText(reason: string | null | undefined): string {
    return rejectionReasonText(reason);
  }

  /** The first block of an order ID, enough to tell rows apart; the full ID is in the tooltip. */
  shortId(orderId: string): string {
    return orderId.replace(/^ORD-/, '').slice(0, 8);
  }

  trackByOrder(_: number, order: OrderHistoryEntry): string {
    return order.orderId;
  }

  isWorking(status: OrderStatus): boolean {
    return status === OrderStatus.New;
  }

  private fetchHistory(silent = false, keepError = false): void {
    const accountId = this.accountId();
    if (accountId === null) {
      return;
    }

    if (!silent) {
      this.loading.set(true);
    }

    this.orderService.getOrderHistory(accountId).subscribe({
      next: (entries) => {
        if (!silent) {
          this.loading.set(false);
          this.loaded.set(true);
        }
        if (!keepError) {
          this.errorMessage.set('');
        }

        this.lastUpdated.set(new Date());
        const sorted = [...entries].sort(
          (a, b) => new Date(b.createdOn).getTime() - new Date(a.createdOn).getTime()
        );
        this.orders.set(sorted);

        if (this.hasWorkingOrders(sorted)) {
          if (!this.autoRefreshStopped()) {
            this.startPollingIfNeeded();
          }
        } else {
          this.stopPolling();
          this.autoRefreshStopped.set(false);
        }
      },
      error: (err) => {
        if (!silent) {
          this.loading.set(false);
          this.loaded.set(true);
        }
        this.errorMessage.set(messageFor(err));
      }
    });
  }

  private hasWorkingOrders(orders: OrderHistoryEntry[]): boolean {
    return orders.some((order) => order.status === OrderStatus.New);
  }

  private startPollingIfNeeded(): void {
    if (this.pollHandle !== null) {
      return;
    }

    this.pollAttempts = 0;
    this.autoRefreshStopped.set(false);
    this.pollHandle = setInterval(() => {
      // A tab nobody is looking at does not need to ask; the attempt is not used up either.
      if (typeof document !== 'undefined' && document.hidden) {
        return;
      }

      this.pollAttempts++;

      if (this.pollAttempts > MAX_POLL_ATTEMPTS) {
        this.stopPolling();
        this.autoRefreshStopped.set(true);
        return;
      }

      this.fetchHistory(true);
    }, POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollHandle !== null) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
  }
}
