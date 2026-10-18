import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CommandSearch } from './command-search';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';

describe('CommandSearch', () => {
  const instruments = [
    { symbol: 'TCS.NS', name: 'Tata Consultancy Services', currency: 'INR', assetClass: 'EQUITY' },
    { symbol: 'INFY.NS', name: 'Infosys', currency: 'INR', assetClass: 'EQUITY' },
    { symbol: 'AAPL', name: 'Apple Inc.', currency: 'USD', assetClass: 'EQUITY' }
  ];
  let getInstruments: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    getInstruments = vi.fn().mockReturnValue(of(instruments));
    TestBed.configureTestingModule({
      imports: [CommandSearch],
      providers: [provideRouter([]), { provide: WatchlistsService, useValue: { getInstruments } }]
    });
  });

  afterEach(() => vi.restoreAllMocks());

  function render() {
    const fixture = TestBed.createComponent(CommandSearch);
    fixture.detectChanges();
    return fixture;
  }
  const q = (f: any, id: string) => f.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  const labels = (f: any) => [...f.nativeElement.querySelectorAll('[data-testid="command-search-result"] .cmdk__label')].map((e: any) => e.textContent.trim());
  const type = (f: any, text: string) => {
    const input = q(f, 'command-search-input') as HTMLInputElement;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    f.detectChanges();
  };

  it('opens with Ctrl+K and closes with Escape', () => {
    const fixture = render();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }));
    fixture.detectChanges();
    expect(q(fixture, 'command-search')).toBeTruthy();

    (q(fixture, 'command-search-input') as HTMLInputElement).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(q(fixture, 'command-search')).toBeNull();
  });

  it('lists every page before anything is typed, and reads the catalogue only once', () => {
    const fixture = render();
    q(fixture, 'command-search-open')!.click();
    fixture.detectChanges();

    expect(labels(fixture)).toContain('Blotter');
    expect(labels(fixture)).not.toContain('TCS.NS');

    fixture.componentInstance.hide();
    fixture.componentInstance.show();
    expect(getInstruments).toHaveBeenCalledTimes(1);
  });

  it('finds instruments by symbol or company name', () => {
    const fixture = render();
    q(fixture, 'command-search-open')!.click();
    fixture.detectChanges();

    type(fixture, 'infosys');

    expect(labels(fixture)).toEqual(['INFY.NS']);
  });

  it('searches with a regular expression once the .* toggle is on', () => {
    const fixture = render();
    q(fixture, 'command-search-open')!.click();
    fixture.detectChanges();
    q(fixture, 'command-search-regex')!.click();
    fixture.detectChanges();

    type(fixture, String.raw`\.NS$`);

    expect(labels(fixture)).toEqual(['TCS.NS', 'INFY.NS']);
  });

  it('Enter opens the highlighted result: an instrument opens the order ticket with it chosen', () => {
    const fixture = render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    q(fixture, 'command-search-open')!.click();
    fixture.detectChanges();
    type(fixture, 'aapl');

    (q(fixture, 'command-search-input') as HTMLInputElement).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();

    expect(navigate).toHaveBeenCalledWith(['/trade'], { queryParams: { symbol: 'AAPL' } });
    expect(q(fixture, 'command-search')).toBeNull();
  });
});
