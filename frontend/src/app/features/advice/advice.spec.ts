import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { Advice } from './advice';
import { AdviceService } from '../../core/advice/advice.service';
import { AuthService } from '../../core/auth/auth.service';

const signal = (symbol: string, over: any = {}) => ({
  symbol,
  direction: 'BUY',
  strength: 0.72,
  rationale: 'SMA20 crossed above SMA50.',
  summary: 'Short-term momentum has turned positive.',
  computedAt: '2026-10-07T00:00:00Z',
  nextAnalysisAt: '2026-10-08T00:00:00Z',
  stale: false,
  chart: [{ date: '2026-10-07', close: 100, sma20: 98, sma50: 95 }],
  disclaimer: 'This reflects past price movement only, not a recommendation or a prediction. It is not financial advice.',
  ...over
});

describe('Advice', () => {
  let service: any;

  async function render(signals: any[] = [signal('TCS.NS')], over: Partial<Record<string, any>> = {}) {
    service = {
      getSignals: vi.fn().mockReturnValue(of(signals)),
      analyse: vi.fn().mockReturnValue(of(signal('TCS.NS'))),
      ...over
    };
    await TestBed.configureTestingModule({
      imports: [Advice],
      providers: [
        { provide: AdviceService, useValue: service },
        { provide: AuthService, useValue: { getProfile: () => of({ accountId: 4 }), logout: vi.fn() } },
        provideRouter([])
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(Advice);
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: any, id: string) => f.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

  it('loads the account signals on init and renders a card per symbol', async () => {
    const fixture = await render();

    expect(service.getSignals).toHaveBeenCalledWith(4);
    expect(el(fixture, 'advice-direction-TCS.NS')?.textContent).toContain('BUY');
  });

  it('every card carries the not-advice disclaimer', async () => {
    const fixture = await render();

    expect(el(fixture, 'advice-disclaimer')?.textContent).toContain('not a recommendation');
  });

  it('a symbol never analysed shows a NO SIGNAL badge and no rationale', async () => {
    const fixture = await render([signal('AAA', { direction: null, strength: null, rationale: null, computedAt: null, nextAnalysisAt: null })]);

    expect(el(fixture, 'advice-direction-AAA')?.textContent?.trim()).toBe('NO SIGNAL');
  });

  it('Analyse now calls the service and swaps in the returned signal', async () => {
    const fixture = await render([signal('TCS.NS', { direction: null, nextAnalysisAt: null })]);
    service.analyse.mockReturnValue(of(signal('TCS.NS', { direction: 'SELL' })));

    el(fixture, 'advice-analyse-TCS.NS')!.click();
    fixture.detectChanges();

    expect(service.analyse).toHaveBeenCalledWith(4, 'TCS.NS');
    expect(el(fixture, 'advice-direction-TCS.NS')?.textContent).toContain('SELL');
  });

  it('Analyse now opens a modal with the chart, the plain-English summary and the technical rationale', async () => {
    const fixture = await render([signal('TCS.NS', { direction: null, nextAnalysisAt: null })]);
    service.analyse.mockReturnValue(of(signal('TCS.NS')));

    el(fixture, 'advice-analyse-TCS.NS')!.click();
    fixture.detectChanges();

    expect(el(fixture, 'advice-modal')).toBeTruthy();
    expect(el(fixture, 'advice-modal-chart')).toBeTruthy();
    expect(el(fixture, 'advice-modal-summary')?.textContent).toContain('Short-term momentum');
    expect(el(fixture, 'advice-modal-rationale')?.textContent).toContain('SMA20');
    expect(el(fixture, 'advice-modal-disclaimer')?.textContent).toContain('not a recommendation');
  });

  it('closing the modal clears it, and it is not shown before any analysis', async () => {
    const fixture = await render([signal('TCS.NS', { direction: null, nextAnalysisAt: null })]);
    service.analyse.mockReturnValue(of(signal('TCS.NS')));
    expect(el(fixture, 'advice-modal')).toBeFalsy();

    el(fixture, 'advice-analyse-TCS.NS')!.click();
    fixture.detectChanges();
    expect(el(fixture, 'advice-modal')).toBeTruthy();

    el(fixture, 'advice-modal-close')!.click();
    fixture.detectChanges();
    expect(el(fixture, 'advice-modal')).toBeFalsy();
  });

  it('a symbol still inside its cooldown disables Analyse now', async () => {
    const future = new Date(Date.now() + 3_600_000).toISOString();
    const fixture = await render([signal('TCS.NS', { nextAnalysisAt: future })]);

    expect((el(fixture, 'advice-analyse-TCS.NS') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows the empty state when the account holds and watches nothing', async () => {
    const fixture = await render([]);

    expect(el(fixture, 'advice-empty')).toBeTruthy();
  });

  it('a failed load shows an error message', async () => {
    const fixture = await render([], { getSignals: vi.fn().mockReturnValue(throwError(() => ({ status: 500 }))) });

    expect(el(fixture, 'advice-error')).toBeTruthy();
  });
});
