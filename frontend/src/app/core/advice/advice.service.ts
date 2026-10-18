import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// Wraps the generated advice client service.
import { AdviceService as GeneratedAdviceService } from '../../clients/advice-client/api/advice.service';
import { SignalResponse } from '../../clients/advice-client/model/models';

@Injectable({ providedIn: 'root' })
export class AdviceService {
  private readonly advice = inject(GeneratedAdviceService);

  /** Cache-only read: never calls Fauxnance, just the symbols this account holds or watches. */
  getSignals(accountId: number): Observable<SignalResponse[]> {
    return this.advice.getSignals(accountId);
  }

  /** The explicit "Analyse now" action. Served from cache inside the cooldown, calls Fauxnance past it. */
  analyse(accountId: number, symbol: string): Observable<SignalResponse> {
    return this.advice.analyseSignal(accountId, symbol);
  }
}
