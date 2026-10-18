import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';

import { AuthService } from '../../core/auth/auth.service';
import { StrategiesService } from '../../core/strategies/strategies.service';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';
import { messageFor } from '../../core/errors/error-messages';
import { StrategyResponse, StrategyExecutionResponse } from '../../clients/strategies-client/model/models';
import { InstrumentResponse } from '../../clients/watchlists-client/model/models';
import { AppShell } from '../../shared/layout/app-shell';
import { InstrumentSearch } from '../../shared/market/instrument-search';

const MAX_STRATEGIES = 10;

/**
 * Automated Strategy Execution (Sprint 10 stretch). A strategy runs unattended once created: this
 * screen only ever does CRUD (create, list, enable/disable, read the log) -- the evaluation and
 * order placement that a strategy actually does happen entirely in the backend, driven by the
 * market-data stream, never from anything this component calls.
 */
@Component({
  selector: 'app-strategies',
  imports: [CommonModule, ReactiveFormsModule, AppShell, InstrumentSearch],
  template: `
    <app-shell title="Automated strategy execution">
      <div class="strat">
        <div class="lt-alert lt-alert-down strat__full" data-testid="strategies-error" role="alert" *ngIf="errorMessage()">{{ errorMessage() }}</div>

        <p class="strat__intro">
          A strategy runs unattended and places real orders through the same route and checks as any
          manual trade. Each strategy is one side only, BUY or SELL &mdash; every condition and limit
          below is exactly what will run, nothing hidden, nothing implicit.
        </p>

        <div class="strat__layout">
          <!-- Left: strategies list -->
          <div class="strat__list">
            <div class="strat__list-head">
              <h2 class="lt-eyebrow">Your strategies</h2>
              <span class="strat__slots">{{ strategies().length }} of {{ maxStrategies }} slots used</span>
            </div>

            <div class="lt-empty" data-testid="strategies-empty" *ngIf="loaded() && strategies().length === 0">
              <p>No strategies yet. Create one on the right to get started.</p>
            </div>

            <div class="lt-card lt-card-pad strat__card" *ngFor="let s of strategies()" [attr.data-testid]="'strategy-card-' + s.id">
              <div class="strat__card-head">
                <div class="strat__card-title">
                  <span class="strat__symbol">{{ s.symbol }}</span>
                  <span class="lt-badge" [ngClass]="s.side === 'BUY' ? 'lt-badge-up' : 'lt-badge-down'">{{ s.side }}</span>
                  <span class="lt-badge lt-badge-info">{{ s.strategyType }}</span>
                </div>
                <button type="button" class="strat__toggle" [class.strat__toggle--on]="s.status === 'ACTIVE'"
                  [attr.aria-pressed]="s.status === 'ACTIVE'" [attr.data-testid]="'strategy-toggle-' + s.id"
                  [disabled]="toggling() === s.id"
                  (click)="toggleStatus(s)">
                  {{ s.status === 'ACTIVE' ? 'Active' : 'Disabled' }}
                  <span class="strat__switch"><span class="strat__switch-dot"></span></span>
                </button>
              </div>

              <p class="strat__rule">{{ ruleText(s) }}</p>

              <div class="strat__limits">
                <span>Max spend <strong>{{ s.maxSpend | number }}</strong>/trade</span>
                <span>Max position <strong>{{ s.maxPositionSize }}</strong></span>
                <span>Stop after <strong>{{ s.maxConsecutiveFailures }}</strong> failures</span>
              </div>

              <div class="lt-alert lt-alert-warn strat__disabled-note" *ngIf="s.status === 'DISABLED' && s.disabledReason" data-testid="strategy-disabled-reason">
                {{ s.disabledReason }}
              </div>

              <div class="strat__card-foot">
                <span class="strat__fails" *ngIf="s.consecutiveFailures">{{ s.consecutiveFailures }} consecutive failure(s)</span>
                <button type="button" class="strat__log-link" [attr.data-testid]="'strategy-view-log-' + s.id" (click)="toggleLog(s)">
                  {{ openLogFor() === s.id ? 'Hide log' : 'View log' }}
                </button>
              </div>

              <div class="strat__log" *ngIf="openLogFor() === s.id" [attr.data-testid]="'strategy-log-' + s.id">
                <p class="strat__log-empty" *ngIf="logEntries().length === 0">No executions yet.</p>
                <div class="strat__log-row" *ngFor="let entry of logEntries()">
                  <span class="strat__log-time">{{ entry.triggeredAt | date:'dd MMM, HH:mm' }}</span>
                  <span>{{ entry.action }}</span>
                  <span>&#64; {{ entry.triggeringPrice | number }}</span>
                  <span class="lt-badge" [ngClass]="outcomeClass(entry.outcome)">{{ entry.outcome }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- Right: create form -->
          <form class="lt-card lt-card-pad strat__form" [formGroup]="createForm" (ngSubmit)="submitCreate()">
            <h2 class="lt-eyebrow">Create a strategy</h2>

            <label class="strat__field">
              Instrument
              <app-instrument-search inputId="strategy-symbol" label="Instrument" placeholder="Search symbol or name"
                [instruments]="instruments()" (picked)="onSymbolPicked($event)"></app-instrument-search>
              <span class="strat__picked" *ngIf="createForm.value.symbol" data-testid="strategy-symbol-picked">{{ createForm.value.symbol }}</span>
            </label>

            <label class="strat__field">
              Side
              <div class="strat__side-toggle">
                <button type="button" class="strat__side-btn" [class.strat__side-btn--buy]="createForm.value.side === 'BUY'"
                  data-testid="strategy-side-buy" (click)="createForm.patchValue({ side: 'BUY' })">BUY</button>
                <button type="button" class="strat__side-btn" [class.strat__side-btn--sell]="createForm.value.side === 'SELL'"
                  data-testid="strategy-side-sell" (click)="createForm.patchValue({ side: 'SELL' })">SELL</button>
              </div>
              <span class="strat__hint">This strategy only ever places orders on this side. For both directions, create two strategies.</span>
            </label>

            <label class="strat__field">
              Strategy type
              <select class="lt-select" formControlName="strategyType" data-testid="strategy-type">
                <option value="CROSSOVER">Moving-average crossover</option>
                <option value="BOLLINGER">Bollinger band</option>
              </select>
            </label>

            <div class="strat__grid2" *ngIf="createForm.value.strategyType === 'CROSSOVER'">
              <label class="strat__field">
                Fast window (quotes)
                <input class="lt-input" type="number" formControlName="fastWindow" data-testid="strategy-fast-window" />
              </label>
              <label class="strat__field">
                Slow window (quotes)
                <input class="lt-input" type="number" formControlName="slowWindow" data-testid="strategy-slow-window" />
              </label>
            </div>

            <div class="strat__grid2" *ngIf="createForm.value.strategyType === 'BOLLINGER'">
              <label class="strat__field">
                Band period (quotes)
                <input class="lt-input" type="number" formControlName="bandPeriod" data-testid="strategy-band-period" />
              </label>
              <label class="strat__field">
                Band width (&sigma;)
                <input class="lt-input" type="number" step="0.1" formControlName="bandMultiplier" data-testid="strategy-band-multiplier" />
              </label>
            </div>

            <label class="strat__field">
              Order size (units per trigger)
              <input class="lt-input" type="number" formControlName="orderQuantity" data-testid="strategy-order-quantity" />
            </label>

            <div class="strat__risk">
              <p class="lt-eyebrow">Risk limits &mdash; required</p>
              <div class="strat__grid2">
                <label class="strat__field">
                  Max spend / trade
                  <input class="lt-input" type="number" formControlName="maxSpend" data-testid="strategy-max-spend" />
                </label>
                <label class="strat__field">
                  Max position size
                  <input class="lt-input" type="number" formControlName="maxPositionSize" data-testid="strategy-max-position" />
                </label>
              </div>
              <label class="strat__field">
                Stop after this many consecutive failures
                <input class="lt-input" type="number" formControlName="maxConsecutiveFailures" data-testid="strategy-max-failures" />
              </label>
            </div>

            <p class="strat__hint">
              Orders place through the same route, checks and idempotency as a manual trade. You can
              disable this strategy at any time and it stops before its next evaluation.
            </p>

            <button type="submit" class="lt-btn lt-btn-primary" data-testid="strategy-create"
              [disabled]="createForm.invalid || !createForm.value.symbol || submitting() || strategies().length >= maxStrategies">
              {{ submitting() ? 'Creating…' : 'Create strategy' }}
            </button>
          </form>
        </div>
      </div>
    </app-shell>
  `,
  styles: [`
    .strat { display: flex; flex-direction: column; gap: 1rem; }
    .strat__full { grid-column: 1 / -1; }
    .strat__intro { margin: 0; max-width: 60rem; font-size: .8125rem; color: var(--muted); line-height: 1.5; }
    .strat__layout { display: flex; gap: 1.25rem; align-items: flex-start; flex-wrap: wrap; }
    .strat__list { flex: 2 1 480px; min-width: 0; display: flex; flex-direction: column; gap: .75rem; }
    .strat__list-head { display: flex; align-items: center; justify-content: space-between; }
    .strat__slots { font-size: .75rem; color: var(--muted); }
    .strat__card { display: flex; flex-direction: column; gap: .5rem; }
    .strat__card-head { display: flex; align-items: center; justify-content: space-between; gap: .5rem; flex-wrap: wrap; }
    .strat__card-title { display: flex; align-items: center; gap: .5rem; }
    .strat__symbol { font-weight: 700; font-size: .9375rem; }
    .strat__rule { margin: 0; font-size: .8125rem; color: var(--text); line-height: 1.5; }
    .strat__limits { display: flex; gap: 1rem; font-size: .6875rem; color: var(--muted); flex-wrap: wrap; }
    .strat__disabled-note { font-size: .75rem; padding: .5rem .75rem; }
    .strat__card-foot { display: flex; align-items: center; justify-content: space-between; margin-top: .25rem; padding-top: .5rem; border-top: 1px solid var(--border); }
    .strat__fails { font-size: .6875rem; color: var(--down); }
    .strat__log-link { background: none; border: none; color: var(--accent, #5b9dff); font-size: .75rem; cursor: pointer; padding: 0; }
    .strat__log { display: flex; flex-direction: column; gap: .375rem; margin-top: .25rem; padding-top: .5rem; border-top: 1px dashed var(--border); }
    .strat__log-empty { margin: 0; font-size: .75rem; color: var(--muted); }
    .strat__log-row { display: grid; grid-template-columns: auto 1fr auto auto; gap: .625rem; font-size: .75rem; align-items: center; }
    .strat__log-time { color: var(--muted); }
    .strat__toggle { display: inline-flex; align-items: center; gap: .5rem; background: none; border: none; cursor: pointer; font-size: .75rem; font-weight: 600; color: var(--muted); }
    .strat__toggle--on { color: var(--up); }
    .strat__switch { width: 30px; height: 16px; border-radius: 999px; background: var(--surface-3); position: relative; display: inline-block; }
    .strat__switch-dot { position: absolute; top: 2px; left: 2px; width: 12px; height: 12px; border-radius: 50%; background: var(--muted); transition: left .15s; }
    .strat__toggle--on .strat__switch { background: color-mix(in srgb, var(--up) 25%, transparent); }
    .strat__toggle--on .strat__switch-dot { left: 16px; background: var(--up); }
    .strat__form { flex: 1 1 320px; min-width: 300px; display: flex; flex-direction: column; gap: .75rem; position: sticky; top: 1rem; }
    .strat__field { display: flex; flex-direction: column; gap: .3rem; font-size: .75rem; color: var(--muted); }
    .strat__picked { font-size: .75rem; color: var(--text); font-weight: 600; }
    .strat__side-toggle { display: grid; grid-template-columns: 1fr 1fr; gap: .5rem; }
    .strat__side-btn { padding: .5rem; border-radius: 7px; border: 1px solid var(--border); background: var(--surface-2); color: var(--muted); font-weight: 700; font-size: .8125rem; cursor: pointer; }
    .strat__side-btn--buy { background: var(--up-bg); color: var(--up); border-color: var(--up); }
    .strat__side-btn--sell { background: var(--down-bg); color: var(--down); border-color: var(--down); }
    .strat__hint { font-size: .6875rem; color: var(--muted); line-height: 1.4; }
    .strat__grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: .625rem; }
    .strat__risk { display: flex; flex-direction: column; gap: .625rem; padding-top: .5rem; border-top: 1px solid var(--border); }
    @media (max-width: 860px) { .strat__layout { flex-direction: column; } .strat__form { position: static; } }
  `]
})
export class Strategies implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly service = inject(StrategiesService);
  private readonly watchlists = inject(WatchlistsService);

  readonly maxStrategies = MAX_STRATEGIES;
  readonly strategies = signal<StrategyResponse[]>([]);
  readonly instruments = signal<InstrumentResponse[]>([]);
  readonly loaded = signal(false);
  readonly errorMessage = signal('');
  readonly submitting = signal(false);
  readonly toggling = signal<number | null>(null);
  readonly openLogFor = signal<number | null>(null);
  readonly logEntries = signal<StrategyExecutionResponse[]>([]);

  private accountId: number | null = null;

  readonly createForm = this.fb.nonNullable.group({
    symbol: ['', Validators.required],
    side: ['BUY', Validators.required],
    strategyType: ['CROSSOVER', Validators.required],
    fastWindow: [6, [Validators.required, Validators.min(1)]],
    slowWindow: [12, [Validators.required, Validators.min(2)]],
    bandPeriod: [20, [Validators.required, Validators.min(2)]],
    bandMultiplier: [2, [Validators.required, Validators.min(0.1)]],
    orderQuantity: [1, [Validators.required, Validators.min(1)]],
    maxSpend: [null as number | null, [Validators.required, Validators.min(0.01)]],
    maxPositionSize: [null as number | null, [Validators.required, Validators.min(1)]],
    maxConsecutiveFailures: [3, [Validators.required, Validators.min(1)]]
  });

  ngOnInit(): void {
    this.auth.getProfile().subscribe({
      next: (profile) => {
        this.accountId = profile.accountId;
        this.loadStrategies();
      },
      error: () => undefined
    });
    this.watchlists.getInstruments().subscribe({ next: (list) => this.instruments.set(list), error: () => undefined });
  }

  private loadStrategies(): void {
    if (this.accountId === null) {
      return;
    }
    this.service.list(this.accountId).subscribe({
      next: (list) => { this.strategies.set(list); this.loaded.set(true); },
      error: (e) => this.fail(e)
    });
  }

  onSymbolPicked(symbol: string): void {
    this.createForm.patchValue({ symbol });
  }

  submitCreate(): void {
    if (this.accountId === null || this.createForm.invalid || !this.createForm.value.symbol) {
      return;
    }
    const v = this.createForm.getRawValue();
    this.submitting.set(true);
    this.service.create(this.accountId, {
      symbol: v.symbol,
      side: v.side as 'BUY' | 'SELL',
      strategyType: v.strategyType as 'CROSSOVER' | 'BOLLINGER',
      fastWindow: v.strategyType === 'CROSSOVER' ? v.fastWindow : undefined,
      slowWindow: v.strategyType === 'CROSSOVER' ? v.slowWindow : undefined,
      bandPeriod: v.strategyType === 'BOLLINGER' ? v.bandPeriod : undefined,
      bandMultiplier: v.strategyType === 'BOLLINGER' ? v.bandMultiplier : undefined,
      orderQuantity: v.orderQuantity,
      maxSpend: v.maxSpend as number,
      maxPositionSize: v.maxPositionSize as number,
      maxConsecutiveFailures: v.maxConsecutiveFailures
    }).subscribe({
      next: (created) => {
        this.strategies.update((rows) => [created, ...rows]);
        this.submitting.set(false);
        this.createForm.patchValue({ symbol: '' });
      },
      error: (e) => {
        this.submitting.set(false);
        this.fail(e);
      }
    });
  }

  toggleStatus(strategy: StrategyResponse): void {
    if (this.accountId === null || strategy.id === undefined) {
      return;
    }
    const next = strategy.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    this.toggling.set(strategy.id);
    this.service.updateStatus(this.accountId, strategy.id, next).subscribe({
      next: (updated) => {
        this.strategies.update((rows) => rows.map((r) => (r.id === updated.id ? updated : r)));
        this.toggling.set(null);
      },
      error: (e) => {
        this.toggling.set(null);
        this.fail(e);
      }
    });
  }

  toggleLog(strategy: StrategyResponse): void {
    if (this.accountId === null || strategy.id === undefined) {
      return;
    }
    if (this.openLogFor() === strategy.id) {
      this.openLogFor.set(null);
      return;
    }
    this.openLogFor.set(strategy.id);
    this.service.log(this.accountId, strategy.id).subscribe({
      next: (entries) => this.logEntries.set(entries),
      error: (e) => this.fail(e)
    });
  }

  ruleText(s: StrategyResponse): string {
    const verb = s.side === 'BUY' ? 'Buy' : 'Sell';
    if (s.strategyType === 'CROSSOVER') {
      return `${verb} ${s.orderQuantity} when the ${s.fastWindow}-quote average crosses ${s.side === 'BUY' ? 'above' : 'below'} the ${s.slowWindow}-quote average.`;
    }
    return `${verb} ${s.orderQuantity} when price crosses ${s.side === 'BUY' ? 'below the lower' : 'above the upper'} band (${s.bandPeriod}-quote, ${s.bandMultiplier}σ).`;
  }

  outcomeClass(outcome: string | undefined): string {
    if (outcome === 'PLACED') return 'lt-badge-up';
    if (outcome === 'REJECTED') return 'lt-badge-down';
    return 'lt-badge-muted';
  }

  private fail(e: unknown): void {
    this.errorMessage.set(messageFor(e));
    this.loaded.set(true);
  }
}
