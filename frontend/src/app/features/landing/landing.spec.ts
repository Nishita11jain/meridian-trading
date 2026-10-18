import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { Landing } from './landing';

describe('Landing', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.removeItem('meridian-motion');
  });
  afterEach(() => {
    sessionStorage.clear();
    localStorage.removeItem('meridian-motion');
    document.documentElement.classList.remove('reduce-motion');
  });

  async function render() {
    await TestBed.configureTestingModule({
      imports: [Landing],
      providers: [provideRouter([])]
    }).compileComponents();
    const fixture = TestBed.createComponent(Landing);
    fixture.detectChanges();
    return fixture;
  }
  const el = (f: any, id: string) => f.nativeElement.querySelector(`[data-testid="${id}"]`);
  const all = (f: any, id: string) => f.nativeElement.querySelectorAll(`[data-testid="${id}"]`);

  it('shows the hero, feature cards and a sign-in call to action for a visitor', async () => {
    const fixture = await render();

    expect(el(fixture, 'landing-eyebrow')).toBeTruthy();
    expect(all(fixture, 'landing-feature').length).toBeGreaterThanOrEqual(6);
    expect(el(fixture, 'landing-signin')).toBeTruthy();
    expect(el(fixture, 'landing-cta').textContent).toContain('Get started');
    expect(el(fixture, 'landing-dashboard')).toBeNull();
  });

  it('offers a signed-in visitor a link to the dashboard instead of sign-in', async () => {
    sessionStorage.setItem('tokens', JSON.stringify({ accessToken: 'a', refreshToken: 'r' }));

    const fixture = await render();

    expect(el(fixture, 'landing-dashboard')).toBeTruthy();
    expect(el(fixture, 'landing-signin')).toBeNull();
    expect(el(fixture, 'landing-cta').textContent).toContain('dashboard');
  });

  it('has a pause/play button for animations that flips the motion setting', async () => {
    const fixture = await render();
    const button = el(fixture, 'landing-motion-toggle') as HTMLButtonElement;
    const before = button.getAttribute('aria-label');

    button.click();
    fixture.detectChanges();

    expect(button.getAttribute('aria-label')).not.toBe(before);
    expect(['Play animations', 'Pause animations']).toContain(before);
  });

  it('has a theme toggle', async () => {
    const fixture = await render();

    expect(el(fixture, 'landing-theme-toggle')).toBeTruthy();
  });
});
