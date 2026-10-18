import { Injectable, inject, signal } from '@angular/core';

import { NotificationsService } from './notifications.service';
import { messageFor } from '../errors/error-messages';
import { NotificationResponse } from '../../clients/notifications-client/model/models';

/** How often the inbox is re-read while a signed-in page is open. */
export const POLL_INTERVAL_MS = 15_000;

/**
 * One source of truth for the inbox, shared by the notifications screen and the sidebar's unread
 * badge, so marking the inbox read on the screen clears the badge at once. Signals only; no state is
 * persisted, and a failed load leaves the last known values rather than blanking the badge.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsStore {
  private readonly notificationsService = inject(NotificationsService);

  readonly notifications = signal<NotificationResponse[]>([]);
  readonly unreadCount = signal(0);
  readonly loaded = signal(false);
  readonly errorMessage = signal('');

  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private pollToken = 0;
  // Ids already seen, so a browser notification fires once, only for something that arrived while the app was open.
  private seen: Set<string> | null = null;

  /**
   * Loads the inbox now and then every {@link POLL_INTERVAL_MS} while the tab is visible, so a
   * notification created after an order settles (the executor answers a few seconds after placing)
   * reaches the badge without a page change. Reads our own API only, never the market-data provider.
   * Returns a token; {@link unwatch} with it stops the polling unless a newer watcher took over.
   */
  watch(accountId: number): number {
    this.stopTimer();
    this.seen = null;
    this.askPermission();
    this.load(accountId);
    this.pollTimer = setInterval(() => {
      if (typeof document === 'undefined' || !document.hidden) {
        this.load(accountId);
      }
    }, POLL_INTERVAL_MS);
    return ++this.pollToken;
  }

  unwatch(token: number): void {
    if (token === this.pollToken) {
      this.stopTimer();
    }
  }

  private stopTimer(): void {
    if (this.pollTimer !== null) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  load(accountId: number): void {
    this.notificationsService.list(accountId).subscribe({
      next: (response) => this.apply(response.notifications, response.unreadCount),
      error: (err) => {
        this.errorMessage.set(messageFor(err));
        this.loaded.set(true);
      }
    });
  }

  markAllRead(accountId: number): void {
    this.notificationsService.markAllRead(accountId).subscribe({
      next: (response) => this.apply(response.notifications, response.unreadCount),
      error: (err) => this.errorMessage.set(messageFor(err))
    });
  }

  /**
   * Marks one notification read. The dot and the badge change at once, then the server's inbox
   * replaces the local guess; a failure reloads the inbox so nothing stays wrong. {@code done} runs
   * when the server has answered, so a caller can hold back a following change until then.
   */
  markRead(accountId: number, notificationId: string, done?: () => void): void {
    this.setLocally(notificationId, new Date().toISOString());
    this.notificationsService.markRead(accountId, notificationId).subscribe({
      next: (response) => {
        this.apply(response.notifications, response.unreadCount);
        done?.();
      },
      error: (err) => {
        this.errorMessage.set(messageFor(err));
        this.load(accountId);
        done?.();
      }
    });
  }

  markUnread(accountId: number, notificationId: string, done?: () => void): void {
    this.setLocally(notificationId, null);
    this.notificationsService.markUnread(accountId, notificationId).subscribe({
      next: (response) => {
        this.apply(response.notifications, response.unreadCount);
        done?.();
      },
      error: (err) => {
        this.errorMessage.set(messageFor(err));
        this.load(accountId);
        done?.();
      }
    });
  }

  private setLocally(notificationId: string, readOn: string | null): void {
    const current = this.notifications().find((n) => n.id === notificationId);
    if (!current || Boolean(current.readOn) === Boolean(readOn)) {
      return;
    }
    this.notifications.update((list) => list.map((n) => (n.id === notificationId ? { ...n, readOn } : n)));
    this.unreadCount.update((count) => Math.max(0, count + (readOn ? -1 : 1)));
  }

  /** Asks once for permission to show browser notifications. A refusal changes nothing else. */
  private askPermission(): void {
    if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
      void Notification.requestPermission().catch(() => undefined);
    }
  }

  /** Shows a browser notification for each unread item not seen before. The first load only records what is there. */
  private announceNew(notifications: NotificationResponse[]): void {
    const known = this.seen;
    this.seen = new Set(notifications.map((n) => n.id));
    if (known === null || typeof Notification === 'undefined' || Notification.permission !== 'granted') {
      return;
    }
    for (const n of notifications) {
      if (!known.has(n.id) && !n.readOn) {
        try {
          new Notification(n.subject, { body: n.message, tag: n.id });
        } catch {
          // Some browsers refuse the constructor outside a service worker; the badge still updates.
        }
      }
    }
  }

  private apply(notifications: NotificationResponse[], unreadCount: number): void {
    this.announceNew(notifications);
    this.notifications.set(notifications);
    this.unreadCount.set(unreadCount);
    this.loaded.set(true);
    this.errorMessage.set('');
  }
}
