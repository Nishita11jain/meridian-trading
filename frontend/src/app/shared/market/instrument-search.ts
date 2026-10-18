import { Component, computed, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

import { InstrumentResponse } from '../../clients/watchlists-client/model/models';

/** How many matches the list shows at most. */
const MAX_MATCHES = 8;

/**
 * Search the instrument catalogue by symbol or company name and pick one, e.g. "tata" finds TCS.NS and
 * TATAMOTORS.NS. An ARIA combobox: arrow keys move, Enter picks, Escape closes. Symbols already chosen
 * elsewhere can be left out with {@code exclude}.
 */
@Component({
  selector: 'app-instrument-search',
  imports: [CommonModule],
  template: `
    <div class="relative">
      <input class="lt-input w-full" type="text" autocomplete="off" role="combobox"
        [attr.id]="inputId()" [attr.aria-label]="label()" [placeholder]="placeholder()"
        aria-autocomplete="list" [attr.aria-expanded]="open()" [attr.aria-controls]="inputId() + '-list'"
        [attr.aria-activedescendant]="open() && active() >= 0 ? inputId() + '-opt-' + active() : null"
        [value]="text()" data-testid="instrument-search"
        (input)="onInput($any($event.target).value)" (focus)="open.set(text().length > 0)"
        (blur)="open.set(false)" (keydown)="onKeydown($event)" />
      <ul *ngIf="open() && matches().length > 0" role="listbox" [attr.id]="inputId() + '-list'"
        class="lt-card absolute left-0 right-0 z-20 mt-1 max-h-72 overflow-y-auto p-1" style="box-shadow: var(--shadow-lg)"
        data-testid="instrument-search-list">
        <li *ngFor="let ins of matches(); let i = index" role="option" [attr.id]="inputId() + '-opt-' + i"
          [attr.aria-selected]="i === active()" data-testid="instrument-search-option"
          class="flex cursor-pointer items-center justify-between gap-3 rounded-lg px-3 py-2 text-sm"
          [style.background]="i === active() ? 'var(--surface-2)' : 'transparent'"
          (mousedown)="$event.preventDefault()" (mouseenter)="active.set(i)" (click)="pick(ins)">
          <span class="min-w-0">
            <strong style="color: var(--text)">{{ ins.symbol }}</strong>
            <span class="block truncate text-xs" style="color: var(--muted)">{{ ins.name }}</span>
          </span>
          <span class="lt-badge lt-badge-muted">{{ ins.currency }}</span>
        </li>
      </ul>
      <p *ngIf="open() && text().length > 0 && matches().length === 0" class="lt-card absolute left-0 right-0 z-20 mt-1 p-3 text-sm"
        style="color: var(--muted)" data-testid="instrument-search-none">No instrument matches "{{ text() }}".</p>
    </div>
  `
})
export class InstrumentSearch {
  readonly instruments = input<InstrumentResponse[]>([]);
  readonly exclude = input<string[]>([]);
  readonly label = input('Search instruments');
  readonly placeholder = input('Search by symbol or name');
  readonly inputId = input('instrument-search');
  readonly picked = output<string>();

  readonly text = signal('');
  readonly open = signal(false);
  readonly active = signal(-1);

  /** Symbol starts-with first, then symbol contains, then name contains; excluded symbols left out. */
  readonly matches = computed(() => {
    const q = this.text().trim().toUpperCase();
    if (!q) {
      return [];
    }
    const skip = new Set(this.exclude());
    const rank = (ins: InstrumentResponse): number => {
      const sym = ins.symbol.toUpperCase();
      if (sym.startsWith(q)) return 0;
      if (sym.includes(q)) return 1;
      if ((ins.name ?? '').toUpperCase().includes(q)) return 2;
      return 3;
    };
    return this.instruments()
      .filter((ins) => !skip.has(ins.symbol) && rank(ins) < 3)
      .sort((a, b) => rank(a) - rank(b) || a.symbol.localeCompare(b.symbol))
      .slice(0, MAX_MATCHES);
  });

  onInput(value: string): void {
    this.text.set(value);
    this.open.set(value.trim().length > 0);
    this.active.set(-1);
  }

  onKeydown(event: KeyboardEvent): void {
    const count = this.matches().length;
    if (event.key === 'ArrowDown' && count > 0) {
      event.preventDefault();
      this.open.set(true);
      this.active.set((this.active() + 1) % count);
    } else if (event.key === 'ArrowUp' && count > 0) {
      event.preventDefault();
      this.active.set(this.active() <= 0 ? count - 1 : this.active() - 1);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const choice = this.matches()[this.active() >= 0 ? this.active() : 0];
      if (choice) {
        this.pick(choice);
      }
    } else if (event.key === 'Escape') {
      this.open.set(false);
    }
  }

  pick(ins: InstrumentResponse): void {
    this.picked.emit(ins.symbol);
    this.text.set('');
    this.open.set(false);
    this.active.set(-1);
  }
}
