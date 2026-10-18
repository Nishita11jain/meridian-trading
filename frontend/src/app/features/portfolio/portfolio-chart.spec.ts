import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('apexcharts', () => ({
  default: class {
    render() { return Promise.resolve(); }
    destroy() {}
    updateOptions() {}
  }
}));

import { PortfolioChart, compactAmount } from './portfolio-chart';
import { PortfolioService } from '../../core/portfolio/portfolio.service';
import { ThemeService } from '../../core/theme/theme.service';

const point = (at: string, value: string | null, invested: string, estimated = false) => ({ at, value, invested, estimated });

const inr = {
  currency: 'INR',
  marketPricesSince: '2026-09-01T00:00:00Z',
  points: [
    point('2026-08-01T00:00:00Z', '90000.00', '100000.00', true),
    point('2026-09-01T00:00:00Z', '105000.00', '100000.00'),
    point('2026-10-01T00:00:00Z', '120000.00', '100000.00')
  ]
};

const usd = { currency: 'USD', points: [point('2026-10-01T00:00:00Z', '500.00', '400.00')] };

describe('PortfolioChart', () => {
  let service: { history: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    service = { history: vi.fn().mockReturnValue(of([inr, usd])) };
    await TestBed.configureTestingModule({
      imports: [PortfolioChart],
      providers: [
        { provide: PortfolioService, useValue: service },
        { provide: ThemeService, useValue: { dark: signal(false) } }
      ]
    }).compileComponents();
  });

  function render(history?: unknown) {
    if (history !== undefined) {
      service.history.mockReturnValue(history as never);
    }
    const fixture = TestBed.createComponent(PortfolioChart);
    fixture.componentRef.setInput('accountId', 4);
    fixture.detectChanges();
    return fixture;
  }

  it('shows the latest value against what was invested, with the overall gain', () => {
    const root: HTMLElement = render().nativeElement;

    expect(root.querySelector('[data-testid="portfolio-chart-value"]')?.textContent).toContain('120,000.00');
    expect(root.querySelector('[data-testid="portfolio-chart-change"]')?.textContent).toContain('+20,000.00');
    expect(root.querySelector('[data-testid="portfolio-chart-change"]')?.textContent).toContain('+20.00%');
    expect(root.querySelector('[data-testid="portfolio-chart-invested"]')?.textContent).toContain('100,000.00');
  });

  it('offers a tab for each currency, and switching shows that currency', () => {
    const fixture = render();
    const tabs = fixture.nativeElement.querySelectorAll('[data-testid="portfolio-chart-currency"]');
    expect(Array.from(tabs as NodeListOf<HTMLElement>).map((t) => t.textContent!.trim())).toEqual(['INR', 'USD']);

    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="portfolio-chart-value"]').textContent).toContain('500.00');
  });

  it('says that values before the platform stored prices are estimated', () => {
    const root: HTMLElement = render().nativeElement;

    expect(root.querySelector('[data-testid="portfolio-chart-estimate-note"]')).not.toBeNull();
  });

  it('shows an empty state with no history, rather than an empty chart', () => {
    const root: HTMLElement = render(of([])).nativeElement;

    expect(root.querySelector('[data-testid="portfolio-chart-empty"]')).not.toBeNull();
  });

  it('shows a plain message when the history cannot be loaded', () => {
    const root: HTMLElement = render(throwError(() => new Error('down'))).nativeElement;

    expect(root.querySelector('[data-testid="portfolio-chart-error"]')?.textContent).toContain('could not be loaded');
  });

  it('abbreviates rupee amounts into thousands, lakhs and crores for the axis', () => {
    expect(compactAmount(12500, 'INR')).toBe('₹13K');
    expect(compactAmount(350000, 'INR')).toBe('₹3.50L');
    expect(compactAmount(25000000, 'INR')).toBe('₹2.50Cr');
    expect(compactAmount(-5000, 'USD')).toBe('-$5.0K');
  });
});
