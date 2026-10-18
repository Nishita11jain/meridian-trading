import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { OrderService } from '../../core/orders/order.service';
import { PortfolioService } from '../../core/portfolio/portfolio.service';
import { DisplayCurrencyService } from '../../core/currency/display-currency.service';
import { formatMoney } from '../../core/currency/currency';
import { messageFor } from '../../core/errors/error-messages';
import { AppShell } from '../../shared/layout/app-shell';
import { MarketBoard } from '../../shared/market/market-board';
import { AccountResponse } from '../../clients/trade-client/model/accountResponse';
import { PortfolioSummary, PricedPosition } from '../../clients/portfolio-client/model/models';

/** How many holdings the dashboard lists; the full table is on the Portfolio screen. */
export const TOP_HOLDINGS = 5;

/**
 * Dashboard: where you stand and what is moving. The account and cash, the portfolio's value and
 * unrealised P&L per currency, the market board with its trend panel, and your largest holdings.
 * The full holdings table lives on the Portfolio screen; this is the overview that links to it.
 */
@Component({
  selector: 'app-dashboard',
  imports: [CommonModule, RouterLink, AppShell, MarketBoard],
  template: `
    <app-shell title="Dashboard">
      <div class="grid gap-4">
        <div class="lt-alert lt-alert-down" data-testid="dashboard-error" role="alert" *ngIf="errorMessage()">{{ errorMessage() }}</div>

        <!-- Overview: who, how much, and the next step, in one card. -->
        <section class="lt-card lt-card-pad lt-appear dash-overview" aria-label="Account overview">
          <!-- A greeting with the name up front; the status and account number are a quiet line under it. -->
          <header class="dash-hero">
            <div data-testid="dashboard-account-card" *ngIf="account() as a">
              <h2 class="dash-hero__hello">Hi, <span class="dash-hero__name" data-testid="dashboard-holder">{{ a.holderName }}</span>!</h2>
              <p class="dash-hero__meta">
                <span class="lt-dot" [style.color]="statusColor(a.status).fg" data-testid="dashboard-status">{{ a.status }}</span>
                <span class="tabular-nums">Account {{ a.accountId }}</span>
              </p>
            </div>
            <div class="dash-hero__actions">
              <a routerLink="/trade" class="lt-btn lt-btn-primary" data-testid="dashboard-new-order">
                <span aria-hidden="true">+</span> New order
              </a>
              <a routerLink="/portfolio" class="lt-btn lt-btn-ghost">Open portfolio</a>
            </div>
          </header>

          <div class="lt-strip dash-overview__stats">
            <div class="lt-stat" data-testid="dashboard-tile-cash">
              <p class="lt-stat__label">Cash available</p>
              <p class="lt-stat__value dash-cash" data-testid="dashboard-cash">
                {{ !account() ? '—' : showCash() ? cur.show(account()!.cashBalance, cashCurrency() || 'INR') : '••••••' }}
                <button type="button" class="dash-eye" data-testid="dashboard-toggle-cash"
                  [attr.aria-pressed]="showCash()" [attr.aria-label]="showCash() ? 'Hide cash balance' : 'Show cash balance'"
                  (click)="showCash.set(!showCash())">
                  <svg *ngIf="!showCash()" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                    <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6z" stroke-linejoin="round"></path>
                    <circle cx="12" cy="12" r="3"></circle>
                  </svg>
                  <svg *ngIf="showCash()" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                    <path d="M3 3l18 18" stroke-linecap="round"></path>
                    <path d="M10.6 10.7A3 3 0 0013.4 13.5" stroke-linecap="round"></path>
                    <path d="M9.9 5.2A11.2 11.2 0 0112 5c6.5 0 10 7 10 7a17.4 17.4 0 01-4.2 4.8" stroke-linecap="round" stroke-linejoin="round"></path>
                    <path d="M6.2 6.3C3.7 8 2 12 2 12s3.5 7 10 7a10.7 10.7 0 004.1-.8" stroke-linecap="round" stroke-linejoin="round"></path>
                  </svg>
                </button>
              </p>
              <p class="lt-stat__sub">Ready for the next order</p>
            </div>

            <div class="lt-stat" data-testid="dashboard-tile-value">
              <p class="lt-stat__label">Market value</p>
              <ng-container *ngIf="byCurrency().length > 0; else noValuation">
                <p *ngFor="let c of byCurrency()" class="lt-stat__value" data-testid="dashboard-market-value">
                  {{ cur.show(c.marketValue, c.currency) }}
                </p>
              </ng-container>
              <p class="lt-stat__sub">
                {{ summary()?.positionCount ?? 0 }} {{ summary()?.positionCount === 1 ? 'holding' : 'holdings' }} at the latest prices
              </p>
            </div>

            <div class="lt-stat" data-testid="dashboard-tile-pnl">
              <p class="lt-stat__label">Unrealised P&amp;L</p>
              <ng-container *ngIf="byCurrency().length > 0; else noValuation">
                <p *ngFor="let c of byCurrency()" class="lt-stat__value" [style.color]="signColor(c.unrealisedPnl)" data-testid="dashboard-pnl">
                  {{ cur.showSigned(c.unrealisedPnl, c.currency) }}
                  <span class="text-[12px] font-semibold" *ngIf="c.unrealisedPnlPercent != null">({{ signedPercent(c.unrealisedPnlPercent) }})</span>
                </p>
              </ng-container>
              <p class="lt-stat__sub">Against what you paid</p>
            </div>
          </div>
        </section>
        <ng-template #noValuation>
          <p class="lt-stat__value" style="color: var(--muted)">—</p>
        </ng-template>

        <p class="lt-alert lt-alert-warn" data-testid="dashboard-partial" role="status" *ngIf="summary()?.partial">
          Some holdings have no price yet, so the values above leave them out.
        </p>

        <app-market-board class="lt-appear lt-appear-2"></app-market-board>

        <section class="lt-card lt-card-pad lt-appear lt-appear-3" aria-labelledby="dashboard-holdings-title">
          <div class="lt-card-head">
            <h2 id="dashboard-holdings-title" class="lt-section-title">Top holdings</h2>
            <a routerLink="/portfolio" class="text-[13px] font-semibold no-underline" style="color: var(--accent-strong)"
              data-testid="dashboard-view-portfolio">View all in portfolio →</a>
          </div>

          <p class="lt-alert lt-alert-down" data-testid="dashboard-holdings-error" role="alert" *ngIf="holdingsError()">{{ holdingsError() }}</p>

          <div class="lt-empty" data-testid="dashboard-holdings-empty" *ngIf="holdingsLoaded() && !holdingsError() && holdings().length === 0">
            <p>You don't hold anything yet.</p>
            <a routerLink="/trade" class="mt-2 inline-block text-sm font-semibold no-underline" style="color: var(--accent-strong)">Place your first order →</a>
          </div>

          <div class="overflow-x-auto" *ngIf="topHoldings().length > 0">
            <table class="lt-table" data-testid="dashboard-holdings-table">
              <thead>
                <tr>
                  <th scope="col">Symbol</th>
                  <th scope="col" class="text-right">Quantity</th>
                  <th scope="col" class="text-right">Avg. cost</th>
                  <th scope="col" class="text-right">Last price</th>
                  <th scope="col" class="text-right">Market value</th>
                  <th scope="col" class="text-right">Unrealised P&amp;L</th>
                  <th scope="col" class="w-[120px]">Weight</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let p of topHoldings()" data-testid="dashboard-holding-row">
                  <td class="whitespace-nowrap">
                    <span class="font-extrabold">{{ p.symbol }}</span>
                    <span class="ml-1.5 text-[11px]" style="color: var(--muted)">{{ p.currency }}</span>
                    <span *ngIf="p.stale" class="lt-badge lt-badge-warn ml-1">Stale</span>
                  </td>
                  <td class="text-right tabular-nums">{{ p.quantity | number }}</td>
                  <td class="text-right tabular-nums" style="color: var(--muted)">{{ money(p.averageCost, p.currency) }}</td>
                  <td class="text-right tabular-nums">{{ p.lastPrice != null ? money(p.lastPrice, p.currency) : '—' }}</td>
                  <td class="text-right tabular-nums font-semibold">{{ p.marketValue != null ? cur.show(p.marketValue, p.currency) : '—' }}</td>
                  <td class="text-right tabular-nums font-semibold" [style.color]="signColor(p.unrealisedPnl)" data-testid="dashboard-holding-pnl">
                    <ng-container *ngIf="p.unrealisedPnl != null; else noPrice">
                      {{ cur.showSigned(p.unrealisedPnl, p.currency) }}
                      <span class="text-[11px]" *ngIf="p.unrealisedPnlPercent != null">({{ signedPercent(p.unrealisedPnlPercent) }})</span>
                    </ng-container>
                  </td>
                  <td>
                    <ng-container *ngIf="weightOf(p) as w; else noWeight">
                      <div class="flex items-center gap-2">
                        <div class="lt-meter flex-1" role="img" [attr.aria-label]="(w | number:'1.0-1') + '% of your ' + p.currency + ' holdings'"><span [style.width.%]="w"></span></div>
                        <span class="w-10 text-right text-[11px] tabular-nums" style="color: var(--muted)">{{ w | number:'1.0-0' }}%</span>
                      </div>
                    </ng-container>
                  </td>
                </tr>
              </tbody>
            </table>
            <p class="mt-2 text-xs" style="color: var(--muted)" *ngIf="holdings().length > topHoldings().length" data-testid="dashboard-holdings-more">
              Showing {{ topHoldings().length }} of {{ holdings().length }} holdings.
            </p>
          </div>
          <ng-template #noPrice><span style="color: var(--muted)">No price yet</span></ng-template>
          <ng-template #noWeight><span class="text-[11px]" style="color: var(--muted)">—</span></ng-template>
        </section>
      </div>
    </app-shell>
  `,
  styles: [`
    .dash-cash { display: flex; align-items: center; gap: .35rem; }
    .dash-eye { display: inline-flex; border: 0; background: none; padding: .2rem; color: var(--muted); cursor: pointer; border-radius: 6px; }
    .dash-eye:hover { color: var(--accent-strong); }
    .dash-eye:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
    /* One overview card in two rows: the greeting and the next step, then the three figures on hairlines. */
    .dash-overview { display: grid; gap: 1rem; }
    .dash-hero { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .75rem 1rem; }
    .dash-hero__hello { margin: 0; font-size: 1.65rem; font-weight: 700; letter-spacing: -.03em; line-height: 1.15; color: var(--muted); }
    .dash-hero__name { font-weight: 800; color: var(--text); }
    .dash-hero__meta { display: flex; flex-wrap: wrap; align-items: center; gap: .35rem .6rem; margin: .35rem 0 0; font-size: .78rem; color: var(--muted); }
    .dash-hero__actions { display: flex; flex-wrap: wrap; gap: .5rem; margin-left: auto; }
    .dash-overview__stats { padding-top: 1rem; border-top: 1px solid var(--border); }
  `]
})
export class Dashboard implements OnInit {
  private readonly authService = inject(AuthService);
  protected readonly cur = inject(DisplayCurrencyService);
  private readonly orderService = inject(OrderService);
  private readonly portfolioService = inject(PortfolioService);

  readonly account = signal<AccountResponse | null>(null);
  /** The currency of the cash balance, read from the balance endpoint (the account itself carries none). */
  readonly cashCurrency = signal('');
  /** The cash balance is hidden until the eye is pressed, so a glance over the shoulder shows nothing. */
  readonly showCash = signal(false);
  readonly summary = signal<PortfolioSummary | null>(null);
  readonly holdings = signal<PricedPosition[]>([]);
  readonly holdingsLoaded = signal(false);
  readonly holdingsError = signal('');
  readonly errorMessage = signal('');

  /** The summary arrives in one base currency; kept as a list so the tiles render the same way. */
  readonly byCurrency = computed(() => {
    const s = this.summary();
    return s && s.positionCount > 0
      ? [{ currency: s.baseCurrency, marketValue: s.marketValue, unrealisedPnl: s.unrealisedPnl, unrealisedPnlPercent: s.unrealisedPnlPercent }]
      : [];
  });

  /** Largest first by market value; an unpriced holding counts at cost, so it is not hidden at the bottom. */
  readonly topHoldings = computed(() =>
    [...this.holdings()]
      .sort((a, b) => Number(b.marketValue ?? b.costBasis) - Number(a.marketValue ?? a.costBasis))
      .slice(0, TOP_HOLDINGS));

  private static readonly STATUS_COLOR: Record<string, { bg: string; fg: string }> = {
    ACTIVE: { bg: 'var(--up-bg)', fg: 'var(--up)' },
    SUSPENDED: { bg: 'var(--warn-bg)', fg: 'var(--warn)' },
    CLOSED: { bg: 'var(--down-bg)', fg: 'var(--down)' }
  };

  /** Each holding's share of everything held in its currency (priced holdings only), for the weight meter. */
  private readonly currencyTotals = computed(() => {
    const totals = new Map<string, number>();
    for (const p of this.holdings()) {
      if (p.marketValue != null) {
        totals.set(p.currency, (totals.get(p.currency) ?? 0) + Number(p.marketValue));
      }
    }
    return totals;
  });

  weightOf(position: PricedPosition): number | null {
    const total = this.currencyTotals().get(position.currency) ?? 0;
    return position.marketValue != null && total > 0 ? (Number(position.marketValue) / total) * 100 : null;
  }

  statusColor(status: string): { bg: string; fg: string } {
    return Dashboard.STATUS_COLOR[status] ?? { bg: 'var(--surface-3)', fg: 'var(--muted)' };
  }

  signColor(value: number | null | undefined): string {
    if (value == null || Number(value) === 0) {
      return 'var(--muted)';
    }
    return Number(value) > 0 ? 'var(--up)' : 'var(--down)';
  }

  /** A price in its own currency with the symbol: $529.76, ₹3,500.00. */
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
    this.authService.getProfile().subscribe({
      next: (profile) => {
        this.orderService.getAccount(profile.accountId).subscribe({
          next: (account) => this.account.set(account),
          error: (err) => this.errorMessage.set(messageFor(err))
        });
        // Only the currency label comes from here; if it cannot be read, the balance shows without one.
        this.orderService.getBalance(profile.accountId).subscribe({
          next: (balance) => this.cashCurrency.set(balance.currency ?? ''),
          error: () => this.cashCurrency.set('')
        });
        // The valuation is read on its own, so a pricing problem never hides the account.
        this.portfolioService.summary(profile.accountId).subscribe({
          next: (summary) => this.summary.set(summary),
          error: () => this.summary.set(null)
        });
        this.portfolioService.positions(profile.accountId).subscribe({
          next: (positions) => {
            this.holdings.set(positions.filter((p) => p.quantity > 0));
            this.holdingsLoaded.set(true);
          },
          error: (err) => {
            this.holdingsError.set(messageFor(err));
            this.holdingsLoaded.set(true);
          }
        });
      },
      error: (err) => this.errorMessage.set(messageFor(err))
    });
  }
}
