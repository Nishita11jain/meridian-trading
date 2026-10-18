import {
  Component, ElementRef, OnDestroy, computed, effect, inject, input, signal, untracked, viewChild
} from '@angular/core';
import { CommonModule } from '@angular/common';
import ApexCharts, { ApexOptions } from 'apexcharts';

import { PortfolioService } from '../../core/portfolio/portfolio.service';
import { ThemeService } from '../../core/theme/theme.service';
import { MotionService } from '../../core/motion/motion.service';
import { messageFor } from '../../core/errors/error-messages';
import { PortfolioHistory, PortfolioHistoryPoint } from '../../clients/portfolio-client/model/models';

/** The floating card beside the pointer: where to put it inside the chart. */
export interface Tip {
  left: number;
  top: number;
}

/** What the header shows: the latest point, or the one under the pointer. */
export interface Reading {
  at: string;
  value: number | null;
  invested: number;
  estimated: boolean;
}

/**
 * The portfolio's value over its whole life, as brokers show it: a value area that is green while above what
 * was invested and red while below, a dashed line of what was invested, and a header with the current value and
 * the overall profit or loss. Hovering reads any day. Before the platform stored prices, holdings are valued at
 * the account's own trade prices; that stretch is labelled as estimated. One currency at a time, so amounts are
 * never added across currencies.
 *
 * Drawn with ApexCharts (MIT): an SVG chart with a gradient fill, a tooltip, and a clean datetime axis.
 */
@Component({
  selector: 'app-portfolio-chart',
  imports: [CommonModule],
  template: `
    <section class="lt-card lt-card-pad" aria-labelledby="portfolio-chart-title" data-testid="portfolio-chart">
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="portfolio-chart-title" class="lt-eyebrow">{{ hovered() ? 'Value on ' + (hovered()!.at | date:'d MMM y') : 'Current value' }}</h2>
          <ng-container *ngIf="reading() as r">
            <p class="mt-1 text-[30px] font-extrabold tabular-nums leading-tight" style="color: var(--text)" data-testid="portfolio-chart-value">
              <span class="text-[13px] font-bold" style="color: var(--muted)">{{ currency() }}</span>
              {{ r.value != null ? (r.value | number:'1.2-2') : '—' }}
              <span *ngIf="r.estimated" class="ml-1 rounded-full px-2 py-0.5 align-middle text-[10px] font-extrabold uppercase"
                style="background: var(--surface-3); color: var(--muted)" data-testid="portfolio-chart-estimated">Estimated</span>
            </p>
            <p class="mt-1.5 flex flex-wrap items-center gap-2 text-sm" *ngIf="pnl() as p">
              <span class="inline-flex items-center gap-1 rounded-full px-2.5 py-1 font-bold tabular-nums"
                [style.background]="p.amount >= 0 ? 'var(--up-bg)' : 'var(--down-bg)'" [style.color]="pnlColour()" data-testid="portfolio-chart-change">
                <span aria-hidden="true">{{ p.amount >= 0 ? '▲' : '▼' }}</span>
                {{ p.amount >= 0 ? '+' : '-' }}{{ abs(p.amount) | number:'1.2-2' }}
                <ng-container *ngIf="p.percent != null">({{ p.percent >= 0 ? '+' : '' }}{{ p.percent | number:'1.2-2' }}%)</ng-container>
                <span class="font-semibold">overall</span>
              </span>
            </p>
            <p class="text-xs tabular-nums" style="color: var(--muted)" data-testid="portfolio-chart-invested">
              Invested {{ r.invested | number:'1.2-2' }}
            </p>
          </ng-container>
        </div>
        <div class="lt-seg" role="tablist" aria-label="Currency" *ngIf="series().length > 1">
          <button *ngFor="let s of series()" type="button" role="tab"
            [attr.aria-selected]="s.currency === currency()" data-testid="portfolio-chart-currency"
            (click)="chosenCurrency.set(s.currency)">{{ s.currency }}</button>
        </div>
      </div>

      <p class="lt-alert lt-alert-down mt-3" role="alert"
        *ngIf="errorMessage()" data-testid="portfolio-chart-error">{{ errorMessage() }}</p>
      <p class="mt-3 text-sm" style="color: var(--muted)" *ngIf="loading()" data-testid="portfolio-chart-loading">Loading history...</p>
      <div class="lt-empty mt-3" *ngIf="!loading() && !errorMessage() && series().length === 0" data-testid="portfolio-chart-empty">
        <p>Your portfolio's history appears after your first trade.</p>
      </div>

      <div class="mt-3" [hidden]="series().length === 0">
        <div class="relative">
          <div #chartHost class="w-full" data-testid="portfolio-chart-canvas"
            role="img" [attr.aria-label]="ariaSummary()" (mouseleave)="hovered.set(null)"></div>
          <!-- The day under the pointer, beside it. -->
          <div *ngIf="hovered() as h" class="lt-card pointer-events-none absolute z-10 min-w-[170px] px-3 py-2 text-xs"
            style="box-shadow: var(--shadow-lg)" [style.left.px]="tip().left" [style.top.px]="tip().top" data-testid="portfolio-chart-tip" aria-hidden="true">
            <p class="font-bold" style="color: var(--text)">{{ h.at | date:'d MMM y' }}
              <span *ngIf="h.estimated" class="ml-1 font-semibold" style="color: var(--muted)">· estimated</span></p>
            <p class="mt-1 flex justify-between gap-4 tabular-nums"><span style="color: var(--muted)">Value</span>
              <strong style="color: var(--text)">{{ h.value != null ? (h.value | number:'1.2-2') : '—' }}</strong></p>
            <p class="flex justify-between gap-4 tabular-nums"><span style="color: var(--muted)">Invested</span>
              <span style="color: var(--text)">{{ h.invested | number:'1.2-2' }}</span></p>
            <p class="flex justify-between gap-4 tabular-nums" *ngIf="pnl() as p"><span style="color: var(--muted)">P&amp;L</span>
              <strong [style.color]="pnlColour()">{{ p.amount >= 0 ? '+' : '-' }}{{ abs(p.amount) | number:'1.2-2' }}</strong></p>
          </div>
        </div>
        <div class="mt-3 flex flex-wrap items-center justify-between gap-3 text-xs" style="color: var(--muted)">
          <span class="flex flex-wrap items-center gap-4">
            <span class="inline-flex items-center gap-1.5"><span class="h-0.5 w-5 rounded" style="background: var(--up)"></span>Value (green above invested, red below)</span>
            <span class="inline-flex items-center gap-1.5"><span class="w-5 border-t-2 border-dashed" style="border-color: var(--muted)"></span>Invested</span>
            <span *ngIf="active()?.marketPricesSince && hasEstimates()" data-testid="portfolio-chart-estimate-note">
              Before {{ active()!.marketPricesSince | date:'d MMM' }} the value is estimated from your own trade prices, before the platform stored market prices.
            </span>
          </span>
        </div>
      </div>
    </section>
  `
})
export class PortfolioChart implements OnDestroy {
  private readonly service = inject(PortfolioService);
  private readonly theme = inject(ThemeService);
  private readonly motion = inject(MotionService);
  /** The line draws in once, on first render; redraws after a currency or theme change appear at once. */
  private drawn = false;

  readonly accountId = input.required<number>();

  readonly series = signal<PortfolioHistory[]>([]);
  readonly chosenCurrency = signal<string | null>(null);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  /** The point under the pointer, while the pointer is on the chart. */
  readonly hovered = signal<Reading | null>(null);
  /** Where the floating card sits: beside the pointer, flipped to the left near the right edge. */
  readonly tip = signal<Tip>({ left: 0, top: 0 });

  private readonly host = viewChild<ElementRef<HTMLElement>>('chartHost');
  private chart: ApexCharts | null = null;
  /** The readings in the order the chart draws them, so a hovered index maps back to its day. */
  private readings: Reading[] = [];

  readonly active = computed<PortfolioHistory | null>(() => {
    const list = this.series();
    return list.find((s) => s.currency === this.chosenCurrency()) ?? list[0] ?? null;
  });
  readonly currency = computed(() => this.active()?.currency ?? '');
  readonly hasEstimates = computed(() => (this.active()?.points ?? []).some((p) => p.estimated));

  private readonly latest = computed<Reading | null>(() => {
    const points = this.active()?.points ?? [];
    const last = points[points.length - 1];
    return last ? toReading(last) : null;
  });

  readonly reading = computed(() => this.hovered() ?? this.latest());

  /** Profit or loss at the reading: value against what was invested then. */
  readonly pnl = computed(() => {
    const r = this.reading();
    if (!r || r.value == null) {
      return null;
    }
    const amount = r.value - r.invested;
    return { amount, percent: r.invested > 0 ? (amount / r.invested) * 100 : null };
  });

  readonly pnlColour = computed(() => {
    const p = this.pnl();
    return !p || p.amount === 0 ? 'var(--muted)' : p.amount > 0 ? 'var(--up)' : 'var(--down)';
  });

  readonly ariaSummary = computed(() => {
    const r = this.latest();
    if (!r || r.value == null) {
      return 'Portfolio value chart';
    }
    const amount = r.value - r.invested;
    const percent = r.invested > 0 ? Math.abs((amount / r.invested) * 100).toFixed(2) : null;
    return `Portfolio value in ${this.currency()}: ${r.value.toFixed(2)}, ${amount >= 0 ? 'up' : 'down'}`
      + (percent !== null ? ` ${percent}%` : '') + ` overall against ${r.invested.toFixed(2)} invested.`;
  });

  constructor() {
    effect(() => {
      const accountId = this.accountId();
      untracked(() => this.load(accountId));
    });
    // Redraw when the data, the currency or the theme changes.
    effect(() => {
      this.active();
      this.theme.dark();
      untracked(() => queueMicrotask(() => this.draw()));
    });
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
    this.chart = null;
  }

  abs(n: number): number {
    return Math.abs(n);
  }

  private load(accountId: number): void {
    this.loading.set(true);
    this.errorMessage.set('');
    this.service.history(accountId, 'ALL').subscribe({
      next: (list) => {
        this.series.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        this.series.set([]);
        this.loading.set(false);
        this.errorMessage.set('The history could not be loaded. ' + messageFor(err));
      }
    });
  }

  /** Builds the chart for the active currency. Rebuilt rather than patched: it is cheap, and it avoids stale state. */
  private draw(): void {
    const host = this.host()?.nativeElement;
    const active = this.active();
    this.chart?.destroy();
    this.chart = null;
    if (!host || !active || active.points.length === 0) {
      return;
    }

    const colours = themeColours();
    const currency = active.currency;
    // One point per day, carrying the last known value forward, so the smoothed line has even steps to follow.
    const readings = dailyGrid(active.points.map(toReading));
    this.readings = readings;

    const last = readings[readings.length - 1];
    const above = last.value == null || last.value >= last.invested;
    const lineColour = above ? colours.up : colours.down;

    const valueData = readings.map((r) => [Date.parse(r.at), r.value]);
    const investedData = readings.map((r) => [Date.parse(r.at), r.invested]);

    const options: ApexOptions = {
      chart: {
        type: 'area',
        height: 300,
        background: 'transparent',
        fontFamily: 'inherit',
        toolbar: { show: false },
        zoom: { enabled: false },
        animations: { enabled: !this.drawn && !this.motion.reduced(), speed: 700, animateGradually: { enabled: false } },
        dropShadow: { enabled: false },
        events: {
          mouseMove: (event: MouseEvent, _context?: unknown, options?: { dataPointIndex?: number }) => {
            const reading = this.readings[options?.dataPointIndex ?? -1];
            if (!reading) {
              this.hovered.set(null);
              return;
            }
            this.hovered.set(reading);
            const box = host.getBoundingClientRect();
            const x = event.clientX - box.left;
            const flip = x > box.width - 200;
            this.tip.set({ left: flip ? x - 186 : x + 16, top: Math.max(4, Math.min(event.clientY - box.top - 40, 200)) });
          }
        }
      },
      series: [
        { name: 'Value', type: 'area', data: valueData as [number, number | null][] },
        { name: 'Invested', type: 'line', data: investedData as [number, number][] }
      ],
      colors: [lineColour, colours.muted],
      stroke: { curve: 'smooth', width: [2.5, 1.5], dashArray: [0, 6] },
      fill: {
        type: ['gradient', 'solid'],
        opacity: [1, 1],
        gradient: { shade: 'light', type: 'vertical', opacityFrom: 0.35, opacityTo: 0.02, stops: [0, 100] }
      },
      markers: { size: 0, hover: { size: 5 } },
      dataLabels: { enabled: false },
      legend: { show: false },
      grid: {
        borderColor: colours.grid,
        strokeDashArray: 3,
        padding: { left: 8, right: 8 },
        xaxis: { lines: { show: false } }
      },
      xaxis: {
        type: 'datetime',
        axisBorder: { show: false },
        axisTicks: { show: false },
        labels: { style: { colors: colours.muted, fontSize: '11px' } },
        tooltip: { enabled: false }
      },
      yaxis: {
        labels: {
          style: { colors: colours.muted, fontSize: '11px' },
          formatter: (value: number) => compactAmount(value, currency)
        }
      },
      tooltip: {
        theme: this.theme.dark() ? 'dark' : 'light',
        shared: true,
        intersect: false,
        x: { format: 'd MMM yyyy' },
        y: { formatter: (value: number) => (value == null ? '—' : value.toFixed(2)) }
      },
      noData: { text: 'No history yet' }
    };

    this.chart = new ApexCharts(host, options);
    this.chart.render();
    this.drawn = true;
  }
}

const DAY_MS = 86_400_000;

/**
 * A point for every day between the first and last reading. Between two real readings the value is the last
 * known one, which is the truth for a portfolio that only changes when a trade or a price changes. Beyond two
 * years the grid is weekly, so the chart stays light on a weak machine.
 */
export function dailyGrid(readings: Reading[]): Reading[] {
  if (readings.length < 2) {
    return readings;
  }
  const sorted = [...readings].sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  const start = Date.parse(sorted[0].at);
  const end = Date.parse(sorted[sorted.length - 1].at);
  const step = (end - start) / DAY_MS > 730 ? 7 * DAY_MS : DAY_MS;
  const grid: Reading[] = [];
  let j = 0;
  for (let t = start; t <= end; t += step) {
    while (j + 1 < sorted.length && Date.parse(sorted[j + 1].at) <= t) {
      j++;
    }
    const source = sorted[j];
    grid.push({ at: new Date(t).toISOString(), value: source.value, invested: source.invested, estimated: source.estimated });
  }
  const last = sorted[sorted.length - 1];
  if (grid[grid.length - 1].at !== last.at) {
    grid.push(last);
  }
  return grid;
}

function toReading(p: PortfolioHistoryPoint): Reading {
  return { at: p.at, value: p.value == null ? null : Number(p.value), invested: Number(p.invested), estimated: !!p.estimated };
}

/** Short money for axis labels: 12.5K, 3.2L (lakh), 4.1Cr (crore) for rupees; K and M otherwise. */
export function compactAmount(value: number, currency: string): string {
  const abs = Math.abs(value);
  const sign = value < 0 ? '-' : '';
  const symbol = currency === 'INR' ? '₹' : currency === 'USD' ? '$' : `${currency} `;
  if (currency === 'INR') {
    if (abs >= 1e7) return `${sign}${symbol}${(abs / 1e7).toFixed(abs >= 1e8 ? 1 : 2)}Cr`;
    if (abs >= 1e5) return `${sign}${symbol}${(abs / 1e5).toFixed(abs >= 1e6 ? 1 : 2)}L`;
    if (abs >= 1e3) return `${sign}${symbol}${(abs / 1e3).toFixed(0)}K`;
    return `${sign}${symbol}${abs.toFixed(0)}`;
  }
  if (abs >= 1e6) return `${sign}${symbol}${(abs / 1e6).toFixed(1)}M`;
  if (abs >= 1e3) return `${sign}${symbol}${(abs / 1e3).toFixed(1)}K`;
  return `${sign}${symbol}${abs.toFixed(0)}`;
}

/** The theme's colours as plain values, read from the page so the chart matches light and dark modes. */
function themeColours(): { up: string; down: string; muted: string; grid: string } {
  const style = getComputedStyle(document.documentElement);
  const read = (name: string, fallback: string) => style.getPropertyValue(name).trim() || fallback;
  return {
    up: read('--up', '#16a34a'),
    down: read('--down', '#dc2626'),
    muted: read('--muted', '#6b7280'),
    grid: read('--border', '#e5e7eb')
  };
}
