import { afterNextRender, Component, DestroyRef, effect, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { messageFor } from '../../core/errors/error-messages';
import { safeReturnPath } from '../../core/guards/return-url';
import { MotionService } from '../../core/motion/motion.service';
import { ThemeService } from '../../core/theme/theme.service';
import { DisplayCurrencyService } from '../../core/currency/display-currency.service';
import { PreferencesService } from '../../core/preferences/preferences.service';
import { SessionStore } from '../../core/auth/session.store';
import { Theme } from '../../clients/preferences-client/model/models';
import { switchMap, of, catchError } from 'rxjs';
import { MeridianScene } from '../landing/meridian-scene';

/**
 * SEC2-688: Sign-in screen component (standalone, Angular 21 style)
 * 
 * Presents a login form with username and password fields.
 * On successful authentication, navigates to dashboard.
 * On error, displays the error message and clears the password field.
 * 
 * Every value the template reads must be a signal (@signal).
 * Observables from HttpClient are handled here; no Observable callbacks update the view.
 * Button is never disabled; instead, we mark fields touched on submit and show validation messages.
 */
@Component({
  selector: 'app-sign-in',
  imports: [ReactiveFormsModule],
  templateUrl: './sign-in.html',
  // `mer` gives the page the landing page's palette and type (landing.css); layout is in sign-in.css.
  host: { class: 'mer mer-auth' }
})
export class SignIn {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly preferencesService = inject(PreferencesService);
  private readonly sessionStore = inject(SessionStore);
  protected readonly theme = inject(ThemeService);
  private readonly displayCurrency = inject(DisplayCurrencyService);
  protected readonly motion = inject(MotionService);

  // Reference to password input for focus management
  passwordInput = viewChild<ElementRef<HTMLInputElement>>('passwordInput');

  // Signals for template state (every value the template reads must be a signal)
  readonly submitting = signal(false);
  // The interceptor sends a user here with ?reason=expired when a protected call returned 401,
  // so they are told why rather than landing on an unexplained sign-in screen.
  readonly errorMessage = signal(this.reasonMessage());
  // Password visibility and the caps-lock hint (the hint is only shown while the field has focus).
  readonly showPassword = signal(false);
  readonly capsLockOn = signal(false);

  // Form with reactive validators (username: max 64, password: max 128)
  readonly form = this.fb.group({
    username: ['', [Validators.required, Validators.maxLength(64)]],
    password: ['', [Validators.required, Validators.maxLength(128)]]
  });

  // The same globe as the landing page, so the two screens read as one place.
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly stage = viewChild<ElementRef<HTMLElement>>('stage');
  private readonly back = viewChild<ElementRef<HTMLCanvasElement>>('back');
  private readonly front = viewChild<ElementRef<HTMLCanvasElement>>('front');
  private scene: MeridianScene | null = null;

  constructor() {
    afterNextRender(() => {
      const stage = this.stage(), back = this.back(), front = this.front();
      if (!stage || !back || !front) return;
      this.scene = new MeridianScene(back.nativeElement, front.nativeElement, stage.nativeElement, this.host.nativeElement, this.motion.reduced());
      this.scene.start();
    });
    // The globe spins up while credentials are being checked.
    // Signals are read before the optional chain so the effects track them even while `scene` is null.
    effect(() => {
      const busy = this.submitting();
      this.scene?.setBusy(busy);
    });
    effect(() => {
      const reduced = this.motion.reduced();
      this.scene?.setReduced(reduced);
    });
    inject(DestroyRef).onDestroy(() => this.scene?.destroy());
  }

  /** Back to the landing page inside the app, so the page cross-fades instead of reloading. */
  goHome(event: MouseEvent): void {
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) return;
    event.preventDefault();
    this.router.navigateByUrl('/');
  }

  private reasonMessage(): string {
    switch (this.route.snapshot.queryParamMap.get('reason')) {
      case 'expired':
        return 'Your session has expired. Please sign in again.';
      case 'idle':
        return 'You were signed out after 15 minutes without activity. Please sign in again.';
      default:
        return '';
    }
  }

  togglePasswordVisibility(): void {
    this.showPassword.update((shown) => !shown);
  }

  /** getModifierState is the only reliable way to read caps lock; it is not available on synthetic events. */
  updateCapsLock(event: KeyboardEvent): void {
    this.capsLockOn.set(event.getModifierState?.('CapsLock') === true);
  }

  private resolveSafeReturnPath(): string {
    return safeReturnPath(this.route.snapshot.queryParamMap.get('returnUrl'));
  }

  /**
   * Handle form submission.
   * If form is invalid, mark all fields as touched and show generic error.
   * If form is valid, call authService.login(), fetch and apply preferences, then navigate on success.
   */
  onSubmit(): void {
    if (this.form.invalid) {
      // Mark fields as touched so validation messages appear
      this.form.markAllAsTouched();
      this.errorMessage.set('Enter your username and password');
      return;
    }

    this.errorMessage.set('');
    this.submitting.set(true);

    const { username, password } = this.form.getRawValue();
    this.authService.login(username!, password!).pipe(
      switchMap(() => {
        // After successful login, extract accountId and fetch preferences
        const token = this.sessionStore.accessToken();
        if (!token) {
          return of(null); // No token, use defaults
        }
        
        // Extract accountId from token (JWT payload, second part)
        try {
          const payload = JSON.parse(atob(token.split('.')[1]));
          const accountId = payload.accountId || payload.sub;
          
          if (!accountId) {
            return of(null); // No accountId claim, use defaults
          }
          
          return this.preferencesService.getPreferences(accountId).pipe(
            catchError(() => {
              // Preferences not found or service error; use defaults
              console.warn('Could not fetch preferences; using defaults');
              return of(null);
            })
          );
        } catch (e) {
          console.error('Failed to extract accountId from token:', e);
          return of(null);
        }
      }),
      catchError((err) => {
        // Login failed
        this.submitting.set(false);
        this.errorMessage.set(messageFor(err));
        this.form.patchValue({ password: '' });
        
        // Focus password input for retry (async so DOM is updated)
        setTimeout(() => {
          this.passwordInput()?.nativeElement.focus();
        }, 0);
        
        throw err; // Prevent navigation
      })
    ).subscribe({
      next: (preferences) => {
        // Apply preferences if available
        if (preferences) {
          // Apply theme from preferences (Theme.Dark = 'DARK', Theme.Light = 'LIGHT')
          if (preferences.theme) {
            this.theme.set(preferences.theme === Theme.Dark);
          }
          // The default account and display currency are stored and editable on the preferences page, but
          // nothing here applies them: a sign-in is for one account, and the API refuses any other with
          // ACC-403. They were once copied into sessionStorage keys that no code read (decision log 0013).
        }
        
        // The next page loads this customer's own display currency, not the previous one's.
        this.displayCurrency.reset();
        // Clear form and navigate on success
        this.submitting.set(false);
        this.form.reset();
        // replaceUrl: the sign-in page must not stay in the Back history of a signed-in user.
        this.router.navigateByUrl(this.resolveSafeReturnPath(), { replaceUrl: true });
      },
      error: (err) => {
        // This is already handled in the catchError above, but we need the error handler for the observable chain
        // If we get here, it means an unexpected error occurred after preferences were fetched
        this.submitting.set(false);
        console.error('Unexpected error during sign-in:', err);
      }
    });
  }
}
