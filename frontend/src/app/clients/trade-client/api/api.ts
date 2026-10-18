export * from './accounts.service';
import { AccountsService } from './accounts.service';
export * from './orders.service';
import { OrdersService } from './orders.service';
export * from './statements.service';
import { StatementsService } from './statements.service';
export const APIS = [AccountsService, OrdersService, StatementsService];
