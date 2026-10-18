import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Notifications } from './notifications';
import { AuthService } from '../../core/auth/auth.service';
import { NotificationsStore } from '../../core/notifications/notifications.store';
import { NotificationsService } from '../../core/notifications/notifications.service';

function makeStore(notifications: any[], unread: number, loaded = true) {
  return {
    notifications: () => notifications,
    unreadCount: () => unread,
    loaded: () => loaded,
    errorMessage: () => '',
    load: vi.fn(),
    watch: vi.fn().mockReturnValue(1),
    unwatch: vi.fn(),
    markAllRead: vi.fn(),
    markRead: vi.fn(),
    markUnread: vi.fn()
  };
}

const row = (overrides: any = {}) => ({
  id: 'n1', accountId: 4, type: 'ORDER_FILLED', channel: 'EMAIL',
  subject: 'Order filled: TCS.NS', message: 'Your BUY order for 10 TCS.NS was filled at 3425.00.',
  deliveryState: 'SENT', readOn: null, createdOn: '2026-10-01T00:00:00Z', ...overrides
});

describe('Notifications', () => {
  let store: ReturnType<typeof makeStore>;

  async function setup(s: ReturnType<typeof makeStore>, getProfile = vi.fn().mockReturnValue(of({ accountId: 4 }))) {
    store = s;
    await TestBed.configureTestingModule({
      imports: [Notifications],
      providers: [
        { provide: AuthService, useValue: { getProfile, logout: vi.fn() } },
        { provide: NotificationsStore, useValue: store },
        { provide: NotificationsService, useValue: {
          detail: vi.fn().mockReturnValue(of({ notification: row(), order: null, alert: null }))
        } },
        provideRouter([])
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(Notifications);
    fixture.detectChanges();
    return fixture;
  }
  const el = (fixture: any, id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`);
  const all = (fixture: any, id: string) => fixture.nativeElement.querySelectorAll(`[data-testid="${id}"]`);

  it('loads the inbox for the signed-in account', async () => {
    const fixture = await setup(makeStore([row()], 1));

    expect(store.load).toHaveBeenCalledWith(4);
    expect(all(fixture, 'notifications-row').length).toBe(1);
    expect(el(fixture, 'notifications-channel').textContent).toContain('EMAIL');
  });

  it('marks an unread notification visually and lists only its subject; the message is in the dialog', async () => {
    const fixture = await setup(makeStore([row({ readOn: null })], 1));

    expect(el(fixture, 'notifications-unread-dot')).toBeTruthy();
    expect(el(fixture, 'notifications-row').textContent).toContain('Order filled: TCS.NS');
    expect(el(fixture, 'notifications-row').textContent).not.toContain('filled at 3425.00');
  });

  it('shows the empty state when there is nothing', async () => {
    const fixture = await setup(makeStore([], 0));

    expect(el(fixture, 'notifications-empty')).toBeTruthy();
  });

  it('mark-all-read is disabled when nothing is unread', async () => {
    const fixture = await setup(makeStore([row({ readOn: '2026-10-01T01:00:00Z' })], 0));

    expect(el(fixture, 'notifications-mark-read').disabled).toBe(true);
  });

  it('mark-all-read calls the store for the account when there are unread notifications', async () => {
    const fixture = await setup(makeStore([row()], 2));

    el(fixture, 'notifications-mark-read').click();

    expect(store.markAllRead).toHaveBeenCalledWith(4);
  });

  it('shows a readable error when the profile cannot be loaded', async () => {
    const fixture = await setup(makeStore([], 0, false), vi.fn().mockReturnValue(throwError(() => ({ status: 401 }))));

    expect(el(fixture, 'notifications-error')).toBeTruthy();
  });

  it('each notification is a button that opens it in a dialog and marks just that one read', async () => {
    const fixture = await setup(makeStore([row(), row({ id: 'n2', subject: 'Other' })], 2));
    expect(el(fixture, 'notification-detail')).toBeNull();

    (all(fixture, 'notifications-open')[0] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(el(fixture, 'notification-detail')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('#notification-detail-title').textContent).toContain('Order filled: TCS.NS');
    expect(store.markRead).toHaveBeenCalledWith(4, 'n1', expect.any(Function));
    expect(store.markAllRead).not.toHaveBeenCalled();

    el(fixture, 'notification-detail-close').click();
    fixture.detectChanges();
    expect(el(fixture, 'notification-detail')).toBeNull();
  });

  it('a notification already read can be opened again any time', async () => {
    const fixture = await setup(makeStore([row({ readOn: '2026-10-01T01:00:00Z' })], 0));

    (all(fixture, 'notifications-open')[0] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(el(fixture, 'notification-detail')).toBeTruthy();
    expect(store.markRead).not.toHaveBeenCalled();
  });
});
