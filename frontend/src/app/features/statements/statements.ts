import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { AuthService } from '../../core/auth/auth.service';
import { messageFor } from '../../core/errors/error-messages';
import { CashLine, StatementsService, TradeLine, calendarYear, financialYear, saveFile } from '../../core/statements/statements.service';
import { AppShell } from '../../shared/layout/app-shell';
import { SearchField } from '../../shared/search/search-field';
import { createMatcher } from '../../core/search/text-matcher';

type Tab = 'trades' | 'cash';
type Preset = 'financial' | 'calendar' | 'custom';

/**
 * Statements: what the account bought and sold, and its cash movements, over a date range, with a CSV download
 * for each and a print view that the browser saves as PDF. A statement is a record of what happened; the
 * blotter is the live view of orders.
 *
 * Layout follows common brokerage statement screens: a segmented tab control, a preset date range with visible
 * From and To fields, a summary above the table, right-aligned figures, and an explicit empty state.
 */
@Component({
  selector: 'app-statements',
  imports: [CommonModule, FormsModule, AppShell, SearchField],
  template: `
    <app-shell title="Statements">
      <div class="stmt">
        <section class="stmt__bar lt-card lt-appear" data-testid="statements-controls">
          <div class="lt-seg" role="tablist" aria-label="Statement type">
            <button type="button" role="tab" data-testid="statements-tab-trades"
              [attr.aria-selected]="tab() === 'trades'" [class.is-on]="tab() === 'trades'" (click)="setTab('trades')">Trades</button>
            <button type="button" role="tab" data-testid="statements-tab-cash"
              [attr.aria-selected]="tab() === 'cash'" [class.is-on]="tab() === 'cash'" (click)="setTab('cash')">Cash</button>
          </div>

          <label class="stmt__field stmt__field--period">
            <span>Period</span>
            <select class="lt-select" data-testid="statements-preset" [(ngModel)]="preset" (ngModelChange)="applyPreset()">
              <option value="financial">Indian financial year (Apr–Mar)</option>
              <option value="calendar">Calendar year</option>
              <option value="custom">Custom range</option>
            </select>
          </label>
          <label class="stmt__field">
            <span>From</span>
            <input class="lt-input" type="date" data-testid="statements-from" [(ngModel)]="from" (ngModelChange)="onRangeEdited()" />
          </label>
          <label class="stmt__field">
            <span>To</span>
            <input class="lt-input" type="date" data-testid="statements-to" [(ngModel)]="to" (ngModelChange)="onRangeEdited()" />
          </label>

          <div class="stmt__actions">
            <button type="button" class="lt-btn lt-btn-ghost" data-testid="statements-csv"
              [disabled]="downloading() || accountId() === null" (click)="downloadCsv()">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M12 4v11m0 0l-4-4m4 4l4-4M5 20h14" stroke-linecap="round" stroke-linejoin="round"></path></svg>
              {{ downloading() ? 'Preparing…' : 'CSV' }}
            </button>
            <button type="button" class="lt-btn lt-btn-primary" data-testid="statements-print" (click)="print()">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M7 9V4h10v5M7 17H5a1 1 0 0 1-1-1v-5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v5a1 1 0 0 1-1 1h-2M7 14h10v6H7z" stroke-linejoin="round"></path></svg>
              Print or PDF
            </button>
          </div>
        </section>

        @if (errorMessage()) {
          <div class="lt-alert lt-alert-down" data-testid="statements-error" role="alert">{{ errorMessage() }}</div>
        }

        <section id="statement-print" class="stmt__sheet lt-card lt-appear lt-appear-2">
          <header class="stmt__head">
            <div>
              <h2>{{ tab() === 'trades' ? 'Trade history' : 'Cash ledger' }}</h2>
              <p class="stmt__period" data-testid="statements-range">{{ from | date:'d MMM y' }} to {{ to | date:'d MMM y' }}
                · <span data-testid="statements-count">{{ rowCount() }} {{ rowCount() === 1 ? 'entry' : 'entries' }}</span>
                @if (!matcher().empty) { <span>({{ visibleCount() }} shown)</span> }
              </p>
            </div>
            <app-search-field class="stmt__search" testId="statements-search" width="240px"
              [label]="tab() === 'trades' ? 'Search symbol, side or order' : 'Search type or date'"
              [placeholder]="tab() === 'trades' ? 'Search symbol, side or order' : 'Search type or date'"
              [(query)]="query" [(regex)]="queryRegex"></app-search-field>
          </header>

          @if (!loaded()) {
            <p class="stmt__muted" data-testid="statements-loading">Loading…</p>
          } @else if (tab() === 'trades') {
            <dl class="lt-strip stmt__summary" data-testid="statements-summary">
              <div class="lt-stat"><dt class="lt-stat__label">Buys</dt><dd class="lt-stat__value up">{{ buys() }}</dd></div>
              <div class="lt-stat"><dt class="lt-stat__label">Sells</dt><dd class="lt-stat__value down">{{ sells() }}</dd></div>
              <div class="lt-stat"><dt class="lt-stat__label">Fills</dt><dd class="lt-stat__value">{{ trades().length }}</dd></div>
            </dl>
            @if (trades().length === 0) {
              <p class="stmt__empty" data-testid="statements-empty">No trades in this period.</p>
            } @else if (visibleTrades().length === 0) {
              <p class="stmt__empty" data-testid="statements-no-match">No trades match the search.</p>
            } @else {
              <div class="stmt__scroll">
                <table class="lt-table" data-testid="statements-trades-table">
                  <thead>
                    <tr>
                      <th scope="col">Date</th><th scope="col">Time</th><th scope="col">Order</th><th scope="col">Symbol</th><th scope="col">Side</th>
                      <th scope="col" class="text-right">Qty</th><th scope="col" class="text-right">Price</th><th scope="col">Ccy</th><th scope="col" class="text-right">Value</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (t of visibleTrades(); track t.orderId + t.executedAt) {
                      <tr data-testid="statements-trade-row">
                        <td class="tabular-nums">{{ t.executedAt | slice:0:10 }}</td>
                        <td class="tabular-nums muted">{{ t.executedAt | slice:11:19 }}</td>
                        <td class="tabular-nums muted" [attr.title]="t.orderId">{{ t.orderId | slice:0:8 }}</td>
                        <td class="font-extrabold">{{ t.symbol }}</td>
                        <td><span class="lt-side" [class.lt-side-buy]="t.side === 'BUY'" [class.lt-side-sell]="t.side === 'SELL'"><span aria-hidden="true">{{ t.side === 'BUY' ? '↗' : '↘' }}</span>{{ t.side }}</span></td>
                        <td class="text-right tabular-nums">{{ t.quantity }}</td>
                        <td class="text-right tabular-nums">{{ t.price | number:'1.2-2' }}</td>
                        <td class="muted">{{ t.currency }}</td>
                        <td class="text-right tabular-nums font-semibold">{{ t.value | number:'1.2-2' }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          } @else {
            <dl class="lt-strip stmt__summary" data-testid="statements-summary">
              <div class="lt-stat"><dt class="lt-stat__label">Money in</dt><dd class="lt-stat__value up">{{ moneyIn() | number:'1.2-2' }}</dd></div>
              <div class="lt-stat"><dt class="lt-stat__label">Money out</dt><dd class="lt-stat__value down">{{ moneyOut() | number:'1.2-2' }}</dd></div>
              <div class="lt-stat"><dt class="lt-stat__label">Net</dt><dd class="lt-stat__value" [class.up]="net() > 0" [class.down]="net() < 0">{{ net() | number:'1.2-2' }}</dd></div>
            </dl>
            @if (cash().length === 0) {
              <p class="stmt__empty" data-testid="statements-empty">No cash movements in this period.</p>
            } @else if (visibleCash().length === 0) {
              <p class="stmt__empty" data-testid="statements-no-match">No cash movements match the search.</p>
            } @else {
              <div class="stmt__scroll">
                <table class="lt-table" data-testid="statements-cash-table">
                  <thead>
                    <tr><th scope="col">Date</th><th scope="col">Time</th><th scope="col">Type</th><th scope="col" class="text-right">Amount</th></tr>
                  </thead>
                  <tbody>
                    @for (c of visibleCash(); track c.createdAt + c.type + $index) {
                      <tr data-testid="statements-cash-row">
                        <td class="tabular-nums">{{ c.createdAt | slice:0:10 }}</td>
                        <td class="tabular-nums muted">{{ c.createdAt | slice:11:19 }}</td>
                        <td><span class="lt-dot" [class.up]="c.amount > 0" [class.down]="c.amount < 0" [class.muted]="c.amount === 0">{{ c.type }}</span></td>
                        <td class="text-right tabular-nums font-semibold" [class.up]="c.amount > 0" [class.down]="c.amount < 0">{{ c.amount | number:'1.2-2' }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          }
        </section>
      </div>
    </app-shell>
  `,
  styles: [`
    /* Every colour comes from the theme tokens, so light and dark both work (the old fallbacks were light-only). */
    .stmt { display: grid; gap: 12px; max-width: 1180px; color: var(--text); }

    /* Controls: one row that wraps on narrow screens. */
    .stmt__bar { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 10px 14px; padding: 12px 16px; }
    .stmt__field { display: grid; gap: 4px; min-width: 140px; }
    .stmt__field--period { min-width: 230px; }
    .stmt__field > span { font-size: 11px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; color: var(--muted); }
    .stmt__field .lt-input, .stmt__field .lt-select { height: 34px; padding-block: 0; }
    .stmt__actions { display: flex; flex-wrap: wrap; gap: 8px; margin-left: auto; }
    .stmt__actions .lt-btn { height: 34px; }

    /* Sheet */
    .stmt__sheet { padding: 14px 18px 16px; }
    .stmt__head { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 12px; }
    .stmt__head h2 { margin: 0; font-size: 17px; font-weight: 750; color: var(--text); }
    .stmt__period { margin: 2px 0 0; font-size: 12.5px; color: var(--muted); }
    .stmt__summary { margin: 0 0 12px; padding-bottom: 12px; border-bottom: 1px solid var(--border); }
    .stmt__summary dd { margin: 0; }
    .stmt__scroll { overflow-x: auto; }

    .muted { color: var(--muted); }
    .up { color: var(--up); }
    .down { color: var(--down); }

    .stmt__empty, .stmt__muted { color: var(--muted); font-size: 14px; padding: 14px 0 4px; margin: 0; }

    @media print {
      .lt-sidebar, .lt-workspace__topbar, .stmt__bar, .lt-alert, .stmt__search { display: none !important; }
      .stmt__sheet { box-shadow: none; border: none; padding: 0; }
    }
  `]
})
export class Statements implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly statements = inject(StatementsService);

  readonly tab = signal<Tab>('trades');
  readonly preset = signal<Preset>('financial');
  from = '';
  to = '';
  readonly accountId = signal<number | null>(null);
  readonly trades = signal<TradeLine[]>([]);
  readonly cash = signal<CashLine[]>([]);
  readonly loaded = signal(false);
  readonly downloading = signal(false);
  readonly errorMessage = signal('');

  /** The search box above the table: plain text, or a regular expression with the .* toggle. */
  readonly query = signal('');
  readonly queryRegex = signal(false);
  readonly matcher = computed(() => createMatcher(this.query(), this.queryRegex()));

  /** The rows the table shows. The summary above it always covers the whole period, not just the matches. */
  readonly visibleTrades = computed(() => {
    const m = this.matcher();
    return m.empty ? this.trades() : this.trades().filter((t) => m.test(t.symbol, t.side, t.orderId, t.currency, t.executedAt));
  });
  readonly visibleCash = computed(() => {
    const m = this.matcher();
    return m.empty ? this.cash() : this.cash().filter((c) => m.test(c.type, c.createdAt, c.amount));
  });
  readonly visibleCount = computed(() => (this.tab() === 'trades' ? this.visibleTrades().length : this.visibleCash().length));

  readonly rowCount = computed(() => (this.tab() === 'trades' ? this.trades().length : this.cash().length));

  /** Counts of buys and sells. Values are not totalled here: fills can be in different currencies. */
  readonly buys = computed(() => this.trades().filter((t) => t.side === 'BUY').length);
  readonly sells = computed(() => this.trades().filter((t) => t.side === 'SELL').length);

  /** Summary of cash: what came in, what went out, and the net. */
  readonly moneyIn = computed(() => this.cash().filter((c) => c.amount > 0).reduce((sum, c) => sum + c.amount, 0));
  readonly moneyOut = computed(() => this.cash().filter((c) => c.amount < 0).reduce((sum, c) => sum + c.amount, 0));
  readonly net = computed(() => this.moneyIn() + this.moneyOut());

  ngOnInit(): void {
    this.applyPresetRange(financialYear(new Date()));
    this.auth.getProfile().subscribe({
      next: (profile) => {
        this.accountId.set(profile.accountId);
        this.load();
      },
      error: (err) => this.errorMessage.set(messageFor(err))
    });
  }

  setTab(tab: Tab): void {
    this.tab.set(tab);
    this.load();
  }

  applyPreset(): void {
    if (this.preset() === 'financial') {
      this.applyPresetRange(financialYear(new Date()));
    } else if (this.preset() === 'calendar') {
      this.applyPresetRange(calendarYear(new Date()));
    }
    this.load();
  }

  /** Editing a date by hand makes the range custom, so the preset select says what is shown. */
  onRangeEdited(): void {
    this.preset.set('custom');
    this.load();
  }

  load(): void {
    const accountId = this.accountId();
    if (accountId === null || !this.from || !this.to) {
      return;
    }
    this.loaded.set(false);
    this.errorMessage.set('');
    if (this.tab() === 'trades') {
      this.statements.trades(accountId, this.from, this.to).subscribe({
        next: (rows) => { this.trades.set(rows); this.loaded.set(true); },
        error: (err) => { this.errorMessage.set(messageFor(err)); this.loaded.set(true); }
      });
    } else {
      this.statements.cash(accountId, this.from, this.to).subscribe({
        next: (rows) => { this.cash.set(rows); this.loaded.set(true); },
        error: (err) => { this.errorMessage.set(messageFor(err)); this.loaded.set(true); }
      });
    }
  }

  downloadCsv(): void {
    const accountId = this.accountId();
    if (accountId === null || this.downloading()) {
      return;
    }
    this.downloading.set(true);
    const kind = this.tab();
    this.statements.downloadCsv(accountId, kind, this.from, this.to).subscribe({
      next: (blob) => {
        this.downloading.set(false);
        saveFile(blob, `${kind}-${this.from}-to-${this.to}.csv`);
      },
      error: (err) => {
        this.downloading.set(false);
        this.errorMessage.set(messageFor(err));
      }
    });
  }

  /** The browser's print dialog saves the statement as a PDF; the print styles hide the navigation and controls. */
  print(): void {
    window.print();
  }

  private applyPresetRange(range: { from: string; to: string }): void {
    this.from = range.from;
    this.to = range.to;
  }
}
