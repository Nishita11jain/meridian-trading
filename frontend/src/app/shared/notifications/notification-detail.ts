import {
  Component, ElementRef, HostListener, OnDestroy, OnInit, computed, inject, input, output, signal, viewChild
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

import { NotificationsService } from '../../core/notifications/notifications.service';
import { NotificationsStore } from '../../core/notifications/notifications.store';
import { messageFor } from '../../core/errors/error-messages';
import { rejectionReasonText } from '../../core/orders/rejection-reasons';
import {
  NotificationDetailResponse, NotificationResponse
} from '../../clients/notifications-client/model/models';

const TYPE_LABEL: Record<string, string> = {
  ORDER_FILLED: 'Order filled',
  ORDER_REJECTED: 'Order rejected',
  ORDER_CANCELLED: 'Order cancelled',
  PRICE_ALERT_TRIGGERED: 'Price alert'
};

/**
 * One notification, opened. Shows its own text at once (from the inbox), then the order or price alert
 * it is about, read from the API as it stands now. Opening an unread notification marks just that one
 * read, so its dot and the badge update everywhere the shared store is shown; "Mark as unread" puts it
 * back. A modal dialog: Esc, a click outside or Close dismisses it, focus stays inside while it is open
 * and goes back to what opened it.
 */
@Component({
  selector: 'app-notification-detail',
  imports: [CommonModule, RouterLink],
  template: `
    <div class="fixed inset-0 z-50 grid place-items-center p-4"
      style="background: color-mix(in srgb, #05060a 62%, transparent)"
      data-testid="notification-detail-backdrop" (click)="onBackdropClick($event)">
      <div #dialog role="dialog" aria-modal="true" aria-labelledby="notification-detail-title"
        aria-describedby="notification-detail-message" tabindex="-1"
        class="lt-card w-full max-w-[480px] max-h-[calc(100vh-2rem)] overflow-y-auto p-5 outline-none"
        style="box-shadow: var(--shadow-lg)" data-testid="notification-detail" (keydown)="trapFocus($event)">

        <div class="flex items-center justify-between gap-3">
          <p class="lt-eyebrow" data-testid="notification-detail-type">{{ typeLabel() }}</p>
          <span class="rounded-full px-2 py-0.5 text-[11px] font-extrabold"
            style="background: var(--info-bg); color: var(--info)" data-testid="notification-detail-channel">{{ current().channel }}</span>
        </div>
        <h3 id="notification-detail-title" class="mt-1 text-[20px] font-extrabold tracking-tight" style="color: var(--text)">
          {{ current().subject }}
        </h3>
        <p class="mt-1 text-xs" style="color: var(--muted)">{{ current().createdOn | date:'medium' }}</p>
        <p id="notification-detail-message" class="mt-3 text-sm leading-relaxed" style="color: var(--text)"
          data-testid="notification-detail-message">{{ current().message }}</p>

        <p *ngIf="loading()" class="mt-5 text-sm" style="color: var(--muted)" data-testid="notification-detail-loading">Loading details...</p>
        <p *ngIf="errorMessage()" class="mt-5 rounded-xl px-3 py-2 text-sm font-medium" role="alert"
          style="background: var(--down-bg); color: var(--down)" data-testid="notification-detail-error">{{ errorMessage() }}</p>

        <ng-container *ngIf="detail() as d">
          <section *ngIf="d.order as order" class="mt-5" data-testid="notification-detail-order" aria-label="Order">
            <p class="lt-eyebrow">Order</p>
            <dl class="mt-2 grid gap-2 text-sm">
              <div class="flex justify-between gap-4"><dt style="color: var(--muted)">Instrument</dt>
                <dd class="font-semibold" style="color: var(--text)">{{ order.symbol }}</dd></div>
              <div class="flex justify-between gap-4"><dt style="color: var(--muted)">Side</dt>
                <dd class="font-semibold" [style.color]="order.side === 'BUY' ? 'var(--up)' : 'var(--down)'">{{ order.side }}</dd></div>
              <div class="flex justify-between gap-4"><dt style="color: var(--muted)">Quantity</dt>
                <dd class="tabular-nums font-semibold" style="color: var(--text)">{{ order.quantity | number }}</dd></div>
              <div class="flex justify-between gap-4"><dt style="color: var(--muted)">Limit price</dt>
                <dd class="tabular-nums font-semibold" style="color: var(--text)">{{ order.price | number:'1.2-4' }}</dd></div>
              <div class="flex justify-between gap-4" *ngIf="order.executedPrice != null"><dt style="color: var(--muted)">Executed price</dt>
                <dd class="tabular-nums font-semibold" style="color: var(--text)" data-testid="notification-detail-executed">{{ order.executedPrice | number:'1.2-4' }}</dd></div>
              <div class="flex justify-between gap-4"><dt style="color: var(--muted)">Status</dt>
                <dd class="font-semibold" style="color: var(--text)" data-testid="notification-detail-status">{{ order.status }}</dd></div>
              <div class="flex justify-between gap-4" *ngIf="order.status === 'REJECTED'"><dt style="color: var(--muted)">Reason</dt>
                <dd class="text-right font-semibold" style="color: var(--down)" data-testid="notification-detail-reason">{{ reasonText(order.rejectionReason) }}</dd></div>
              <div class="flex justify-between gap-4" *ngIf="order.createdOn"><dt style="color: var(--muted)">Placed</dt>
                <dd style="color: var(--text)">{{ order.createdOn | date:'medium' }}</dd></div>
              <div class="flex justify-between gap-4"><dt style="color: var(--muted)">Order</dt>
                <dd class="tabular-nums truncate text-xs" style="color: var(--muted)" [title]="order.orderId">{{ order.orderId }}</dd></div>
            </dl>
            <a routerLink="/blotter" class="mt-3 inline-block text-sm font-semibold" style="color: var(--accent)"
              data-testid="notification-detail-blotter" (click)="close()">View in blotter</a>
          </section>

          <section *ngIf="d.alert as alert" class="mt-5" data-testid="notification-detail-alert" aria-label="Price alert">
            <p class="lt-eyebrow">Price alert</p>
            <dl class="mt-2 grid gap-2 text-sm">
              <div class="flex justify-between gap-4"><dt style="color: var(--muted)">Instrument</dt>
                <dd class="font-semibold" style="color: var(--text)">{{ alert.symbol }}</dd></div>
              <div class="flex justify-between gap-4"><dt style="color: var(--muted)">Condition</dt>
                <dd class="tabular-nums font-semibold" style="color: var(--text)" data-testid="notification-detail-condition">
                  {{ alert.direction === 'ABOVE' ? 'Rises above' : 'Falls below' }} {{ alert.targetPrice | number:'1.2-4' }}</dd></div>
              <div class="flex justify-between gap-4" *ngIf="alert.triggeredPrice != null"><dt style="color: var(--muted)">Triggered at</dt>
                <dd class="tabular-nums font-semibold" style="color: var(--text)" data-testid="notification-detail-triggered">{{ alert.triggeredPrice | number:'1.2-4' }}</dd></div>
              <div class="flex justify-between gap-4" *ngIf="alert.triggeredOn"><dt style="color: var(--muted)">When</dt>
                <dd style="color: var(--text)">{{ alert.triggeredOn | date:'medium' }}</dd></div>
              <div class="flex justify-between gap-4"><dt style="color: var(--muted)">Alert status</dt>
                <dd class="font-semibold" style="color: var(--text)">{{ alert.status }}</dd></div>
            </dl>
            <a routerLink="/watchlists" class="mt-3 inline-block text-sm font-semibold" style="color: var(--accent)"
              data-testid="notification-detail-watchlists" (click)="close()">View watchlists</a>
          </section>

          <p *ngIf="referenceMissing()" class="mt-5 text-sm" style="color: var(--muted)" data-testid="notification-detail-unavailable">
            The {{ d.notification.orderId ? 'order' : 'alert' }} details are no longer available.
          </p>
        </ng-container>

        <div class="mt-6 grid grid-cols-2 gap-3">
          <button type="button" class="lt-btn lt-btn-ghost py-3" data-testid="notification-detail-unread"
            [disabled]="marking() || !current().readOn" (click)="markUnread()">Mark as unread</button>
          <button type="button" class="lt-btn lt-btn-ghost py-3" data-testid="notification-detail-close" (click)="close()">Close</button>
        </div>
      </div>
    </div>
  `
})
export class NotificationDetail implements OnInit, OnDestroy {
  private readonly notificationsService = inject(NotificationsService);
  private readonly store = inject(NotificationsStore);

  /** The notification as the inbox listed it, shown at once while the details load. */
  readonly notification = input.required<NotificationResponse>();
  readonly accountId = input.required<number>();
  readonly closed = output<void>();

  private readonly dialog = viewChild<ElementRef<HTMLElement>>('dialog');
  private opener: HTMLElement | null = null;

  readonly detail = signal<NotificationDetailResponse | null>(null);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  /** True while a read or unread change is with the server, so the two cannot cross. */
  readonly marking = signal(false);

  /** The live copy from the shared store, so read/unread shows as it changes; the input until then. */
  readonly current = computed(() => {
    const id = this.notification().id;
    return this.store.notifications().find((n) => n.id === id) ?? this.notification();
  });

  readonly typeLabel = computed(() => TYPE_LABEL[this.current().type] ?? 'Notification');

  /** It names an order or alert, but that record could not be read any more. */
  readonly referenceMissing = computed(() => {
    const d = this.detail();
    if (!d) {
      return false;
    }
    return (Boolean(d.notification.orderId) && !d.order) || (d.notification.alertId != null && !d.alert);
  });

  ngOnInit(): void {
    this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setTimeout(() => this.dialog()?.nativeElement.focus());

    const n = this.notification();
    if (!n.readOn) {
      this.marking.set(true);
      this.store.markRead(this.accountId(), n.id, () => this.marking.set(false));
    }

    this.notificationsService.detail(this.accountId(), n.id).subscribe({
      next: (detail) => {
        this.detail.set(detail);
        this.loading.set(false);
      },
      error: (err) => {
        this.errorMessage.set(err?.status === 404
          ? 'This notification no longer exists.'
          : 'The details could not be loaded. ' + messageFor(err));
        this.loading.set(false);
      }
    });
  }

  ngOnDestroy(): void {
    // Back to what opened it, if it is still on the page; otherwise the host decides.
    if (this.opener?.isConnected) {
      this.opener.focus();
    }
  }

  reasonText(reason: string | null | undefined): string {
    return rejectionReasonText(reason);
  }

  markUnread(): void {
    if (this.marking()) {
      return;
    }
    this.marking.set(true);
    this.store.markUnread(this.accountId(), this.notification().id, () => this.marking.set(false));
    this.close();
  }

  close(): void {
    this.closed.emit();
  }

  onBackdropClick(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.close();
    }
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    this.close();
  }

  /** Keeps Tab and Shift+Tab inside the dialog, so keyboard focus cannot wander behind it. */
  trapFocus(event: KeyboardEvent): void {
    if (event.key !== 'Tab') {
      return;
    }
    const dialog = this.dialog()?.nativeElement;
    const focusable = dialog
      ? Array.from(dialog.querySelectorAll<HTMLElement>('button:not([disabled]), [href]'))
      : [];
    if (focusable.length === 0) {
      event.preventDefault();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === dialog)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
