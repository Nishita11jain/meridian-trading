import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { AssistantService } from '../../core/assistant/assistant.service';

interface ChatLine {
  from: 'user' | 'assistant';
  text: string;
}

/** Starter questions. Quick replies are faster than typing, so a new user sees these before anything else. */
export const SUGGESTIONS: readonly string[] = [
  'Summarise my holdings',
  'What is a limit order?',
  'Why was my order rejected?',
  'When does the NSE market open?'
];

/**
 * Finny, the assistant: a launcher in the bottom-right corner that opens a chat drawer. Mounted once
 * in the app shell, so it is on every signed-in page. It answers questions and summarises the user's
 * holdings. It does not place or change orders, and its answers use the latest stored prices, not live ones.
 */
@Component({
  selector: 'app-assistant-panel',
  imports: [FormsModule, NgTemplateOutlet],
  template: `
    <ng-template #glyph>
      <!-- The assistant's mark. -->
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M12 3.5 5 17.5M12 3.5l7 14"></path>
        <path d="M5 17.5a15.6 15.6 0 0 0 14 0"></path>
        <path d="M12 3.5 9.2 13"></path>
        <circle cx="12" cy="3.5" r="1.3" fill="currentColor" stroke="none"></circle>
        <path d="M18.5 4.5l.5 1.2 1.2.5-1.2.5-.5 1.2-.5-1.2-1.2-.5 1.2-.5z" fill="currentColor" stroke="none"></path>
      </svg>
    </ng-template>

    <button type="button" class="fn-launcher" data-testid="assistant-open"
      [attr.aria-expanded]="open()" aria-controls="assistant-drawer" [attr.aria-label]="open() ? 'Close Finny' : 'Ask Finny, the assistant'"
      (click)="toggle()">
      <span class="fn-mark"><ng-container *ngTemplateOutlet="glyph"></ng-container></span>
      <span class="fn-launcher__label">Ask Finny</span>
    </button>

    @if (open()) {
      <aside id="assistant-drawer" class="fn-drawer" role="dialog" aria-label="Finny assistant" data-testid="assistant-drawer">
        <header class="fn-head">
          <span class="fn-mark fn-mark--lg"><ng-container *ngTemplateOutlet="glyph"></ng-container></span>
          <div class="fn-head__text">
            <h2>Finny</h2>
            <p>Read-only help · latest stored prices</p>
          </div>
          <button type="button" class="fn-close" data-testid="assistant-close" aria-label="Close assistant" (click)="toggle()">✕</button>
        </header>

        <p class="fn-note">Answers use the latest stored prices, not live ones. Finny cannot place or change orders.</p>

        <div class="fn-log" role="log" aria-live="polite" data-testid="assistant-log" #log>
          @if (lines().length === 0) {
            <div class="fn-suggestions" data-testid="assistant-suggestions">
              <p class="fn-line fn-line--bot">Hi, I'm Finny, your finance sidekick. Pick a question or type your own.</p>
              @for (suggestion of suggestions; track suggestion) {
                <button type="button" class="fn-chip" data-testid="assistant-chip" (click)="ask(suggestion)">{{ suggestion }}</button>
              }
            </div>
          }

          @for (line of lines(); track $index) {
            <div class="fn-row" [class.fn-row--user]="line.from === 'user'">
              @if (line.from === 'assistant') {
                <span class="fn-mark fn-mark--sm"><ng-container *ngTemplateOutlet="glyph"></ng-container></span>
              }
              <p class="fn-line" [class.fn-line--user]="line.from === 'user'" [class.fn-line--bot]="line.from === 'assistant'"
                [attr.data-from]="line.from">{{ line.text }}</p>
            </div>
          }

          @if (sending()) {
            <div class="fn-row" data-testid="assistant-pending">
              <span class="fn-mark fn-mark--sm"><ng-container *ngTemplateOutlet="glyph"></ng-container></span>
              <p class="fn-line fn-line--bot fn-typing" aria-label="Finny is typing"><span></span><span></span><span></span></p>
            </div>
          }
        </div>

        <form class="fn-form" (ngSubmit)="send()">
          <label class="sr-only" for="assistant-question">Ask Finny</label>
          <textarea id="assistant-question" name="question" rows="2" maxlength="500"
            data-testid="assistant-input" [(ngModel)]="draft"
            placeholder="Ask about your holdings or the app…"
            (keydown.enter)="onEnter($event)"></textarea>
          <button type="submit" class="fn-send" data-testid="assistant-send" aria-label="Send question" [disabled]="sending() || !draft.trim()">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round"></path></svg>
          </button>
        </form>
      </aside>
    }
  `,
  styles: [`
    .fn-mark { display: inline-grid; place-items: center; width: 30px; height: 30px; flex: none; border-radius: 50%; background: color-mix(in srgb, var(--accent-ink) 16%, transparent); }
    .fn-mark svg { width: 18px; height: 18px; }
    .fn-mark--lg { width: 36px; height: 36px; background: var(--accent-soft); color: var(--accent-strong); }
    .fn-mark--lg svg { width: 21px; height: 21px; }
    .fn-mark--sm { width: 26px; height: 26px; background: var(--accent-soft); color: var(--accent-strong); }
    .fn-mark--sm svg { width: 15px; height: 15px; }

    .fn-launcher {
      position: fixed; right: 18px; bottom: 18px; z-index: 40;
      display: inline-flex; align-items: center; gap: 8px; height: 44px; padding: 0 16px 0 7px;
      border: 1px solid color-mix(in srgb, var(--accent) 50%, transparent); border-radius: 999px; cursor: pointer;
      background: var(--accent); color: var(--accent-ink); font: inherit; font-size: 13px; font-weight: 700;
      box-shadow: 0 8px 22px color-mix(in srgb, var(--accent) 28%, transparent);
    }
    .fn-launcher:hover { box-shadow: 0 12px 26px color-mix(in srgb, var(--accent) 36%, transparent); }
    .fn-launcher:focus-visible { outline: 2px solid var(--focus); outline-offset: 3px; }

    .fn-drawer {
      position: fixed; right: 18px; bottom: 72px; z-index: 40;
      width: min(380px, calc(100vw - 36px)); max-height: 70vh;
      display: flex; flex-direction: column; gap: 10px; padding: 14px;
      background: var(--surface); color: var(--text);
      border: 1px solid var(--border); border-radius: 16px; box-shadow: var(--shadow-lg);
    }

    .fn-head { display: flex; align-items: center; gap: 10px; }
    .fn-head__text { flex: 1; min-width: 0; }
    .fn-head h2 { margin: 0; font-size: 15px; font-weight: 750; letter-spacing: -.01em; color: var(--text); }
    .fn-head p { margin: 0; font-size: 11.5px; color: var(--muted); }
    .fn-close { border: 0; background: transparent; color: var(--muted); cursor: pointer; font-size: 14px; padding: 6px 8px; border-radius: 8px; }
    .fn-close:hover { background: var(--surface-2); color: var(--text); }
    .fn-note { margin: 0; padding: 7px 10px; border-radius: 9px; background: var(--surface-2); font-size: 11.5px; line-height: 1.4; color: var(--muted); }

    .fn-log { flex: 1; min-height: 170px; overflow-y: auto; display: flex; flex-direction: column; gap: 9px; padding-right: 2px; }
    .fn-suggestions { display: flex; flex-direction: column; gap: 7px; }
    .fn-chip {
      align-self: flex-start; text-align: left; cursor: pointer; padding: 6px 11px; border-radius: 999px;
      border: 1px solid color-mix(in srgb, var(--accent) 40%, var(--border)); background: transparent;
      color: var(--accent-strong); font: inherit; font-size: 12.5px; font-weight: 600;
    }
    .fn-chip:hover { background: var(--accent-soft); }

    .fn-row { display: flex; align-items: flex-end; gap: 7px; }
    .fn-row--user { justify-content: flex-end; }
    .fn-line { margin: 0; padding: 8px 11px; border-radius: 13px; max-width: 82%; line-height: 1.45; white-space: pre-wrap; font-size: 13.5px; }
    .fn-line--bot { background: var(--surface-2); color: var(--text); border-bottom-left-radius: 4px; }
    .fn-line--user { background: var(--accent); color: var(--accent-ink); border-bottom-right-radius: 4px; }

    .fn-typing { display: inline-flex; gap: 4px; align-items: center; }
    .fn-typing span { width: 6px; height: 6px; border-radius: 50%; background: var(--muted); animation: fn-blink 1s infinite ease-in-out; }
    .fn-typing span:nth-child(2) { animation-delay: .15s; }
    .fn-typing span:nth-child(3) { animation-delay: .3s; }
    @keyframes fn-blink { 0%, 80%, 100% { opacity: .3; } 40% { opacity: 1; } }
    :host-context(.reduce-motion) .fn-typing span { animation: none; }

    .fn-form { display: flex; gap: 7px; align-items: flex-end; }
    .fn-form textarea {
      flex: 1; resize: none; padding: 8px 10px; border-radius: 11px; border: 1px solid var(--border-strong);
      background: var(--surface-2); color: var(--text); font: inherit; font-size: 13.5px;
    }
    .fn-form textarea:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    .fn-send { display: grid; place-items: center; width: 40px; height: 40px; flex: none; border-radius: 11px; border: 0; cursor: pointer; background: var(--accent); color: var(--accent-ink); }
    .fn-send:disabled { opacity: .45; cursor: default; }

    @media (max-width: 600px) {
      .fn-drawer { right: 0; bottom: 0; left: 0; width: 100%; max-height: 100vh; height: 100vh; border-radius: 0; }
      .fn-launcher { right: 14px; bottom: 14px; padding: 0 7px; }
      .fn-launcher__label { display: none; }
    }
  `]
})
export class AssistantPanel {
  private readonly assistant = inject(AssistantService);

  readonly suggestions = SUGGESTIONS;
  readonly open = signal(false);
  readonly sending = signal(false);
  readonly lines = signal<ChatLine[]>([]);
  draft = '';

  private readonly log = viewChild<ElementRef<HTMLElement>>('log');

  toggle(): void {
    this.open.update((value) => !value);
  }

  onEnter(event: Event): void {
    // Enter sends; Shift+Enter keeps a line break in the question.
    const keyboard = event as KeyboardEvent;
    if (!keyboard.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  /** A starter chip: asks the suggestion as if it had been typed. */
  ask(question: string): void {
    this.draft = question;
    this.send();
  }

  send(): void {
    const question = this.draft.trim();
    if (!question || this.sending()) {
      return;
    }
    this.lines.update((lines) => [...lines, { from: 'user', text: question }]);
    this.draft = '';
    this.sending.set(true);

    this.assistant.ask(question).subscribe({
      next: (answer) => {
        this.lines.update((lines) => [...lines, { from: 'assistant', text: answer.reply }]);
        this.finish();
      },
      error: (error: unknown) => {
        this.lines.update((lines) => [...lines, { from: 'assistant', text: messageFor(error) }]);
        this.finish();
      }
    });
  }

  private finish(): void {
    this.sending.set(false);
    queueMicrotask(() => {
      const element = this.log()?.nativeElement;
      if (element) {
        element.scrollTop = element.scrollHeight;
      }
    });
  }
}

/** Plain text for each refusal the server can give. Nothing here shows a status code or an internal message. */
function messageFor(error: unknown): string {
  const status = error instanceof HttpErrorResponse ? error.status : 0;
  switch (status) {
    case 429:
      return 'You have asked a lot in the last minute. Try again shortly.';
    case 422:
      return 'Type a question first, and keep it under 500 characters.';
    case 503:
      return 'Finny is unavailable right now. Everything else in the app works as normal.';
    default:
      return 'Finny could not answer. Try again.';
  }
}
