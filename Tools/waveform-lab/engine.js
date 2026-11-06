(function (root) {
  'use strict';
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  function pack(levels, codec = 'ghostgram') {
    if (levels.length !== 100 || levels.some(v => !Number.isInteger(v) || v < 0 || v > 31)) throw Error('Нужны 100 целых значений от 0 до 31');
    const bytes = new Uint8Array(63);
    levels.forEach((v, i) => {
      const p = i * 5, index = p >> 3, offset = p % 8;
      if (codec === 'ghostgram') {
        const spill = Math.max(0, 5 - (8 - offset));
        bytes[index] |= (v >> spill) << offset;
        if (spill) bytes[index + 1] |= v & ((1 << spill) - 1);
      } else {
        bytes[index] |= v << offset;
        if (offset > 3) bytes[index + 1] |= v >> (8 - offset);
      }
    });
    return bytes;
  }
  // Faithful port of SeekBarWaveform.calculateHeights, including Java float32
  // accumulation and the stock reader's ordering across byte boundaries.
  function heights(bytes, count) {
    if (!Number.isInteger(count) || count < 1 || count > 10000) throw Error('Неверное количество полосок');
    const values = Array(count).fill(0), n = Math.floor(bytes.length * 8 / 5);
    const step = Math.fround(n / count);
    let accumulator = 0, next = 0, bar = 0;
    for (let i = 0; i < n; i++) {
      if (i !== next) continue;
      let repeats = 0, last = next;
      while (last === next) {
        accumulator = Math.fround(accumulator + step);
        next = Math.floor(accumulator); repeats++;
      }
      const bit = i * 5, index = bit >> 3, offset = bit % 8;
      const current = 8 - offset, rest = 5 - current;
      let v = (bytes[index] >> offset) & ((1 << Math.min(5, current)) - 1);
      if (rest > 0 && index + 1 < bytes.length) v = (v << rest) | (bytes[index + 1] & ((1 << rest) - 1));
      for (let j = 0; j < repeats; j++) {
        if (bar >= count) return values;
        values[bar++] = Math.max(0, Math.fround(7 * v / 31));
      }
    }
    return values;
  }
  function geometry(bytes, width, scale = 1) {
    return heights(bytes, Math.floor(width / 3)).map((h, i) => ({x: i * 3, width: 2, top: -h * scale - 1, bottom: h * scale + 1}));
  }
  function contour(points) {
    let segment = 0;
    return Array.from({length: 100}, (_, i) => {
      const x = i / 99;
      while (segment + 4 < points.length && x > points[segment + 2]) segment += 2;
      const t = (x - points[segment]) / (points[segment + 2] - points[segment]);
      return clamp(Math.round(points[segment + 1] + t * (points[segment + 3] - points[segment + 1])), 0, 31);
    });
  }
  function columns(pixels) { return Array.from({length: 100}, (_, i) => pixels[Math.min(pixels.length - 1, Math.floor(i * pixels.length / 100))]); }
  function oldPreset(data, glyphs) {
    if (data.contour) return contour(data.contour);
    if (data.columns) return columns(data.columns);
    const pixels = Array(data.text.length * 6 + 3).fill(0);
    Array.from(data.text).forEach((letter, i) => {
      const rows = glyphs[letter].split('/');
      for (let x = 0; x < 5; x++) {
        let h = 0;
        rows.forEach((row, y) => { if (row[x] === '1') h = Math.max(h, 1 + Math.abs(y - 3) * 10); });
        pixels[2 + i * 6 + x] = h;
      }
    });
    return columns(pixels);
  }
  // Filled classic heart -> vertical intersections. Keep both edges; this is
  // the information which the Telegram amplitude-only format cannot retain.
  function heart(rotation = 0, span = 0.55, notch = 1) {
    const polygon = Array.from({length: 720}, (_, i) => {
      const t = i * 2 * Math.PI / 720;
      const x = 16 * Math.pow(Math.sin(t), 3);
      const y = -(13 * Math.cos(t) - (4 + notch) * Math.cos(2*t) - 2 * Math.cos(3*t) - Math.cos(4*t));
      return [x * Math.cos(rotation) - y * Math.sin(rotation), x * Math.sin(rotation) + y * Math.cos(rotation)];
    });
    const xs = polygon.map(p => p[0]), ys = polygon.map(p => p[1]);
    const loX = Math.min(...xs), hiX = Math.max(...xs), loY = Math.min(...ys), hiY = Math.max(...ys);
    const result = Array.from({length: 100}, () => ({top: 0, bottom: 0}));
    for (let i = 0; i < 100; i++) {
      const x = ((i + .5) / 100 - .5) / span + .5;
      if (x < 0 || x > 1) continue;
      const scan = loX + x * (hiX - loX), intersections = [];
      for (let j = 0; j < polygon.length; j++) {
        const a = polygon[j], b = polygon[(j + 1) % polygon.length];
        if ((a[0] <= scan && b[0] > scan) || (b[0] <= scan && a[0] > scan)) intersections.push(a[1] + (scan - a[0]) / (b[0] - a[0]) * (b[1] - a[1]));
      }
      if (intersections.length) {
        const normalize = y => (y - (loY + hiY) / 2) * 14 / (hiY - loY);
        intersections.sort((a,b) => a-b);
        result[i] = {top: normalize(intersections[0]), bottom: normalize(intersections[intersections.length-1])};
        if (intersections.length > 2) {
          result[i].segments = [];
          for (let j=0;j+1<intersections.length;j+=2) result[i].segments.push([normalize(intersections[j]),normalize(intersections[j+1])]);
        }
      }
    }
    return result;
  }
  function project(bounds, mode = 'envelope') {
    return bounds.map(b => clamp(Math.round((mode === 'height' ? (b.bottom - b.top) / 2 : Math.max(Math.abs(b.top), Math.abs(b.bottom))) * 31 / 7), 0, 31));
  }
  function toBounds(levels) { return levels.map(v => ({top: -7 * v / 31, bottom: 7 * v / 31})); }
  const api = {pack, heights, geometry, contour, oldPreset, heart, project, toBounds, clamp};
  if (typeof module !== 'undefined') module.exports = api;
  else root.WaveLab = api;
})(typeof window !== 'undefined' ? window : globalThis);
