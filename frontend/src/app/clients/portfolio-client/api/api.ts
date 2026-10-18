export * from './operations.service';
import { OperationsService } from './operations.service';
export * from './pL.service';
import { PLService } from './pL.service';
export * from './portfolio.service';
import { PortfolioService } from './portfolio.service';
export const APIS = [OperationsService, PLService, PortfolioService];
