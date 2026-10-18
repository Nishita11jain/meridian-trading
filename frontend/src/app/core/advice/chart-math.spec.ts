import { describe, expect, it } from 'vitest';

import { plotChart } from './chart-math';
import { ChartPoint } from '../../clients/advice-client/model/models';

const point = (date: string, close: number, sma20: number | null = null, sma50: number | null = null): ChartPoint => ({
  date, close, sma20, sma50
});

describe('plotChart', () => {
  it('plots one coordinate per close, and leaves the averages out where they are null', () => {
    const chart = plotChart([point('2026-01-01', 100), point('2026-01-02', 110), point('2026-01-03', 120)]);

    expect(chart.closePoints.split(' ')).toHaveLength(3);
    expect(chart.sma20Points).toBe('');
    expect(chart.sma50Points).toBe('');
  });

  it('finds the crossover where the fast average swaps sides of the slow one', () => {
    const chart = plotChart([
      point('2026-01-01', 100, 90, 95),  // fast below slow
      point('2026-01-02', 101, 96, 95),  // fast above slow -- the cross
      point('2026-01-03', 102, 98, 95)   // still above, not a new cross
    ]);

    expect(chart.crossover).not.toBeNull();
    expect(chart.crossover?.date).toBe('2026-01-02');
  });

  it('finds no crossover when the fast average never swaps sides', () => {
    const chart = plotChart([
      point('2026-01-01', 100, 90, 95),
      point('2026-01-02', 101, 91, 96),
      point('2026-01-03', 102, 92, 97)
    ]);

    expect(chart.crossover).toBeNull();
  });

  it('handles an empty series without throwing', () => {
    expect(plotChart([])).toEqual({ closePoints: '', sma20Points: '', sma50Points: '', crossover: null });
  });

  it('handles a flat series (no price movement) without dividing by zero', () => {
    const chart = plotChart([point('2026-01-01', 100), point('2026-01-02', 100)]);

    expect(chart.closePoints.split(' ')).toHaveLength(2);
    expect(chart.closePoints).not.toContain('NaN');
  });
});
