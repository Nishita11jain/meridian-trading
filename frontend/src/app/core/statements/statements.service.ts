import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

/** One filled trade, as the statement lists it. Prices are in the instrument's own currency. */
export interface TradeLine {
  executedAt: string;
  orderId: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  quantity: number;
  price: number;
  currency: string;
  value: number;
}

/** One cash movement. Money in is positive, money out is negative. */
export interface CashLine {
  createdAt: string;
  type: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL';
  amount: number;
  reference: string | null;
}

/**
 * Trade and cash history for the signed-in account. Reads the account's own rows; the server refuses any other
 * account. Downloads are fetched as a file, so the browser saves them with the server's file name.
 */
@Injectable({ providedIn: 'root' })
export class StatementsService {
  private readonly http = inject(HttpClient);

  trades(accountId: number, from: string, to: string): Observable<TradeLine[]> {
    return this.http.get<TradeLine[]>(`/api/v1/accounts/${accountId}/trades`, { params: range(from, to) });
  }

  cash(accountId: number, from: string, to: string): Observable<CashLine[]> {
    return this.http.get<CashLine[]>(`/api/v1/accounts/${accountId}/cash-ledger`, { params: range(from, to) });
  }

  downloadCsv(accountId: number, kind: 'trades' | 'cash', from: string, to: string): Observable<Blob> {
    return this.http.get(`/api/v1/accounts/${accountId}/statement/${kind === 'trades' ? 'trades.csv' : 'cash.csv'}`, {
      params: range(from, to),
      responseType: 'blob'
    });
  }
}

function range(from: string, to: string): HttpParams {
  return new HttpParams().set('from', from).set('to', to);
}

/** Saves a downloaded file under the name the server gave it, or a fallback. */
export function saveFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * The statement range presets. The Indian financial year runs 1 April to 31 March, which is what tax filings use;
 * the calendar year is offered as well.
 */
export function financialYear(today: Date): { from: string; to: string } {
  const startYear = today.getMonth() >= 3 ? today.getFullYear() : today.getFullYear() - 1;
  return { from: `${startYear}-04-01`, to: `${startYear + 1}-03-31` };
}

export function calendarYear(today: Date): { from: string; to: string } {
  const year = today.getFullYear();
  return { from: `${year}-01-01`, to: `${year}-12-31` };
}
