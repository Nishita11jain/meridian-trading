import { AfterViewInit, Component, ElementRef, HostListener, input, output, viewChild } from '@angular/core';

/**
 * A styled confirmation dialog, in place of the browser's `window.confirm`. Render it with `@if` while a
 * decision is pending. Focus starts on the safe choice (so a stray Enter cannot confirm a destructive action),
 * Tab stays inside, and Esc or a click on the dimmed page counts as "keep".
 */
@Component({
  selector: 'app-confirm-dialog',
  template: `
    <div class="lt-dialog-backdrop" [attr.data-testid]="testId() + '-backdrop'" (click)="onBackdrop($event)">
      <div #dialog class="lt-dialog" role="alertdialog" aria-modal="true" tabindex="-1"
        [attr.aria-labelledby]="testId() + '-title'" [attr.aria-describedby]="testId() + '-body'"
        [attr.data-testid]="testId()" (keydown)="trapFocus($event)">
        <p class="lt-eyebrow">{{ eyebrow() }}</p>
        <h2 class="lt-dialog__title" [id]="testId() + '-title'">{{ title() }}</h2>
        <p class="lt-dialog__body" [id]="testId() + '-body'">{{ body() }}</p>
        <ng-content></ng-content>
        <div class="lt-dialog__actions">
          <button #keepButton type="button" class="lt-btn lt-btn-ghost" [attr.data-testid]="testId() + '-keep'"
            (click)="dismissed.emit()">{{ cancelLabel() }}</button>
          <button type="button" class="lt-btn" [class.lt-btn-danger]="tone() === 'danger'" [class.lt-btn-primary]="tone() !== 'danger'"
            [attr.data-testid]="testId() + '-confirm'" (click)="confirmed.emit()">{{ confirmLabel() }}</button>
        </div>
      </div>
    </div>
  `
})
export class ConfirmDialog implements AfterViewInit {
  readonly eyebrow = input('Please confirm');
  readonly title = input.required<string>();
  readonly body = input('');
  readonly confirmLabel = input('Confirm');
  readonly cancelLabel = input('Keep');
  readonly tone = input<'danger' | 'default'>('default');
  readonly testId = input('confirm-dialog');

  readonly confirmed = output<void>();
  readonly dismissed = output<void>();

  private readonly dialog = viewChild.required<ElementRef<HTMLElement>>('dialog');
  private readonly keepButton = viewChild.required<ElementRef<HTMLButtonElement>>('keepButton');

  ngAfterViewInit(): void {
    this.keepButton().nativeElement.focus();
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    this.dismissed.emit();
  }

  protected onBackdrop(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.dismissed.emit();
    }
  }

  /** Keeps Tab and Shift+Tab inside the dialog. */
  protected trapFocus(event: KeyboardEvent): void {
    if (event.key !== 'Tab') {
      return;
    }
    const buttons = Array.from(this.dialog().nativeElement.querySelectorAll<HTMLElement>('button:not([disabled])'));
    if (buttons.length === 0) {
      return;
    }
    const first = buttons[0];
    const last = buttons[buttons.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
