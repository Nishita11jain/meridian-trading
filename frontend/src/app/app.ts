import { Component, HostListener, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { IdleSignOutService } from './core/idle/idle-sign-out.service';
import { MotionService } from './core/motion/motion.service';
import { ConfirmDialog } from './shared/ui/confirm-dialog';
import { LeavePageConfirmService } from './core/guards/leave-page-confirm.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ConfirmDialog],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  protected readonly title = signal('sprint-09-trading-ui');
  protected readonly leavePageConfirm = inject(LeavePageConfirmService);
  // Injected only so the service exists and starts watching once a session does.
  private readonly idle = inject(IdleSignOutService);
  // Injected so the `reduce-motion` class is on <html> from the first screen, whichever page loads first.
  private readonly motion = inject(MotionService);

  protected confirmLeavePage(): void {
    this.leavePageConfirm.confirm();
  }

  protected cancelLeavePage(): void {
    this.leavePageConfirm.cancel();
  }

  @HostListener('window:keydown', ['$event'])
  protected onReloadShortcut(event: KeyboardEvent): void {
    const key = (event.key ?? '').toLowerCase();
    if (!key) {
      return;
    }
    const isReloadShortcut =
      key === 'f5' ||
      ((event.ctrlKey || event.metaKey) && key === 'r');

    if (!isReloadShortcut || !this.leavePageConfirm.hasUnsavedChanges()) {
      return;
    }

    event.preventDefault();
    void this.leavePageConfirm.requestLeave('You have an order that has not been placed. If you reload now, what you entered will be lost. Reload this page?')
      .then((confirmed) => {
        if (!confirmed) {
          return;
        }
        this.leavePageConfirm.allowNextBeforeUnload();
        window.location.reload();
      });
  }
}
