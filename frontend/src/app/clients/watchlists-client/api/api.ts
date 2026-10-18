export * from './alerts.service';
import { AlertsService } from './alerts.service';
export * from './market.service';
import { MarketService } from './market.service';
export * from './watchlists.service';
import { WatchlistsService } from './watchlists.service';
export const APIS = [AlertsService, MarketService, WatchlistsService];
