import { Component, ElementRef, OnDestroy, OnInit, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import ApexCharts, { ApexOptions } from 'apexcharts';

import { AuthService } from '../../core/auth/auth.service';
import { PortfolioService } from '../../core/portfolio/portfolio.service';
import { DisplayCurrencyService } from '../../core/currency/display-currency.service';
import { formatMoney } from '../../core/currency/currency';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';
import { messageFor } from '../../core/errors/error-messages';
import { PortfolioSummary, PricedPosition } from '../../clients/portfolio-client/model/models';
import { AppShell } from '../../shared/layout/app-shell';
import { PortfolioChart } from './portfolio-chart';
import { ThemeService } from '../../core/theme/theme.service';
import { MotionService } from '../../core/motion/motion.service';
import { SearchField } from '../../shared/search/search-field';
import { createMatcher } from '../../core/search/text-matcher';

/**
 * Slice colours. Every position gets one, cycling through this palette in order of size, so two neighbours
 * never share a colour until there are more holdings than colours. Muted mid-tones built around the brand's
 * lilac and amber, readable on both themes, with no pure red or green (those mean price direction).
 */
const DONUT_PALETTE = [
  '#8F7FE0', '#E0A872', '#5FA8C9', '#C98BB9', '#7FB7A4', '#D9C27A',
  '#6C7BD9', '#B9876B', '#9AA7C7', '#E3B9CE', '#5E9C8E', '#BFA3E8'
] as const;

/** One currency's line in the summary strip. */
interface CurrencySummary {
  currency: string;
  currentValue: number;
  invested: number;
  unrealised: number;
  unrealisedPercent: number | null;
  realised: number;
  /** The day's change on what is held: each holding's change per unit (from its quote) x quantity. Null when no quote has one. */
  today: number | null;
}

/** One slice of the allocation donut: one position, with its share and colour. */
interface Slice {
  label: string;
  value: number;
  share: number;
  colour: string;
}

/**
 * Portfolio (Sprint 10), laid out as brokers do: a summary per currency (current value, invested, total
 * and today's P&L), the portfolio's value over time, one allocation donut (largest holdings, the rest as "Other"), and the holdings table
 * with each holding's share and P&L. Amounts are never converted between currencies, so each currency
 * has its own summary line and its own donut. Prices come from the platform's cache; a stale or missing
 * price is shown and flagged, never hidden.
 */
@Component({
  selector: 'app-portfolio',
  imports: [CommonModule, AppShell, PortfolioChart, SearchField],
  template: `
    <app-shell title="Portfolio & P&L">
      <div class="grid gap-4">
        <div class="lt-alert lt-alert-down" data-testid="portfolio-error" role="alert" *ngIf="errorMessage()">{{ errorMessage() }}</div>

        <div class="lt-alert lt-alert-warn" data-testid="portfolio-partial" role="status" *ngIf="summary()?.partial">
          Some holdings could not be priced just now, so these figures are incomplete. The unpriced rows are marked stale.
        </div>

        <div class="lt-empty" data-testid="portfolio-loading" *ngIf="!loaded() && !errorMessage()">
          <p>Loading your portfolio...</p>
        </div>

        <!-- Summary: one line, in the base currency -->
        <section *ngIf="summary() as s" class="lt-card lt-card-pad lt-appear" aria-labelledby="portfolio-summary-title">
          <div class="lt-card-head">
            <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <h2 id="portfolio-summary-title" class="lt-section-title">Summary</h2>
              <p class="text-[13px]" style="color: var(--muted)" data-testid="portfolio-cash">
                Cash <strong class="tabular-nums" style="color: var(--text)">{{ cur.show(s.cashBalance, 'INR') }}</strong>
                · {{ s.positionCount }} {{ s.positionCount === 1 ? 'holding' : 'holdings' }}
              </p>
            </div>
            <span class="lt-dot" [style.color]="s.partial ? 'var(--warn)' : 'var(--up)'">
              {{ s.partial ? 'Partial valuation' : 'Fully priced' }}
            </span>
          </div>

          <div class="pf-rows">
            <div *ngFor="let c of currencySummaries()" class="pf-row" data-testid="portfolio-currency-card">
              <span class="pf-ccy">{{ cur.preferred() }}</span>
              <div class="lt-strip">
                <div class="lt-stat">
                  <p class="lt-stat__label">Current value</p>
                  <p class="lt-stat__value" data-testid="portfolio-current">{{ cur.show(c.currentValue, 'INR') }}</p>
                </div>
                <div class="lt-stat">
                  <p class="lt-stat__label">Invested</p>
                  <p class="lt-stat__value" data-testid="portfolio-invested">{{ cur.show(c.invested, 'INR') }}</p>
                </div>
                <div class="lt-stat">
                  <p class="lt-stat__label">Total P&amp;L</p>
                  <p class="lt-stat__value" [style.color]="signColour(c.unrealised)" data-testid="portfolio-total-pnl">
                    {{ cur.showSigned(c.unrealised, 'INR') }}
                    <span class="text-[12px] font-semibold" *ngIf="c.unrealisedPercent != null">({{ signedPercent(c.unrealisedPercent) }})</span>
                  </p>
                  <p class="lt-stat__sub tabular-nums" *ngIf="c.realised !== 0" data-testid="portfolio-realised">Realised {{ cur.showSigned(c.realised, 'INR') }}</p>
                </div>
                <div class="lt-stat">
                  <p class="lt-stat__label">Today's P&amp;L</p>
                  <p class="lt-stat__value" [style.color]="signColour(c.today)" data-testid="portfolio-today">
                    {{ c.today == null ? '—' : cur.showSigned(c.today, 'INR') }}
                  </p>
                  <p class="lt-stat__sub">From each rupee holding's latest session change</p>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- Value over time -->
        <app-portfolio-chart class="lt-appear lt-appear-2" *ngIf="accountId() as id" [accountId]="id"></app-portfolio-chart>

        <div class="grid gap-4 lt-appear lt-appear-3 xl:grid-cols-[minmax(300px,.75fr)_minmax(0,1.25fr)]" *ngIf="positions().length > 0">
          <!-- Allocation: one donut per currency -->
          <section class="lt-card lt-card-pad" aria-labelledby="allocation-title" data-testid="allocation-chart">
            <div class="lt-card-head">
              <h2 id="allocation-title" class="lt-section-title">Allocation</h2>
              <div class="lt-seg" role="tablist" aria-label="Currency" *ngIf="allocationCurrencies().length > 1">
                <button *ngFor="let cur of allocationCurrencies()" type="button" role="tab"
                  [attr.aria-selected]="cur === allocationCurrency()" data-testid="allocation-currency" (click)="chosenCurrency.set(cur)">{{ cur }}</button>
              </div>
            </div>

            <div class="flex flex-col gap-5" *ngIf="slices().length > 0; else nothingPriced">
              <div class="w-full">
                <div class="mb-2 flex items-baseline justify-between text-sm" data-testid="allocation-readout">
                  <span style="color: var(--muted)">{{ hoveredSlice()?.label ?? (allocationCurrency() + ' total') }}</span>
                  <strong class="tabular-nums" style="color: var(--text)" data-testid="allocation-centre">
                    {{ hoveredSlice() ? (hoveredSlice()!.share | number:'1.0-1') + '%' : money(allocationTotal(), allocationCurrency()) }}
                  </strong>
                </div>
                <div #donutHost class="mx-auto h-72 w-full max-w-[300px]" role="img" data-testid="allocation-donut"
                  [attr.aria-label]="donutLabel()"></div>
              </div>

              <ul class="grid min-w-[180px] flex-1 gap-2 pr-1 text-sm max-h-80 overflow-y-auto" data-testid="allocation-legend">
                <li *ngFor="let s of slices()" class="flex items-center justify-between gap-3 rounded-lg px-2 py-1"
                  [style.background]="hovered() === s.label ? 'var(--surface-2)' : null"
                  (mouseenter)="hovered.set(s.label)" (mouseleave)="hovered.set(null)" data-testid="allocation-legend-item">
                  <span class="inline-flex items-center gap-2 font-semibold" style="color: var(--text)">
                    <span class="h-2.5 w-2.5 rounded-full" [style.background]="s.colour"></span>{{ s.label }}
                  </span>
                  <span class="tabular-nums" style="color: var(--muted)">
                    <strong style="color: var(--text)">{{ s.share | number:'1.1-1' }}%</strong> · {{ money(s.value, allocationCurrency()) }}
                  </span>
                </li>
              </ul>
            </div>
            <ng-template #nothingPriced>
              <p class="text-sm" style="color: var(--muted)">No holding in {{ allocationCurrency() }} has a price yet.</p>
            </ng-template>
          </section>

          <!-- Holdings -->
          <section class="lt-card lt-card-pad" aria-labelledby="holdings-title">
            <div class="lt-card-head">
              <h2 id="holdings-title" class="lt-section-title">Holdings <span class="lt-seg__count">{{ visiblePositions().length }}</span></h2>
              <app-search-field testId="positions-search" label="Search holdings" placeholder="Search symbol or currency" width="220px"
                [(query)]="query" [(regex)]="queryRegex"></app-search-field>
            </div>
            <p class="lt-empty" data-testid="positions-no-match" *ngIf="visiblePositions().length === 0">No holdings match the search.</p>
            <div class="overflow-x-auto" *ngIf="visiblePositions().length > 0">
              <table class="lt-table" data-testid="positions-table">
                <thead>
                  <tr>
                    <th scope="col">Symbol</th>
                    <th scope="col" class="text-right">Qty</th>
                    <th scope="col" class="text-right">Avg cost</th>
                    <th scope="col" class="text-right">Last</th>
                    <th scope="col" class="text-right">Current value</th>
                    <th scope="col" class="text-right">P&amp;L</th>
                    <th scope="col" class="w-[130px]">Allocation</th>
                  </tr>
                </thead>
                <tbody>
                  <tr *ngFor="let p of visiblePositions(); trackBy: trackBySymbol" data-testid="position-row">
                    <td class="whitespace-nowrap">
                      <span class="font-extrabold">{{ p.symbol }}</span>
                      <span class="ml-1.5 text-[11px]" style="color: var(--muted)">{{ p.currency }}</span>
                      <span *ngIf="p.stale" class="lt-badge lt-badge-warn ml-1" data-testid="position-stale">Stale</span>
                    </td>
                    <td class="text-right tabular-nums">{{ p.quantity | number }}</td>
                    <td class="text-right tabular-nums" style="color: var(--muted)">{{ money(p.averageCost, p.currency) }}</td>
                    <td class="text-right tabular-nums">{{ p.lastPrice != null ? money(p.lastPrice, p.currency) : '—' }}</td>
                    <td class="text-right tabular-nums font-semibold">{{ p.marketValue != null ? cur.show(p.marketValue, p.currency) : '—' }}</td>
                    <td class="whitespace-nowrap text-right tabular-nums font-semibold" [style.color]="signColour(p.unrealisedPnl)" data-testid="position-pnl">
                      <ng-container *ngIf="p.unrealisedPnl != null; else noPrice">
                        {{ cur.showSigned(p.unrealisedPnl, p.currency) }}
                        <span class="block text-[11px]" *ngIf="p.unrealisedPnlPercent != null">{{ signedPercent(p.unrealisedPnlPercent) }}</span>
                      </ng-container>
                    </td>
                    <td data-testid="position-allocation">
                      <div class="flex items-center gap-2" *ngIf="allocationOf(p) as share; else noShare">
                        <div class="lt-meter flex-1" aria-hidden="true"><span [style.width.%]="share"></span></div>
                        <span class="w-12 text-right text-[11px] tabular-nums" style="color: var(--muted)">{{ share | number:'1.1-1' }}%</span>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <ng-template #noPrice><span style="color: var(--muted)">No price yet</span></ng-template>
            <ng-template #noShare><span class="text-[11px]" style="color: var(--muted)">—</span></ng-template>
          </section>
        </div>

        <div class="lt-empty" data-testid="portfolio-empty" *ngIf="loaded() && positions().length === 0 && !errorMessage()">
          <p>You don't hold anything yet.</p>
        </div>
      </div>
    </app-shell>
  `,
  styles: [`
    /* Each currency: a small code, then its figures on hairlines. Rows divided, never boxed. */
    .pf-rows { display: grid; }
    .pf-row { display: grid; grid-template-columns: 52px minmax(0, 1fr); align-items: start; gap: .75rem; padding: .7rem 0; border-top: 1px solid var(--border); }
    .pf-row:first-child { border-top: 0; padding-top: .1rem; }
    .pf-ccy { margin-top: .15rem; justify-self: start; padding: .15rem .4rem; border-radius: 6px; background: var(--surface-3); color: var(--muted); font: 800 .7rem var(--font-num); letter-spacing: .04em; }
  `]
})
export class Portfolio implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  protected readonly cur = inject(DisplayCurrencyService);
  private readonly theme = inject(ThemeService);
  private readonly motion = inject(MotionService);
  /** The donut draws in once, on first render; later redraws (hover, theme) appear at once. */
  private donutDrawn = false;
  private readonly service = inject(PortfolioService);
  private readonly market = inject(WatchlistsService);


  readonly summary = signal<PortfolioSummary | null>(null);
  readonly positions = signal<PricedPosition[]>([]);
  /** Each symbol's change per unit over its latest session, from the price cache. */
  private readonly dayChange = signal<Map<string, number>>(new Map());
  readonly loaded = signal(false);
  readonly errorMessage = signal('');
  readonly accountId = signal<number | null>(null);

  /** The currency the donut shows (chosen by the viewer, else the largest). */
  readonly chosenCurrency = signal<string | null>(null);
  readonly hovered = signal<string | null>(null);

  /** The holdings search: plain text, or a regular expression with the .* toggle. */
  readonly query = signal('');
  readonly queryRegex = signal(false);

  /** One line: the summary is already expressed in the base currency (INR) by the API. */
  readonly currencySummaries = computed<CurrencySummary[]>(() => {
    const s = this.summary();
    if (!s) {
      return [];
    }
    return [{
      currency: s.baseCurrency,
      currentValue: Number(s.marketValue),
      invested: Number(s.costBasis),
      unrealised: Number(s.unrealisedPnl),
      unrealisedPercent: s.unrealisedPnlPercent == null ? null : Number(s.unrealisedPnlPercent),
      realised: Number(s.realisedPnl),
      today: this.todayFor(s.baseCurrency)
    }];
  });

  /** Holdings largest first by current value (an unpriced one at cost). */
  readonly sortedPositions = computed(() =>
    [...this.positions()].sort((a, b) => Number(b.marketValue ?? b.costBasis) - Number(a.marketValue ?? a.costBasis)));

  /** The holdings the table shows, after the search. Allocation figures always use every holding. */
  readonly visiblePositions = computed(() => {
    const matcher = createMatcher(this.query(), this.queryRegex());
    return matcher.empty ? this.sortedPositions() : this.sortedPositions().filter((p) => matcher.test(p.symbol, p.currency));
  });

  trackBySymbol(_: number, position: PricedPosition): string {
    return position.symbol + position.currency;
  }

  /** The currencies that have priced holdings, largest total first. */
  readonly allocationCurrencies = computed(() => {
    const totals = new Map<string, number>();
    for (const p of this.positions()) {
      if (p.marketValue != null) {
        totals.set(p.currency, (totals.get(p.currency) ?? 0) + Number(p.marketValue));
      }
    }
    return [...totals.entries()].sort((a, b) => b[1] - a[1]).map(([currency]) => currency);
  });

  readonly allocationCurrency = computed(() => {
    const chosen = this.chosenCurrency();
    const available = this.allocationCurrencies();
    return chosen && available.includes(chosen) ? chosen : available[0] ?? '';
  });

  private readonly pricedInCurrency = computed(() =>
    this.positions().filter((p) => p.currency === this.allocationCurrency() && p.marketValue != null && Number(p.marketValue) > 0));

  readonly allocationTotal = computed(() => this.pricedInCurrency().reduce((sum, p) => sum + Number(p.marketValue), 0));

  /** Every priced position as a slice, largest first. Nothing is grouped away: each holding is visible. */
  readonly slices = computed<Slice[]>(() => {
    const total = this.allocationTotal();
    if (total <= 0) {
      return [];
    }
    return [...this.pricedInCurrency()]
      .sort((a, b) => Number(b.marketValue) - Number(a.marketValue))
      .map((p, i) => ({
        label: p.symbol,
        value: Number(p.marketValue),
        share: (Number(p.marketValue) / total) * 100,
        colour: DONUT_PALETTE[i % DONUT_PALETTE.length]
      }));
  });

  readonly hoveredSlice = computed(() => this.slices().find((s) => s.label === this.hovered()) ?? null);

  private readonly donutHost = viewChild<ElementRef<HTMLElement>>('donutHost');
  private donut: ApexCharts | null = null;
  private readonly donutEffect = effect(() => {
    this.slices();
    this.theme.dark();
    untracked(() => queueMicrotask(() => this.drawDonut()));
  });

  ngOnDestroy(): void {
    this.donut?.destroy();
    this.donut = null;
  }

  /** The allocation donut with every position: a ring, a readout in the middle, and hover on each slice. */
  private drawDonut(): void {
    const host = this.donutHost()?.nativeElement;
    const slices = this.slices();
    this.donut?.destroy();
    this.donut = null;
    if (!host || slices.length === 0) {
      return;
    }
    const style = getComputedStyle(document.documentElement);
    const surface = style.getPropertyValue('--surface').trim() || '#ffffff';
    const options: ApexOptions = {
      chart: {
        type: 'donut',
        height: 300,
        background: 'transparent',
        fontFamily: 'inherit',
        animations: { enabled: !this.donutDrawn && !this.motion.reduced(), speed: 550, animateGradually: { enabled: false } },
        toolbar: { show: false },
        events: {
          dataPointMouseEnter: (_event?: unknown, _context?: unknown, options?: { dataPointIndex?: number }) =>
            this.hovered.set(slices[options?.dataPointIndex ?? -1]?.label ?? null),
          dataPointMouseLeave: () => this.hovered.set(null)
        }
      },
      series: slices.map((s) => s.value),
      labels: slices.map((s) => s.label),
      colors: slices.map((s) => s.colour),
      stroke: { width: 2, colors: [surface] },
      dataLabels: { enabled: false },
      legend: { show: false },
      plotOptions: {
        pie: {
          donut: {
            size: '72%',
            labels: {
              show: true,
              name: { show: true, fontSize: '12px', color: style.getPropertyValue('--muted').trim() || '#6b7280' },
              value: { show: true, fontSize: '16px', fontWeight: 800, color: style.getPropertyValue('--text').trim() || '#111827' },
              total: {
                show: true,
                label: this.allocationCurrency(),
                fontSize: '12px',
                color: style.getPropertyValue('--muted').trim() || '#6b7280',
                formatter: () => this.allocationTotal().toLocaleString('en-IN', { maximumFractionDigits: 0 })
              }
            }
          }
        }
      },
      tooltip: {
        theme: this.theme.dark() ? 'dark' : 'light',
        y: { formatter: (value: number) => `${value.toFixed(2)} (${((value / this.allocationTotal()) * 100).toFixed(1)}%)` }
      }
    };
    this.donut = new ApexCharts(host, options);
    this.donut.render();
    this.donutDrawn = true;
  }

  /** What the donut shows, for a screen reader (the legend lists it too). */
  readonly donutLabel = computed(() => `Allocation in ${this.allocationCurrency()}: `
    + this.slices().map((s) => `${s.label} ${s.share.toFixed(1)}%`).join(', '));

  /** A holding's share of its currency's priced total, or null without a price. */
  allocationOf(position: PricedPosition): number | null {
    if (position.marketValue == null) {
      return null;
    }
    const total = this.positions()
      .filter((p) => p.currency === position.currency && p.marketValue != null)
      .reduce((sum, p) => sum + Number(p.marketValue), 0);
    return total > 0 ? (Number(position.marketValue) / total) * 100 : null;
  }

  private todayFor(currency: string): number | null {
    const changes = this.dayChange();
    let total = 0;
    let any = false;
    for (const p of this.positions()) {
      const change = changes.get(p.symbol);
      if (p.currency === currency && change !== undefined) {
        total += change * p.quantity;
        any = true;
      }
    }
    return any ? Math.round(total * 100) / 100 : null;
  }

  signColour(value: number | null | undefined): string {
    if (value == null || Number(value) === 0) {
      return 'var(--muted)';
    }
    return Number(value) > 0 ? 'var(--up)' : 'var(--down)';
  }

  /** A price or amount in its own currency with the symbol: $529.76, ₹3,500.00. */
  money(amount: number | string, currency: string): string {
    return formatMoney(Number(amount), currency);
  }

  signed(value: number | null | undefined): string {
    if (value == null) {
      return '—';
    }
    const n = Number(value);
    const text = Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return (n > 0 ? '+' : n < 0 ? '-' : '') + text;
  }

  signedPercent(value: number | null | undefined): string {
    if (value == null) {
      return '';
    }
    const n = Number(value);
    return (n > 0 ? '+' : '') + n.toFixed(2) + '%';
  }

  ngOnInit(): void {
    this.cur.ensureLoaded();
    this.auth.getProfile().subscribe({
      next: (profile) => {
        this.accountId.set(profile.accountId);
        this.load(profile.accountId);
      },
      error: (err) => this.fail(err)
    });
  }

  private load(accountId: number): void {
    this.service.summary(accountId).subscribe({ next: (s) => { this.summary.set(s); this.loaded.set(true); }, error: (e) => this.fail(e) });
    this.service.positions(accountId).subscribe({ next: (p) => { this.positions.set(p); this.loaded.set(true); }, error: (e) => this.fail(e) });
    // Today's P&L needs each holding's session change; without it that figure shows "—", nothing else changes.
    this.market.getQuotes().subscribe({
      next: (quotes) => this.dayChange.set(new Map(quotes
        .filter((q) => q.change != null)
        .map((q) => [q.symbol, Number(q.change)] as [string, number]))),
      error: () => undefined
    });
  }

  private fail(err: unknown): void {
    this.loaded.set(true);
    const status = (err as { status?: number } | null)?.status;
    this.errorMessage.set(status === 503
      ? 'Prices are temporarily unavailable, so your portfolio cannot be valued right now. Try again shortly.'
      : messageFor(err));
  }
}
