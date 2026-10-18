import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { ActivatedRouteSnapshot, provideRouter, withRouterConfig, withViewTransitions } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideApi as provideAuthApi } from './clients/auth-client/provide-api';
import { provideApi as provideTradeApi } from './clients/trade-client/provide-api';
import { provideApi as providePreferencesApi } from './clients/preferences-client/provide-api';
import { provideApi as provideNotificationsApi } from './clients/notifications-client/provide-api';
import { provideApi as provideWatchlistsApi } from './clients/watchlists-client/provide-api';
import { provideApi as providePortfolioApi } from './clients/portfolio-client/provide-api';
import { provideApi as provideAdviceApi } from './clients/advice-client/provide-api';
import { provideApi as provideStrategiesApi } from './clients/strategies-client/provide-api';
import { AUTH_API_BASE, TRADE_API_BASE } from './core/config/api-config';
import { authHeaderInterceptor } from './core/interceptors/auth-header.interceptor';

import { routes } from './app.routes';

/** The route path of the deepest page in a snapshot ('' is the landing page). */
function leafPath(snapshot: ActivatedRouteSnapshot): string | undefined {
  let current = snapshot;
  while (current.firstChild) {
    current = current.firstChild;
  }
  return current.routeConfig?.path;
}

/** Moving between these two pages shares the globe and the logo, so that hand-off is slowed down (sign-in.css). */
const MERIDIAN_PAGES = new Set(['', 'sign-in']);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([authHeaderInterceptor])),
    provideAuthApi(AUTH_API_BASE),
    provideTradeApi(TRADE_API_BASE),
    // The preferences extension is served by the Trade REST API, so it shares that base and is
    // reached on the same origin behind the dev proxy. Its /api/ paths get the bearer token from the
    // interceptor like every other platform call.
    providePreferencesApi(TRADE_API_BASE),
    // Notifications is served by the Trade REST API too, same base and interceptor path.
    provideNotificationsApi(TRADE_API_BASE),
    // Market data, watchlists and alerts are served by the Trade REST API as well.
    provideWatchlistsApi(TRADE_API_BASE),
    // Portfolio and P&L, also on the Trade REST API.
    providePortfolioApi(TRADE_API_BASE),
    // Trade Advice & Signals, also on the Trade REST API.
    provideAdviceApi(TRADE_API_BASE),
    // Automated Strategy Execution, also on the Trade REST API.
    provideStrategiesApi(TRADE_API_BASE),
    // When a guard cancels a Back/Forward (the unsaved-order prompt), put the browser's history back
    // exactly as it was. The default ('replace') rewrites an entry instead, so after choosing "Stay"
    // the next Back would leave the app.
    // Pages cross-fade (styles.css, ::view-transition-*); browsers without the API switch instantly.
    provideRouter(
      routes,
      withRouterConfig({ canceledNavigationResolution: 'computed' }),
      withViewTransitions({
        skipInitialTransition: true,
        onViewTransitionCreated: ({ transition, from, to }) => {
          const a = leafPath(from);
          const b = leafPath(to);
          if (a !== undefined && b !== undefined && a !== b && MERIDIAN_PAGES.has(a) && MERIDIAN_PAGES.has(b)) {
            // Marks this one transition so CSS can slow it (`:active-view-transition-type(meridian)`).
            transition.types?.add('meridian');
          }
        }
      })
    )
  ]
};
