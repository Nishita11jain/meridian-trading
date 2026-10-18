import { Component, ElementRef, OnDestroy, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import ApexCharts from 'apexcharts';
import { CommonModule } from '@angular/common';

import { MarketHistoryRange, WatchlistsService } from '../../core/watchlists/watchlists.service';

const RANGE_OPTIONS: ReadonlyArray<{ key: MarketHistoryRange; label: string }> = [
  { key: '1Y', label: '1Y' },
  { key: '6M', label: '6M' },
  { key: '3M', label: '3M' },
  { key: '1M', label: '1M' },
  { key: '5D', label: '5D' }
];

/**
 * The recent price trend of one instrument, drawn like the market board's trend panel. It reads the
 * platform's tick history (never the market-data provider) and redraws when the symbol changes.
 */
@Component({
  selector: 'app-price-trend',
  imports: [CommonModule],
  template: `
    <section class="lt-card lt-card-pad" aria-labelledby="price-trend-title" data-testid="price-trend">
      <div class="lt-card-head">
        <h2 id="price-trend-title" class="lt-section-title">{{ symbol() }} price trend</h2>
      </div>
      <div class="mt-2 inline-flex gap-1 rounded-lg p-1" style="background: var(--surface-2)" role="tablist" aria-label="Chart range">
        <button *ngFor="let option of rangeOptions" type="button" class="lt-btn lt-btn-sm"
          [attr.aria-selected]="selectedRange() === option.key" role="tab"
          [style.background]="selectedRange() === option.key ? 'var(--surface)' : 'transparent'"
          [style.color]="selectedRange() === option.key ? 'var(--text)' : 'var(--muted)'"
          data-testid="price-trend-range"
          (click)="setRange(option.key)">{{ option.label }}</button>
      </div>
      <div *ngIf="prices().length >= 2" #host class="h-56 w-full" role="img"
        [attr.aria-label]="'Price trend for ' + symbol()" data-testid="price-trend-chart"></div>
      <p class="text-sm" style="color: var(--muted)" *ngIf="prices().length >= 2" data-testid="price-trend-caption">
        {{ prices().length }} price updates since {{ firstAt() | date:'MMM d, h:mm a' }}.
      </p>
      <p class="text-xs" style="color: var(--muted)" *ngIf="hasCandles() && candleScaleText()" data-testid="price-trend-scale">
        Scale: {{ candleScaleText() }}
      </p>
      <p class="text-sm" style="color: var(--muted)" *ngIf="loaded() && prices().length < 2" data-testid="price-trend-empty">
        {{ prices().length === 1 ? 'Only one price so far. The trend appears once the price changes.' : 'No history yet for ' + symbol() + '.' }}
      </p>
    </section>
  `
})
export class PriceTrend implements OnDestroy {
  private readonly service = inject(WatchlistsService);

  readonly symbol = input.required<string>();
  readonly rangeOptions = RANGE_OPTIONS;
  readonly selectedRange = signal<MarketHistoryRange>('5D');

  private readonly history = signal<{ price: number; at: string | null; open: number | null; high: number | null; low: number | null }[]>([]);
  readonly loaded = signal(false);
  readonly prices = computed(() => this.history().map((h) => h.price));
  readonly firstAt = computed(() => this.history()[0]?.at ?? null);
  readonly hasCandles = computed(() => this.history().filter((h) => h.open != null && h.high != null && h.low != null).length >= 2);
  readonly candleScaleText = computed(() => {
    const candles = this.history().filter((h) => h.high != null && h.low != null);
    if (candles.length < 2) {
      return null;
    }
    const high = Math.max(...candles.map((c) => c.high as number));
    const low = Math.min(...candles.map((c) => c.low as number));
    return `${low.toFixed(2)} - ${high.toFixed(2)}`;
  });

  private axisTickAmount(): number {
    const range = this.selectedRange();
    if (range === '1Y') {
      return 8;
    }
    if (range === '6M') {
      return 7;
    }
    if (range === '3M') {
      return 6;
    }
    if (range === '1M') {
      return 5;
    }
    return 5;
  }

  private readonly host = viewChild<ElementRef<HTMLElement>>('host');
  private chart: ApexCharts | null = null;

  private readonly loadEffect = effect((onCleanup) => {
    const symbol = this.symbol();
    const range = this.selectedRange();
    this.loaded.set(false);
    const sub = this.service.getHistory(symbol, range).subscribe({
      // History is newest first; reversed into time order for the line.
      next: (ticks) => {
        this.history.set(ticks
          .map((t) => ({
            price: Number(t.lastPrice),
            at: t.quoteAsOf ?? null,
            open: t.openPrice == null ? null : Number(t.openPrice),
            high: t.highPrice == null ? null : Number(t.highPrice),
            low: t.lowPrice == null ? null : Number(t.lowPrice)
          }))
          .filter((t) => !Number.isNaN(t.price))
          .reverse());
        this.loaded.set(true);
      },
      error: () => {
        this.history.set([]);
        this.loaded.set(true);
      }
    });
    onCleanup(() => sub.unsubscribe());
  });

  setRange(range: MarketHistoryRange): void {
    if (range !== this.selectedRange()) {
      this.selectedRange.set(range);
    }
  }

  private readonly drawEffect = effect(() => {
    this.history();
    untracked(() => queueMicrotask(() => this.draw()));
  });

  private draw(): void {
    const host = this.host()?.nativeElement;
    const values = this.prices();
    const candles = this.history()
      .filter((h) => h.at != null && h.open != null && h.high != null && h.low != null)
      .map((h) => ({
        x: new Date(h.at as string).toISOString().slice(0, 10),
        y: [h.open as number, h.high as number, h.low as number, h.price]
      }));
    this.chart?.destroy();
    this.chart = null;
    if (!host || values.length < 2) {
      return;
    }

    if (candles.length >= 2) {
      this.chart = new ApexCharts(host, {
        chart: { type: 'candlestick', height: 220, animations: { enabled: false }, background: 'transparent', toolbar: { show: false } },
        series: [{ name: 'OHLC', data: candles }],
        plotOptions: {
          candlestick: {
            colors: {
              upward: 'var(--up)',
              downward: 'var(--down)'
            }
          }
        },
        xaxis: {
          type: 'category',
          tickAmount: this.axisTickAmount(),
          labels: {
            show: true,
            rotate: -35,
            hideOverlappingLabels: true,
            style: { colors: 'var(--muted)', fontSize: '11px' },
            formatter: (value: string) => {
              const parsed = new Date(value);
              return Number.isNaN(parsed.getTime())
                ? value
                : parsed.toLocaleDateString('en-GB', { month: 'short', day: 'numeric' });
            }
          },
          axisBorder: { show: true, color: 'var(--border)' },
          axisTicks: { show: true, color: 'var(--border)' }
        },
        yaxis: {
          labels: {
            show: true,
            style: { colors: 'var(--muted)', fontSize: '11px' },
            formatter: (value: number) => Number(value).toFixed(2)
          }
        },
        grid: { show: true, borderColor: 'var(--border)', strokeDashArray: 3 },
        tooltip: {
          enabled: true,
          y: {
            formatter: (value: number) => Number(value).toFixed(2)
          }
        },
        dataLabels: { enabled: false }
      });
      this.chart.render();
      return;
    }

    const delta = values[values.length - 1] - values[0];
    const colour = delta > 0 ? 'var(--up)' : delta < 0 ? 'var(--down)' : 'var(--muted)';
    this.chart = new ApexCharts(host, {
      chart: { type: 'area', sparkline: { enabled: true }, height: 160, animations: { enabled: false }, background: 'transparent' },
      series: [{ name: 'Price', data: values }],
      colors: [colour],
      stroke: { curve: 'smooth', width: 2 },
      fill: { type: 'gradient', gradient: { shade: 'light', type: 'vertical', opacityFrom: 0.35, opacityTo: 0.02, stops: [0, 100] } },
      tooltip: { enabled: true, fixed: { enabled: false } },
      dataLabels: { enabled: false }
    });
    this.chart.render();
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
    this.chart = null;
  }
}
