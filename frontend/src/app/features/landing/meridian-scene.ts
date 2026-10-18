/**
 * The landing page's rotating body: a dotted globe with order arcs between real exchanges, wrapped in a
 * spiral of 72 daily candles. It draws on two canvases, one behind the headline and one in front of it,
 * so candles on the near side of the spiral pass over the words and the rest pass behind them.
 *
 * Plain Canvas 2D, no dependencies. It drives two CSS custom properties on the stage that tie the
 * headline to the motion: `--rev` (0..1, the intro fill as the globe spins up) and `--sweep` /
 * `--sweep2` (0..1, a band of light that follows the rotation across each word).
 *
 * Every browser API it touches is feature-checked, so it is inert under jsdom in unit tests.
 */

type Vec3 = [number, number, number];
type Projected = [number, number, number, number]; // screen x, screen y, depth z, perspective scale

interface Colors { bg: string; fg: string; accent: string; warm: string; up: string; down: string; stars: boolean; }
interface Candle { o: number; cl: number; hi: number; lo: number; }
interface Arc { out: Vec3[]; ph: number; }
interface Exchange { n: string; v: Vec3; }

const TAU = Math.PI * 2;
const CRUISE = 0.0009;

function rot(x: number, y: number, z: number, ay: number, ax: number): Vec3 {
  const ca = Math.cos(ay), sa = Math.sin(ay);
  const X = x * ca + z * sa, Z = -x * sa + z * ca;
  const cb = Math.cos(ax), sb = Math.sin(ax);
  return [X, y * cb - Z * sb, y * sb + Z * cb];
}

function latLon(lat: number, lon: number): Vec3 {
  const a = lat * Math.PI / 180, b = lon * Math.PI / 180;
  return [Math.cos(a) * Math.sin(b), Math.sin(a), Math.cos(a) * Math.cos(b)];
}

/** `#RRGGBB` plus an alpha, as `#RRGGBBAA`. */
function alpha(hex: string, a: number): string {
  return hex.slice(0, 7) + Math.round(Math.max(0, Math.min(1, a)) * 255).toString(16).padStart(2, '0');
}

const easeOut = (x: number) => 1 - Math.pow(1 - x, 3);

export class MeridianScene {
  private readonly backCtx: CanvasRenderingContext2D | null;
  private readonly frontCtx: CanvasRenderingContext2D | null;
  private reduce: boolean;

  private colors: Colors = { bg: '#0A0B1D', fg: '#ECE9FA', accent: '#A99BE8', warm: '#E6B48A', up: '#A99BE8', down: '#E6B48A', stars: true };
  private dpr = 1;
  private ang = 0;
  private vel = 0.02; // starts fast and settles to the cruise speed: the intro spin-up
  private cruise = CRUISE;
  private tilt = 0;
  private tiltTarget = 0;
  private born = 0;
  private last = 0;
  private frame = 0;
  private resizeFrame = 0;
  private visible = true;
  private drag: { x: number; y: number } | null = null;
  private readonly cleanups: Array<() => void> = [];

  private readonly dots: Vec3[] = [];
  private readonly exchanges: Exchange[];
  private readonly arcs: Arc[];
  private readonly stars: Array<[number, number, number, number]>;
  private readonly candles: Candle[] = [];

  constructor(
    private readonly back: HTMLCanvasElement,
    private readonly front: HTMLCanvasElement,
    private readonly stage: HTMLElement,
    private readonly themeHost: HTMLElement,
    reduced = false
  ) {
    this.backCtx = safeContext(back);
    this.frontCtx = safeContext(front);
    this.reduce = reduced;

    // Land: a Fibonacci sphere, kept where a smooth noise field is high, so it reads as continents.
    const n = 4200, ga = Math.PI * (3 - Math.sqrt(5));
    for (let i = 0; i < n; i++) {
      const y = 1 - (i / (n - 1)) * 2, r = Math.sqrt(1 - y * y), th = ga * i;
      const x = Math.cos(th) * r, z = Math.sin(th) * r;
      const lat = Math.asin(y), lon = Math.atan2(x, z);
      const f = Math.sin(lon * 2 + 1.3) * Math.cos(lat * 2.2) + 0.65 * Math.sin(lon * 5 - lat * 3 + 2) + 0.45 * Math.cos(lat * 7 + lon * 3) - Math.abs(lat) * 0.5;
      if (f > 0.25) this.dots.push([x, y, z]);
    }

    const venues: Array<[string, number, number]> = [
      ['NYSE', 40.7, -74], ['LSE', 51.5, -0.1], ['XETRA', 50.1, 8.7], ['NSE', 19.07, 72.88],
      ['SGX', 1.35, 103.8], ['HKEX', 22.3, 114.2], ['TSE', 35.68, 139.7]
    ];
    this.exchanges = venues.map(([name, a, b]) => ({ n: name, v: latLon(a, b) }));

    this.arcs = [[0, 1], [1, 3], [3, 5], [5, 6], [2, 4], [0, 3]].map(([a, b], i) => {
      const A = this.exchanges[a].v, B = this.exchanges[b].v;
      const d = Math.acos(Math.min(1, A[0] * B[0] + A[1] * B[1] + A[2] * B[2])), sn = Math.sin(d);
      const out: Vec3[] = [];
      for (let k = 0; k <= 40; k++) {
        const t = k / 40, w1 = Math.sin((1 - t) * d) / sn, w2 = Math.sin(t * d) / sn, h = 1 + 0.18 * d * Math.sin(Math.PI * t);
        out.push([(A[0] * w1 + B[0] * w2) * h, (A[1] * w1 + B[1] * w2) * h, (A[2] * w1 + B[2] * w2) * h]);
      }
      return { out, ph: i * 0.41 };
    });

    let seed = 11;
    const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
    this.stars = Array.from({ length: 110 }, () => [rnd(), rnd(), rnd() * 1.2 + 0.3, rnd() * TAU] as [number, number, number, number]);
    let p = 100;
    for (let i = 0; i < 72; i++) {
      const o = p, cl = o * (1 + (rnd() - 0.48) * 0.05);
      this.candles.push({ o, cl, hi: Math.max(o, cl) * (1 + rnd() * 0.02), lo: Math.min(o, cl) * (1 - rnd() * 0.02) });
      p = cl;
    }
  }

  start(): void {
    if (!this.backCtx || !this.frontCtx) {
      this.setTextVars(1, 0);
      return;
    }
    this.readColors();
    this.resize();
    this.born = now();
    if (this.reduce) this.vel = 0;

    if (typeof ResizeObserver === 'function') {
      const ro = new ResizeObserver(() => this.queueResize());
      ro.observe(this.stage);
      this.cleanups.push(() => ro.disconnect());
    }
    // Pause when the stage is scrolled away or the tab is hidden.
    if (typeof IntersectionObserver === 'function') {
      const io = new IntersectionObserver(([e]) => { this.visible = e.isIntersecting; this.schedule(); });
      io.observe(this.stage);
      this.cleanups.push(() => io.disconnect());
    }
    this.listen(document, 'visibilitychange', () => this.schedule());
    // Follow the theme class itself: ThemeService may apply it a moment later, inside a view transition.
    if (typeof MutationObserver === 'function') {
      const mo = new MutationObserver(() => this.refreshColors());
      mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
      this.cleanups.push(() => mo.disconnect());
    }

    this.listen(this.stage, 'pointerdown', (e: Event) => {
      const pe = e as PointerEvent;
      this.drag = { x: pe.clientX, y: pe.clientY };
      this.stage.setPointerCapture?.(pe.pointerId);
    });
    this.listen(this.stage, 'pointermove', (e: Event) => {
      if (!this.drag) return;
      const pe = e as PointerEvent, dx = pe.clientX - this.drag.x, dy = pe.clientY - this.drag.y;
      this.vel = this.vel * 0.6 + dx * 0.00016;
      this.ang += dx * 0.008;
      this.tiltTarget = Math.max(-0.5, Math.min(0.5, this.tiltTarget + dy * 0.004));
      this.drag = { x: pe.clientX, y: pe.clientY };
      this.paintIfIdle();
    });
    const end = () => { this.drag = null; };
    this.listen(this.stage, 'pointerup', end);
    this.listen(this.stage, 'pointercancel', end);

    this.paint(now());
    this.schedule();
  }

  /** Pause to a still frame, or start turning again (with a short spin-up), when the motion setting changes. */
  setReduced(reduced: boolean): void {
    if (reduced === this.reduce) return;
    this.reduce = reduced;
    if (!this.backCtx) return;
    if (reduced) {
      if (this.frame && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(this.frame);
      this.frame = 0;
      this.vel = 0;
      this.tilt = this.tiltTarget;
      this.paint(now());
    } else {
      this.vel = 0.02;
      this.born = now();
      this.schedule();
    }
  }

  /** Spin faster while something is in progress (sign-in), easing back to the cruise speed after. */
  setBusy(busy: boolean): void {
    this.cruise = busy ? CRUISE * 6 : CRUISE;
  }

  /** Re-read the palette after a theme change. */
  refreshColors(): void {
    if (!this.backCtx) return;
    this.readColors();
    this.paintIfIdle();
  }

  destroy(): void {
    if (typeof cancelAnimationFrame === 'function') {
      if (this.frame) cancelAnimationFrame(this.frame);
      if (this.resizeFrame) cancelAnimationFrame(this.resizeFrame);
    }
    this.frame = 0;
    this.resizeFrame = 0;
    this.cleanups.splice(0).forEach(fn => fn());
  }

  // ------------------------------------------------------------------ loop

  private schedule(): void {
    const running = !this.reduce && this.visible && document.visibilityState !== 'hidden';
    if (running && !this.frame && typeof requestAnimationFrame === 'function') {
      this.last = now();
      this.frame = requestAnimationFrame(this.tick);
    } else if (!running && this.frame) {
      cancelAnimationFrame(this.frame);
      this.frame = 0;
    }
  }

  private readonly tick = (t: number) => {
    this.frame = 0;
    const dt = Math.min(50, t - this.last);
    this.last = t;
    this.vel += (this.cruise - this.vel) * (1 - Math.exp(-dt / 900));
    this.ang += this.vel * dt * 0.1;
    this.tilt += (this.tiltTarget - this.tilt) * (1 - Math.exp(-dt / 140));
    this.paint(t);
    this.schedule();
  };

  /** With reduced motion there is no loop; draw a single still frame on demand instead. */
  private paintIfIdle(): void {
    if (this.frame) return;
    this.tilt = this.tiltTarget;
    this.paint(now());
  }

  private listen(target: EventTarget, type: string, fn: EventListener): void {
    target.addEventListener(type, fn);
    this.cleanups.push(() => target.removeEventListener(type, fn));
  }

  /**
   * Resizing from inside the ResizeObserver callback makes the browser report "ResizeObserver loop
   * completed with undelivered notifications" (Angular logs it as an error), so the work is moved
   * to the next animation frame, outside the observer's delivery.
   */
  private queueResize(): void {
    if (this.resizeFrame || typeof requestAnimationFrame !== 'function') return;
    this.resizeFrame = requestAnimationFrame(() => {
      this.resizeFrame = 0;
      if (this.resize()) this.paintIfIdle();
    });
  }

  /** Match the canvases to the stage. Returns false when nothing changed, so there is nothing to repaint. */
  private resize(): boolean {
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const w = Math.round(this.stage.clientWidth * dpr), h = Math.round(this.stage.clientHeight * dpr);
    const changed = dpr !== this.dpr || this.back.width !== w || this.back.height !== h;
    this.dpr = dpr;
    for (const c of [this.back, this.front]) {
      if (c.width !== w) c.width = w;
      if (c.height !== h) c.height = h;
    }
    return changed;
  }

  private readColors(): void {
    const cs = getComputedStyle(this.themeHost), v = (n: string, fb: string) => cs.getPropertyValue(n).trim() || fb;
    const c = this.colors;
    this.colors = {
      bg: v('--mer-bg', c.bg), fg: v('--mer-fg', c.fg), accent: v('--mer-accent', c.accent), warm: v('--mer-warm', c.warm),
      up: v('--mer-up', c.up), down: v('--mer-down', c.down), stars: v('--mer-stars', '1') === '1'
    };
  }

  private setTextVars(rev: number, sweep: number): void {
    const s = this.stage.style;
    s.setProperty('--rev', rev.toFixed(3));
    s.setProperty('--sweep', sweep.toFixed(4));
    s.setProperty('--sweep2', ((sweep + 0.5) % 1).toFixed(4));
  }

  // ------------------------------------------------------------------ drawing

  private paint(t: number): void {
    const back = this.backCtx, front = this.frontCtx;
    if (!back || !front) return;
    const w = this.back.width, h = this.back.height;
    if (!w || !h) return;
    back.clearRect(0, 0, w, h);
    front.clearRect(0, 0, w, h);
    const time = this.reduce ? 0 : t;
    this.draw(back, w, h, time, 'back');
    this.draw(front, w, h, time, 'front');

    const rev = this.reduce ? 1 : easeOut(Math.min(1, Math.max(0, (t - this.born) / 2800)));
    this.setTextVars(rev, ((this.ang / (TAU * 0.3)) % 1 + 1) % 1);
  }

  private draw(ctx: CanvasRenderingContext2D, w: number, h: number, t: number, layer: 'back' | 'front'): void {
    const C = this.colors, dpr = this.dpr;
    const R = Math.min(w, h * 1.05) * 0.225, cx = w / 2, cy = h / 2, ax = 0.26 + this.tilt;
    const gy = this.ang * 0.55 + 1.9, hy = this.ang, HR = 1.5, H = 0.78;
    const proj = (x: number, y: number, z: number, ay: number): Projected => {
      const [X, Y, Z] = rot(x, y, z, ay, ax), k = 3.4 / (3.4 - Z);
      return [cx + X * R * k, cy - Y * R * k, Z, k];
    };

    // Candle spiral. Each layer draws its own half: behind the centre plane, or in front of it.
    const all = this.candles.map((d, i) => {
      const th = i * 0.19, yb = H - i * (2 * H / 71), x = Math.cos(th) * HR, z = Math.sin(th) * HR;
      const yy = (v: number) => yb + (v / d.o - 1) * 3.2;
      const top = proj(x, yy(d.hi), z, hy);
      return {
        d, z: top[2], top, bot: proj(x, yy(d.lo), z, hy), o: proj(x, yy(d.o), z, hy), c: proj(x, yy(d.cl), z, hy),
        mine: (layer === 'front') === (top[2] >= 0)
      };
    });
    const mine = all.filter(k => k.mine).sort((a, b) => a.z - b.z);
    const drawCandles = () => {
      ctx.lineWidth = dpr;
      ctx.strokeStyle = C.accent;
      for (let i = 0; i < all.length - 1; i++) {
        const a = all[i], b = all[i + 1];
        if (!a.mine) continue;
        ctx.globalAlpha = 0.08 + 0.26 * ((a.z + HR) / (2 * HR));
        ctx.beginPath(); ctx.moveTo(a.c[0], a.c[1]); ctx.lineTo(b.c[0], b.c[1]); ctx.stroke();
      }
      for (const k of mine) {
        const up = k.d.cl >= k.d.o, col = up ? C.up : C.down, wd = 7.5 * k.top[3] * dpr;
        ctx.globalAlpha = 0.22 + 0.63 * ((k.z + HR) / (2 * HR));
        ctx.strokeStyle = col; ctx.fillStyle = col; ctx.lineWidth = 1.2 * dpr;
        ctx.beginPath(); ctx.moveTo(k.top[0], k.top[1]); ctx.lineTo(k.bot[0], k.bot[1]); ctx.stroke();
        const y0 = Math.min(k.o[1], k.c[1]), hh = Math.max(2 * dpr, Math.abs(k.o[1] - k.c[1]));
        if (up) { ctx.fillStyle = C.bg; ctx.fillRect(k.o[0] - wd / 2, y0, wd, hh); ctx.strokeRect(k.o[0] - wd / 2, y0, wd, hh); }
        else ctx.fillRect(k.o[0] - wd / 2, y0, wd, hh);
      }
      ctx.globalAlpha = 1;
    };

    if (layer === 'front') { drawCandles(); return; }

    // Back layer: sky, the far half of the spiral, then the globe over it.
    if (C.stars) {
      ctx.fillStyle = C.fg;
      for (const [sx, sy, sr, ph] of this.stars) {
        ctx.globalAlpha = 0.05 + 0.17 * (0.5 + 0.5 * Math.sin(t * 0.0009 + ph));
        ctx.beginPath(); ctx.arc(sx * w, sy * h, sr * dpr, 0, TAU); ctx.fill();
      }
    }
    ctx.globalAlpha = 1;
    drawCandles();

    // Atmosphere: a lilac halo, with warm sunlight on the upper-left limb.
    let g = ctx.createRadialGradient(cx, cy, R * 0.96, cx, cy, R * 1.34);
    g.addColorStop(0, alpha(C.accent, 0.28)); g.addColorStop(0.3, alpha(C.accent, 0.07)); g.addColorStop(1, alpha(C.accent, 0));
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, R * 1.34, 0, TAU); ctx.fill();
    ctx.save();
    ctx.beginPath(); ctx.arc(cx, cy, R * 1.18, 0, TAU); ctx.arc(cx, cy, R * 0.99, 0, TAU, true); ctx.clip();
    g = ctx.createRadialGradient(cx - R * 0.75, cy - R * 0.75, 0, cx - R * 0.75, cy - R * 0.75, R * 1.5);
    g.addColorStop(0, alpha(C.warm, 0.42)); g.addColorStop(0.55, alpha(C.warm, 0.07)); g.addColorStop(1, alpha(C.warm, 0));
    ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);
    ctx.restore();

    // Opaque planet body, so the far side of the spiral is hidden behind it.
    ctx.fillStyle = C.bg; ctx.beginPath(); ctx.arc(cx, cy, R, 0, TAU); ctx.fill();
    g = ctx.createRadialGradient(cx - R * 0.4, cy - R * 0.4, R * 0.1, cx, cy, R);
    g.addColorStop(0, alpha(C.accent, 0.16)); g.addColorStop(1, alpha(C.accent, 0.02));
    ctx.fillStyle = g; ctx.fill();

    // Land, lit warm toward the sun and lilac into the night.
    g = ctx.createLinearGradient(cx - R, cy - R, cx + R * 0.8, cy + R * 0.8);
    g.addColorStop(0, C.warm); g.addColorStop(0.38, C.fg); g.addColorStop(0.6, C.accent); g.addColorStop(1, C.accent);
    ctx.fillStyle = g;
    for (const p of this.dots) {
      const [x, y, z] = proj(p[0], p[1], p[2], gy);
      if (z < -0.2) continue;
      ctx.globalAlpha = z < 0 ? 0.06 : 0.3 + 0.7 * z;
      const sz = (z < 0 ? 1 : 1.8) * dpr;
      ctx.fillRect(x - sz / 2, y - sz / 2, sz, sz);
    }

    // Orders travelling between exchanges.
    ctx.lineCap = 'round';
    for (const arc of this.arcs) {
      const u = (t * 0.00018 + arc.ph) % 1.6, head = Math.min(1, u), tail = Math.max(0, u - 0.45);
      if (tail >= 1) continue;
      const pr = arc.out.map(v => proj(v[0], v[1], v[2], gy));
      const i0 = Math.floor(tail * 40), i1 = Math.floor(head * 40);
      for (let i = i0; i < i1; i++) {
        const a = pr[i], b = pr[i + 1];
        if (a[2] < 0) continue;
        const f = (i - i0) / (i1 - i0 + 1);
        ctx.strokeStyle = f > 0.75 ? C.warm : C.accent; ctx.globalAlpha = f; ctx.lineWidth = 1.6 * dpr;
        ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke();
      }
      const hp = pr[i1];
      if (u < 1 && hp && hp[2] > 0) {
        ctx.globalAlpha = 1; ctx.fillStyle = C.warm; ctx.shadowColor = C.warm; ctx.shadowBlur = 9 * dpr;
        ctx.beginPath(); ctx.arc(hp[0], hp[1], 2.2 * dpr, 0, TAU); ctx.fill();
        ctx.shadowBlur = 0;
      }
    }

    ctx.font = `${10 * dpr}px "JetBrains Mono", monospace`;
    for (const e of this.exchanges) {
      const [x, y, z] = proj(e.v[0], e.v[1], e.v[2], gy);
      if (z < 0.1) continue;
      const pulse = (t * 0.001 + e.v[0] + 1) % 1;
      ctx.globalAlpha = z; ctx.fillStyle = C.warm; ctx.beginPath(); ctx.arc(x, y, 2.6 * dpr, 0, TAU); ctx.fill();
      ctx.globalAlpha = z * (1 - pulse); ctx.strokeStyle = C.warm; ctx.lineWidth = dpr;
      ctx.beginPath(); ctx.arc(x, y, (3 + 11 * pulse) * dpr, 0, TAU); ctx.stroke();
      ctx.globalAlpha = z * 0.55; ctx.fillStyle = C.fg; ctx.fillText(e.n, x + 7 * dpr, y - 5 * dpr);
    }
    ctx.globalAlpha = 1;
  }
}

function now(): number {
  return typeof performance !== 'undefined' ? performance.now() : Date.now();
}

/** jsdom has no canvas and logs "not implemented" from getContext; treat that as no canvas. */
function safeContext(c: HTMLCanvasElement): CanvasRenderingContext2D | null {
  try {
    if (typeof navigator !== 'undefined' && /jsdom/i.test(navigator.userAgent)) return null;
    return c.getContext('2d');
  } catch {
    return null;
  }
}
