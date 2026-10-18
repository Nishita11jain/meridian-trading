import { formatMoney } from '../../core/currency/currency';
import { Component, ElementRef, OnDestroy, OnInit, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import ApexCharts from 'apexcharts';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

import { WatchlistsService } from '../../core/watchlists/watchlists.service';
import { InstrumentResponse, MarketQuoteResponse } from '../../clients/watchlists-client/model/models';
import { closedMessage, isClosed } from '../../core/market/trading-status';
import { FeedRefresher } from '../../core/market/feed-refresher';


/**
 * The market board and its trend panel. Prices come from the platform's price cache (market_price,
 * through GET /api/v1/market/quotes), the trend from the tick history (price_history, through
 * GET /api/v1/market/history/{symbol}: the latest distinct price updates); neither calls the
 * market-data provider. Choosing a row shows
 * its trend; Trade opens the order ticket with that instrument chosen. Open/Closed and the closed Trade
 * button come from the API's trading status (the instrument list), never from the feed's market state.
 */
@Component({
  selector: 'app-market-board',
  imports: [CommonModule, RouterLink],
  template: `
    <div class="grid gap-4 xl:grid-cols-[minmax(0,1.7fr)_minmax(280px,1fr)]">
      <section class="lt-card lt-card-pad" aria-labelledby="market-board-title">
        <div class="lt-card-head">
          <h2 id="market-board-title" class="lt-section-title">Market board</h2>
          <!-- Market pulse: how many symbols on the board are up, down or unchanged today. -->
          <div class="mb-pulse" *ngIf="breadth() as b" data-testid="market-pulse"
            role="img" [attr.aria-label]="b.up + ' up, ' + b.down + ' down, ' + b.flat + ' unchanged'">
            <span class="mb-pulse__count" style="color: var(--up)">{{ b.up }} up</span>
            <span class="mb-pulse__bar" aria-hidden="true">
              <span [style.flex-grow]="b.up" style="background: var(--up)"></span>
              <span [style.flex-grow]="b.flat" style="background: var(--border-strong)"></span>
              <span [style.flex-grow]="b.down" style="background: var(--down)"></span>
            </span>
            <span class="mb-pulse__count" style="color: var(--down)">{{ b.down }} down</span>
          </div>
        </div>

        <p class="lt-alert lt-alert-down" data-testid="market-error" role="alert" *ngIf="errorMessage()">{{ errorMessage() }}</p>

        <div class="lt-empty" data-testid="market-empty" *ngIf="loaded() && quotes().length === 0 && !errorMessage()">
          <p>No prices yet. The feed publishes prices every few minutes.</p>
        </div>

        <div class="overflow-x-auto" *ngIf="quotes().length > 0">
          <table class="lt-table" data-testid="market-table">
            <thead>
              <tr>
                <th scope="col">Symbol</th>
                <th scope="col" class="text-right">Last price</th>
                <th scope="col" class="text-right">Change</th>
                <th scope="col" class="text-right">As of</th>
                <th scope="col" class="text-right"><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let q of quotes(); trackBy: trackBySymbol" data-testid="market-row" class="cursor-pointer"
                [style.background]="q.symbol === selectedSymbol() ? 'var(--accent-soft)' : null"
                (click)="selectSymbol(q.symbol)">
                <td class="whitespace-nowrap">
                  <button type="button" class="font-extrabold" style="color: var(--text)" data-testid="market-select"
                    [attr.aria-pressed]="q.symbol === selectedSymbol()"
                    (click)="selectSymbol(q.symbol); $event.stopPropagation()">{{ q.symbol }}</button>
                  <span *ngIf="statusOf(q.symbol) as st" class="ml-2 rounded-full px-1.5 py-0.5 text-[10px] font-extrabold uppercase"
                    [style.background]="st.tradingStatus === 'OPEN' ? 'var(--up-bg)' : 'var(--surface-3)'"
                    [style.color]="st.tradingStatus === 'OPEN' ? 'var(--up)' : 'var(--muted)'"
                    [attr.title]="st.tradingStatus === 'CLOSED' ? closedText(st) : st.market + ' is open'"
                    data-testid="market-state">{{ st.tradingStatus === 'OPEN' ? 'Open' : 'Closed' }}</span>
                  <span *ngIf="q.stale" class="ml-1 rounded-full px-1.5 py-0.5 text-[10px] font-extrabold uppercase"
                    style="background: var(--warn-bg); color: var(--warn)" data-testid="market-stale">Stale</span>
                </td>
                <td class="text-right tabular-nums">
                  <ng-container *ngIf="q.lastPrice != null; else noValue">
                    {{ money(q.lastPrice, q.currency) }}
                  </ng-container>
                </td>
                <td class="whitespace-nowrap text-right tabular-nums" [style.color]="changeColor(q.changePercent)" data-testid="market-change">
                  <span class="lt-move" aria-hidden="true" *ngIf="q.changePercent != null">
                    <span [class.lt-move--up]="q.changePercent > 0" [class.lt-move--down]="q.changePercent < 0" [style.width.%]="moveWidth(q.changePercent)"></span>
                  </span>{{ q.changePercent != null ? ((q.changePercent > 0 ? '+' : '') + (q.changePercent | number:'1.2-2') + '%') : '—' }}
                </td>
                <td class="text-right text-[13px] tabular-nums" style="color: var(--muted)">{{ q.quoteAsOf ? (q.quoteAsOf | date:'shortTime') : '—' }}</td>
                <td class="text-right">
                  <a *ngIf="!closed(q.symbol); else tradeClosed" [routerLink]="['/trade']" [queryParams]="{ symbol: q.symbol }"
                    class="lt-btn lt-btn-ghost lt-btn-sm" data-testid="market-trade" [attr.aria-label]="'Trade ' + q.symbol"
                    (click)="$event.stopPropagation()">Trade</a>
                  <ng-template #tradeClosed>
                    <a [routerLink]="['/trade']" [queryParams]="{ symbol: q.symbol }" class="lt-btn lt-btn-ghost lt-btn-sm"
                      data-testid="market-trade-closed" [attr.title]="closedText(statusOf(q.symbol)!)"
                      [attr.aria-label]="'Queue a trade for ' + q.symbol + '. ' + closedText(statusOf(q.symbol)!)"
                      (click)="$event.stopPropagation()">Queue</a>
                  </ng-template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <ng-template #noValue><span style="color: var(--muted)">—</span></ng-template>
      </section>

      <aside class="lt-card lt-card-pad" aria-labelledby="market-trend-title" data-testid="market-trend">
        <p class="lt-eyebrow">Recent trend</p>
        <h3 id="market-trend-title" class="mt-0.5 text-[17px] font-extrabold tracking-tight" style="color: var(--text)">
          {{ selectedSymbol() ?? 'Choose a symbol' }}
        </h3>

        <ng-container *ngIf="selectedQuote() as quote; else trendPrompt">
          <dl class="mt-3 grid gap-2 text-sm">
            <div class="flex items-center justify-between">
              <dt style="color: var(--muted)">Last price</dt>
              <dd class="tabular-nums font-semibold" style="color: var(--text)">
                {{ quote.lastPrice != null ? money(quote.lastPrice, quote.currency) : '—' }}</dd>
            </div>
            <div class="flex items-center justify-between">
              <dt style="color: var(--muted)">Change</dt>
              <dd class="tabular-nums font-semibold" [style.color]="changeColor(quote.changePercent)">
                {{ quote.changePercent != null ? ((quote.changePercent > 0 ? '+' : '') + (quote.changePercent | number:'1.2-2') + '%') : '—' }}</dd>
            </div>
            <div class="flex items-center justify-between" *ngIf="quote.previousClose != null">
              <dt style="color: var(--muted)">Previous close</dt>
              <dd class="tabular-nums" style="color: var(--text)">{{ quote.previousClose | number:'1.2-2' }}</dd>
            </div>
          </dl>

          <div class="mt-3" data-testid="sparkline-panel">
            <div *ngIf="sparklinePoints()" #sparkHost class="h-24 w-full" role="img"
              [attr.aria-label]="'Price trend for ' + selectedSymbol()" data-testid="sparkline"></div>
            <p class="mt-2 text-xs" style="color: var(--muted)" *ngIf="sparklinePoints() && !flat()" data-testid="sparkline-caption">
              {{ historyLength() }} price updates since {{ firstAt() | date:'MMM d, h:mm a' }}.
            </p>
            <p class="mt-2 text-xs" style="color: var(--muted)" *ngIf="flat()" data-testid="sparkline-flat">
              No price change across the last {{ historyLength() }} updates, since {{ firstAt() | date:'MMM d, h:mm a' }}.
            </p>
            <p class="text-sm" style="color: var(--muted)" *ngIf="historyLength() === 1" data-testid="sparkline-single">
              Only one price so far ({{ lastAt() | date:'MMM d, h:mm a' }}). The trend appears once the price changes.
            </p>
            <p class="text-sm" style="color: var(--muted)" *ngIf="historyLength() === 0" data-testid="sparkline-empty">
              No history yet for {{ selectedSymbol() }}.
            </p>
          </div>

          <ng-container *ngIf="statusOf(quote.symbol) as st; else tradeSelected">
            <ng-container *ngIf="st.tradingStatus === 'CLOSED'; else tradeSelected">
              <p class="mt-4 rounded-xl px-3 py-2 text-sm" style="background: var(--surface-2); color: var(--muted)" data-testid="market-trend-closed">
                {{ closedText(st) }}
              </p>
              <a [routerLink]="['/trade']" [queryParams]="{ symbol: quote.symbol }" class="lt-btn lt-btn-primary mt-3 w-full justify-center"
                data-testid="market-trend-trade-closed">Queue a trade for {{ quote.symbol }}</a>
            </ng-container>
          </ng-container>
          <ng-template #tradeSelected>
            <a [routerLink]="['/trade']" [queryParams]="{ symbol: quote.symbol }" class="lt-btn lt-btn-primary mt-3 w-full justify-center py-2"
              data-testid="market-trend-trade">Trade {{ quote.symbol }}</a>
          </ng-template>
        </ng-container>

        <ng-template #trendPrompt>
          <p class="mt-4 text-sm leading-relaxed" style="color: var(--muted)">
            Choose a symbol on the board to see its latest price and recent trend.
          </p>
        </ng-template>
      </aside>
    </div>
  `,
  styles: [`
    .mb-pulse { display: inline-flex; align-items: center; gap: .5rem; font-size: .72rem; font-weight: 700; }
    .mb-pulse__count { font-family: var(--font-num); white-space: nowrap; }
    .mb-pulse__bar { display: flex; gap: 2px; width: 120px; height: 6px; }
    .mb-pulse__bar > span { min-width: 0; border-radius: 999px; flex-basis: 0; transition: flex-grow .5s ease; }
  `]
})
export class MarketBoard implements OnInit, OnDestroy {
  private readonly service = inject(WatchlistsService);
  /** Re-reads prices just after each new batch from the feed, not on a fixed timer. */
  private readonly refresher = new FeedRefresher(() => this.service.getFreshness(), () => this.load());

  readonly quotes = signal<MarketQuoteResponse[]>([]);
  readonly loaded = signal(false);
  readonly errorMessage = signal('');
  readonly selectedSymbol = signal<string | null>(null);
  /** The selected symbol's price updates, oldest first. */
  private readonly history = signal<{ price: number; at: string | null }[]>([]);
  readonly prices = computed(() => this.history().map((h) => h.price));

  readonly selectedQuote = computed(() => this.quotes().find((q) => q.symbol === this.selectedSymbol()) ?? null);
  readonly historyLength = computed(() => this.history().length);
  readonly firstAt = computed(() => this.history()[0]?.at ?? null);
  readonly lastAt = computed(() => this.history()[this.history().length - 1]?.at ?? null);
  /** Two or more updates, all at the same price: drawn as a level line and said in words. */
  readonly flat = computed(() => {
    const values = this.prices();
    return values.length >= 2 && Math.min(...values) === Math.max(...values);
  });
  /** Advancers, decliners and unchanged among the symbols that have a change today; null with none. */
  readonly breadth = computed(() => {
    const moves = this.quotes().map((q) => q.changePercent).filter((c): c is number => c != null);
    if (moves.length === 0) {
      return null;
    }
    return {
      up: moves.filter((c) => c > 0).length,
      down: moves.filter((c) => c < 0).length,
      flat: moves.filter((c) => c === 0).length
    };
  });

  /** The largest move on the board, so each row's bar is drawn against it. */
  private readonly maxMove = computed(() =>
    Math.max(0, ...this.quotes().map((q) => Math.abs(Number(q.changePercent ?? 0)))));

  /** Half the bar is the largest move: a row's bar fills that half in proportion to its own move. */
  moveWidth(changePercent: number | null | undefined): number {
    const max = this.maxMove();
    return changePercent == null || max === 0 ? 0 : (Math.abs(Number(changePercent)) / max) * 50;
  }

  /** Each instrument's market state, as the API reports it (it owns the trading hours). */
  private readonly statuses = signal<Map<string, InstrumentResponse>>(new Map());

  /** The sparkline polyline, or null with fewer than two prices. Scaled into a 100x28 viewBox. */
  private readonly sparkHost = viewChild<ElementRef<HTMLElement>>('sparkHost');
  private spark: ApexCharts | null = null;
  private readonly sparkEffect = effect(() => {
    this.prices();
    this.trendColor();
    untracked(() => queueMicrotask(() => this.drawSpark()));
  });

  /** The price trend as a small ApexCharts area: the same line the table shows, drawn with a soft fill. */
  private drawSpark(): void {
    const host = this.sparkHost()?.nativeElement;
    const values = this.prices();
    this.spark?.destroy();
    this.spark = null;
    if (!host || values.length < 2) {
      return;
    }
    const colour = this.trendColor();
    this.spark = new ApexCharts(host, {
      chart: { type: 'area', sparkline: { enabled: true }, height: 96, animations: { enabled: false }, background: 'transparent' },
      series: [{ name: 'Price', data: values }],
      colors: [colour],
      stroke: { curve: 'smooth', width: 2 },
      fill: { type: 'gradient', gradient: { shade: 'light', type: 'vertical', opacityFrom: 0.35, opacityTo: 0.02, stops: [0, 100] } },
      tooltip: { enabled: false },
      dataLabels: { enabled: false }
    });
    this.spark.render();
  }

  readonly sparklinePoints = computed(() => {
    const values = this.prices();
    if (values.length < 2) {
      return null;
    }
    const min = Math.min(...values);
    const max = Math.max(...values);
    if (min === max) {
      // A level line across the middle, not along the bottom edge where it reads as missing.
      return '0,14 100,14';
    }
    const range = max - min;
    const step = 100 / (values.length - 1);
    return values
      .map((v, i) => `${(i * step).toFixed(1)},${(26 - ((v - min) / range) * 24).toFixed(1)}`)
      .join(' ');
  });

  /** Green when the trend ends higher than it started, red when lower, neutral when flat. */
  readonly trendColor = computed(() => {
    const values = this.prices();
    const delta = values.length < 2 ? 0 : values[values.length - 1] - values[0];
    return delta > 0 ? 'var(--up)' : delta < 0 ? 'var(--down)' : 'var(--muted)';
  });

  ngOnInit(): void {
    this.refresher.start();
  }

  ngOnDestroy(): void {
    this.refresher.stop();
    this.spark?.destroy();
    this.spark = null;
  }

  private load(): void {
    // Open/Closed comes with the instrument list; a failure leaves the tags off rather than guessing.
    this.service.getInstruments().subscribe({
      next: (instruments) => this.statuses.set(new Map(instruments.map((i) => [i.symbol, i] as [string, InstrumentResponse]))),
      error: () => undefined
    });
    this.service.getQuotes().subscribe({
      next: (quotes) => {
        this.quotes.set(quotes);
        this.loaded.set(true);
        this.errorMessage.set('');
        // Open the first symbol's trend so the panel is useful straight away.
        if (this.selectedSymbol() === null && quotes.length > 0) {
          this.selectSymbol(quotes[0].symbol);
        }
      },
      error: () => {
        this.loaded.set(true);
        this.errorMessage.set('Market prices could not be loaded. They will be retried shortly.');
      }
    });
  }

  selectSymbol(symbol: string): void {
    if (symbol === this.selectedSymbol() && this.history().length > 0) {
      return;
    }
    this.selectedSymbol.set(symbol);
    this.history.set([]);
    this.service.getHistory(symbol).subscribe({
      // History is newest first; reversed into time order for the line.
      next: (ticks) => this.history.set(ticks
        .map((t) => ({ price: Number(t.lastPrice), at: t.quoteAsOf ?? null }))
        .filter((t) => !Number.isNaN(t.price))
        .reverse()),
      error: () => this.history.set([])
    });
  }

  statusOf(symbol: string): InstrumentResponse | null {
    return this.statuses().get(symbol) ?? null;
  }

  closed(symbol: string): boolean {
    return isClosed(this.statusOf(symbol));
  }

  closedText(instrument: InstrumentResponse): string {
    return closedMessage(instrument);
  }

  changeColor(changePercent: number | null | undefined): string {
    if (changePercent == null || changePercent === 0) {
      return 'var(--muted)';
    }
    return changePercent > 0 ? 'var(--up)' : 'var(--down)';
  }

  /** A price in its own currency with the symbol: $529.76, ₹3,500.00. */
  money(amount: number | string, currency: string | null | undefined): string {
    return formatMoney(Number(amount), currency);
  }

  trackBySymbol(_: number, quote: MarketQuoteResponse): string {
    return quote.symbol;
  }
}
