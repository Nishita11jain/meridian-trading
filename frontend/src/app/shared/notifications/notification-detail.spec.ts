import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { NotificationDetail } from './notification-detail';
import { NotificationsService } from '../../core/notifications/notifications.service';
import { NotificationsStore } from '../../core/notifications/notifications.store';

const ORDER_ID = 'ORD-33333333-3333-3333-3333-333333333333';

const unread = {
  id: 'n1', accountId: 4, type: 'ORDER_REJECTED', channel: 'IN_APP',
  subject: 'Order rejected: TCS.NS', message: 'Your BUY order for 1000 TCS.NS was rejected: INSUFFICIENT_FUNDS.',
  deliveryState: 'SENT', readOn: null, createdOn: '2026-10-01T09:00:00Z',
  orderId: ORDER_ID, alertId: null, symbol: 'TCS.NS'
} as any;
const read = { ...unread, id: 'n2', readOn: '2026-10-01T09:30:00Z' };

const order = {
  orderId: ORDER_ID, symbol: 'TCS.NS', side: 'BUY', quantity: 1000, price: 56678, executedPrice: null,
  status: 'REJECTED', rejectionReason: 'INSUFFICIENT_FUNDS', createdOn: '2026-10-01T09:00:00Z'
};
const inbox = (list: any[], unreadCount: number) => of({ notifications: list, unreadCount });

describe('NotificationDetail', () => {
  let service: Record<string, ReturnType<typeof vi.fn>>;
  let store: NotificationsStore;

  beforeEach(async () => {
    service = {
      list: vi.fn(),
      markAllRead: vi.fn(),
      detail: vi.fn().mockReturnValue(of({ notification: unread, order, alert: null })),
      markRead: vi.fn().mockReturnValue(inbox([{ ...unread, readOn: '2026-10-01T10:00:00Z' }, read], 0)),
      markUnread: vi.fn().mockReturnValue(inbox([unread, { ...read, readOn: null }], 2))
    };
    await TestBed.configureTestingModule({
      imports: [NotificationDetail],
      providers: [{ provide: NotificationsService, useValue: service }, provideRouter([])]
    }).compileComponents();
    // The real store, so the dot and the badge it drives are what is checked.
    store = TestBed.inject(NotificationsStore);
    store.notifications.set([unread, read]);
    store.unreadCount.set(1);
  });

  function open(notification: any) {
    const fixture = TestBed.createComponent(NotificationDetail);
    fixture.componentRef.setInput('notification', notification);
    fixture.componentRef.setInput('accountId', 4);
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);
    fixture.detectChanges();
    return { fixture, closed };
  }
  const el = (fixture: any, id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

  it('is a modal dialog named by the notification subject, described by its message', () => {
    const { fixture } = open(unread);

    const dialog = el(fixture, 'notification-detail')!;
    expect(dialog.getAttribute('role')).toBe('dialog');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(fixture.nativeElement.querySelector('#notification-detail-title').textContent).toContain('Order rejected: TCS.NS');
    expect(el(fixture, 'notification-detail-message')?.textContent).toContain('INSUFFICIENT_FUNDS');
    expect(el(fixture, 'notification-detail-type')?.textContent).toContain('Order rejected');
  });

  it('opening an unread notification marks just that one read, and the badge goes down at once', () => {
    service['markRead'].mockReturnValue(new Subject()); // the server has not answered yet
    open(unread);

    expect(service['markRead']).toHaveBeenCalledWith(4, 'n1');
    expect(store.unreadCount()).toBe(0);
    expect(store.notifications().find((n) => n.id === 'n1')?.readOn).toBeTruthy();
    expect(store.notifications().find((n) => n.id === 'n2')?.readOn).toBe(read.readOn);
  });

  it('opening a notification already read changes nothing', () => {
    open(read);

    expect(service['markRead']).not.toHaveBeenCalled();
    expect(store.unreadCount()).toBe(1);
  });

  it('shows the order it is about, with the rejection reason in plain words', () => {
    const { fixture } = open(unread);

    expect(service['detail']).toHaveBeenCalledWith(4, 'n1');
    const section = el(fixture, 'notification-detail-order')!;
    expect(section.textContent).toContain('TCS.NS');
    expect(section.textContent).toContain('BUY');
    expect(section.textContent).toContain('1,000');
    expect(section.textContent).toContain('56,678.00');
    expect(el(fixture, 'notification-detail-reason')?.textContent).toContain('Not enough cash');
    expect(el(fixture, 'notification-detail-blotter')?.getAttribute('href')).toBe('/blotter');
    expect(el(fixture, 'notification-detail-alert')).toBeNull();
  });

  it('shows the executed price of a filled order and no reason', () => {
    service['detail'].mockReturnValue(of({
      notification: read, alert: null,
      order: { ...order, status: 'FILLED', rejectionReason: null, executedPrice: 3425.5 }
    }));
    const { fixture } = open(read);

    expect(el(fixture, 'notification-detail-executed')?.textContent).toContain('3,425.50');
    expect(el(fixture, 'notification-detail-reason')).toBeNull();
  });

  it('shows the price alert it is about', () => {
    const alertNote = { ...read, id: 'n3', type: 'PRICE_ALERT_TRIGGERED', subject: 'Price alert: TCS.NS', orderId: null, alertId: 7 };
    service['detail'].mockReturnValue(of({
      notification: alertNote, order: null,
      alert: { alertId: 7, symbol: 'TCS.NS', direction: 'ABOVE', targetPrice: 3500, status: 'TRIGGERED',
        triggeredOn: '2026-10-01T09:05:00Z', triggeredPrice: 3510.25 }
    }));
    const { fixture } = open(alertNote);

    expect(el(fixture, 'notification-detail-type')?.textContent).toContain('Price alert');
    expect(el(fixture, 'notification-detail-condition')?.textContent).toContain('Rises above 3,500.00');
    expect(el(fixture, 'notification-detail-triggered')?.textContent).toContain('3,510.25');
    expect(el(fixture, 'notification-detail-watchlists')?.getAttribute('href')).toBe('/watchlists');
    expect(el(fixture, 'notification-detail-order')).toBeNull();
  });

  it('says when the order it names can no longer be read', () => {
    service['detail'].mockReturnValue(of({ notification: read, order: null, alert: null }));
    const { fixture } = open(read);

    expect(el(fixture, 'notification-detail-unavailable')?.textContent).toContain('order details are no longer available');
  });

  it('an older notification without a reference shows its own text and nothing missing', () => {
    const plain = { ...read, orderId: null, alertId: null, symbol: null };
    service['detail'].mockReturnValue(of({ notification: plain, order: null, alert: null }));
    const { fixture } = open(plain);

    expect(el(fixture, 'notification-detail-message')).toBeTruthy();
    expect(el(fixture, 'notification-detail-unavailable')).toBeNull();
    expect(el(fixture, 'notification-detail-order')).toBeNull();
  });

  it('keeps its own text and explains when the details cannot be loaded', () => {
    service['detail'].mockReturnValue(throwError(() => ({ status: 0 })));
    const { fixture } = open(read);

    expect(el(fixture, 'notification-detail-error')?.textContent).toContain('could not be loaded');
    expect(el(fixture, 'notification-detail-message')?.textContent).toContain('INSUFFICIENT_FUNDS');
    expect(el(fixture, 'notification-detail-loading')).toBeNull();
  });

  it('says so when the notification no longer exists', () => {
    service['detail'].mockReturnValue(throwError(() => ({ status: 404, error: { errorCode: 'NTF-404' } })));
    const { fixture } = open(read);

    expect(el(fixture, 'notification-detail-error')?.textContent).toContain('no longer exists');
  });

  it('Mark as unread puts it back, raises the badge, and closes the dialog', () => {
    const { fixture, closed } = open(read);

    (el(fixture, 'notification-detail-unread') as HTMLButtonElement).click();

    expect(service['markUnread']).toHaveBeenCalledWith(4, 'n2');
    expect(store.unreadCount()).toBe(2);
    expect(closed).toHaveBeenCalled();
  });

  it('Mark as unread waits until the read has been recorded, so the two cannot cross', () => {
    const pending = new Subject<any>();
    service['markRead'].mockReturnValue(pending);
    const { fixture } = open(unread);

    const button = el(fixture, 'notification-detail-unread') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    fixture.componentInstance.markUnread();
    expect(service['markUnread']).not.toHaveBeenCalled();

    pending.next({ notifications: [{ ...unread, readOn: '2026-10-01T10:00:00Z' }, read], unreadCount: 0 });
    fixture.detectChanges();
    expect(button.disabled).toBe(false);
  });

  it('closes on Close, on Escape and on the backdrop, but not on a click inside', () => {
    const { fixture, closed } = open(read);

    el(fixture, 'notification-detail')!.click();
    expect(closed).not.toHaveBeenCalled();

    el(fixture, 'notification-detail-backdrop')!.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    (el(fixture, 'notification-detail-close') as HTMLButtonElement).click();
    expect(closed).toHaveBeenCalledTimes(3);
  });
});
