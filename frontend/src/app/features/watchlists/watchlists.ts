import { formatMoney } from '../../core/currency/currency';
import { Component, ElementRef, HostListener, OnDestroy, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { OrderService } from '../../core/orders/order.service';
import { FlagColour, WatchlistsService } from '../../core/watchlists/watchlists.service';
import { messageFor } from '../../core/errors/error-messages';
import { FeedRefresher } from '../../core/market/feed-refresher';
import { closedMessage, isClosed } from '../../core/market/trading-status';
import {
  InstrumentResponse,
  WatchlistItemResponse,
  WatchlistResponse,
  AlertResponse,
  WatchlistPresetResponse
} from '../../clients/watchlists-client/model/models';
import { AppShell } from '../../shared/layout/app-shell';
import { InstrumentSearch } from '../../shared/market/instrument-search';
import { SearchField } from '../../shared/search/search-field';
import { createMatcher } from '../../core/search/text-matcher';

type SortKey = 'symbol' | 'price' | 'change';

/** The optional columns; Symbol, Last price and the actions are always shown. */
export type ColumnKey = 'change' | 'changePercent' | 'bid' | 'ask' | 'previousClose' | 'asOf' | 'alert';
export const COLUMNS: { key: ColumnKey; label: string }[] = [
  { key: 'changePercent', label: 'Change %' },
  { key: 'change', label: 'Change' },
  { key: 'bid', label: 'Bid' },
  { key: 'ask', label: 'Ask' },
  { key: 'previousClose', label: 'Previous close' },
  { key: 'asOf', label: 'As of' },
  { key: 'alert', label: 'Alert' }
];
const DEFAULT_COLUMNS: ColumnKey[] = ['changePercent', 'asOf', 'alert'];
/** The viewer's own column choice, kept in this browser only. */
const COLUMNS_STORAGE_KEY = 'lt.watchlist.columns';

/** The flag colours, in menu order, with how each is shown. */
export const FLAGS: { colour: FlagColour; label: string; css: string }[] = [
  { colour: 'RED', label: 'Red', css: 'var(--down)' },
  { colour: 'AMBER', label: 'Amber', css: 'var(--warn)' },
  { colour: 'GREEN', label: 'Green', css: 'var(--up)' }
];

/** The last price the viewer saw per instrument, kept in this browser, for "since your last visit". */
const SEEN_STORAGE_KEY = 'lt.watchlist.seen';

/** How long a changed price stays highlighted. */
const FLASH_MS = 1500;

/**
 * Watchlists and price alerts (Sprint 10). One list at a time, as a table: price, change and time per
 * instrument, its market state, whether you hold it and any alert on it, with Buy, Sell, Alert and
 * Remove on each row. Lists can be created, renamed, deleted and reordered by dragging; rows can carry a
 * colour flag (red, amber, green) and be filtered to the flagged ones; a price that moved since the
 * viewer's last visit says so. Prices come from the platform's cache and refresh in step with the feed
 * (just after each new batch), never from the market-data provider in the browser.
 */
@Component({
  selector: 'app-watchlists',
  imports: [CommonModule, ReactiveFormsModule, RouterLink, AppShell, InstrumentSearch, SearchField],
  template: `
    <app-shell title="Watchlists & alerts">
      <div class="wl">
        <div class="lt-alert lt-alert-down wl__full" data-testid="watchlists-error" role="alert" *ngIf="errorMessage()">{{ errorMessage() }}</div>

        <!-- Left rail: your lists, one per line, and how to start a new one. -->
        <aside class="lt-card wl__rail lt-appear" aria-labelledby="watchlists-title">
          <h2 id="watchlists-title" class="lt-eyebrow wl__rail-title">Your watchlists</h2>

          <div class="lt-empty" data-testid="watchlists-empty" *ngIf="loaded() && watchlists().length === 0">
            <p>No watchlists yet. Create one to start following instruments.</p>
          </div>

          <div class="wl__lists" role="tablist" aria-label="Watchlists" aria-orientation="vertical" *ngIf="watchlists().length > 0">
            <button *ngFor="let wl of watchlists()" type="button" role="tab" data-testid="watchlist-tab"
              class="wl__list" [class.is-on]="wl.id === selected()?.id"
              [attr.aria-selected]="wl.id === selected()?.id" (click)="select(wl.id!)">{{ wl.name }} <span class="wl__count tabular-nums">{{ wl.items.length }}</span></button>
          </div>

          <form [formGroup]="watchlistForm" (ngSubmit)="createWatchlist()" class="wl__create">
            <input class="lt-input" data-testid="watchlist-name" formControlName="name"
              placeholder="New list, e.g. Banks" aria-label="New watchlist name" />
            <button type="submit" data-testid="watchlist-create" class="lt-btn lt-btn-primary" [disabled]="watchlistForm.invalid">Create</button>
          </form>
          <select *ngIf="presets().length > 0" class="lt-select wl__preset" data-testid="watchlist-preset" aria-label="Start from a ready-made list"
            (change)="createFromPreset($any($event.target).value); $any($event.target).value = ''">
            <option value="">Or start from a ready-made list…</option>
            <option *ngFor="let p of presets()" [value]="p.key">{{ p.name }} ({{ p.symbols.length }})</option>
          </select>
        </aside>

        <!-- Main panel: the chosen list. -->
        <section class="lt-card lt-card-pad wl__main lt-appear lt-appear-2" aria-label="Selected watchlist">
          <ng-container *ngIf="selected() as wl; else noList">
            <div class="lt-card-head" data-testid="watchlist-card">
              <div class="flex flex-wrap items-center gap-2">
                <ng-container *ngIf="!renaming(); else renameForm">
                  <h3 class="wl__title" data-testid="watchlist-title">{{ wl.name }}</h3>
                  <button type="button" class="lt-btn lt-btn-ghost lt-btn-sm" data-testid="watchlist-rename" (click)="startRename(wl)">Rename</button>
                  <ng-container *ngIf="!confirmingDelete(); else deleteConfirm">
                    <button type="button" class="lt-btn lt-btn-ghost lt-btn-sm" data-testid="watchlist-delete" (click)="confirmingDelete.set(true)">Delete</button>
                  </ng-container>
                </ng-container>
                <ng-template #renameForm>
                  <form class="flex items-center gap-2" (submit)="$event.preventDefault(); saveRename(wl)">
                    <input #renameInput class="lt-input" data-testid="watchlist-rename-input" [value]="wl.name" maxlength="100"
                      aria-label="New name" (keydown.escape)="renaming.set(false)" />
                    <button type="submit" class="lt-btn lt-btn-primary lt-btn-sm" data-testid="watchlist-rename-save">Save</button>
                    <button type="button" class="lt-btn lt-btn-ghost lt-btn-sm" (click)="renaming.set(false)">Cancel</button>
                  </form>
                </ng-template>
                <ng-template #deleteConfirm>
                  <span class="text-[13px]" style="color: var(--down)" role="alert">Delete "{{ wl.name }}"? Its alerts are kept.</span>
                  <button type="button" class="lt-btn lt-btn-danger lt-btn-sm" data-testid="watchlist-delete-confirm" (click)="deleteWatchlist(wl)">Delete</button>
                  <button type="button" class="lt-btn lt-btn-ghost lt-btn-sm" (click)="confirmingDelete.set(false)">Cancel</button>
                </ng-template>
              </div>
              <div class="wl__add">
                <app-instrument-search inputId="watchlist-add" label="Add an instrument to this list"
                  placeholder="Add: search symbol or name" [instruments]="instruments()" [exclude]="symbolsOf(wl)"
                  (picked)="addInstrument(wl.id!, $event)"></app-instrument-search>
              </div>
            </div>

            <div class="lt-empty" data-testid="watchlist-items-empty" *ngIf="wl.items.length === 0">
              <p>This list is empty. Search above to add instruments.</p>
            </div>

            <div *ngIf="wl.items.length > 0">
              <div class="wl__toolbar">
                <p class="wl__moved" data-testid="watchlist-moved" role="status" *ngIf="movedCount() > 0; else movedSpacer">
                  <span class="lt-dot" style="color: var(--accent-strong)"></span>
                  {{ movedCount() }} {{ movedCount() === 1 ? 'price has' : 'prices have' }} moved since your last visit.
                  <button type="button" class="wl__link" data-testid="watchlist-mark-seen" (click)="markAllSeen()">Mark as seen</button>
                </p>
                <ng-template #movedSpacer><span></span></ng-template>
                <div class="flex flex-wrap items-center gap-2">
                  <app-search-field testId="watchlist-search" label="Filter this list" placeholder="Filter this list" width="200px"
                    [(query)]="rowQuery" [(regex)]="rowRegex"></app-search-field>
                  <button type="button" class="lt-btn lt-btn-sm" data-testid="watchlist-flagged-only"
                    [class.lt-btn-primary]="flaggedOnly()" [class.lt-btn-ghost]="!flaggedOnly()" [attr.aria-pressed]="flaggedOnly()"
                    (click)="flaggedOnly.set(!flaggedOnly())">Flagged only</button>
                  <div class="relative">
                    <button type="button" class="lt-btn lt-btn-ghost lt-btn-sm" data-testid="watchlist-columns"
                      [attr.aria-expanded]="columnsOpen()" aria-controls="watchlist-columns-menu" (click)="columnsOpen.set(!columnsOpen())">Columns</button>
                    <fieldset *ngIf="columnsOpen()" id="watchlist-columns-menu" class="lt-card absolute right-0 z-20 mt-1 grid min-w-[190px] gap-1.5 p-3 text-sm"
                      style="box-shadow: var(--shadow-lg)" data-testid="watchlist-columns-menu">
                      <legend class="sr-only">Columns to show</legend>
                      <label *ngFor="let c of columnChoices" class="flex cursor-pointer items-center gap-2" style="color: var(--text)">
                        <input type="checkbox" [checked]="shown(c.key)" (change)="toggleColumn(c.key)" [attr.data-testid]="'watchlist-column-' + c.key" />
                        {{ c.label }}
                      </label>
                      <button type="button" class="mt-1 text-left text-xs underline" style="color: var(--muted)" (click)="resetColumns()">Reset to default</button>
                    </fieldset>
                  </div>
                </div>
              </div>

              <div class="lt-empty" data-testid="watchlist-flagged-empty" *ngIf="flaggedOnly() && rows().length === 0">
                <p>No flagged instruments in this list. Click the dot beside a symbol to flag it.</p>
              </div>
              <div class="lt-empty" data-testid="watchlist-no-match" *ngIf="rows().length > 0 && visibleRows().length === 0">
                <p>Nothing in this list matches the filter.</p>
              </div>

              <div class="overflow-x-auto" *ngIf="visibleRows().length > 0">
              <table class="lt-table" data-testid="watchlist-table">
                <thead>
                  <tr>
                    <th scope="col" class="w-8"><span class="sr-only">Order</span></th>
                    <th scope="col" [attr.aria-sort]="ariaSort('symbol')">
                      <button type="button" data-testid="watchlist-sort-symbol" (click)="sortBy('symbol')">Symbol {{ sortMark('symbol') }}</button>
                    </th>
                    <th scope="col" class="text-right" [attr.aria-sort]="ariaSort('price')">
                      <button type="button" data-testid="watchlist-sort-price" (click)="sortBy('price')">Last price {{ sortMark('price') }}</button>
                    </th>
                    <th scope="col" class="text-right" *ngIf="shown('change')">Change</th>
                    <th scope="col" class="text-right" *ngIf="shown('changePercent')" [attr.aria-sort]="ariaSort('change')">
                      <button type="button" data-testid="watchlist-sort-change" (click)="sortBy('change')">Change % {{ sortMark('change') }}</button>
                    </th>
                    <th scope="col" class="text-right" *ngIf="shown('bid')">Bid</th>
                    <th scope="col" class="text-right" *ngIf="shown('ask')">Ask</th>
                    <th scope="col" class="text-right" *ngIf="shown('previousClose')">Prev. close</th>
                    <th scope="col" class="text-right" *ngIf="shown('asOf')">As of</th>
                    <th scope="col" *ngIf="shown('alert')">Alert</th>
                    <th scope="col" class="text-right"><span class="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  <tr *ngFor="let item of visibleRows(); trackBy: trackBySymbol" data-testid="watchlist-item" class="wl__row"
                    [class.lt-flash]="flashing().has(item.symbol)"
                    [style.opacity]="dragging() === item.symbol ? 0.5 : null"
                    (dragover)="onDragOver($event)" (drop)="onDrop($event, item.symbol)">
                    <td>
                      <button type="button" class="wl__grip" data-testid="watchlist-drag" [draggable]="canReorder()" [disabled]="!canReorder()"
                        [attr.title]="canReorder() ? 'Drag to reorder, or Alt+Up / Alt+Down' : 'Show the whole list in your order to reorder'"
                        [attr.aria-label]="'Move ' + item.symbol + ' (Alt+Up or Alt+Down)'"
                        (dragstart)="onDragStart($event, item.symbol)" (dragend)="dragging.set(null)"
                        (keydown)="onMoveKey($event, item.symbol)">⋮⋮</button>
                    </td>
                    <td class="whitespace-nowrap">
                      <span class="relative inline-block align-middle" data-flag-root>
                        <button type="button" class="mr-2 inline-flex h-5 w-5 items-center justify-center rounded-full"
                          data-testid="watchlist-flag" aria-haspopup="menu" [attr.aria-expanded]="flagMenuFor() === item.symbol"
                          [attr.aria-label]="item.flag ? item.symbol + ' is flagged ' + flagLabel(item.flag) + '. Change flag' : 'Flag ' + item.symbol"
                          (click)="toggleFlagMenu(item.symbol)">
                          <span class="h-2.5 w-2.5 rounded-full"
                            [style.background]="item.flag ? flagCss(item.flag) : 'transparent'"
                            [style.border]="item.flag ? 'none' : '1.5px solid var(--border-strong, var(--muted))'"></span>
                        </button>
                        <span *ngIf="flagMenuFor() === item.symbol" role="menu" [attr.aria-label]="'Flag for ' + item.symbol"
                          class="lt-card absolute left-0 top-6 z-20 grid min-w-[130px] gap-0.5 p-1 text-sm" style="box-shadow: var(--shadow-lg)"
                          data-testid="watchlist-flag-menu">
                          <button *ngFor="let f of flags" type="button" role="menuitemradio" [attr.aria-checked]="item.flag === f.colour"
                            class="flex items-center gap-2 rounded-md px-2 py-1.5 text-left" style="color: var(--text)"
                            [attr.data-testid]="'watchlist-flag-' + f.colour" (click)="setFlag(wl.id!, item.symbol, f.colour)">
                            <span class="h-2.5 w-2.5 rounded-full" [style.background]="f.css"></span>{{ f.label }}
                          </button>
                          <button type="button" role="menuitemradio" [attr.aria-checked]="!item.flag"
                            class="flex items-center gap-2 rounded-md px-2 py-1.5 text-left" style="color: var(--muted)"
                            data-testid="watchlist-flag-none" (click)="setFlag(wl.id!, item.symbol, null)">No flag</button>
                        </span>
                      </span>
                      <span class="font-extrabold">{{ item.symbol }}</span>
                      <span *ngIf="statusOf(item.symbol) as st" class="lt-badge ml-2"
                        [class.lt-badge-up]="st.tradingStatus === 'OPEN'" [class.lt-badge-muted]="st.tradingStatus !== 'OPEN'"
                        [attr.title]="st.tradingStatus === 'CLOSED' ? closedText(st) : null"
                        data-testid="watchlist-state">{{ st.tradingStatus === 'OPEN' ? 'Open' : 'Closed' }}</span>
                      <span *ngIf="item.quote?.stale" class="lt-badge lt-badge-warn ml-1" data-testid="watchlist-stale">Stale</span>
                      <span *ngIf="held(item.symbol) as qty" class="lt-badge lt-badge-info ml-1" data-testid="watchlist-held">Held {{ qty | number }}</span>
                      <span class="wl__name" *ngIf="nameOf(item.symbol) as name" [attr.title]="name">{{ name }}</span>
                    </td>
                    <td class="whitespace-nowrap text-right tabular-nums" data-testid="watchlist-price">
                      <ng-container *ngIf="item.quote?.lastPrice != null; else noQuote">
                        {{ money(item.quote!.lastPrice!, item.quote!.currency) }}
                        <span *ngIf="movedSince(item) as m" class="block text-[11px] font-semibold" [style.color]="m > 0 ? 'var(--up)' : 'var(--down)'"
                          data-testid="watchlist-since">{{ m > 0 ? '▲ +' : '▼ ' }}{{ m | number:'1.2-2' }}% since last visit</span>
                      </ng-container>
                    </td>
                    <td class="text-right tabular-nums" *ngIf="shown('change')" [style.color]="changeColor(item.quote?.change)" data-testid="watchlist-change-abs">
                      {{ item.quote?.change != null ? ((item.quote!.change! > 0 ? '+' : '') + (item.quote!.change | number:'1.2-2')) : '—' }}
                    </td>
                    <td class="whitespace-nowrap text-right tabular-nums" *ngIf="shown('changePercent')" [style.color]="changeColor(item.quote?.changePercent)" data-testid="watchlist-change">
                      <span class="lt-move" aria-hidden="true" *ngIf="item.quote?.changePercent != null">
                        <span [class.lt-move--up]="item.quote!.changePercent! > 0" [class.lt-move--down]="item.quote!.changePercent! < 0" [style.width.%]="moveWidth(item.quote!.changePercent)"></span>
                      </span>{{ item.quote?.changePercent != null ? ((item.quote!.changePercent! > 0 ? '+' : '') + (item.quote!.changePercent | number:'1.2-2') + '%') : '—' }}
                    </td>
                    <td class="text-right tabular-nums" *ngIf="shown('bid')" data-testid="watchlist-bid">{{ item.quote?.bid != null ? (item.quote!.bid | number:'1.2-4') : '—' }}</td>
                    <td class="text-right tabular-nums" *ngIf="shown('ask')" data-testid="watchlist-ask">{{ item.quote?.ask != null ? (item.quote!.ask | number:'1.2-4') : '—' }}</td>
                    <td class="text-right tabular-nums" *ngIf="shown('previousClose')" data-testid="watchlist-prev-close">{{ item.quote?.previousClose != null ? (item.quote!.previousClose | number:'1.2-2') : '—' }}</td>
                    <td class="text-right text-[12px] tabular-nums" *ngIf="shown('asOf')" style="color: var(--muted)">{{ item.quote?.quoteAsOf ? (item.quote!.quoteAsOf | date:'shortTime') : '—' }}</td>
                    <td class="whitespace-nowrap text-[12px] tabular-nums" *ngIf="shown('alert')" data-testid="watchlist-alert">
                      <ng-container *ngIf="alertsFor(item.symbol) as list">
                        <span *ngIf="list.length > 0; else noAlert" style="color: var(--text)">
                          {{ list[0].direction === 'ABOVE' ? '≥' : '≤' }} {{ list[0].targetPrice | number:'1.2-2' }}
                          <span *ngIf="list.length > 1" style="color: var(--muted)">+{{ list.length - 1 }}</span>
                        </span>
                      </ng-container>
                    </td>
                    <td class="whitespace-nowrap text-right">
                      <span class="inline-flex items-center gap-1">
                        <ng-container *ngIf="!closed(item.symbol); else marketClosed">
                          <a [routerLink]="['/trade']" [queryParams]="{ symbol: item.symbol, side: 'BUY' }" class="lt-btn lt-btn-buy lt-btn-sm"
                            data-testid="watchlist-buy" [attr.aria-label]="'Buy ' + item.symbol">Buy</a>
                          <a *ngIf="held(item.symbol)" [routerLink]="['/trade']" [queryParams]="{ symbol: item.symbol, side: 'SELL' }"
                            class="lt-btn lt-btn-sell lt-btn-sm" data-testid="watchlist-sell" [attr.aria-label]="'Sell ' + item.symbol">Sell</a>
                        </ng-container>
                        <ng-template #marketClosed>
                          <button type="button" class="lt-btn lt-btn-ghost lt-btn-sm" disabled data-testid="watchlist-closed"
                            [attr.title]="closedText(statusOf(item.symbol)!)" [attr.aria-label]="closedText(statusOf(item.symbol)!)">Closed</button>
                        </ng-template>
                        <button type="button" class="wl__icon" data-testid="watchlist-set-alert" title="Set a price alert"
                          [attr.aria-label]="'Set an alert on ' + item.symbol" (click)="prepareAlert(item.symbol)">
                          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true"><path d="M6 9a6 6 0 1 1 12 0c0 5 2 6 2 6H4s2-1 2-6z" stroke-linejoin="round"></path><path d="M9.5 20a2.5 2.5 0 0 0 5 0" stroke-linecap="round"></path></svg>
                        </button>
                        <button type="button" class="wl__icon wl__icon--remove" data-testid="watchlist-remove" title="Remove from this list"
                          [attr.aria-label]="'Remove ' + item.symbol + ' from ' + wl.name" (click)="removeInstrument(wl.id!, item.symbol)">
                          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" stroke-linecap="round"></path></svg>
                        </button>
                      </span>
                    </td>
                  </tr>
                </tbody>
              </table>
              </div>
              <p class="mt-2 text-xs" style="color: var(--muted)" *ngIf="sortKey() !== null">
                Sorted by {{ sortKey() }}.
                <button type="button" class="wl__link" data-testid="watchlist-sort-clear" (click)="clearSort()">Back to your order</button> to drag rows again.
              </p>
            </div>
          </ng-container>
          <ng-template #noList>
            <div class="lt-empty"><p>Create a list on the left to start following instruments.</p></div>
          </ng-template>
          <ng-template #noQuote><span style="color: var(--muted)">No price yet</span></ng-template>
          <ng-template #noAlert><span style="color: var(--muted)">—</span></ng-template>
        </section>

        <section class="lt-card lt-card-pad wl__full lt-appear lt-appear-3" aria-labelledby="alerts-title" #alertsSection>
          <div class="lt-card-head">
            <h2 id="alerts-title" class="lt-section-title">Price alerts</h2>
            <form [formGroup]="alertForm" (ngSubmit)="createAlert()" class="wl__alert-form">
              <label class="wl__alert-field">
                <span class="lt-field-label">Instrument</span>
                <select class="lt-select" data-testid="alert-symbol" formControlName="symbol">
                  <option value="">Choose…</option>
                  <option *ngFor="let ins of instruments()" [value]="ins.symbol">{{ ins.symbol }}</option>
                </select>
              </label>
              <label class="wl__alert-field">
                <span class="lt-field-label">Direction</span>
                <select class="lt-select" data-testid="alert-direction" formControlName="direction">
                  <option value="ABOVE">Rises above</option>
                  <option value="BELOW">Falls below</option>
                </select>
              </label>
              <label class="wl__alert-field">
                <span class="lt-field-label">Target price</span>
                <input #alertTarget class="lt-input" data-testid="alert-target" type="text" inputmode="decimal"
                  formControlName="targetPrice" placeholder="3500.00" />
              </label>
              <button type="submit" data-testid="alert-create" class="lt-btn lt-btn-primary" [disabled]="alertForm.invalid">Create alert</button>
            </form>
          </div>

          <div class="lt-empty" data-testid="alerts-empty" *ngIf="loaded() && alerts().length === 0"><p>No alerts yet.</p></div>

          <ul class="wl__alerts" *ngIf="alerts().length > 0">
            <li *ngFor="let a of alerts()" data-testid="alert-row" class="wl__alert">
              <span class="wl__alert-what">
                <strong>{{ a.symbol }}</strong>
                <span class="tabular-nums" style="color: var(--muted)">{{ a.direction === 'ABOVE' ? '≥' : '≤' }} {{ a.targetPrice | number:'1.2-2' }}</span>
              </span>
              <span class="flex items-center gap-2">
                <span class="lt-dot" data-testid="alert-status" [style.color]="statusFg(a.status)">{{ a.status }}</span>
                <button type="button" *ngIf="a.status === 'ACTIVE'" data-testid="alert-cancel" class="wl__link" style="color: var(--down)"
                  (click)="cancelAlert(a.id!)">Cancel</button>
              </span>
            </li>
          </ul>
        </section>
      </div>
    </app-shell>
  `,
  styles: [`
    /* Lists on the left, the chosen list beside them, alerts across the bottom. */
    .wl { display: grid; grid-template-columns: 230px minmax(0, 1fr); gap: 1rem; align-items: start; }
    .wl__full { grid-column: 1 / -1; }
    .wl__rail { display: grid; gap: .6rem; padding: .9rem .8rem; position: sticky; top: 5rem; }
    .wl__rail-title { margin: 0 .3rem; }
    .wl__lists { display: grid; gap: 2px; }
    .wl__list {
      display: flex; align-items: center; justify-content: space-between; gap: .5rem; width: 100%;
      padding: .5rem .6rem; border: 1px solid transparent; border-radius: 9px; background: transparent;
      color: var(--muted); font: inherit; font-size: .85rem; font-weight: 700; text-align: left; cursor: pointer;
      transition: background-color .15s ease, color .15s ease, border-color .15s ease;
    }
    .wl__list:hover { color: var(--text); background: var(--surface-2); }
    .wl__list.is-on { color: var(--text); background: var(--accent-soft); border-color: color-mix(in srgb, var(--accent) 30%, transparent); }
    .wl__count { font-size: .7rem; font-weight: 600; color: var(--muted); }
    .wl__create { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: .4rem; padding-top: .6rem; border-top: 1px solid var(--border); }
    .wl__create .lt-input, .wl__preset { height: 2.1rem; font-size: .8rem; padding-block: 0; }
    .wl__title { margin: 0; font-size: 1.05rem; font-weight: 750; color: var(--text); }
    .wl__add { min-width: 260px; flex: 0 1 320px; }
    .wl__toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .5rem .75rem; margin-bottom: .6rem; }
    .wl__moved { display: inline-flex; flex-wrap: wrap; align-items: center; gap: .4rem; margin: 0; font-size: .8rem; color: var(--muted); }
    .wl__link { border: 0; background: none; padding: 0; font: inherit; font-weight: 700; color: var(--accent-strong); cursor: pointer; text-decoration: underline; text-underline-offset: 2px; }
    .wl__grip { padding: 0 .25rem; border: 0; background: none; color: var(--muted); font-size: .9rem; line-height: 1; cursor: grab; opacity: .45; transition: opacity .15s ease; }
    .wl__row:hover .wl__grip, .wl__grip:focus-visible { opacity: 1; }
    .wl__grip:disabled { cursor: default; }
    /* The company name on a quiet second line, aligned under the symbol (past the flag dot). */
    .wl__name { display: block; max-width: 220px; margin: .1rem 0 0 1.75rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: .7rem; color: var(--muted); }
    .wl__icon { display: inline-grid; place-items: center; width: 1.85rem; height: 1.85rem; border: 1px solid transparent; border-radius: 8px; background: transparent; color: var(--muted); cursor: pointer; transition: color .15s ease, background-color .15s ease, border-color .15s ease; }
    .wl__icon:hover { color: var(--accent-strong); background: var(--accent-soft); }
    .wl__icon--remove:hover { color: var(--down); background: var(--down-bg); }
    .wl__alert-form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: .5rem; }
    .wl__alert-field { display: grid; gap: .2rem; min-width: 140px; }
    .wl__alert-field .lt-field-label { margin: 0; font-size: .65rem; }
    .wl__alert-field .lt-input, .wl__alert-field .lt-select { height: 2.1rem; padding-block: 0; font-size: .8rem; }
    .wl__alert-form .lt-btn { height: 2.1rem; }
    .wl__alerts { display: grid; grid-template-columns: repeat(auto-fill, minmax(270px, 1fr)); gap: .5rem; margin: 0; padding: 0; list-style: none; }
    .wl__alert { display: flex; align-items: center; justify-content: space-between; gap: .75rem; padding: .55rem .75rem; border: 1px solid var(--border); border-radius: 10px; font-size: .8125rem; }
    .wl__alert-what { display: inline-flex; gap: .5rem; align-items: baseline; white-space: nowrap; color: var(--text); }
    @media (max-width: 980px) {
      .wl { grid-template-columns: minmax(0, 1fr); }
      .wl__rail { position: static; }
      .wl__add { flex-basis: 100%; }
    }
  `]
})
export class Watchlists implements OnInit, OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly service = inject(WatchlistsService);
  private readonly auth = inject(AuthService);
  private readonly orders = inject(OrderService);

  private readonly alertTarget = viewChild<ElementRef<HTMLInputElement>>('alertTarget');
  private readonly alertsSection = viewChild<ElementRef<HTMLElement>>('alertsSection');
  private readonly renameInput = viewChild<ElementRef<HTMLInputElement>>('renameInput');

  readonly instruments = signal<InstrumentResponse[]>([]);
  readonly presets = signal<WatchlistPresetResponse[]>([]);
  readonly watchlists = signal<WatchlistResponse[]>([]);
  readonly alerts = signal<AlertResponse[]>([]);
  readonly loaded = signal(false);
  readonly errorMessage = signal('');

  readonly selectedId = signal<number | null>(null);
  readonly renaming = signal(false);
  readonly confirmingDelete = signal(false);
  readonly sortKey = signal<SortKey | null>(null);
  readonly sortAscending = signal(true);
  readonly dragging = signal<string | null>(null);
  /** Symbols whose price just changed, highlighted briefly. */
  readonly flashing = signal<Set<string>>(new Set());

  readonly columnChoices = COLUMNS;
  readonly columns = signal<ColumnKey[]>(loadColumns());
  readonly columnsOpen = signal(false);
  readonly flags = FLAGS;
  /** The row whose flag menu is open. */
  readonly flagMenuFor = signal<string | null>(null);
  readonly flaggedOnly = signal(false);
  /** The prices the viewer saw on their last visit (this browser), the baseline for "since last visit". */
  readonly seenBaseline = signal<Map<string, number>>(loadSeen());

  private readonly holdings = signal<Map<string, number>>(new Map());
  private lastPrices = new Map<string, number>();
  private accountId: number | null = null;
  private readonly refresher = new FeedRefresher(() => this.service.getFreshness(), () => this.refreshPrices());

  readonly watchlistForm = this.fb.nonNullable.group({ name: ['', [Validators.required, Validators.maxLength(100)]] });
  readonly alertForm = this.fb.nonNullable.group({
    symbol: ['', Validators.required],
    direction: ['ABOVE', Validators.required],
    targetPrice: ['', [Validators.required, Validators.pattern(/^\d+(?:\.\d{1,2})?$/)]]
  });

  /** The list on screen: the chosen one, else the first. */
  readonly selected = computed<WatchlistResponse | null>(() => {
    const lists = this.watchlists();
    return lists.find((w) => w.id === this.selectedId()) ?? lists[0] ?? null;
  });

  private readonly statuses = computed(() => new Map(this.instruments().map((i) => [i.symbol, i] as [string, InstrumentResponse])));

  /** The selected list's rows: flagged ones only if asked, in the customer's order or sorted by a column. */
  readonly rows = computed<WatchlistItemResponse[]>(() => {
    const items = this.selected()?.items ?? [];
    return this.sorted(this.flaggedOnly() ? items.filter((i) => !!i.flag) : items);
  });

  /** The filter box above the table: plain text, or a regular expression with the .* toggle. */
  readonly rowQuery = signal('');
  readonly rowRegex = signal(false);
  private readonly rowMatcher = computed(() => createMatcher(this.rowQuery(), this.rowRegex()));

  /** The rows the table shows: rows() after the filter box, matched on symbol and company name. */
  readonly visibleRows = computed(() => {
    const matcher = this.rowMatcher();
    return matcher.empty ? this.rows() : this.rows().filter((i) => matcher.test(i.symbol, this.nameOf(i.symbol)));
  });

  /** Rows can be dragged only when the whole list shows in the customer's own order. */
  readonly canReorder = computed(() => this.sortKey() === null && !this.flaggedOnly() && this.rowMatcher().empty);

  /** The largest move in the list, so each row's bar is drawn against it. */
  private readonly maxMove = computed(() =>
    Math.max(0, ...(this.selected()?.items ?? []).map((i) => Math.abs(Number(i.quote?.changePercent ?? 0)))));

  /** Half the bar is the largest move; a row's bar fills that half in proportion to its own move. */
  moveWidth(changePercent: number | null | undefined): number {
    const max = this.maxMove();
    return changePercent == null || max === 0 ? 0 : (Math.abs(Number(changePercent)) / max) * 50;
  }

  /** The company name from the catalogue, shown quietly beside the symbol; null when it is not known. */
  nameOf(symbol: string): string | null {
    return this.statuses().get(symbol)?.name ?? null;
  }

  /** How many rows in the selected list moved since the last visit. */
  readonly movedCount = computed(() => (this.selected()?.items ?? []).filter((i) => this.movedSince(i) !== null).length);

  private sorted(list: WatchlistItemResponse[]): WatchlistItemResponse[] {
    const items = [...list];
    const key = this.sortKey();
    if (key === null) {
      return items;
    }
    const value = (i: WatchlistItemResponse): number | string | null =>
      key === 'symbol' ? i.symbol : key === 'price' ? i.quote?.lastPrice ?? null : i.quote?.changePercent ?? null;
    const direction = this.sortAscending() ? 1 : -1;
    return items.sort((a, b) => {
      const x = value(a);
      const y = value(b);
      if (x === null || y === null) {
        return x === y ? 0 : x === null ? 1 : -1; // no price sorts last either way
      }
      return (typeof x === 'string' ? x.localeCompare(String(y)) : Number(x) - Number(y)) * direction;
    });
  }

  ngOnInit(): void {
    this.service.getAlerts().subscribe({ next: (a) => this.alerts.set(a), error: (e) => this.fail(e) });
    this.service.getPresets().subscribe({ next: (p) => this.presets.set(p), error: () => undefined });
    this.auth.getProfile().subscribe({
      next: (profile) => {
        this.accountId = profile.accountId;
        this.loadHoldings();
      },
      error: () => undefined
    });
    this.refresher.start();
  }

  ngOnDestroy(): void {
    this.refresher.stop();
  }

  /** One refresh: the lists with their prices, each market's state, and alerts (one may have triggered). */
  private refreshPrices(): void {
    this.service.getWatchlists().subscribe({
      next: (lists) => {
        this.noteChangedPrices(lists);
        this.watchlists.set(lists);
        this.loaded.set(true);
        this.rememberSeen(lists);
      },
      error: (e) => this.fail(e)
    });
    this.service.getInstruments().subscribe({ next: (i) => this.instruments.set(i), error: () => undefined });
    if (this.loaded()) {
      this.service.getAlerts().subscribe({ next: (a) => this.alerts.set(a), error: () => undefined });
      this.loadHoldings();
    }
  }

  private loadHoldings(): void {
    if (this.accountId === null) {
      return;
    }
    this.orders.getPositions(this.accountId).subscribe({
      next: (positions) => this.holdings.set(new Map(positions.filter((p) => p.quantity > 0).map((p) => [p.symbol, p.quantity] as [string, number]))),
      error: () => undefined
    });
  }

  /** Highlights prices that differ from the last refresh (not on the first load). */
  private noteChangedPrices(lists: WatchlistResponse[]): void {
    const changed = new Set<string>();
    const first = this.lastPrices.size === 0;
    for (const item of lists.flatMap((l) => l.items)) {
      const price = item.quote?.lastPrice;
      if (price == null) {
        continue;
      }
      const before = this.lastPrices.get(item.symbol);
      if (!first && before !== undefined && before !== Number(price)) {
        changed.add(item.symbol);
      }
      this.lastPrices.set(item.symbol, Number(price));
    }
    if (changed.size > 0) {
      this.flashing.set(changed);
      setTimeout(() => this.flashing.set(new Set()), FLASH_MS);
    }
  }

  // ---------------------------------------------------------------- lists

  select(id: number): void {
    this.selectedId.set(id);
    this.renaming.set(false);
    this.confirmingDelete.set(false);
  }

  symbolsOf(wl: WatchlistResponse): string[] {
    return wl.items.map((i) => i.symbol);
  }

  createWatchlist(): void {
    if (this.watchlistForm.invalid) { return; }
    this.clearError();
    this.service.createWatchlist({ name: this.watchlistForm.getRawValue().name }).subscribe({
      next: (created) => {
        this.watchlistForm.reset({ name: '' });
        this.watchlists.update((lists) => [...lists, created]);
        this.select(created.id!);
      },
      error: (e) => this.fail(e)
    });
  }

  /** "Start from a ready-made list": creates the customer's own list with that market's instruments. */
  createFromPreset(key: string): void {
    if (!key) { return; }
    this.clearError();
    this.service.createFromPreset(key).subscribe({
      next: (created) => {
        this.watchlists.update((lists) => [...lists, created]);
        this.select(created.id!);
      },
      error: (e) => this.fail(e)
    });
  }

  startRename(_: WatchlistResponse): void {
    this.renaming.set(true);
    setTimeout(() => this.renameInput()?.nativeElement.select());
  }

  saveRename(wl: WatchlistResponse): void {
    const name = (this.renameInput()?.nativeElement.value ?? '').trim();
    if (!name || name === wl.name) {
      this.renaming.set(false);
      return;
    }
    this.clearError();
    this.service.renameWatchlist(wl.id!, name).subscribe({
      next: (renamed) => { this.replace(renamed); this.renaming.set(false); },
      error: (e) => this.fail(e)
    });
  }

  deleteWatchlist(wl: WatchlistResponse): void {
    this.clearError();
    this.service.deleteWatchlist(wl.id!).subscribe({
      next: () => {
        this.watchlists.update((lists) => lists.filter((l) => l.id !== wl.id));
        this.selectedId.set(null);
        this.confirmingDelete.set(false);
      },
      error: (e) => this.fail(e)
    });
  }

  addInstrument(watchlistId: number, symbol: string): void {
    if (!symbol) { return; }
    this.clearError();
    this.service.addInstrument(watchlistId, { symbol }).subscribe({ next: (w) => this.replace(w), error: (e) => this.fail(e) });
  }

  removeInstrument(watchlistId: number, symbol: string): void {
    this.clearError();
    this.service.removeInstrument(watchlistId, symbol).subscribe({ next: (w) => this.replace(w), error: (e) => this.fail(e) });
  }

  private replace(updated: WatchlistResponse): void {
    this.watchlists.update((lists) => lists.map((l) => (l.id === updated.id ? updated : l)));
  }

  // ---------------------------------------------------------------- sorting

  sortBy(key: SortKey): void {
    if (this.sortKey() === key) {
      this.sortAscending.update((a) => !a);
    } else {
      this.sortKey.set(key);
      // Prices and changes read best biggest first; symbols A to Z.
      this.sortAscending.set(key === 'symbol');
    }
  }

  clearSort(): void {
    this.sortKey.set(null);
  }

  sortMark(key: SortKey): string {
    return this.sortKey() !== key ? '' : this.sortAscending() ? '▲' : '▼';
  }

  ariaSort(key: SortKey): string | null {
    return this.sortKey() !== key ? null : this.sortAscending() ? 'ascending' : 'descending';
  }

  // ---------------------------------------------------------------- reordering

  onDragStart(event: DragEvent, symbol: string): void {
    this.dragging.set(symbol);
    event.dataTransfer?.setData('text/plain', symbol);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  onDragOver(event: DragEvent): void {
    if (this.dragging() !== null) {
      event.preventDefault();
    }
  }

  onDrop(event: DragEvent, target: string): void {
    event.preventDefault();
    const moving = this.dragging();
    this.dragging.set(null);
    if (moving && moving !== target && this.canReorder()) {
      const order = this.symbolsOf(this.selected()!).filter((s) => s !== moving);
      order.splice(order.indexOf(target), 0, moving);
      this.saveOrder(order);
    }
  }

  /** Keyboard reordering: Alt+Up / Alt+Down on the handle moves the row. */
  onMoveKey(event: KeyboardEvent, symbol: string): void {
    if (!event.altKey || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') || !this.canReorder()) {
      return;
    }
    event.preventDefault();
    const order = this.symbolsOf(this.selected()!);
    const from = order.indexOf(symbol);
    const to = from + (event.key === 'ArrowUp' ? -1 : 1);
    if (to < 0 || to >= order.length) {
      return;
    }
    [order[from], order[to]] = [order[to], order[from]];
    this.saveOrder(order);
    setTimeout(() => (event.target as HTMLElement | null)?.focus());
  }

  /** Shows the new order at once, then saves it; a failure reloads the lists. */
  private saveOrder(order: string[]): void {
    const wl = this.selected();
    if (!wl) {
      return;
    }
    const bySymbol = new Map(wl.items.map((i) => [i.symbol, i] as [string, WatchlistItemResponse]));
    this.replace({ ...wl, items: order.map((s) => bySymbol.get(s)!).filter(Boolean) });
    this.service.reorderInstruments(wl.id!, order).subscribe({
      next: (saved) => this.replace(saved),
      error: (e) => { this.fail(e); this.refreshPrices(); }
    });
  }

  // ---------------------------------------------------------------- flags

  toggleFlagMenu(symbol: string): void {
    this.flagMenuFor.set(this.flagMenuFor() === symbol ? null : symbol);
  }

  setFlag(watchlistId: number, symbol: string, flag: FlagColour | null): void {
    this.flagMenuFor.set(null);
    this.clearError();
    this.service.setInstrumentFlag(watchlistId, symbol, flag).subscribe({ next: (w) => this.replace(w), error: (e) => this.fail(e) });
  }

  flagCss(flag: string | null | undefined): string {
    return FLAGS.find((f) => f.colour === flag)?.css ?? 'transparent';
  }

  flagLabel(flag: string | null | undefined): string {
    return (FLAGS.find((f) => f.colour === flag)?.label ?? '').toLowerCase();
  }

  /** A click outside an open flag menu, or Escape, closes it. */
  @HostListener('document:click', ['$event'])
  protected closeFlagMenuOnOutsideClick(event: Event): void {
    if (this.flagMenuFor() !== null && !(event.target as HTMLElement | null)?.closest('[data-flag-root]')) {
      this.flagMenuFor.set(null);
    }
  }

  @HostListener('document:keydown.escape')
  protected closeFlagMenuOnEscape(): void {
    this.flagMenuFor.set(null);
  }

  // ---------------------------------------------------------------- since your last visit

  /** The % move since the price the viewer last saw, or null when it has not moved or was never seen. */
  movedSince(item: WatchlistItemResponse): number | null {
    const now = item.quote?.lastPrice;
    const before = this.seenBaseline().get(item.symbol);
    if (now == null || before === undefined || before === 0 || Number(now) === before) {
      return null;
    }
    return Math.round(((Number(now) - before) / before) * 10000) / 100;
  }

  /** Clears the notes: the prices on screen become what was last seen. */
  markAllSeen(): void {
    const seen = new Map(this.seenBaseline());
    for (const item of this.watchlists().flatMap((l) => l.items)) {
      if (item.quote?.lastPrice != null) {
        seen.set(item.symbol, Number(item.quote.lastPrice));
      }
    }
    this.seenBaseline.set(seen);
    saveSeen(seen);
  }

  /**
   * Records the prices now on screen as "seen" for the next visit. The baseline for this visit stays as
   * it was loaded, so the notes remain until the viewer marks them seen or comes back later.
   */
  private rememberSeen(lists: WatchlistResponse[]): void {
    const stored = loadSeen();
    for (const item of lists.flatMap((l) => l.items)) {
      if (item.quote?.lastPrice != null) {
        stored.set(item.symbol, Number(item.quote.lastPrice));
      }
    }
    saveSeen(stored);
  }

  // ---------------------------------------------------------------- columns

  shown(key: ColumnKey): boolean {
    return this.columns().includes(key);
  }

  toggleColumn(key: ColumnKey): void {
    const next = this.shown(key) ? this.columns().filter((c) => c !== key) : COLUMNS.map((c) => c.key).filter((c) => c === key || this.shown(c));
    this.columns.set(next);
    if (key === 'changePercent' && !next.includes(key) && this.sortKey() === 'change') {
      this.clearSort();
    }
    saveColumns(next);
  }

  resetColumns(): void {
    this.columns.set([...DEFAULT_COLUMNS]);
    saveColumns(this.columns());
  }

  // ---------------------------------------------------------------- per-row information

  statusOf(symbol: string): InstrumentResponse | null {
    return this.statuses().get(symbol) ?? null;
  }

  closed(symbol: string): boolean {
    return isClosed(this.statusOf(symbol));
  }

  closedText(instrument: InstrumentResponse): string {
    return closedMessage(instrument);
  }

  held(symbol: string): number {
    return this.holdings().get(symbol) ?? 0;
  }

  alertsFor(symbol: string): AlertResponse[] {
    return this.alerts().filter((a) => a.symbol === symbol && a.status === 'ACTIVE');
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

  trackBySymbol(_: number, item: WatchlistItemResponse): string {
    return item.symbol;
  }

  // ---------------------------------------------------------------- alerts

  /** "Alert" on a row: fills the alert form with that instrument and puts the cursor on the target price. */
  prepareAlert(symbol: string): void {
    this.alertForm.patchValue({ symbol, targetPrice: '' });
    this.alertsSection()?.nativeElement.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    setTimeout(() => this.alertTarget()?.nativeElement.focus());
  }

  createAlert(): void {
    if (this.alertForm.invalid) { return; }
    this.clearError();
    const value = this.alertForm.getRawValue();
    this.service.createAlert({ symbol: value.symbol, direction: value.direction as never, targetPrice: Number(value.targetPrice) }).subscribe({
      next: () => { this.alertForm.patchValue({ targetPrice: '' }); this.service.getAlerts().subscribe((a) => this.alerts.set(a)); },
      error: (e) => this.fail(e)
    });
  }

  cancelAlert(id: number): void {
    this.clearError();
    this.service.cancelAlert(id).subscribe({
      next: () => this.service.getAlerts().subscribe((a) => this.alerts.set(a)),
      error: (e) => this.fail(e)
    });
  }

  statusBg(status: string): string {
    return status === 'TRIGGERED' ? 'var(--up-bg)' : status === 'CANCELLED' ? 'var(--surface-2)' : 'var(--info-bg)';
  }
  statusFg(status: string): string {
    return status === 'TRIGGERED' ? 'var(--up)' : status === 'CANCELLED' ? 'var(--muted)' : 'var(--info)';
  }

  private fail(err: unknown): void {
    this.loaded.set(true);
    this.errorMessage.set(messageFor(err));
  }
  private clearError(): void {
    this.errorMessage.set('');
  }
}

/** The viewer's saved column choice; the default when there is none, or storage is unavailable. */
function loadColumns(): ColumnKey[] {
  try {
    const saved = JSON.parse(localStorage.getItem(COLUMNS_STORAGE_KEY) ?? 'null');
    if (Array.isArray(saved)) {
      const known = COLUMNS.map((c) => c.key);
      return saved.filter((c): c is ColumnKey => known.includes(c));
    }
  } catch {
    // Private window, blocked storage or a bad value: use the default.
  }
  return [...DEFAULT_COLUMNS];
}

function saveColumns(columns: ColumnKey[]): void {
  try {
    localStorage.setItem(COLUMNS_STORAGE_KEY, JSON.stringify(columns));
  } catch {
    // Not saved; the choice still applies for this visit.
  }
}

/** The last-seen prices stored in this browser; empty when there are none or storage is unavailable. */
function loadSeen(): Map<string, number> {
  try {
    const saved = JSON.parse(localStorage.getItem(SEEN_STORAGE_KEY) ?? 'null');
    if (saved && typeof saved === 'object') {
      return new Map(Object.entries(saved).filter(([, v]) => typeof v === 'number') as [string, number][]);
    }
  } catch {
    // Private window, blocked storage or a bad value: nothing seen yet.
  }
  return new Map();
}

function saveSeen(seen: Map<string, number>): void {
  try {
    localStorage.setItem(SEEN_STORAGE_KEY, JSON.stringify(Object.fromEntries(seen)));
  } catch {
    // Not saved; the notes still work for this visit.
  }
}
