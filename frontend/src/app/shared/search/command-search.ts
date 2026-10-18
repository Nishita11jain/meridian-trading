import { Component, ElementRef, HostListener, Injector, computed, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';

import { createMatcher } from '../../core/search/text-matcher';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';
import { InstrumentResponse } from '../../clients/watchlists-client/model/models';

/** One line in the results: a page to open, or an instrument to trade. */
interface Result {
  kind: 'page' | 'instrument';
  label: string;
  detail: string;
  url: string;
  queryParams?: Record<string, string>;
}

/** The app's pages, in the order the sidebar shows them. */
const PAGES: readonly Result[] = [
  { kind: 'page', label: 'Dashboard', detail: 'Overview and market board', url: '/dashboard' },
  { kind: 'page', label: 'New order', detail: 'Place a limit order', url: '/trade' },
  { kind: 'page', label: 'Blotter', detail: 'Your orders and their status', url: '/blotter' },
  { kind: 'page', label: 'Portfolio', detail: 'Holdings, value and P&L', url: '/portfolio' },
  { kind: 'page', label: 'Statements', detail: 'Trades and cash, with CSV', url: '/statements' },
  { kind: 'page', label: 'Watchlists', detail: 'Lists and price alerts', url: '/watchlists' },
  { kind: 'page', label: 'Notifications', detail: 'Your inbox', url: '/notifications' },
  { kind: 'page', label: 'Profile & settings', detail: 'Preferences and avatar', url: '/profile' }
];

const MAX_INSTRUMENTS = 8;

/**
 * The top-bar search (Ctrl+K or ⌘K): jump to a page, or open the order ticket for an instrument. Plain text
 * by default; the `.*` button switches to a regular expression, with the same rules as every other search box.
 * The instrument catalogue is read once, the first time the search opens, from our own API.
 */
@Component({
  selector: 'app-command-search',
  template: `
    <button type="button" class="cmdk-trigger" data-testid="command-search-open" aria-haspopup="dialog"
      [attr.aria-expanded]="open()" aria-label="Search pages and instruments (Ctrl+K)" (click)="show()">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
        <circle cx="11" cy="11" r="7"></circle><path d="M20 20l-3.5-3.5" stroke-linecap="round"></path>
      </svg>
      <span class="cmdk-trigger__text">Search pages, symbols…</span>
      <kbd class="cmdk-trigger__kbd">{{ shortcut }}</kbd>
    </button>

    @if (open()) {
      <div class="lt-dialog-backdrop cmdk-backdrop" data-testid="command-search-backdrop" (click)="onBackdrop($event)">
        <div class="lt-dialog cmdk" role="dialog" aria-modal="true" aria-label="Search" data-testid="command-search">
          <div class="lt-search cmdk__field" [class.is-invalid]="matcher().error">
            <span class="lt-search__icon" aria-hidden="true">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="11" cy="11" r="7"></circle><path d="M20 20l-3.5-3.5" stroke-linecap="round"></path></svg>
            </span>
            <input #input class="lt-input" type="text" role="combobox" aria-autocomplete="list" aria-controls="cmdk-results"
              [attr.aria-activedescendant]="results().length ? 'cmdk-result-' + active() : null" autocomplete="off" spellcheck="false"
              data-testid="command-search-input" [attr.placeholder]="regex() ? 'Regex, e.g. ^(TCS|INFY)' : 'Search pages and instruments'"
              [value]="query()" (input)="onInput($any($event.target).value)" (keydown)="onKey($event)" />
            <button type="button" class="lt-search__mode" data-testid="command-search-regex" [attr.aria-pressed]="regex()"
              aria-label="Search with a regular expression" (click)="regex.set(!regex()); active.set(0); input.focus()">.*</button>
          </div>
          @if (matcher().error) {
            <p class="cmdk__error" role="status">{{ matcher().error }}</p>
          }

          <ul id="cmdk-results" class="cmdk__list" role="listbox" aria-label="Results">
            @for (r of results(); track r.kind + r.url + r.label; let i = $index) {
              @if (i === 0 || results()[i - 1].kind !== r.kind) {
                <li class="cmdk__group" role="presentation">{{ r.kind === 'page' ? 'Pages' : 'Trade an instrument' }}</li>
              }
              <li role="option" class="cmdk__item" [id]="'cmdk-result-' + i" [attr.aria-selected]="i === active()"
                [class.is-active]="i === active()" data-testid="command-search-result"
                (mouseenter)="active.set(i)" (mousedown)="$event.preventDefault()" (click)="go(r)">
                <span class="cmdk__label">{{ r.label }}</span>
                <span class="cmdk__detail">{{ r.detail }}</span>
              </li>
            } @empty {
              <li class="cmdk__empty" role="presentation">Nothing matches "{{ query() }}".</li>
            }
          </ul>
          <p class="cmdk__hint"><kbd>↑</kbd><kbd>↓</kbd> move · <kbd>Enter</kbd> open · <kbd>Esc</kbd> close</p>
        </div>
      </div>
    }
  `,
  styles: [`
    .cmdk-trigger {
      display: inline-flex; align-items: center; gap: .5rem; height: 2.35rem; min-width: 15rem; padding: 0 .5rem 0 .75rem;
      border: 1px solid var(--border-strong); border-radius: 12px; background: color-mix(in srgb, var(--surface) 90%, transparent);
      color: var(--muted); font: inherit; font-size: .8rem; cursor: pointer; transition: border-color .15s ease, color .15s ease;
    }
    .cmdk-trigger:hover { color: var(--text); border-color: color-mix(in srgb, var(--accent) 35%, var(--border-strong)); }
    .cmdk-trigger__text { flex: 1; text-align: left; }
    .cmdk-trigger__kbd, .cmdk__hint kbd {
      padding: .05rem .35rem; border: 1px solid var(--border-strong); border-radius: 5px; background: var(--surface-2);
      font: 600 .65rem var(--font-num); color: var(--muted);
    }
    .cmdk-backdrop { place-items: start center; padding-top: 12vh; }
    .cmdk { max-width: 560px; padding: .75rem; }
    .cmdk__field .lt-input { height: 2.6rem; font-size: .9rem; }
    .cmdk__error { margin: .4rem .2rem 0; font-size: .72rem; font-weight: 600; color: var(--down); }
    .cmdk__list { max-height: min(52vh, 420px); overflow-y: auto; margin: .5rem 0 0; padding: 0; list-style: none; }
    .cmdk__group { padding: .55rem .6rem .25rem; font-size: .62rem; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }
    .cmdk__item { display: flex; align-items: baseline; justify-content: space-between; gap: 1rem; padding: .5rem .6rem; border-radius: 8px; cursor: pointer; }
    .cmdk__item.is-active { background: var(--accent-soft); }
    .cmdk__label { font-size: .85rem; font-weight: 700; color: var(--text); }
    .cmdk__detail { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: .75rem; color: var(--muted); }
    .cmdk__empty { padding: 1rem .6rem; font-size: .8rem; color: var(--muted); }
    .cmdk__hint { display: flex; align-items: center; gap: .3rem; margin: .5rem .2rem 0; font-size: .7rem; color: var(--muted); }
    @media (max-width: 900px) { .cmdk-trigger { min-width: 0; } .cmdk-trigger__text, .cmdk-trigger__kbd { display: none; } }
  `]
})
export class CommandSearch {
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);

  readonly open = signal(false);
  readonly query = signal('');
  readonly regex = signal(false);
  readonly active = signal(0);
  private readonly instruments = signal<InstrumentResponse[]>([]);
  private catalogueRequested = false;
  private readonly inputRef = viewChild<ElementRef<HTMLInputElement>>('input');

  readonly shortcut = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘K' : 'Ctrl K';

  readonly matcher = computed(() => createMatcher(this.query(), this.regex()));

  readonly results = computed<Result[]>(() => {
    const m = this.matcher();
    const pages = PAGES.filter((p) => m.test(p.label, p.detail));
    const instruments = m.empty ? [] : this.instruments()
      .filter((i) => m.test(i.symbol, i.name))
      .slice(0, MAX_INSTRUMENTS)
      .map<Result>((i) => ({
        kind: 'instrument', label: i.symbol, detail: `${i.name} · ${i.currency}`,
        url: '/trade', queryParams: { symbol: i.symbol }
      }));
    return [...pages, ...instruments];
  });

  @HostListener('document:keydown', ['$event'])
  protected onGlobalKey(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      this.open() ? this.hide() : this.show();
    }
  }

  show(): void {
    this.query.set('');
    this.active.set(0);
    this.open.set(true);
    this.loadCatalogue();
    setTimeout(() => this.inputRef()?.nativeElement.focus());
  }

  hide(): void {
    this.open.set(false);
  }

  onInput(value: string): void {
    this.query.set(value);
    this.active.set(0);
  }

  onKey(event: KeyboardEvent): void {
    const count = this.results().length;
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        if (count) this.active.set((this.active() + 1) % count);
        break;
      case 'ArrowUp':
        event.preventDefault();
        if (count) this.active.set((this.active() - 1 + count) % count);
        break;
      case 'Enter': {
        event.preventDefault();
        const chosen = this.results()[this.active()];
        if (chosen) this.go(chosen);
        break;
      }
      case 'Escape':
        event.preventDefault();
        this.hide();
        break;
    }
  }

  go(result: Result): void {
    this.hide();
    this.router.navigate([result.url], result.queryParams ? { queryParams: result.queryParams } : {});
  }

  protected onBackdrop(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.hide();
    }
  }

  /**
   * Reads the catalogue once, on first open. The service is looked up here rather than injected, so the shell
   * (and every page test that renders it) does not need the market API until someone actually searches.
   */
  private loadCatalogue(): void {
    if (this.catalogueRequested) {
      return;
    }
    this.catalogueRequested = true;
    try {
      this.injector.get(WatchlistsService).getInstruments().subscribe({
        next: (list) => this.instruments.set(list),
        error: () => (this.catalogueRequested = false)
      });
    } catch {
      this.catalogueRequested = false;
    }
  }
}
