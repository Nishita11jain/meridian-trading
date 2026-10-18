import { ChartPoint } from '../../clients/advice-client/model/models';

/** The three SVG polylines the Analyse-now chart draws, plus the one crossover it found, if any. */
export interface PlottedChart {
  closePoints: string;
  sma20Points: string;
  sma50Points: string;
  crossover: { x: number; y: number; date: string } | null;
}

const WIDTH = 700;
const HEIGHT = 260;
const PAD_TOP = 20;
const PAD_BOTTOM = 30;

/**
 * Scales a symbol's candle history into SVG coordinates for the Analyse-now chart. All three
 * series share one y-scale (close, SMA20, SMA50), so the lines stay visually comparable; a series
 * only plots the points where it actually has a value, so the averages trail in once the window
 * has enough history, exactly as the backend computed them.
 */
export function plotChart(points: ChartPoint[]): PlottedChart {
  if (points.length === 0) {
    return { closePoints: '', sma20Points: '', sma50Points: '', crossover: null };
  }

  const values: number[] = [];
  for (const p of points) {
    values.push(p.close);
    if (p.sma20 != null) values.push(p.sma20);
    if (p.sma50 != null) values.push(p.sma50);
  }
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min;

  const x = (i: number) => (points.length === 1 ? 0 : (i / (points.length - 1)) * WIDTH);
  const y = (value: number) =>
    span === 0
      ? HEIGHT / 2
      : PAD_TOP + ((max - value) / span) * (HEIGHT - PAD_TOP - PAD_BOTTOM);

  const toPolyline = (pick: (p: ChartPoint) => number | null | undefined) =>
    points
      .map((p, i) => ({ i, v: pick(p) }))
      .filter((p): p is { i: number; v: number } => p.v != null)
      .map((p) => `${x(p.i)},${y(p.v)}`)
      .join(' ');

  return {
    closePoints: toPolyline((p) => p.close),
    sma20Points: toPolyline((p) => p.sma20),
    sma50Points: toPolyline((p) => p.sma50),
    crossover: findCrossover(points, x, y)
  };
}

/** The most recent point where SMA20 and SMA50 swapped which one is on top. */
function findCrossover(
  points: ChartPoint[],
  x: (i: number) => number,
  y: (v: number) => number
): { x: number; y: number; date: string } | null {
  let found: { x: number; y: number; date: string } | null = null;
  for (let i = 1; i < points.length; i++) {
    const prev = points[i - 1];
    const curr = points[i];
    if (prev.sma20 == null || prev.sma50 == null || curr.sma20 == null || curr.sma50 == null) {
      continue;
    }
    const prevDiff = prev.sma20 - prev.sma50;
    const currDiff = curr.sma20 - curr.sma50;
    if (prevDiff === 0 || currDiff === 0) {
      continue;
    }
    if (Math.sign(prevDiff) !== Math.sign(currDiff)) {
      found = { x: x(i), y: y(curr.sma20), date: curr.date };
    }
  }
  return found;
}
