import { afterNextRender, Component, DestroyRef, effect, ElementRef, inject, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { SessionStore } from '../../core/auth/session.store';
import { MotionService } from '../../core/motion/motion.service';
import { ThemeService } from '../../core/theme/theme.service';
import { MeridianScene } from './meridian-scene';

interface Module { title: string; body: string; link: string; }

/**
 * The public landing page at '/'. A rotating globe wrapped in a spiral of daily candles sits between a
 * split headline; the candles pass behind and in front of the words, and a band of light that follows
 * the rotation brings each word to full brightness as it passes.
 *
 * A visitor gets a sign-in call to action; a visitor who already has a session is offered the
 * dashboard instead. Styles live in `landing.css` (global, every selector under `.mer`) because they
 * exceed the per-component style budget in angular.json.
 */
@Component({
  selector: 'app-landing',
  imports: [RouterLink],
  host: { class: 'mer' },
  template: `
    <nav class="mer-nav">
      <a routerLink="/" class="mer-brand" aria-label="Meridian home"><i aria-hidden="true"></i>Meridian</a>
      <div class="mer-nav-right">
        <button type="button" class="mer-mode" data-testid="landing-motion-toggle"
          [attr.aria-label]="motion.reduced() ? 'Play animations' : 'Pause animations'"
          [attr.title]="motion.reduced() ? 'Play animations' : 'Pause animations'"
          (click)="motion.toggle()">
          @if (motion.reduced()) {
            <svg width="14" height="14" viewBox="0 0 14 14" fill="currentColor" aria-hidden="true"><path d="M3.5 1.8v10.4a.5.5 0 0 0 .77.42l8-5.2a.5.5 0 0 0 0-.84l-8-5.2a.5.5 0 0 0-.77.42z"></path></svg>
          } @else {
            <svg width="14" height="14" viewBox="0 0 14 14" fill="currentColor" aria-hidden="true"><rect x="2.5" y="2" width="3" height="10" rx=".6"></rect><rect x="8.5" y="2" width="3" height="10" rx=".6"></rect></svg>
          }
        </button>
        <button type="button" class="mer-mode" data-testid="landing-theme-toggle"
          [attr.aria-label]="theme.dark() ? 'Switch to light mode' : 'Switch to dark mode'"
          (click)="theme.toggle()">◐</button>
        @if (signedIn()) {
          <a routerLink="/dashboard" class="mer-signin" data-testid="landing-dashboard">Dashboard</a>
        } @else {
          <a routerLink="/sign-in" class="mer-signin" data-testid="landing-signin">Sign in</a>
        }
      </div>
    </nav>

    <div class="mer-meta" data-testid="landing-eyebrow">
      <span>NYSE · LSE · XETRA · NSE · SGX · HKEX · TSE</span>
      <span>72 sessions · OHLC / 1D</span>
    </div>

    <div class="mer-stage" #stage>
      <canvas #back data-layer="back" aria-hidden="true"></canvas>
      <h1 class="mer-sr">Every candle. Every angle.</h1>
      <div class="mer-word mer-l" aria-hidden="true">
        <span class="o">Every<br>candle.</span><span class="b">Every<br>candle.</span><span class="f">Every<br>candle.</span>
      </div>
      <div class="mer-word mer-r" aria-hidden="true">
        <span class="o">Every<br>angle.</span><span class="b">Every<br>angle.</span><span class="f">Every<br>angle.</span>
      </div>
      <canvas #front data-layer="front" aria-hidden="true"></canvas>
      <span class="mer-hint" aria-hidden="true">drag to rotate</span>
    </div>

    <div class="mer-bar">
      <p class="mer-lede">Place orders, watch quotes and follow every position from New York to Tokyo, laid out plainly so nothing about a trade is hidden.</p>
      <div class="mer-ctas">
        @if (signedIn()) {
          <a routerLink="/dashboard" class="mer-btn mer-primary" data-testid="landing-cta">Open your dashboard →</a>
        } @else {
          <a routerLink="/sign-in" class="mer-btn mer-primary" data-testid="landing-cta">Get started →</a>
        }
        <button type="button" class="mer-btn" (click)="showModules()">View modules</button>
      </div>
    </div>

    <section class="mer-mods" #modsEl aria-label="Modules">
      @for (m of modules; track m.title) {
        <a [routerLink]="m.link" class="mer-mod" data-testid="landing-feature">
          <h2>{{ m.title }}</h2><p>{{ m.body }}</p><b aria-hidden="true">→</b>
        </a>
      }
    </section>

    <footer class="mer-foot">Meridian · Demo environment</footer>
  `
})
export class Landing {
  protected readonly theme = inject(ThemeService);
  protected readonly motion = inject(MotionService);
  private readonly session = inject(SessionStore);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly signedIn = this.session.isAuthenticated;

  private readonly stage = viewChild.required<ElementRef<HTMLElement>>('stage');
  private readonly back = viewChild.required<ElementRef<HTMLCanvasElement>>('back');
  private readonly front = viewChild.required<ElementRef<HTMLCanvasElement>>('front');
  private readonly modulesEl = viewChild.required<ElementRef<HTMLElement>>('modsEl');
  private scene: MeridianScene | null = null;

  readonly modules: Module[] = [
    { title: 'Order Placement', link: '/trade', body: 'Pre-flight checks, instant cost calculation and full order history.' },
    { title: 'Market Data', link: '/dashboard', body: 'Real-time quotes with freshness indicators, so you never trade on stale data.' },
    { title: 'Watchlists', link: '/watchlists', body: 'Price-level alerts by email, SMS or in-app across your whole universe.' },
    { title: 'Portfolio Analytics', link: '/portfolio', body: 'Cost basis, mark-to-market and multi-currency positions in one view.' },
    { title: 'Trade History', link: '/blotter', body: 'Every fill, rejection and cancellation, logged for compliance.' },
    { title: 'Security', link: '/profile', body: 'JWT sessions, encrypted transport and parameterized queries.' }
  ];

  constructor() {
    afterNextRender(() => {
      this.scene = new MeridianScene(this.back().nativeElement, this.front().nativeElement, this.stage().nativeElement, this.host.nativeElement, this.motion.reduced());
      this.scene.start();
    });
    // Pause to a still frame, or spin up again, when the motion button is pressed.
    // The signal is read first, outside the optional chain: while `scene` is still null the chain would
    // short-circuit, Angular would record no dependency, and the effect would never run again.
    effect(() => {
      const reduced = this.motion.reduced();
      this.scene?.setReduced(reduced);
    });

    inject(DestroyRef).onDestroy(() => this.scene?.destroy());
  }

  showModules(): void {
    this.modulesEl().nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}
