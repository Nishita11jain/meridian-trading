import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { InstrumentSearch } from './instrument-search';

const catalogue = [
  { symbol: 'TCS.NS', name: 'Tata Consultancy Services Limited', assetClass: 'EQUITY', currency: 'INR', market: 'NSE', tradingStatus: 'OPEN' },
  { symbol: 'TATAMOTORS.NS', name: 'Tata Motors Limited', assetClass: 'EQUITY', currency: 'INR', market: 'NSE', tradingStatus: 'OPEN' },
  { symbol: 'MSFT', name: 'Microsoft Corporation', assetClass: 'EQUITY', currency: 'USD', market: 'US market', tradingStatus: 'OPEN' }
] as any[];

describe('InstrumentSearch', () => {
  async function render(exclude: string[] = []) {
    await TestBed.configureTestingModule({ imports: [InstrumentSearch] }).compileComponents();
    const fixture = TestBed.createComponent(InstrumentSearch);
    fixture.componentRef.setInput('instruments', catalogue);
    fixture.componentRef.setInput('exclude', exclude);
    const picked = vi.fn();
    fixture.componentInstance.picked.subscribe(picked);
    fixture.detectChanges();
    return { fixture, picked };
  }
  function type(fixture: any, text: string) {
    const input = fixture.nativeElement.querySelector('[data-testid="instrument-search"]') as HTMLInputElement;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    return input;
  }
  const options = (fixture: any) =>
    [...fixture.nativeElement.querySelectorAll('[data-testid="instrument-search-option"]')].map((o: any) => o.textContent as string);

  it('finds instruments by company name as well as symbol', async () => {
    const { fixture } = await render();
    type(fixture, 'tata');

    expect(options(fixture).some((o) => o.includes('TCS.NS'))).toBe(true);
    expect(options(fixture).some((o) => o.includes('TATAMOTORS.NS'))).toBe(true);
    expect(options(fixture).some((o) => o.includes('MSFT'))).toBe(false);
  });

  it('leaves out instruments already chosen', async () => {
    const { fixture } = await render(['TCS.NS']);
    type(fixture, 'tata');

    expect(options(fixture).some((o) => o.includes('TCS.NS'))).toBe(false);
  });

  it('Enter picks the first match and clears the box', async () => {
    const { fixture, picked } = await render();
    const input = type(fixture, 'ms');

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    fixture.detectChanges();

    expect(picked).toHaveBeenCalledWith('MSFT');
    expect(input.value).toBe('');
  });

  it('arrow keys choose an option, and it is announced as active', async () => {
    const { fixture, picked } = await render();
    const input = type(fixture, 'tata');

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true }));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true }));
    fixture.detectChanges();
    expect(input.getAttribute('aria-activedescendant')).toBe('instrument-search-opt-1');

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    expect(picked).toHaveBeenCalledTimes(1);
  });

  it('says when nothing matches', async () => {
    const { fixture } = await render();
    type(fixture, 'zzz');

    expect(fixture.nativeElement.querySelector('[data-testid="instrument-search-none"]').textContent).toContain('zzz');
  });
});
