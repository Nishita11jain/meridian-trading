import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { NotificationsStore, POLL_INTERVAL_MS } from './notifications.store';
import { NotificationsService } from './notifications.service';

const note = (id: string, readOn: string | null) => ({
  id, accountId: 4, type: 'ORDER_FILLED', channel: 'IN_APP', subject: id, message: 'm',
  deliveryState: 'SENT', readOn, createdOn: '2026-10-01T09:00:00Z'
}) as any;

describe('NotificationsStore, one notification at a time', () => {
  let service: Record<string, ReturnType<typeof vi.fn>>;
  let store: NotificationsStore;

  beforeEach(() => {
    service = { list: vi.fn(), markAllRead: vi.fn(), detail: vi.fn(), markRead: vi.fn(), markUnread: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: NotificationsService, useValue: service }] });
    store = TestBed.inject(NotificationsStore);
    store.notifications.set([note('a', null), note('b', null)]);
    store.unreadCount.set(2);
  });

  it('marks one read at once, then takes the server inbox', () => {
    const answer = new Subject<any>();
    service['markRead'].mockReturnValue(answer);
    const done = vi.fn();

    store.markRead(4, 'a', done);
    expect(store.unreadCount()).toBe(1);
    expect(store.notifications()[0].readOn).toBeTruthy();
    expect(store.notifications()[1].readOn).toBeNull();
    expect(done).not.toHaveBeenCalled();

    answer.next({ notifications: [note('a', '2026-10-01T10:00:00Z'), note('b', null)], unreadCount: 1 });
    expect(store.notifications()[0].readOn).toBe('2026-10-01T10:00:00Z');
    expect(done).toHaveBeenCalled();
  });

  it('marks one unread again', () => {
    store.notifications.set([note('a', '2026-10-01T10:00:00Z'), note('b', null)]);
    store.unreadCount.set(1);
    service['markUnread'].mockReturnValue(new Subject());

    store.markUnread(4, 'a');

    expect(store.unreadCount()).toBe(2);
    expect(store.notifications()[0].readOn).toBeNull();
  });

  it('does not count twice when the notification is already in that state', () => {
    service['markRead'].mockReturnValue(new Subject());
    store.notifications.set([note('a', '2026-10-01T10:00:00Z')]);
    store.unreadCount.set(0);

    store.markRead(4, 'a');

    expect(store.unreadCount()).toBe(0);
  });

  it('a failure shows a message and reloads the inbox, so the local guess does not stick', () => {
    service['markRead'].mockReturnValue(throwError(() => ({ status: 0 })));
    service['list'].mockReturnValue(of({ notifications: [note('a', null), note('b', null)], unreadCount: 2 }));
    const done = vi.fn();

    store.markRead(4, 'a', done);

    expect(service['list']).toHaveBeenCalledWith(4);
    expect(store.unreadCount()).toBe(2);
    expect(store.notifications()[0].readOn).toBeNull();
    expect(done).toHaveBeenCalled();
  });

  describe('watching the inbox', () => {
    beforeEach(() => {
      vi.useFakeTimers();
      service['list'].mockReturnValue(of({ notifications: [], unreadCount: 3 }));
    });
    afterEach(() => vi.useRealTimers());

    it('loads at once and again every interval, so a new notification reaches the badge without a page change', () => {
      const token = store.watch(4);
      expect(service['list']).toHaveBeenCalledTimes(1);
      expect(store.unreadCount()).toBe(3);

      vi.advanceTimersByTime(POLL_INTERVAL_MS * 2);
      expect(service['list']).toHaveBeenCalledTimes(3);

      store.unwatch(token);
      vi.advanceTimersByTime(POLL_INTERVAL_MS * 2);
      expect(service['list']).toHaveBeenCalledTimes(3);
    });

    it('a newer watcher (the next page) is not stopped by the page that is leaving', () => {
      const leaving = store.watch(4);
      store.watch(4);
      store.unwatch(leaving);

      vi.advanceTimersByTime(POLL_INTERVAL_MS);
      expect(service['list']).toHaveBeenCalledTimes(3);
    });
  });
});

describe('NotificationsStore, browser notifications', () => {
  let service: Record<string, ReturnType<typeof vi.fn>>;
  let store: NotificationsStore;
  let shown: Array<{ title: string; options: any }>;

  beforeEach(() => {
    shown = [];
    class FakeNotification {
      static permission = 'granted';
      static requestPermission = vi.fn().mockResolvedValue('granted');
      constructor(title: string, options: any) { shown.push({ title, options }); }
    }
    vi.stubGlobal('Notification', FakeNotification);
    service = { list: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: NotificationsService, useValue: service }] });
    store = TestBed.inject(NotificationsStore);
  });

  afterEach(() => vi.unstubAllGlobals());

  it('stays quiet on the first load and announces only what arrives afterwards', () => {
    service['list'].mockReturnValueOnce(of({ notifications: [note('a', null)], unreadCount: 1 }));
    store.watch(4);
    expect(shown).toHaveLength(0);

    service['list'].mockReturnValueOnce(of({ notifications: [note('b', null), note('a', null)], unreadCount: 2 }));
    store.load(4);
    expect(shown).toHaveLength(1);
    expect(shown[0].title).toBe('b');
    expect(shown[0].options.tag).toBe('b');

    service['list'].mockReturnValueOnce(of({ notifications: [note('b', null), note('a', null)], unreadCount: 2 }));
    store.load(4);
    expect(shown).toHaveLength(1);
    store.unwatch(1);
  });

  it('shows nothing when permission is not granted', () => {
    (Notification as any).permission = 'denied';
    service['list'].mockReturnValueOnce(of({ notifications: [], unreadCount: 0 }));
    store.watch(4);
    service['list'].mockReturnValueOnce(of({ notifications: [note('c', null)], unreadCount: 1 }));
    store.load(4);
    expect(shown).toHaveLength(0);
    store.unwatch(1);
  });
});
