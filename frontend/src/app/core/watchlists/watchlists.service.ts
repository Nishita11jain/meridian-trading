import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// Wraps the generated market/watchlists/alerts client services.
import { MarketService } from '../../clients/watchlists-client/api/market.service';
import { WatchlistsService as GeneratedWatchlistsService } from '../../clients/watchlists-client/api/watchlists.service';
import { AlertsService } from '../../clients/watchlists-client/api/alerts.service';
import {
  InstrumentResponse,
  MarketQuoteResponse,
  FxRatesResponse,
  WatchlistResponse,
  AlertResponse,
  CreateWatchlistRequest,
  AddInstrumentRequest,
  CreateAlertRequest,
  MarketFreshnessResponse,
  WatchlistPresetResponse
} from '../../clients/watchlists-client/model/models';

/** The colours a watchlist row can be flagged with. */
export type FlagColour = 'RED' | 'AMBER' | 'GREEN';
export type MarketHistoryRange = '5D' | '1M' | '3M' | '6M' | '1Y';

@Injectable({ providedIn: 'root' })
export class WatchlistsService {
  private readonly market = inject(MarketService);
  private readonly watchlists = inject(GeneratedWatchlistsService);
  private readonly alerts = inject(AlertsService);

  getInstruments(): Observable<InstrumentResponse[]> {
    return this.market.getInstruments();
  }

  /** The rupee rates a dollar order would be locked at (stored daily rate, with the margin). */
  getFxRates(): Observable<FxRatesResponse> {
    return this.market.getFxRates();
  }

  getQuotes(): Observable<MarketQuoteResponse[]> {
    return this.market.getQuotes();
  }

  getHistory(symbol: string, range: MarketHistoryRange = '5D'): Observable<MarketQuoteResponse[]> {
    return this.market.getHistory(symbol, range);
  }

  /** When prices were last stored and when the next batch is due (for refreshing in step with the feed). */
  getFreshness(): Observable<MarketFreshnessResponse> {
    return this.market.getMarketFreshness();
  }

  getWatchlists(): Observable<WatchlistResponse[]> {
    return this.watchlists.getWatchlists();
  }

  createWatchlist(request: CreateWatchlistRequest): Observable<WatchlistResponse> {
    return this.watchlists.createWatchlist(request);
  }

  addInstrument(id: number, request: AddInstrumentRequest): Observable<WatchlistResponse> {
    return this.watchlists.addInstrument(id, request);
  }

  removeInstrument(id: number, symbol: string): Observable<WatchlistResponse> {
    return this.watchlists.removeInstrument(id, symbol);
  }

  renameWatchlist(id: number, name: string): Observable<WatchlistResponse> {
    return this.watchlists.renameWatchlist(id, { name });
  }

  deleteWatchlist(id: number): Observable<unknown> {
    return this.watchlists.deleteWatchlist(id);
  }

  reorderInstruments(id: number, symbols: string[]): Observable<WatchlistResponse> {
    return this.watchlists.reorderInstruments(id, { symbols });
  }

  /** Puts a colour flag on an instrument in its list; null removes it. */
  setInstrumentFlag(id: number, symbol: string, flag: FlagColour | null): Observable<WatchlistResponse> {
    return this.watchlists.setInstrumentFlag(id, symbol, { flag: flag as never });
  }

  getPresets(): Observable<WatchlistPresetResponse[]> {
    return this.watchlists.getWatchlistPresets();
  }

  createFromPreset(key: string): Observable<WatchlistResponse> {
    return this.watchlists.createWatchlistFromPreset(key);
  }

  getAlerts(): Observable<AlertResponse[]> {
    return this.alerts.getAlerts();
  }

  createAlert(request: CreateAlertRequest): Observable<AlertResponse> {
    return this.alerts.createAlert(request);
  }

  cancelAlert(id: number): Observable<AlertResponse> {
    return this.alerts.cancelAlert(id);
  }
}
