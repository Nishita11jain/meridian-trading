import { Component, HostListener, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

import { AuthService } from '../../core/auth/auth.service';
import { AdviceService } from '../../core/advice/advice.service';
import { messageFor } from '../../core/errors/error-messages';
import { plotChart } from '../../core/advice/chart-math';
import { SignalResponse } from '../../clients/advice-client/model/models';
import { AppShell } from '../../shared/layout/app-shell';

/**
 * Trade advice & signals (Sprint 10 stretch). One card per symbol the account holds or watches,
 * with a BUY/SELL/HOLD badge, a strength bar and the sentence that produced it. There is no
 * background refresh: a card's data only ever changes when the customer presses "Analyse now" on
 * it, and that call is a cache read unless the symbol's cooldown has passed, in which case it is
 * the one and only place this screen reaches Fauxnance.
 *
 * "Analyse now" opens a modal rather than expanding the card in place: the chart behind a signal
 * (60-90 days of candles with the SMA20/SMA50 overlay) is only ever fetched by that one action, so
 * it has nowhere to live until the customer asks for it.
 */
@Component({
  selector: 'app-advice',
  imports: [CommonModule, AppShell],
  template: `
    <app-shell title="Trade advice &amp; signals">
      <div class="adv">
        <div class="lt-alert lt-alert-down adv__full" data-testid="advice-error" role="alert" *ngIf="errorMessage()">{{ errorMessage() }}</div>

        <p class="adv__intro">
          A stated view on each instrument you hold or watch &mdash; a direction, a strength and the reasoning
          behind it. This informs a decision; it never places a trade.
        </p>

        <div class="lt-empty" data-testid="advice-empty" *ngIf="loaded() && signals().length === 0">
          <p>Nothing to show yet. Hold a position or add an instrument to a watchlist to see advice here.</p>
        </div>

        <div class="adv__grid" *ngIf="signals().length > 0">
          <div class="lt-card lt-card-pad adv__card" *ngFor="let s of signals()" [attr.data-testid]="'advice-card-' + s.symbol">
            <div class="adv__card-head">
              <span class="adv__symbol">{{ s.symbol }}</span>
              <span class="lt-badge" [ngClass]="badgeClass(s.direction)" [attr.data-testid]="'advice-direction-' + s.symbol">
                {{ s.direction ?? 'NO SIGNAL' }}
              </span>
            </div>

            <ng-container *ngIf="s.direction; else noSignal">
              <div class="adv__strength">
                <div class="adv__strength-track">
                  <div class="adv__strength-fill" [ngClass]="badgeClass(s.direction)" [style.width.%]="(s.strength ?? 0) * 100"></div>
                </div>
                <span class="adv__strength-label">strength {{ (s.strength ?? 0) | number:'1.2-2' }}</span>
              </div>
              <p class="adv__rationale">{{ s.rationale }}</p>
              <div class="adv__meta">
                <span *ngIf="s.stale" class="lt-badge lt-badge-warn">stale</span>
                <span>as of {{ s.computedAt ? (s.computedAt | date:'dd MMM, HH:mm') : '—' }}</span>
              </div>
            </ng-container>
            <ng-template #noSignal>
              <p class="adv__rationale adv__rationale--muted">Not analysed yet. Select Analyse now to generate a signal.</p>
            </ng-template>

            <p class="adv__disclaimer" data-testid="advice-disclaimer">{{ s.disclaimer }}</p>

            <button type="button" class="lt-btn lt-btn-primary lt-btn-sm adv__analyse" [attr.data-testid]="'advice-analyse-' + s.symbol"
              [disabled]="isAnalysing(s.symbol) || !canAnalyseNow(s)"
              (click)="analyse(s.symbol)">
              {{ isAnalysing(s.symbol) ? 'Analysing…' : 'Analyse now' }}
            </button>
            <p class="adv__cooldown" *ngIf="!canAnalyseNow(s) && s.nextAnalysisAt">
              Available again after {{ s.nextAnalysisAt | date:'dd MMM, HH:mm' }}.
            </p>
          </div>
        </div>
      </div>

      <!-- The chart lives only here: nothing on the cards above fetches or renders it. -->
      <div class="lt-dialog-backdrop" data-testid="advice-modal-backdrop" *ngIf="modalOpen()" (click)="onBackdrop($event)">
        <div class="lt-dialog adv-modal" role="dialog" aria-modal="true" [attr.aria-labelledby]="'advice-modal-title'" data-testid="advice-modal">
          <div class="adv-modal__head">
            <div>
              <div class="adv-modal__symbol-row">
                <h2 class="adv-modal__symbol" id="advice-modal-title">{{ modalSymbol() }}</h2>
                <span class="lt-badge" *ngIf="modalSignal()?.direction" [ngClass]="badgeClass(modalSignal()?.direction)" data-testid="advice-modal-direction">
                  {{ modalSignal()?.direction }} &middot; {{ (modalSignal()?.strength ?? 0) | number:'1.2-2' }}
                </span>
              </div>
              <p class="adv-modal__subtitle">Daily closes &middot; SMA20 / SMA50 crossover</p>
            </div>
            <button type="button" class="lt-icon-button" aria-label="Close" data-testid="advice-modal-close" (click)="closeModal()">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6L6 18" stroke-linecap="round"/></svg>
            </button>
          </div>

          <div class="adv-modal__body">
            <p *ngIf="modalLoading()" class="adv-modal__loading" data-testid="advice-modal-loading">Fetching candles and computing the signal&hellip;</p>
            <div class="lt-alert lt-alert-down" *ngIf="modalError()" role="alert" data-testid="advice-modal-error">{{ modalError() }}</div>

            <ng-container *ngIf="!modalLoading() && modalSignal() as s">
              <svg *ngIf="plotted() as chart" viewBox="0 0 700 260" width="100%" height="260" preserveAspectRatio="none"
                role="img" aria-label="Closing price with SMA20 and SMA50 overlaid" data-testid="advice-modal-chart">
                <line x1="0" y1="20" x2="700" y2="20" class="adv-modal__grid"/>
                <line x1="0" y1="80" x2="700" y2="80" class="adv-modal__grid"/>
                <line x1="0" y1="140" x2="700" y2="140" class="adv-modal__grid"/>
                <line x1="0" y1="200" x2="700" y2="200" class="adv-modal__grid"/>
                <polyline [attr.points]="chart.closePoints" fill="none" class="adv-modal__line-close"/>
                <polyline [attr.points]="chart.sma50Points" fill="none" class="adv-modal__line-sma50"/>
                <polyline [attr.points]="chart.sma20Points" fill="none" class="adv-modal__line-sma20"/>
                <ng-container *ngIf="chart.crossover as x">
                  <circle [attr.cx]="x.x" [attr.cy]="x.y" r="5" class="adv-modal__crossover-dot"/>
                  <line [attr.x1]="x.x" y1="20" [attr.x2]="x.x" y2="230" class="adv-modal__crossover-line"/>
                </ng-container>
              </svg>
              <p class="lt-empty" *ngIf="!plotted()" data-testid="advice-modal-no-chart">No candle history is available for this symbol yet.</p>

              <div class="adv-modal__legend" *ngIf="plotted()">
                <span><span class="adv-modal__swatch adv-modal__swatch--close"></span>Close price</span>
                <span><span class="adv-modal__swatch adv-modal__swatch--fast"></span>SMA20 (fast)</span>
                <span><span class="adv-modal__swatch adv-modal__swatch--slow"></span>SMA50 (slow)</span>
              </div>

              <p class="adv-modal__summary" *ngIf="s.summary" data-testid="advice-modal-summary">{{ s.summary }}</p>
              <p class="adv-modal__rationale" data-testid="advice-modal-rationale">{{ s.rationale ?? 'Not enough candle history to compute a signal yet.' }}</p>

              <div class="adv-modal__stats">
                <div class="adv-modal__stat">
                  <div class="adv-modal__stat-label">Computed</div>
                  <div class="adv-modal__stat-value">{{ s.computedAt ? (s.computedAt | date:'dd MMM, HH:mm') : '—' }}</div>
                </div>
                <div class="adv-modal__stat">
                  <div class="adv-modal__stat-label">Next analysis available</div>
                  <div class="adv-modal__stat-value">{{ s.nextAnalysisAt ? (s.nextAnalysisAt | date:'dd MMM, HH:mm') : 'now' }}</div>
                </div>
                <div class="adv-modal__stat">
                  <div class="adv-modal__stat-label">Source</div>
                  <div class="adv-modal__stat-value">Daily market candles</div>
                </div>
              </div>

              <p class="adv-modal__disclaimer" data-testid="advice-modal-disclaimer">{{ s.disclaimer }}</p>

              <div class="adv-modal__foot">
                <span class="adv-modal__foot-note" *ngIf="s.stale">Showing the last signal computed &mdash; the most recent refresh attempt failed.</span>
                <div class="adv-modal__foot-actions">
                  <button type="button" class="lt-btn lt-btn-sm" data-testid="advice-modal-reanalyse"
                    [disabled]="modalLoading() || !canAnalyseNow(s)" (click)="analyse(s.symbol)">
                    {{ modalLoading() ? 'Analysing…' : 'Analyse now' }}
                  </button>
                  <button type="button" class="lt-btn lt-btn-ghost lt-btn-sm" data-testid="advice-modal-close-foot" (click)="closeModal()">Close</button>
                </div>
              </div>
            </ng-container>
          </div>
        </div>
      </div>
    </app-shell>
  `,
  styles: [`
    .adv { display: flex; flex-direction: column; gap: 1rem; }
    .adv__full { grid-column: 1 / -1; }
    .adv__intro { margin: 0; max-width: 46rem; font-size: .8125rem; color: var(--muted); line-height: 1.5; }
    .adv__grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: .875rem; }
    .adv__card { display: flex; flex-direction: column; gap: .625rem; }
    .adv__card-head { display: flex; align-items: center; justify-content: space-between; gap: .5rem; }
    .adv__symbol { font-weight: 700; font-size: .9375rem; }
    .adv__strength { display: flex; align-items: center; gap: .5rem; }
    .adv__strength-track { flex-grow: 1; height: 6px; border-radius: 999px; background: var(--surface-3); overflow: hidden; }
    .adv__strength-fill { height: 100%; }
    .adv__strength-fill.lt-badge-up { background: var(--up); }
    .adv__strength-fill.lt-badge-down { background: var(--down); }
    .adv__strength-fill.lt-badge-muted { background: var(--muted); }
    .adv__strength-label { font-size: .6875rem; color: var(--muted); white-space: nowrap; }
    .adv__rationale { margin: 0; font-size: .8125rem; color: var(--text); line-height: 1.5; }
    .adv__rationale--muted { color: var(--muted); }
    .adv__meta { display: flex; align-items: center; gap: .5rem; font-size: .6875rem; color: var(--muted); }
    .adv__analyse { align-self: flex-start; }
    .adv__cooldown { margin: 0; font-size: .6875rem; color: var(--muted); }
    .adv__disclaimer, .adv-modal__disclaimer { margin: 0; font-size: .6875rem; color: var(--muted); font-style: italic; }

    .adv-modal { max-width: 760px; width: 100%; max-height: calc(100vh - 2rem); overflow-y: auto; }
    .adv-modal__head { display: flex; align-items: flex-start; justify-content: space-between; gap: .75rem; padding-bottom: .875rem; border-bottom: 1px solid var(--border); }
    .adv-modal__symbol-row { display: flex; align-items: center; gap: .625rem; flex-wrap: wrap; }
    .adv-modal__symbol { margin: 0; font-size: 1.125rem; font-weight: 700; }
    .adv-modal__subtitle { margin: .2rem 0 0; font-size: .75rem; color: var(--muted); }
    .adv-modal__body { display: flex; flex-direction: column; gap: 1rem; padding-top: 1rem; }
    .adv-modal__loading { margin: 0; font-size: .8125rem; color: var(--muted); }
    .adv-modal__grid { stroke: var(--border); stroke-width: 1; }
    .adv-modal__line-close { stroke: var(--text); stroke-width: 2; }
    .adv-modal__line-sma20 { stroke: var(--up); stroke-width: 2.25; }
    .adv-modal__line-sma50 { stroke: var(--muted); stroke-width: 1.75; stroke-dasharray: 5,4; }
    .adv-modal__crossover-dot { fill: var(--up); stroke: var(--surface); stroke-width: 2; }
    .adv-modal__crossover-line { stroke: var(--up); stroke-width: 1; stroke-dasharray: 2,3; opacity: .5; }
    .adv-modal__legend { display: flex; gap: 1rem; justify-content: center; font-size: .6875rem; color: var(--muted); margin-top: -.5rem; }
    .adv-modal__legend span { display: inline-flex; align-items: center; gap: .375rem; }
    .adv-modal__swatch { width: 14px; height: 2px; display: inline-block; }
    .adv-modal__swatch--close { background: var(--text); }
    .adv-modal__swatch--fast { background: var(--up); }
    .adv-modal__swatch--slow { background: var(--muted); }
    .adv-modal__summary { margin: 0; font-size: .9375rem; font-weight: 600; color: var(--text); line-height: 1.5; }
    .adv-modal__rationale { margin: 0; font-size: .8125rem; color: var(--muted); line-height: 1.5; background: var(--surface-2); border: 1px solid var(--border); border-radius: 8px; padding: .75rem .875rem; }
    .adv-modal__stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: .625rem; }
    .adv-modal__stat { background: var(--surface-2); border: 1px solid var(--border); border-radius: 8px; padding: .625rem .75rem; }
    .adv-modal__stat-label { font-size: .6875rem; color: var(--muted); }
    .adv-modal__stat-value { font-size: .8125rem; margin-top: .125rem; }
    .adv-modal__foot { display: flex; align-items: center; justify-content: space-between; gap: .75rem; flex-wrap: wrap; }
    .adv-modal__foot-note { font-size: .75rem; color: var(--muted); }
    .adv-modal__foot-actions { display: flex; gap: .5rem; margin-left: auto; }
  `]
})
export class Advice implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly service = inject(AdviceService);

  readonly signals = signal<SignalResponse[]>([]);
  readonly loaded = signal(false);
  readonly errorMessage = signal('');
  private readonly analysingSymbol = signal<string | null>(null);

  readonly modalOpen = signal(false);
  readonly modalSymbol = signal('');
  readonly modalSignal = signal<SignalResponse | null>(null);
  readonly modalLoading = signal(false);
  readonly modalError = signal('');

  readonly plotted = computed(() => {
    const chart = this.modalSignal()?.chart;
    return chart && chart.length > 0 ? plotChart(chart) : null;
  });

  private accountId: number | null = null;

  ngOnInit(): void {
    this.auth.getProfile().subscribe({
      next: (profile) => {
        this.accountId = profile.accountId;
        this.loadSignals();
      },
      error: () => undefined
    });
  }

  private loadSignals(): void {
    if (this.accountId === null) {
      return;
    }
    this.service.getSignals(this.accountId).subscribe({
      next: (signals) => {
        this.signals.set(signals);
        this.loaded.set(true);
      },
      error: (e) => this.fail(e)
    });
  }

  analyse(symbol: string): void {
    if (this.accountId === null) {
      return;
    }
    this.analysingSymbol.set(symbol);
    this.modalSymbol.set(symbol);
    this.modalOpen.set(true);
    this.modalLoading.set(true);
    this.modalError.set('');

    this.service.analyse(this.accountId, symbol).subscribe({
      next: (updated) => {
        this.signals.update((rows) => rows.map((row) => (row.symbol === symbol ? updated : row)));
        this.modalSignal.set(updated);
        this.modalLoading.set(false);
        this.analysingSymbol.set(null);
      },
      error: (e) => {
        this.modalLoading.set(false);
        this.analysingSymbol.set(null);
        this.modalError.set(messageFor(e));
      }
    });
  }

  closeModal(): void {
    this.modalOpen.set(false);
    this.modalSignal.set(null);
    this.modalError.set('');
  }

  onBackdrop(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.closeModal();
    }
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.modalOpen()) {
      this.closeModal();
    }
  }

  isAnalysing(symbol: string): boolean {
    return this.analysingSymbol() === symbol;
  }

  canAnalyseNow(signal: SignalResponse): boolean {
    return !signal.nextAnalysisAt || new Date(signal.nextAnalysisAt).getTime() <= Date.now();
  }

  badgeClass(direction: string | null | undefined): string {
    if (direction === 'BUY') return 'lt-badge-up';
    if (direction === 'SELL') return 'lt-badge-down';
    return 'lt-badge-muted';
  }

  private fail(e: unknown): void {
    this.errorMessage.set(messageFor(e));
    this.loaded.set(true);
  }
}
