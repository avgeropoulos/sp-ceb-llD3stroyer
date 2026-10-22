// Analysis helpers for the audio tests (Node only): FFT, spectral centroid, band energies,
// loop-seam metrics, a WAV writer and a spectrogram PNG writer for eyeballing sounds.
import fs from 'node:fs';
import zlib from 'node:zlib';

/** In-place radix-2 FFT on (re, im) Float64Arrays of power-of-two length. */
export function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      [re[i], re[j]] = [re[j], re[i]];
      [im[i], im[j]] = [im[j], im[i]];
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len;
    const wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const ar = re[i + k], ai = im[i + k];
        const br = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci;
        const bi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr;
        re[i + k] = ar + br; im[i + k] = ai + bi;
        re[i + k + len / 2] = ar - br; im[i + k + len / 2] = ai - bi;
        const t = cr * wr - ci * wi;
        ci = cr * wi + ci * wr;
        cr = t;
      }
    }
  }
}

/** STFT power frames: returns { frames: Float64Array[], N, hop }. */
export function stft(buf, N = 2048, hop = 512) {
  const win = new Float64Array(N);
  for (let i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);
  const frames = [];
  for (let s = 0; s + N <= Math.max(N, buf.length); s += hop) {
    const re = new Float64Array(N), im = new Float64Array(N);
    for (let i = 0; i < N; i++) re[i] = (buf[s + i] || 0) * win[i];
    fft(re, im);
    const p = new Float64Array(N / 2);
    for (let k = 0; k < N / 2; k++) p[k] = re[k] * re[k] + im[k] * im[k];
    frames.push(p);
  }
  return { frames, N, hop };
}

/** Energy-weighted spectral centroid (Hz) over the whole sound, plus band energy fractions. */
export function spectrum(buf, sr) {
  const { frames, N } = stft(buf, 2048, 512);
  const total = new Float64Array(N / 2);
  for (const p of frames) for (let k = 0; k < p.length; k++) total[k] += p[k];
  let num = 0, den = 0;
  const bands = { sub: 0, low: 0, mid: 0, high: 0, air: 0 };
  for (let k = 1; k < total.length; k++) {
    const f = k * sr / N;
    num += f * total[k];
    den += total[k];
    if (f < 120) bands.sub += total[k];
    else if (f < 500) bands.low += total[k];
    else if (f < 2000) bands.mid += total[k];
    else if (f < 6000) bands.high += total[k];
    else bands.air += total[k];
  }
  for (const b in bands) bands[b] /= den || 1;
  return { centroid: num / (den || 1), bands };
}

export function stats(buf, sr) {
  let peak = 0, sum = 0, nan = 0;
  for (const v of buf) {
    if (!Number.isFinite(v)) { nan++; continue; }
    peak = Math.max(peak, Math.abs(v));
    sum += v * v;
  }
  const win = Math.round(0.05 * sr);
  let w = 0, wmax = 0;
  for (let i = 0; i < buf.length; i++) {
    w += buf[i] * buf[i];
    if (i >= win) w -= buf[i - win] * buf[i - win];
    wmax = Math.max(wmax, w);
  }
  return { peak, rms: Math.sqrt(sum / buf.length), momentary: Math.sqrt(wmax / Math.min(win, buf.length)), nan, dur: buf.length / sr };
}

/**
 * Seam metrics for a loop: the jump and the second difference across the wrap, compared with
 * the distribution of the same quantities inside the buffer.
 */
export function seam(buf) {
  const n = buf.length;
  const d1 = (i) => buf[(i + 1) % n] - buf[i % n];
  const d2 = (i) => buf[(i + 2) % n] - 2 * buf[(i + 1) % n] + buf[i % n];
  const a1 = [], a2 = [];
  for (let i = 0; i < n; i += 7) { a1.push(Math.abs(d1(i))); a2.push(Math.abs(d2(i))); }
  a1.sort((a, b) => a - b);
  a2.sort((a, b) => a - b);
  const p999a = a1[Math.floor(a1.length * 0.999)], p999b = a2[Math.floor(a2.length * 0.999)];
  const jump = Math.abs(d1(n - 1));
  const curv = Math.max(Math.abs(d2(n - 2)), Math.abs(d2(n - 1)));
  // Short-window energy continuity: RMS of the last and first 20 ms.
  const w = Math.min(n >> 2, 960);
  let e0 = 0, e1 = 0;
  for (let i = 0; i < w; i++) { e0 += buf[i] * buf[i]; e1 += buf[n - 1 - i] * buf[n - 1 - i]; }
  return { first: buf[0], last: buf[n - 1], jump, curv, p999jump: p999a, p999curv: p999b, rmsHead: Math.sqrt(e0 / w), rmsTail: Math.sqrt(e1 / w) };
}

export function writeWav(path, buf, sr) {
  const n = buf.length;
  const b = Buffer.alloc(44 + n * 2);
  b.write('RIFF', 0); b.writeUInt32LE(36 + n * 2, 4); b.write('WAVE', 8);
  b.write('fmt ', 12); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22);
  b.writeUInt32LE(sr, 24); b.writeUInt32LE(sr * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34);
  b.write('data', 36); b.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) b.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round((buf[i] || 0) * 32767))), 44 + i * 2);
  fs.writeFileSync(path, b);
}

const CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (const x of buf) c = CRC[(c ^ x) & 255] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
export function writePng(path, w, h, rgb) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 3 + 1)] = 0;
    rgb.copy(raw, y * (w * 3 + 1) + 1, y * w * 3, (y + 1) * w * 3);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  fs.writeFileSync(path, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
}

/**
 * Writes a labelled grid of spectrograms (log frequency 40 Hz–16 kHz, 70 dB range) plus the
 * waveform envelope under each one.
 */
export function writeSpectrogramSheet(path, items, { cols = 4, cw = 300, ch = 150 } = {}) {
  const rows = Math.ceil(items.length / cols);
  const W = cols * cw, H = rows * (ch + 40);
  const img = Buffer.alloc(W * H * 3, 18);
  const put = (x, y, r, g, b) => {
    if (x < 0 || y < 0 || x >= W || y >= H) return;
    const o = (y * W + x) * 3;
    img[o] = r; img[o + 1] = g; img[o + 2] = b;
  };
  items.forEach((it, idx) => {
    const ox = (idx % cols) * cw, oy = Math.floor(idx / cols) * (ch + 40);
    const { buf, sr } = it;
    const N = 1024, hop = Math.max(64, Math.floor(buf.length / (cw - 4)));
    const { frames } = stft(buf, N, hop);
    let mx = 1e-20;
    for (const p of frames) for (const v of p) mx = Math.max(mx, v);
    const fLo = 40, fHi = Math.min(16000, sr / 2);
    for (let x = 0; x < cw - 4 && x < frames.length; x++) {
      const p = frames[x];
      for (let y = 0; y < ch; y++) {
        const f = fLo * Math.pow(fHi / fLo, 1 - y / ch);
        const k = Math.min(p.length - 1, Math.round(f * N / sr));
        const db = 10 * Math.log10(p[k] / mx + 1e-12);
        const v = Math.max(0, Math.min(1, (db + 70) / 70));
        put(ox + 2 + x, oy + y, Math.round(255 * Math.min(1, v * 1.6)), Math.round(255 * Math.max(0, v * 1.6 - 0.6)), Math.round(255 * Math.max(0, v - 0.75) * 4));
      }
    }
    // frequency guides at 100 Hz, 1 kHz, 10 kHz
    for (const f of [100, 1000, 10000]) {
      const y = Math.round(ch * (1 - Math.log(f / fLo) / Math.log(fHi / fLo)));
      for (let x = 0; x < 6; x++) put(ox + x, oy + y, 80, 200, 80);
    }
    // waveform envelope strip
    const step = buf.length / (cw - 4);
    for (let x = 0; x < cw - 4; x++) {
      let m = 0;
      for (let i = Math.floor(x * step); i < Math.floor((x + 1) * step); i++) m = Math.max(m, Math.abs(buf[i] || 0));
      const hgt = Math.round(m * 24);
      for (let y = 0; y < hgt; y++) put(ox + 2 + x, oy + ch + 30 - y, 120, 170, 255);
      if (m > 0.98) for (let y = 0; y < 26; y++) put(ox + 2 + x, oy + ch + 30 - y, 255, 40, 40);
    }
    // a tiny label: index bars (name is printed by the caller in a legend)
    for (let b = 0; b <= idx; b++) put(ox + 4 + (b % 40) * 3, oy + ch + 3 + Math.floor(b / 40) * 3, 255, 255, 255);
  });
  writePng(path, W, H, img);
}
