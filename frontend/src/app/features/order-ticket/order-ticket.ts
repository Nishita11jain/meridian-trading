import { ActivatedRoute } from '@angular/router';
import { Component, DestroyRef, ElementRef, HostListener, OnDestroy, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { Subscription, filter, map, switchMap, take, timer } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators, AbstractControl, ValidationErrors } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { OrderService } from '../../core/orders/order.service';
import { messageFor } from '../../core/errors/error-messages';
import { AppShell } from '../../shared/layout/app-shell';
import { BalanceResponse } from '../../clients/trade-client/model/balanceResponse';
import { HasUnsavedChanges } from '../../core/guards/unsaved-changes.guard';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';
import { DisplayCurrencyService } from '../../core/currency/display-currency.service';
import { currencySymbol, formatMoney } from '../../core/currency/currency';
import { FxRatesResponse } from '../../clients/watchlists-client/model/models';
import { MarketQuoteResponse } from '../../clients/watchlists-client/model/marketQuoteResponse';
import { InstrumentResponse } from '../../clients/watchlists-client/model/instrumentResponse';
import { isClosed, queuedMessage } from '../../core/market/trading-status';
import { PriceTrend } from '../../shared/market/price-trend';
import { rejectionReasonText } from '../../core/orders/rejection-reasons';
import { LeavePageConfirmService } from '../../core/guards/leave-page-confirm.service';

const SYMBOL_PATTERN = /^(?:[A-Z][A-Z0-9]{0,19}|[A-Z][A-Z0-9]{0,16}\.(?:NS|BO)|FX:[A-Z]{6}|X:[A-Z0-9\-]{2,17})$/;

/** True when we cannot tell whether the order was placed: no response, or a server error. */
function isAmbiguousFailure(err: unknown): boolean {
  const status = (err as { status?: unknown } | null)?.status;
  return typeof status === 'number' && (status === 0 || status >= 500);
}

/** How far a limit may sit from the market price before the ticket warns: a fat-finger guard. */
/** How often, and how many times, the ticket checks whether a placed order has settled. */
const SETTLE_POLL_MS = 2000;
const SETTLE_MAX_CHECKS = 15;
const PRICE_WARNING_THRESHOLD = 0.10;

function money(value: number): string {
  return value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function priceValidator(control: AbstractControl): ValidationErrors | null {
  const raw = String(control.value ?? '').trim();
  if (!raw) {
    return null;
  }

  if (!/^\d+(?:\.\d{1,2})?$/.test(raw)) {
    return { priceFormat: true };
  }

  const value = Number(raw);
  if (!(value > 0)) {
    return { pricePositive: true };
  }

  return null;
}

/**
 * Order ticket: its own page, with the form on the left and a live summary (account, cash, estimated
 * cost) on the right. Submitting validates and opens a review; nothing is sent until it is confirmed.
 */
@Component({
  selector: 'app-order-ticket',
  imports: [CommonModule, ReactiveFormsModule, AppShell, PriceTrend],
  template: `
    <app-shell title="New order">
      <!--
        One card, two columns: the order on the left, what it costs and the Review button on the right.
        Fields sit directly on the card (no box around each), separated by space and one hairline.
      -->
      <section class="lt-card ticket lt-appear" aria-labelledby="ticket-title">
        <form [formGroup]="ticketForm" (ngSubmit)="submitOrder()" novalidate class="ticket__grid">
          <div class="ticket__form">
            <div class="flex flex-wrap items-center justify-between gap-3">
              <h2 id="ticket-title" class="ticket__title">Limit order</h2>
              <span class="lt-badge lt-badge-muted" data-testid="ticket-account-badge">Account {{ accountDisplay() }}</span>
            </div>

            <div class="lt-alert lt-alert-down" data-testid="ticket-error" role="alert" *ngIf="errorMessage()">{{ errorMessage() }}</div>

            <div class="lt-seg ticket__side" role="group" aria-label="Order side">
              <button type="button" data-testid="ticket-side-buy" class="ticket__side-btn"
                [class.is-buy]="ticketForm.controls.side.value === 'BUY'"
                [attr.aria-pressed]="ticketForm.controls.side.value === 'BUY'"
                (click)="ticketForm.controls.side.setValue('BUY')"><span aria-hidden="true">↗</span> Buy</button>
              <button type="button" data-testid="ticket-side-sell" class="ticket__side-btn"
                [class.is-sell]="ticketForm.controls.side.value === 'SELL'"
                [attr.aria-pressed]="ticketForm.controls.side.value === 'SELL'"
                (click)="ticketForm.controls.side.setValue('SELL')"><span aria-hidden="true">↘</span> Sell</button>
            </div>

            <div class="ticket__field">
              <label class="lt-field-label" for="ticket-symbol-input">Instrument</label>
              <!--
                A search-and-select combobox over the tradable instruments in the catalogue (read once
                from our own API; no Fauxnance call). Typing filters by symbol or company name; arrow
                keys move, Enter picks, Esc closes. If the catalogue could not be read it falls back to
                free text with the symbol-format check, and the server validates either way.
              -->
              <div class="relative">
                <input
                  id="ticket-symbol-input"
                  class="lt-input ticket__input"
                  role="combobox"
                  aria-autocomplete="list"
                  aria-controls="ticket-symbol-listbox"
                  [attr.aria-expanded]="listOpen()"
                  [attr.aria-activedescendant]="activeIndex() >= 0 ? 'ticket-symbol-option-' + activeIndex() : null"
                  [style.border-color]="ticketForm.controls.symbol.touched && ticketForm.controls.symbol.invalid ? 'var(--down)' : null"
                  data-testid="ticket-symbol"
                  [attr.aria-invalid]="ticketForm.controls.symbol.touched && ticketForm.controls.symbol.invalid"
                  [attr.aria-describedby]="ticketForm.controls.symbol.touched && ticketForm.controls.symbol.invalid ? 'ticket-symbol-hint' : null"
                  type="text" formControlName="symbol" [attr.placeholder]="instrumentList() ? 'Search by symbol or name, e.g. TCS or Tata' : 'AAPL or INFY.NS'"
                  autocapitalize="characters" autocomplete="off" spellcheck="false"
                  (focus)="openList()" (input)="onSymbolInput()" (keydown)="onSymbolKeydown($event)" (blur)="closeList()"
                />
                <ul id="ticket-symbol-listbox" role="listbox" aria-label="Instruments"
                  class="absolute left-0 right-0 z-30 mt-1 max-h-72 overflow-auto rounded-xl border p-1"
                  style="background: var(--surface); border-color: var(--border-strong); box-shadow: var(--shadow-lg)"
                  data-testid="ticket-symbol-listbox"
                  *ngIf="listOpen() && instrumentList() && filteredInstruments().length > 0">
                  <li *ngFor="let ins of filteredInstruments(); let i = index"
                    role="option" [id]="'ticket-symbol-option-' + i" [attr.aria-selected]="i === activeIndex()"
                    data-testid="ticket-symbol-option"
                    class="flex cursor-pointer items-center justify-between gap-3 rounded-lg px-3 py-2 text-sm"
                    [style.background]="i === activeIndex() ? 'var(--surface-2)' : 'transparent'"
                    (mousedown)="$event.preventDefault()" (mouseenter)="activeIndex.set(i)" (click)="selectInstrument(ins)">
                    <span class="min-w-0">
                      <strong style="color: var(--text)">{{ ins.symbol }}</strong>
                      <span *ngIf="ins.tradingStatus === 'CLOSED'" class="lt-badge lt-badge-muted ml-1.5" data-testid="ticket-symbol-closed">Closed</span>
                      <span class="block truncate text-xs" style="color: var(--muted)">{{ ins.name }}</span>
                    </span>
                    <span class="flex flex-none items-center gap-2 text-xs">
                      <span class="tabular-nums" style="color: var(--muted)" *ngIf="quotes().get(ins.symbol) as q">{{ q.lastPrice | number:'1.2-4' }}</span>
                      <span class="lt-badge lt-badge-muted">{{ ins.currency }}</span>
                    </span>
                  </li>
                </ul>
                <p class="absolute left-0 right-0 z-30 mt-1 rounded-xl border px-3 py-2.5 text-xs"
                  style="background: var(--surface); border-color: var(--border-strong); color: var(--muted); box-shadow: var(--shadow-lg)"
                  data-testid="ticket-symbol-none"
                  *ngIf="listOpen() && instrumentList() && filteredInstruments().length === 0 && symbolText()">
                  No tradable instrument matches "{{ symbolText() }}".
                </p>
              </div>
              <small id="ticket-symbol-hint" class="ticket__hint ticket__hint--error" *ngIf="ticketForm.controls.symbol.touched && ticketForm.controls.symbol.invalid">
                {{ instrumentList() ? 'Choose an instrument from the list.' : 'Enter a valid symbol, for example AAPL, INFY.NS, FX:EURUSD or X:BTC.' }}
              </small>
              <div class="ticket__meta">
                <span class="truncate" style="color: var(--text)" *ngIf="selectedInstrument() as ins" data-testid="ticket-instrument-name">
                  {{ ins.name }} <span style="color: var(--muted)">· {{ ins.currency }}</span>
                </span>
                <!-- The cached market price (refreshed by the platform poller); no Fauxnance call is made from here. -->
                <span class="inline-flex flex-wrap items-center gap-1.5" *ngIf="marketQuote() as q" data-testid="ticket-market-price">
                  Market <strong class="tabular-nums" style="color: var(--text)">{{ fmt(q.lastPrice, q.currency, 2, 4) }}</strong>
                  · {{ q.quoteAsOf | date:'shortTime' }}
                  <span class="lt-badge lt-badge-warn" *ngIf="q.stale" data-testid="ticket-market-stale">stale</span>
                </span>
                <span *ngIf="!marketQuote() && symbolValid()" data-testid="ticket-no-price">
                  No recent price for {{ symbolText() }}. Add it to a watchlist to see its price here.
                </span>
              </div>
            </div>

            <div class="ticket__pair">
              <div class="ticket__field">
                <label class="lt-field-label" for="ticket-quantity-input">Quantity</label>
                <input
                  id="ticket-quantity-input"
                  class="lt-input ticket__input"
                  [style.border-color]="(ticketForm.controls.quantity.touched && ticketForm.controls.quantity.invalid) || blockReason() ? 'var(--down)' : null"
                  data-testid="ticket-quantity" type="number" min="1" step="1" formControlName="quantity" placeholder="0"
                  [attr.aria-invalid]="ticketForm.controls.quantity.touched && ticketForm.controls.quantity.invalid"
                  [attr.aria-describedby]="ticketForm.controls.quantity.touched && ticketForm.controls.quantity.invalid ? 'ticket-quantity-hint' : null"
                />
                <small id="ticket-quantity-hint" class="ticket__hint ticket__hint--error" *ngIf="ticketForm.controls.quantity.touched && ticketForm.controls.quantity.invalid">
                  Quantity must be a whole number greater than zero.
                </small>
                <!-- What the quantity is limited by: cash for a buy, the holding for a sell. -->
                <div class="ticket__meta">
                  <span *ngIf="ticketForm.controls.side.value === 'SELL' && holdings() !== null && symbolValid()" data-testid="ticket-holding">
                    You hold <strong class="tabular-nums" style="color: var(--text)">{{ heldQuantity() }}</strong>
                  </span>
                  <ng-container *ngIf="maxQuantity() !== null">
                    <span data-testid="ticket-max">Max {{ maxQuantity() }}{{ ticketForm.controls.side.value === 'BUY' ? ' at this price' : '' }}</span>
                    <button type="button" class="ticket__link" data-testid="ticket-use-max" [disabled]="!maxQuantity()" (click)="useMax()">Use max</button>
                  </ng-container>
                </div>
              </div>

              <div class="ticket__field">
                <label class="lt-field-label" for="ticket-price-input">Limit price</label>
                <div class="ticket__price">
                <span class="ticket__price-symbol" aria-hidden="true" data-testid="ticket-price-symbol">{{ priceSymbol() }}</span>
                <input
                  id="ticket-price-input"
                  class="lt-input ticket__input ticket__input--price"
                  [style.border-color]="ticketForm.controls.price.touched && ticketForm.controls.price.invalid ? 'var(--down)' : (priceWarning() ? 'var(--warn)' : null)"
                  data-testid="ticket-price" type="text" inputmode="decimal" formControlName="price" placeholder="25.50"
                  [attr.aria-invalid]="ticketForm.controls.price.touched && ticketForm.controls.price.invalid"
                  [attr.aria-describedby]="ticketForm.controls.price.touched && ticketForm.controls.price.invalid ? 'ticket-price-hint' : (priceWarning() ? 'ticket-price-warning' : null)"
                />
                </div>
                <small id="ticket-price-hint" class="ticket__hint ticket__hint--error" *ngIf="ticketForm.controls.price.touched && ticketForm.controls.price.invalid">
                  Price must be greater than zero with at most two decimal places.
                </small>
                <span id="ticket-price-warning" class="ticket__hint" style="color: var(--warn)"
                  data-testid="ticket-price-warning" role="status" *ngIf="priceWarning() as warning">{{ warning }}</span>
              </div>
            </div>

            <p class="lt-alert lt-alert-down" data-testid="ticket-block" role="alert" *ngIf="blockReason() as reason">{{ reason }}</p>
            <p class="lt-alert lt-alert-muted" data-testid="ticket-queued" role="status" *ngIf="queueNotice() as notice">{{ notice }}</p>
          </div>

          <!-- The order's cost and the one action, beside the form rather than under it. -->
          <aside class="ticket__summary" aria-label="Order summary">
            <p class="lt-eyebrow">{{ ticketForm.controls.side.value === 'BUY' ? 'Maximum cost' : 'Minimum proceeds' }}</p>
            <p class="ticket__total tabular-nums" *ngIf="estimate() !== null" data-testid="ticket-estimate">
              <span class="sr-only">{{ ticketForm.controls.side.value === 'BUY' ? 'Maximum cost' : 'Minimum proceeds' }}</span>
              {{ fmt(estimate()!, amountCurrency()) }}
            </p>
            <p class="ticket__total ticket__total--empty" *ngIf="estimate() === null">—</p>
            <p class="ticket__note" *ngIf="estimate() === null">Enter a quantity and a limit price to see the amount.</p>
            <p class="ticket__note tabular-nums" *ngIf="estimate() !== null" data-testid="ticket-estimate-basis">
              {{ quantityValue() }} × {{ fmt(limitPrice()!, amountCurrency()) }} (quantity × limit price).
              {{ ticketForm.controls.side.value === 'BUY' ? 'A limit buy fills at your limit or better, so you pay at most this.' : 'A limit sell fills at your limit or better, so you receive at least this.' }}
            </p>

            <p class="ticket__note tabular-nums" *ngIf="rate() !== null && baseEstimate() !== null" data-testid="ticket-estimate-base">
              About {{ cur.show(baseEstimate(), balance()?.currency ?? 'INR') }} in cash, at {{ fmt(rate()!, balance()?.currency, 2, 4) }} to the {{ currencyName(amountCurrency()) }},
              the rate locked when the order is placed (it includes a {{ fxRates()?.marginPercent }}% margin).
            </p>

            <dl class="ticket__facts">
              <div *ngIf="balance() as b" data-testid="ticket-cash">
                <dt>Cash</dt><dd class="tabular-nums">{{ cur.show(b.cashBalance, b.currency) }}</dd>
              </div>
              <div *ngIf="ticketForm.controls.side.value === 'BUY' && cashAfter() !== null">
                <dt>Cash after</dt><dd class="tabular-nums" [style.color]="cashAfter()! < 0 ? 'var(--down)' : null">{{ cur.show(cashAfter(), balance()?.currency ?? 'INR') }}</dd>
              </div>
              <div *ngIf="ticketForm.controls.side.value === 'SELL' && holdings() !== null && symbolValid()">
                <dt>Holding after</dt><dd class="tabular-nums">{{ holdingAfter() }}</dd>
              </div>
            </dl>

            <button #reviewButton data-testid="ticket-submit" type="submit" class="lt-btn ticket__review"
              [class.lt-btn-buy]="ticketForm.controls.side.value === 'BUY'" [class.lt-btn-sell]="ticketForm.controls.side.value === 'SELL'"
              [disabled]="submitting() || blockReason() !== null">
              <span class="inline-block h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent" *ngIf="submitting()"></span>
              {{ submitting() ? 'Submitting...' : 'Review ' + (ticketForm.controls.side.value === 'BUY' ? 'buy' : 'sell') + ' order' }}
            </button>
            <p class="ticket__note">Nothing is sent until you confirm.</p>
          </aside>
        </form>
      </section>

      <!-- Once an instrument is chosen, its recent trend sits under the ticket, as on the dashboard. -->
      <div class="ticket__trend lt-appear" *ngIf="symbolValid()">
        <app-price-trend [symbol]="symbolText()"></app-price-trend>
      </div>

      <!--
        The review dialog: the order in full, at the moment of commitment, with nothing else on screen.
        Esc, a click outside, or Edit closes it and keeps what was typed; focus stays inside while it is
        open and returns to the Review button when it closes.
      -->
      <div *ngIf="reviewing()" class="lt-dialog-backdrop" data-testid="ticket-review-backdrop" (click)="onBackdropClick($event)">
        <div #reviewDialog role="dialog" aria-modal="true" aria-labelledby="ticket-review-title" tabindex="-1"
          class="lt-dialog" data-testid="ticket-review" (keydown)="trapFocus($event)">
          <div data-testid="ticket-review-summary">
            <p class="lt-eyebrow">Review ·
              <span [style.color]="ticketForm.controls.side.value === 'BUY' ? 'var(--up)' : 'var(--down)'">{{ ticketForm.controls.side.value }}</span>
              · Limit</p>
            <h3 id="ticket-review-title" class="lt-dialog__title">
              {{ ticketForm.controls.side.value === 'BUY' ? 'Buy' : 'Sell' }} {{ ticketForm.controls.quantity.value }} {{ symbolText() }}
            </h3>
            <p class="review__total tabular-nums" *ngIf="estimate() !== null">
              {{ fmt(estimate()!, amountCurrency()) }}
              <span class="review__total-label">{{ ticketForm.controls.side.value === 'BUY' ? 'at most' : 'at least' }}</span>
            </p>
          </div>

          <dl class="review__rows">
            <div>
              <dt>Limit</dt>
              <dd class="tabular-nums">{{ limitPrice() !== null ? fmt(limitPrice()!, amountCurrency()) : ticketForm.controls.price.value }}</dd>
            </div>
            <div>
              <dt>Market</dt>
              <dd class="tabular-nums" *ngIf="marketQuote() as q">{{ fmt(q.lastPrice, q.currency, 2, 4) }} <span class="review__muted">· {{ q.quoteAsOf | date:'shortTime' }}</span></dd>
              <dd class="review__muted" *ngIf="!marketQuote()">No recent price</dd>
            </div>
            <div *ngIf="ticketForm.controls.side.value === 'BUY' && balance() as b">
              <dt>Cash</dt>
              <dd class="tabular-nums" data-testid="ticket-review-cash">{{ cur.show(b.cashBalance, b.currency) }} → {{ cur.show(cashAfter(), b.currency) }}</dd>
            </div>
            <div *ngIf="ticketForm.controls.side.value === 'SELL' && holdings() !== null">
              <dt>Holding</dt>
              <dd class="tabular-nums" data-testid="ticket-review-holding">{{ heldQuantity() }} → {{ holdingAfter() }}</dd>
            </div>
          </dl>

          <p class="review__note tabular-nums" *ngIf="rate() !== null && baseEstimate() !== null" data-testid="ticket-review-converted">
            About {{ cur.show(baseEstimate(), balance()?.currency ?? 'INR') }} in cash, at {{ fmt(rate()!, balance()?.currency, 2, 4) }} to the {{ currencyName(amountCurrency()) }}.
            The rate is locked when the order is placed.
          </p>
          <p class="review__note" *ngIf="currenciesDiffer() && rate() === null" data-testid="ticket-review-currency-note">
            The exchange rate has not loaded, so the cash amount cannot be shown.
          </p>
          <p class="lt-alert lt-alert-warn mt-3 text-xs" data-testid="ticket-review-price-warning" *ngIf="priceWarning() as warning">{{ warning }}</p>
          <p class="lt-alert lt-alert-down mt-3 text-xs" data-testid="ticket-review-blocked" role="alert" *ngIf="blockReason() as reason">{{ reason }} Edit the order to continue.</p>

          <div class="lt-dialog__actions">
            <button type="button" data-testid="ticket-edit" class="lt-btn lt-btn-ghost" [disabled]="submitting()" (click)="closeReview()">Edit</button>
            <button type="button" data-testid="ticket-confirm" class="lt-btn"
              [class.lt-btn-buy]="ticketForm.controls.side.value === 'BUY'"
              [class.lt-btn-sell]="ticketForm.controls.side.value === 'SELL'"
              [disabled]="submitting() || blockReason() !== null" (click)="confirmOrder()">
              <span class="inline-block h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent" *ngIf="submitting()"></span>
              {{ submitting() ? 'Placing...' : 'Place ' + (ticketForm.controls.side.value === 'BUY' ? 'buy' : 'sell') + ' order' }}
            </button>
          </div>
        </div>
      </div>

      <!-- The outcome floats over the page like the review did, and follows the order until it settles. -->
      <div class="lt-toast" data-testid="ticket-success" role="status" *ngIf="outcomeMessage()">
        <span class="lt-toast__icon" [class]="'lt-toast__icon lt-toast__icon--' + outcomeTone()" aria-hidden="true">{{ outcomeTone() === 'up' ? '✓' : outcomeTone() === 'down' ? '!' : '•' }}</span>
        <div class="lt-toast__body">
          <strong class="lt-toast__title">{{ outcomeTitle() }}</strong>
          {{ outcomeMessage() }}
        </div>
        <button type="button" class="lt-toast__close" data-testid="ticket-success-close" aria-label="Dismiss" (click)="outcomeMessage.set('')">✕</button>
      </div>
    </app-shell>
  `,
  styles: [`
    .ticket { max-width: 980px; margin: 0 auto; overflow: visible; }
    .ticket__trend { max-width: 980px; margin: 1rem auto 0; }
    .ticket__grid { display: grid; grid-template-columns: minmax(0, 1fr) 300px; }
    .ticket__form { display: grid; gap: .85rem; align-content: start; padding: 1.15rem 1.35rem 1.25rem; }
    .ticket__title { margin: 0; font-size: 1.1rem; font-weight: 750; letter-spacing: -.02em; color: var(--text); }
    .ticket__side { display: grid; grid-template-columns: 1fr 1fr; padding: 4px; }
    .ticket__side-btn { justify-content: center; padding: .55rem .7rem !important; font-size: .85rem !important; }
    .ticket__side-btn.is-buy { background: var(--up-bg) !important; color: var(--up) !important; box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--up) 45%, transparent) !important; }
    .ticket__side-btn.is-sell { background: var(--down-bg) !important; color: var(--down) !important; box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--down) 45%, transparent) !important; }
    .ticket__field { display: grid; gap: .3rem; min-width: 0; align-content: start; }
    .ticket__input { height: 2.6rem; font-size: .95rem; }
    .ticket__price { position: relative; }
    .ticket__price-symbol { position: absolute; left: .85rem; top: 50%; transform: translateY(-50%); color: var(--muted); font-weight: 700; pointer-events: none; }
    .ticket__input--price { padding-left: 1.9rem; }
    .ticket__pair { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; align-items: start; }
    .ticket__meta { display: flex; flex-wrap: wrap; align-items: center; gap: .25rem .75rem; font-size: .75rem; color: var(--muted); }
    /* No reserved row while there is nothing to say (before an instrument is chosen). */
    .ticket__meta:empty { display: none; }
    .ticket__hint { display: block; font-size: .75rem; font-weight: 600; line-height: 1.4; }
    .ticket__hint--error { color: var(--down); }
    .ticket__link { border: 0; background: none; padding: 0; font: inherit; font-weight: 700; color: var(--accent-strong); text-decoration: underline; cursor: pointer; }
    .ticket__link:disabled { opacity: .5; cursor: default; }

    .ticket__summary {
      display: flex; flex-direction: column; gap: .35rem; padding: 1.15rem 1.35rem 1.3rem;
      border-left: 1px solid var(--border);
      background: linear-gradient(180deg, color-mix(in srgb, var(--accent-soft) 45%, transparent), transparent 60%);
      border-radius: 0 var(--radius) var(--radius) 0;
    }
    .ticket__total { margin: 0; font-family: var(--font-num); font-size: 1.7rem; font-weight: 700; letter-spacing: -.02em; color: var(--text); line-height: 1.15; }
    .ticket__total--empty { color: var(--muted); }
    .ticket__note { margin: 0; font-size: .75rem; color: var(--muted); }
    .ticket__facts { display: grid; gap: .35rem; margin: .6rem 0 .7rem; padding-top: .65rem; border-top: 1px solid var(--border); font-size: .8125rem; }
    .ticket__facts > div { display: flex; justify-content: space-between; gap: .75rem; }
    .ticket__facts dt { color: var(--muted); }
    .ticket__facts dd { margin: 0; color: var(--text); font-weight: 600; }
    .ticket__review { width: 100%; padding-block: .75rem; font-size: .875rem; margin-top: .25rem; }

    .review__total { margin: .6rem 0 0; font-family: var(--font-num); font-size: 1.6rem; font-weight: 700; letter-spacing: -.02em; color: var(--text); }
    .review__total-label { margin-left: .35rem; font-family: var(--font-body); font-size: .75rem; font-weight: 600; color: var(--muted); }
    .review__rows { display: grid; gap: .4rem; margin: .9rem 0 0; padding-top: .8rem; border-top: 1px solid var(--border); font-size: .8125rem; }
    .review__rows > div { display: flex; justify-content: space-between; gap: 1rem; }
    .review__rows dt { color: var(--muted); }
    .review__rows dd { margin: 0; color: var(--text); font-weight: 600; text-align: right; }
    .review__muted { color: var(--muted); font-weight: 500; }
    .review__note { margin: .6rem 0 0; font-size: .72rem; color: var(--muted); }

    @media (max-width: 820px) {
      .ticket__grid { grid-template-columns: minmax(0, 1fr); }
      .ticket__summary { border-left: 0; border-top: 1px solid var(--border); border-radius: 0 0 var(--radius) var(--radius); }
    }
    @media (max-width: 520px) { .ticket__pair { grid-template-columns: minmax(0, 1fr); } }
  `]
})
export class OrderTicket implements OnInit, OnDestroy, HasUnsavedChanges {
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  /** Follows the last placed order until the executor settles it (see followSettlement). */
  private settlementWatch: Subscription | null = null;
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly authService = inject(AuthService);
  private readonly orderService = inject(OrderService);
  private readonly watchlistsService = inject(WatchlistsService);
  protected readonly cur = inject(DisplayCurrencyService);
  private readonly leavePageConfirm = inject(LeavePageConfirmService);
  private clearUnsavedChecker: (() => void) | null = null;

  readonly accountId = signal<number | null>(null);
  readonly submitting = signal(false);
  readonly errorMessage = signal('');
  readonly outcomeMessage = signal('');
  /** How the floating receipt is coloured: green once filled, red when refused, neutral while working. */
  readonly outcomeTone = computed<'up' | 'down' | 'info'>(() => {
    const text = this.outcomeMessage();
    return /FILLED/.test(text) ? 'up' : /REJECTED|CANCELLED/.test(text) ? 'down' : 'info';
  });
  /** The receipt's one-line headline; the message underneath carries the detail. */
  readonly outcomeTitle = computed(() => {
    const text = this.outcomeMessage();
    return /FILLED/.test(text) ? 'Order filled' : /REJECTED/.test(text) ? 'Order rejected'
      : /CANCELLED/.test(text) ? 'Order cancelled' : 'Order placed';
  });
  // Cash on the account (null until read, or if it could not be read: the order can still be placed).
  readonly balance = signal<BalanceResponse | null>(null);
  // Quantity x price while both are valid, otherwise null. In the instrument's own currency.
  readonly estimate = signal<number | null>(null);
  // Rupees per dollar for a buy and a sell (stored daily rate plus the margin), read once when the page opens.
  readonly fxRates = signal<FxRatesResponse | null>(null);
  readonly fxRatesFailed = signal(false);
  // True while the user is looking at the summary that must be confirmed before anything is sent.
  readonly reviewing = signal(false);
  // Latest cached quote per symbol, from the platform's market-data read model (never a Fauxnance call).
  readonly quotes = signal<Map<string, MarketQuoteResponse>>(new Map());
  // Quantity held per symbol; null until read, or if it could not be read (then SELLs are not blocked).
  readonly holdings = signal<Map<string, number> | null>(null);

  // One idempotency key per ticket attempt. If the answer never arrived (no response, or a
  // server error) the retry reuses the same key, so the API answers ORD-409 instead of placing
  // a second order. A definite answer (accepted, or refused with an error code) ends the
  // attempt, and the next submit gets a fresh key.
  private pendingAttempt: { fingerprint: string; key: string } | null = null;

  // The tradable instruments, for the picker. null until read, or if it could not be read: the field then
  // falls back to free text with the symbol-format check.
  readonly instrumentList = signal<InstrumentResponse[] | null>(null);

  /**
   * With the catalogue loaded, the symbol must be one of its instruments (typed in any case); without
   * it, the contract's symbol format is checked instead. The server validates the symbol either way.
   */
  private readonly symbolRule = (control: AbstractControl): ValidationErrors | null => {
    const value = String(control.value ?? '').trim().toUpperCase();
    if (!value) {
      return null;
    }
    const list = this.instrumentList();
    if (list) {
      return list.some((ins) => ins.symbol === value) ? null : { unknownInstrument: true };
    }
    return SYMBOL_PATTERN.test(value) ? null : { pattern: true };
  };

  readonly listOpen = signal(false);
  readonly activeIndex = signal(-1);

  readonly ticketForm = this.fb.group({
    symbol: ['', [Validators.required, this.symbolRule]],
    side: this.fb.nonNullable.control<'BUY' | 'SELL'>('BUY'),
    quantity: ['', [Validators.required, Validators.pattern(/^[1-9]\d*$/)]],
    price: ['', [Validators.required, priceValidator]]
  });

  // The form's value as a signal, so the checks below recompute as the user types.
  private readonly formValue = signal(this.ticketForm.getRawValue());

  readonly symbolText = computed(() => String(this.formValue().symbol ?? '').trim().toUpperCase());
  readonly symbolValid = computed(() => {
    const list = this.instrumentList();
    return list ? list.some((ins) => ins.symbol === this.symbolText()) : SYMBOL_PATTERN.test(this.symbolText());
  });

  readonly selectedInstrument = computed(() => this.instrumentList()?.find((ins) => ins.symbol === this.symbolText()) ?? null);

  /**
   * The picker's options for what has been typed: matched on symbol or company name, case-insensitive,
   * ranked exact symbol, then symbol prefix, then name prefix, then anywhere; at most ten.
   */
  readonly filteredInstruments = computed(() => {
    const list = this.instrumentList() ?? [];
    const query = this.symbolText();
    if (!query) {
      return list.slice(0, 10);
    }
    const rank = (ins: InstrumentResponse): number => {
      const name = ins.name.toUpperCase();
      if (ins.symbol === query) return 0;
      if (ins.symbol.startsWith(query)) return 1;
      if (name.startsWith(query) || name.includes(' ' + query)) return 2;
      if (ins.symbol.includes(query) || name.includes(query)) return 3;
      return 9;
    };
    return list
      .map((ins) => ({ ins, r: rank(ins) }))
      .filter((x) => x.r < 9)
      .sort((a, b) => a.r - b.r || a.ins.symbol.localeCompare(b.ins.symbol))
      .slice(0, 10)
      .map((x) => x.ins);
  });
  readonly quantityValue = computed(() => {
    const q = String(this.formValue().quantity ?? '').trim();
    return /^[1-9]\d*$/.test(q) ? Number(q) : null;
  });
  readonly limitPrice = computed(() => {
    const p = String(this.formValue().price ?? '').trim();
    return /^\d+(?:\.\d{1,2})?$/.test(p) && Number(p) > 0 ? Number(p) : null;
  });

  readonly marketQuote = computed(() => {
    const quote = this.quotes().get(this.symbolText());
    return quote && quote.lastPrice != null ? quote : null;
  });

  /** A warning, not a block: a limit far from the market is usually a typo, but the customer decides. */
  readonly priceWarning = computed<string | null>(() => {
    const quote = this.marketQuote();
    const limit = this.limitPrice();
    const market = quote ? Number(quote.lastPrice) : NaN;
    if (!quote || limit === null || !(market > 0)) {
      return null;
    }
    const deviation = (limit - market) / market;
    if (Math.abs(deviation) <= PRICE_WARNING_THRESHOLD) {
      return null;
    }
    const pct = Math.round(Math.abs(deviation) * 100);
    const at = formatMoney(market, quote.currency, 2, false, 4);
    if (this.formValue().side === 'BUY') {
      return deviation < 0
        ? `Your limit is ${pct}% below the market price (${at}). A buy only fills at or below your limit, so this order will most likely be rejected.`
        : `Your limit is ${pct}% above the market price (${at}). It would fill near the market price, but cash is checked at your limit, so far more is reserved than the order needs.`;
    }
    return deviation > 0
      ? `Your limit is ${pct}% above the market price (${at}). A sell only fills at or above your limit, so this order will most likely be rejected.`
      : `Your limit is ${pct}% below the market price (${at}). It would sell near the market price; check this is the limit you meant.`;
  });

  /**
   * A block: the order cannot succeed, so it is not sent. A BUY costing more than the cash, or a SELL of
   * more than is held. Nothing is blocked when the cash or holdings could not be read: the server, which
   * always checks again, decides then.
   */
  /** The note shown while the selected market is closed: the order is accepted and queued. */
  readonly queueNotice = computed<string | null>(() => {
    const instrument = this.selectedInstrument();
    return instrument && isClosed(instrument) ? queuedMessage(instrument) : null;
  });

  readonly blockReason = computed<string | null>(() => {
    // A closed market does not block: the order is queued (decision log 0019). The cash and holdings checks
    // still apply, because a queued order reserves them now.
    const quantity = this.quantityValue();
    if (this.rateMissing()) {
      return 'No exchange rate is available, so orders in a foreign currency cannot be placed right now.';
    }
    if (this.formValue().side === 'BUY') {
      const balance = this.balance();
      const limit = this.limitPrice();
      if (!balance || quantity === null || limit === null) {
        return null;
      }
      // Cash is in rupees, so a dollar order is checked at the rate it will be locked at.
      const cost = Math.round(quantity * limit * (this.rate() ?? 1) * 100) / 100;
      const cash = Number(balance.cashBalance);
      return cost > cash
        ? `Not enough cash: this order needs ${this.cur.show(cost, balance.currency)} and you have ${this.cur.show(cash, balance.currency)}.`
        : null;
    }
    const holdings = this.holdings();
    if (!holdings || quantity === null || !this.symbolValid()) {
      return null;
    }
    const held = holdings.get(this.symbolText()) ?? 0;
    return quantity > held
      ? `Not enough shares: you hold ${held} ${this.symbolText()} and this order sells ${quantity}.`
      : null;
  });

  /** The currency the order is priced in: the instrument's (from its quote), else the cash balance's. */
  readonly amountCurrency = computed(() => this.marketQuote()?.currency ?? this.balance()?.currency ?? '');

  /** True when the instrument is quoted in a different currency from the cash label: nothing is converted. */
  readonly currenciesDiffer = computed(() => {
    const quoted = this.marketQuote()?.currency;
    const cash = this.balance()?.currency;
    return !!quoted && !!cash && quoted !== cash;
  });

  /** True when the order is priced in a currency other than the one cash is held in. */
  readonly needsConversion = computed(() => this.currenciesDiffer());

  /** Rupees per unit of the instrument's currency for this side (margin included); null for a rupee order or before the rates load. */
  readonly rate = computed<number | null>(() => {
    const rates = this.fxRates();
    if (!this.needsConversion() || !rates) {
      return null;
    }
    return Number(this.formValue().side === 'BUY' ? rates.buyRate : rates.sellRate);
  });

  /** A foreign-currency order cannot be priced because the rates could not be read. */
  readonly rateMissing = computed(() => this.needsConversion() && this.fxRates() === null && this.fxRatesFailed());

  /** The order's amount in the cash currency: converted at the locked rate for a foreign order. */
  readonly baseEstimate = computed<number | null>(() => {
    const estimate = this.estimate();
    if (estimate === null) {
      return null;
    }
    const rate = this.rate();
    return rate === null ? estimate : Math.round(estimate * rate * 100) / 100;
  });

  /** The symbol shown inside the price box: the currency the limit price is entered in. */
  readonly priceSymbol = computed(() =>
    currencySymbol(this.selectedInstrument()?.currency ?? this.marketQuote()?.currency ?? this.balance()?.currency));

  /** A price or amount in its own currency with the symbol: $529.76, ₹3,500.00. */
  fmt(amount: number | string | null | undefined, currency: string | null | undefined, digits = 2, maxDigits = digits): string {
    return amount === null || amount === undefined ? '—' : formatMoney(Number(amount), currency, digits, false, maxDigits);
  }

  currencyName(currency: string): string {
    return currency === 'USD' ? 'dollar' : currency === 'INR' ? 'rupee' : currency;
  }

  readonly heldQuantity = computed(() => this.holdings()?.get(this.symbolText()) ?? 0);

  readonly cashAfter = computed(() => {
    const balance = this.balance();
    const cost = this.baseEstimate();
    return balance && cost !== null ? Number(balance.cashBalance) - cost : null;
  });

  readonly holdingAfter = computed(() => this.heldQuantity() - (this.quantityValue() ?? 0));

  private readonly reviewDialog = viewChild<ElementRef<HTMLElement>>('reviewDialog');
  private readonly reviewButton = viewChild<ElementRef<HTMLButtonElement>>('reviewButton');

  /** The largest quantity that would pass the block: cash / limit for a BUY, the holding for a SELL. */
  readonly maxQuantity = computed<number | null>(() => {
    if (this.formValue().side === 'BUY') {
      const balance = this.balance();
      const limit = this.limitPrice();
      return balance && limit !== null ? Math.floor(Number(balance.cashBalance) / (limit * (this.rate() ?? 1))) : null;
    }
    const holdings = this.holdings();
    return holdings && this.symbolValid() ? holdings.get(this.symbolText()) ?? 0 : null;
  });

  constructor() {
    // Any edit ends a review: what was confirmed must be what is sent.
    this.ticketForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      this.formValue.set(this.ticketForm.getRawValue());
      this.reviewing.set(false);
      this.estimate.set(this.computeEstimate());
    });
  }

  private computeEstimate(): number | null {
    const { quantity, price } = this.ticketForm.getRawValue();
    const q = String(quantity ?? '').trim();
    const p = String(price ?? '').trim();
    if (!/^[1-9]\d*$/.test(q) || !/^\d+(?:\.\d{1,2})?$/.test(p) || !(Number(p) > 0)) {
      return null;
    }
    return Math.round(Number(q) * Number(p) * 100) / 100;
  }

  accountDisplay(): string {
    const id = this.accountId();
    return id === null ? '' : `${id}`;
  }

  ngOnInit(): void {
    this.clearUnsavedChecker = this.leavePageConfirm.registerUnsavedChangesChecker(() => this.hasUnsavedChanges());

    // "Trade" on the market board opens the ticket with that instrument chosen (?symbol=TCS.NS).
    const symbol = this.route?.snapshot.queryParamMap.get('symbol')?.trim().toUpperCase();
    if (symbol && SYMBOL_PATTERN.test(symbol)) {
      this.ticketForm.controls.symbol.setValue(symbol);
    }
    // Buy / Sell on a watchlist row also chooses the side (?side=SELL).
    const side = this.route?.snapshot.queryParamMap.get('side')?.trim().toUpperCase();
    if (side === 'BUY' || side === 'SELL') {
      this.ticketForm.controls.side.setValue(side);
    }
    this.cur.ensureLoaded();
    this.loadQuotes();
    this.loadInstruments();
    this.loadFxRates();
    this.authService.getProfile().subscribe({
      next: (profile) => {
        this.accountId.set(profile.accountId);
        this.loadBalance(profile.accountId);
        this.loadHoldings(profile.accountId);
      },
      error: (err) => {
        this.errorMessage.set(messageFor(err));
      }
    });
  }

  private loadFxRates(): void {
    this.watchlistsService.getFxRates().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (rates) => this.fxRates.set(rates),
      error: () => this.fxRatesFailed.set(true)
    });
  }

  ngOnDestroy(): void {
    this.clearUnsavedChecker?.();
    this.clearUnsavedChecker = null;
  }

  private loadBalance(accountId: number): void {
    // Informational only: if it cannot be read the ticket still works and the server checks the cash.
    this.orderService.getBalance(accountId).subscribe({
      next: (balance) => this.balance.set(balance),
      error: () => this.balance.set(null)
    });
  }

  private loadHoldings(accountId: number): void {
    this.orderService.getPositions(accountId).subscribe({
      next: (positions) => this.holdings.set(new Map(positions.map((p) => [p.symbol, p.quantity] as [string, number]))),
      error: () => this.holdings.set(null)
    });
  }

  /** Reads the cached quotes from our own API: the market-data read model, so no Fauxnance quota is used. */
  private loadQuotes(): void {
    this.watchlistsService.getQuotes().subscribe({
      next: (quotes) => this.quotes.set(new Map(quotes.map((q) => [q.symbol, q] as [string, MarketQuoteResponse]))),
      error: () => this.quotes.set(new Map())
    });
  }

  /** Closes the review dialog, keeping what was typed, and returns focus to the Review button. */
  closeReview(): void {
    if (this.submitting()) {
      return;
    }
    this.reviewing.set(false);
    setTimeout(() => this.reviewButton()?.nativeElement.focus());
  }

  /** A click on the dimmed backdrop (not inside the dialog) closes it, like Edit. */
  onBackdropClick(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.closeReview();
    }
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.reviewing()) {
      this.closeReview();
    }
  }

  /** Keeps Tab and Shift+Tab inside the open dialog, so keyboard focus cannot wander behind it. */
  trapFocus(event: KeyboardEvent): void {
    if (event.key !== 'Tab') {
      return;
    }
    const dialog = this.reviewDialog()?.nativeElement;
    const focusable = dialog
      ? Array.from(dialog.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled])'))
      : [];
    if (focusable.length === 0) {
      event.preventDefault();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === dialog)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private loadInstruments(): void {
    this.watchlistsService.getInstruments().subscribe({
      next: (instruments) => {
        this.instrumentList.set(instruments);
        // The symbol rule depends on the list, so re-check whatever was typed before it arrived.
        // No change event: a re-check is not an edit, so it must not close an open review.
        this.ticketForm.controls.symbol.updateValueAndValidity({ emitEvent: false });
      },
      error: () => this.instrumentList.set(null)
    });
  }

  openList(): void {
    if (this.instrumentList()) {
      this.listOpen.set(true);
    }
  }

  closeList(): void {
    this.listOpen.set(false);
    this.activeIndex.set(-1);
  }

  onSymbolInput(): void {
    this.activeIndex.set(-1);
    this.openList();
  }

  selectInstrument(instrument: InstrumentResponse): void {
    const control = this.ticketForm.controls.symbol;
    control.setValue(instrument.symbol);
    control.markAsDirty();
    control.markAsTouched();
    this.closeList();
  }

  /** Arrow keys move through the options, Enter picks the highlighted one, Escape closes the list. */
  onSymbolKeydown(event: KeyboardEvent): void {
    const options = this.filteredInstruments();
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.openList();
        if (options.length) {
          this.activeIndex.set((this.activeIndex() + 1) % options.length);
        }
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.openList();
        if (options.length) {
          this.activeIndex.set(this.activeIndex() <= 0 ? options.length - 1 : this.activeIndex() - 1);
        }
        break;
      case 'Enter':
        if (this.listOpen() && this.activeIndex() >= 0 && options[this.activeIndex()]) {
          event.preventDefault(); // pick the option rather than submit the form
          this.selectInstrument(options[this.activeIndex()]);
        }
        break;
      case 'Escape':
        if (this.listOpen()) {
          event.stopPropagation();
          this.closeList();
        }
        break;
      case 'Tab':
        this.closeList();
        break;
    }
  }

  /** Fills in the largest quantity the cash (BUY) or the holding (SELL) allows. */
  useMax(): void {
    const max = this.maxQuantity();
    if (max && max > 0) {
      this.ticketForm.controls.quantity.setValue(String(max));
      this.ticketForm.controls.quantity.markAsDirty();
    }
  }

  /**
   * True when the user has typed an order that has not been sent: the form was edited, something is
   * entered, and no request is in flight. A submitted order marks the form pristine, so nothing is
   * asked after a successful submit, and the next edit makes it count again.
   */
  hasUnsavedChanges(): boolean {
    if (this.submitting()) {
      return false;
    }

    const value = this.ticketForm.getRawValue();
    return this.ticketForm.dirty && Boolean(value.symbol || value.quantity || value.price);
  }

  /** Closing the tab, reloading or leaving the site: the router never sees these, the browser asks. */
  @HostListener('window:beforeunload', ['$event'])
  protected warnBeforeTheTabIsLeft(event: BeforeUnloadEvent): void {
    if (this.leavePageConfirm.consumeBeforeUnloadBypass()) {
      return;
    }

    if (this.hasUnsavedChanges()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  /** The form's submit: validates, then shows the summary. Nothing is sent until it is confirmed. */
  /**
   * The order is accepted as NEW; the executor fills or rejects it a moment later, and only then do cash
   * and holdings change. So follow the order (our own API, every 2 seconds, for up to 30 seconds) and,
   * once it has settled, re-read cash and holdings and say how it ended. If it has not settled by then,
   * re-read once anyway and leave the rest to the blotter.
   */
  private followSettlement(accountId: number, orderId: string): void {
    this.settlementWatch?.unsubscribe();
    let checks = 0;
    this.settlementWatch = timer(SETTLE_POLL_MS, SETTLE_POLL_MS).pipe(
      take(SETTLE_MAX_CHECKS),
      switchMap(() => this.orderService.getOrderHistory(accountId)),
      map((orders) => {
        checks++;
        return orders.find((o) => o.orderId === orderId);
      }),
      filter((order) => (order !== undefined && order.status !== 'NEW') || checks >= SETTLE_MAX_CHECKS),
      take(1),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: (order) => {
        this.loadBalance(accountId);
        this.loadHoldings(accountId);
        if (order?.status === 'FILLED') {
          const at = order.executedPrice != null ? ` at ${money(Number(order.executedPrice))}` : '';
          this.outcomeMessage.set(`Order ${orderId} FILLED${at}. Cash and holdings are updated.`);
        } else if (order?.status === 'REJECTED') {
          this.outcomeMessage.set(`Order ${orderId} REJECTED: ${rejectionReasonText(order.rejectionReason)}. No cash moved.`);
        } else if (order?.status === 'CANCELLED') {
          this.outcomeMessage.set(`Order ${orderId} was CANCELLED. No cash moved.`);
        }
      },
      // A failed check changes nothing on screen; the blotter still shows the outcome.
      error: () => undefined
    });
  }

  submitOrder(): void {
    this.outcomeMessage.set('');
    this.errorMessage.set('');

    if (this.ticketForm.invalid || this.accountId() === null) {
      this.ticketForm.markAllAsTouched();
      if (this.accountId() === null) {
        this.errorMessage.set('Could not determine the trading account for this session. Sign in again.');
      }
      return;
    }

    this.estimate.set(this.computeEstimate());
    if (this.blockReason() !== null) {
      // The reason is already shown beside the form; a dialog for an order that cannot be placed would
      // only have to be closed again.
      return;
    }
    this.reviewing.set(true);
    // Focus the dialog itself rather than Place order, so a stray Enter cannot send the order.
    setTimeout(() => this.reviewDialog()?.nativeElement.focus());

    // Re-read what the checks depend on, so they are not run against the page as it loaded.
    const accountId = this.accountId();
    if (accountId !== null) {
      this.loadBalance(accountId);
      this.loadHoldings(accountId);
    }
    this.loadQuotes();
    // And whether the market is still open: a market that closed since the page loaded blocks the order.
    this.loadInstruments();
  }

  /** Sends the order that is on the review summary. */
  confirmOrder(): void {
    if (!this.reviewing() || this.submitting() || this.ticketForm.invalid || this.blockReason() !== null) {
      return;
    }
    this.outcomeMessage.set('');
    this.errorMessage.set('');

    const value = this.ticketForm.getRawValue();
    const accountId = this.accountId();
    if (accountId === null) {
      return;
    }

    this.submitting.set(true);
    this.orderService.placeOrder(
      accountId,
      String(value.symbol).trim().toUpperCase(),
      value.side as 'BUY' | 'SELL',
      Number(value.quantity),
      Number(value.price),
      this.idempotencyKeyFor(value)
    ).subscribe({
      next: (response) => {
        this.submitting.set(false);
        this.pendingAttempt = null;
        this.reviewing.set(false);
        // A placed order starts a fresh ticket: clear all typed fields.
        this.ticketForm.reset({ symbol: '', side: 'BUY', quantity: '', price: '' });
        this.closeList();
        if (response.status === 'NEW') {
          this.outcomeMessage.set(`Order ${response.orderId} is NEW — it is working. This will update automatically once it settles.`);
        } else if (response.status === 'REJECTED') {
          this.outcomeMessage.set(`Order ${response.orderId} REJECTED. ${response.message}`);
        } else if (response.status === 'CANCELLED') {
          this.outcomeMessage.set(`Order ${response.orderId} CANCELLED. ${response.message}`);
        } else {
          this.outcomeMessage.set(`Order ${response.orderId} is ${response.status}. ${response.message}`);
        }
        this.loadBalance(accountId);
        this.followSettlement(accountId, response.orderId);
      },
      error: (err) => {
        this.submitting.set(false);
        this.reviewing.set(false);
        this.errorMessage.set(messageFor(err));
        if (!isAmbiguousFailure(err)) {
          this.pendingAttempt = null;
        }
      }
    });
  }

  private idempotencyKeyFor(value: { symbol: string | null; side: string | null; quantity: string | null; price: string | null }): string {
    const fingerprint = [value.symbol, value.side, value.quantity, value.price].join('|');
    if (this.pendingAttempt?.fingerprint === fingerprint) {
      return this.pendingAttempt.key;
    }

    const key = this.nextIdempotencyKey();
    this.pendingAttempt = { fingerprint, key };
    return key;
  }

  private nextIdempotencyKey(): string {
    if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
      return globalThis.crypto.randomUUID();
    }

    return `idem-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }
}
