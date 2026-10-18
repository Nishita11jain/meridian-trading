import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { StrategiesService as GeneratedStrategiesService } from '../../clients/strategies-client/api/strategies.service';
import { StrategyRequest, StrategyResponse, StrategyExecutionResponse, UpdateStrategyStatusRequest } from '../../clients/strategies-client/model/models';

@Injectable({ providedIn: 'root' })
export class StrategiesService {
  private readonly strategies = inject(GeneratedStrategiesService);

  list(accountId: number): Observable<StrategyResponse[]> {
    return this.strategies.listStrategies(accountId);
  }

  create(accountId: number, request: StrategyRequest): Observable<StrategyResponse> {
    return this.strategies.createStrategy(accountId, request);
  }

  updateStatus(accountId: number, strategyId: number, status: 'ACTIVE' | 'DISABLED'): Observable<StrategyResponse> {
    const request: UpdateStrategyStatusRequest = { status };
    return this.strategies.updateStrategyStatus(accountId, strategyId, request);
  }

  log(accountId: number, strategyId: number, limit = 20): Observable<StrategyExecutionResponse[]> {
    return this.strategies.getStrategyLog(accountId, strategyId, limit);
  }
}
