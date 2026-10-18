import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { SignIn } from './sign-in';

describe('SignIn: idle message, show/hide password, caps lock', () => {
  let route: { snapshot: { queryParamMap: ReturnType<typeof convertToParamMap> } };

  beforeEach(async () => {
    route = { snapshot: { queryParamMap: convertToParamMap({}) } };
    await TestBed.configureTestingModule({
      imports: [SignIn],
      providers: [
        { provide: AuthService, useValue: { login: vi.fn() } },
        { provide: Router, useValue: { navigate: vi.fn(), navigateByUrl: vi.fn() } },
        { provide: ActivatedRoute, useValue: route }
      ]
    }).compileComponents();
  });

  function render() {
    const fixture = TestBed.createComponent(SignIn);
    fixture.detectChanges();
    return fixture;
  }
  const el = (fixture: any, id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

  it('explains an idle sign-out', () => {
    route.snapshot.queryParamMap = convertToParamMap({ reason: 'idle' });

    expect(render().componentInstance.errorMessage()).toContain('without activity');
  });

  it('shows no message for an unknown reason', () => {
    route.snapshot.queryParamMap = convertToParamMap({ reason: 'whatever' });

    expect(render().componentInstance.errorMessage()).toBe('');
  });

  it('the password is hidden until the user asks to see it, and can be hidden again', () => {
    const fixture = render();
    const input = el(fixture, 'signin-password') as HTMLInputElement;
    const toggle = el(fixture, 'signin-toggle-password') as HTMLButtonElement;

    expect(input.type).toBe('password');
    expect(toggle.textContent).toContain('Show password');

    toggle.click();
    fixture.detectChanges();
    expect(input.type).toBe('text');
    expect(toggle.textContent).toContain('Hide password');
    expect(toggle.getAttribute('aria-pressed')).toBe('true');

    toggle.click();
    fixture.detectChanges();
    expect(input.type).toBe('password');
  });

  it('the toggle does not submit the form', () => {
    const fixture = render();

    expect((el(fixture, 'signin-toggle-password') as HTMLButtonElement).type).toBe('button');
  });

  it('warns while caps lock is on and the password field has focus, and not otherwise', () => {
    const fixture = render();
    const input = el(fixture, 'signin-password') as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { modifierCapsLock: true }));
    fixture.detectChanges();
    expect(el(fixture, 'signin-caps-lock')?.textContent).toContain('Caps Lock is on');

    input.dispatchEvent(new KeyboardEvent('keyup', { modifierCapsLock: false }));
    fixture.detectChanges();
    expect(el(fixture, 'signin-caps-lock')).toBeNull();

    input.dispatchEvent(new KeyboardEvent('keydown', { modifierCapsLock: true }));
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(el(fixture, 'signin-caps-lock')).toBeNull();
  });
});
