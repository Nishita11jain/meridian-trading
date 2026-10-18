import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
vi.mock('apexcharts', () => ({ default: class { render() { return Promise.resolve(); } destroy() {} } }));

import { PriceTrend } from './price-trend';
import { WatchlistsService } from '../../core/watchlists/watchlists.service';

const tick = (price: number, quoteAsOf: string) => ({
  symbol: 'TCS.NS',
  lastPrice: price,
  bid: null,
  ask: null,
  currency: 'INR',
  change: null,
  changePercent: null,
  previousClose: null,
  marketState: 'open',
  stale: false,
  quoteAsOf,
  openPrice: price - 5,
  highPrice: price + 8,
  lowPrice: price - 12
});

describe('PriceTrend', () => {
  async function render() {
    const service = {
      getHistory: vi.fn().mockReturnValue(of([
        tick(3530, '2026-10-01T09:35:00Z'),
        tick(3520, '2026-10-01T09:30:00Z'),
        tick(3510, '2026-10-01T09:25:00Z')
      ]))
    };

    await TestBed.configureTestingModule({
      imports: [PriceTrend],
      providers: [{ provide: WatchlistsService, useValue: service }]
    }).compileComponents();

    const fixture = TestBed.createComponent(PriceTrend);
    fixture.componentRef.setInput('symbol', 'TCS.NS');
    fixture.detectChanges();
    return { fixture, service };
  }

  const all = (f: any, id: string) => [...f.nativeElement.querySelectorAll(`[data-testid="${id}"]`)] as HTMLElement[];

  it('loads 5D history by default for the selected symbol', async () => {
    const { fixture, service } = await render();

    expect(service.getHistory).toHaveBeenCalledWith('TCS.NS', '5D');
    expect(fixture.componentInstance.hasCandles()).toBe(true);
  });

  it('changes the cache history range when a range button is selected', async () => {
    const { fixture, service } = await render();

    const oneYear = all(fixture, 'price-trend-range').find((b) => b.textContent?.trim() === '1Y') as HTMLButtonElement;
    oneYear.click();
    fixture.detectChanges();

    expect(service.getHistory).toHaveBeenCalledWith('TCS.NS', '1Y');
  });
});
