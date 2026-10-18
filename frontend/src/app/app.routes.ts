import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { guestGuard } from './core/guards/guest.guard';
import { unsavedChangesGuard } from './core/guards/unsaved-changes.guard';

export const routes: Routes = [
  // The public landing page. Open to everyone; it offers a signed-in visitor a link to the dashboard.
  { path: '', title: 'Meridian — Enterprise Trading Platform', loadComponent: () => import('./features/landing/landing').then(m => m.Landing) },
  { path: 'sign-in', title: 'Sign in · Meridian', canActivate: [guestGuard], loadComponent: () => import('./features/sign-in/sign-in').then(m => m.SignIn) },
  { path: 'dashboard', title: 'Dashboard · Meridian', canActivate: [authGuard], loadComponent: () => import('./features/dashboard/dashboard').then(m => m.Dashboard) },
  { path: 'trade', title: 'New order · Meridian', canActivate: [authGuard], canDeactivate: [unsavedChangesGuard], loadComponent: () => import('./features/order-ticket/order-ticket').then(m => m.OrderTicket) },
  { path: 'statements', title: 'Statements · Meridian', canActivate: [authGuard], loadComponent: () => import('./features/statements/statements').then(m => m.Statements) },
  { path: 'blotter', title: 'Blotter · Meridian', canActivate: [authGuard], loadComponent: () => import('./features/blotter/blotter').then(m => m.Blotter) },
  { path: 'profile', title: 'Profile & settings · Meridian', canActivate: [authGuard], loadComponent: () => import('./features/profile/profile').then(m => m.Profile) },
  { path: 'preferences', redirectTo: 'profile' },
  { path: 'notifications', title: 'Notifications · Meridian', canActivate: [authGuard], loadComponent: () => import('./features/notifications/notifications').then(m => m.Notifications) },
  { path: 'watchlists', title: 'Watchlists · Meridian', canActivate: [authGuard], loadComponent: () => import('./features/watchlists/watchlists').then(m => m.Watchlists) },
  { path: 'portfolio', title: 'Portfolio · Meridian', canActivate: [authGuard], loadComponent: () => import('./features/portfolio/portfolio').then(m => m.Portfolio) },
  { path: 'advice', title: 'Trade advice · Meridian', canActivate: [authGuard], loadComponent: () => import('./features/advice/advice').then(m => m.Advice) },
  { path: 'strategies', title: 'Strategies · Meridian', canActivate: [authGuard], loadComponent: () => import('./features/strategies/strategies').then(m => m.Strategies) },
  // An unknown URL goes to the dashboard (guarded) instead of rendering an empty screen.
  { path: '**', redirectTo: '/dashboard' }
];
