import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { PortfolioService as GeneratedPortfolioService } from '../../clients/portfolio-client/api/portfolio.service';
import { PLService } from '../../clients/portfolio-client/api/pL.service';
import { PortfolioHistory, PortfolioSummary, PricedPosition, PnlResponse } from '../../clients/portfolio-client/model/models';

export type HistoryRange = '1D' | '1W' | '1M' | 'ALL';

@Injectable({ providedIn: 'root' })
export class PortfolioService {
  private readonly portfolio = inject(GeneratedPortfolioService);
  private readonly pl = inject(PLService);

  summary(accountId: number): Observable<PortfolioSummary> {
    return this.portfolio.getPortfolioSummary(accountId);
  }

  positions(accountId: number): Observable<PricedPosition[]> {
    return this.portfolio.getPricedPositions(accountId);
  }

  /** Value and invested amount over time, one series per currency. */
  history(accountId: number, range: HistoryRange): Observable<PortfolioHistory[]> {
    return this.portfolio.getPortfolioHistory(accountId, range);
  }

  /** Profit and loss in the base currency; from and to (yyyy-mm-dd) bound the realised figure only. */
  pnl(accountId: number, from?: string, to?: string, bySymbol = false): Observable<PnlResponse> {
    return this.pl.getPnl(accountId, from, to, bySymbol);
  }
}
