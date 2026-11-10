// Baut app/src/main/assets/country-mask.bin: Weltgitter (0,2°), je Zelle das Land (für Max/Min je Land über die ganze Fläche),
// dazu je Land die Landesmitte für die Beschriftung.
// Quelle: Natural Earth 1:50m Admin 0 Countries (gemeinfrei), https://www.naturalearthdata.com/
// Aufruf: node tools/country-mask.mjs <ne_50m_admin_0_countries.geojson> <ausgabe country-mask.bin>
// Format: "WCM2", Breite int32, Höhe int32 (BE), Anzahl Länder uint8, je Land 2 Byte ASCII-Code,
// dann Breite×Höhe Bytes (Zeile 0 = Norden 90°, Spalte 0 = Westen −180°; 255 = kein Land; Zellmitte zählt),
// dann je Land die Landesmitte: Breite float32, Länge float32 (BE).
// Landesmitte = der Punkt, der am weitesten von Küste und Grenzen entfernt liegt (liegt so auch bei Norwegen,
// Chile, Kroatien sicher im Land und auf dem Hauptteil, z. B. USA ohne Alaska); bei fast gleich guten Punkten
// der zur Flächenmitte hin.
import fs from 'fs';
const [inFile, outFile] = process.argv.slice(2);
const STEP = 0.2, W = Math.round(360 / STEP), H = Math.round(180 / STEP);
const FIX = { 'Somaliland': 'SO', 'N. Cyprus': 'CY' };
// Mutterland als Rahmen [Süd, Nord, West, Ost]
const HOME = { FR: [41, 52, -6, 10], NL: [50, 54, 3, 8], NO: [57, 82, 4, 32] };
const g = JSON.parse(fs.readFileSync(inFile, 'utf8'));
const grid = Buffer.alloc(W * H, 255);
const codes = [], ix = new Map();
for (const f of g.features) {
  const p = f.properties;
  const cc = /^[A-Z]{2}$/.test(p.ISO_A2_EH) ? p.ISO_A2_EH : FIX[p.NAME];
  if (!cc) continue;
  const polys = f.geometry.type === 'Polygon' ? [f.geometry.coordinates] : f.geometry.coordinates;
  for (const rings of polys) {
    let s = 90, n = -90, w = 180, e = -180;
    for (const r of rings) for (const [lo, la] of r) { if (la < s) s = la; if (la > n) n = la; if (lo < w) w = lo; if (lo > e) e = lo; }
    // Überseegebiete nicht zum Mutterland zählen (sonst wäre Französisch-Guayana der Höchstwert Frankreichs)
    const home = HOME[cc];
    if (home && (n < home[0] || s > home[1] || e < home[2] || w > home[3])) continue;
    // Spitzbergen ist in Natural Earth Teil Norwegens – eigenes Gebiet (SJ), sonst käme Norwegens Tiefstwert von dort
    const pcc = cc === 'NO' && s > 74 ? 'SJ' : cc;
    if (!ix.has(pcc)) { ix.set(pcc, codes.length); codes.push(pcc); }
    const v = ix.get(pcc);
    const y0 = Math.max(0, Math.floor((90 - n) / STEP)), y1 = Math.min(H - 1, Math.ceil((90 - s) / STEP));
    for (let y = y0; y <= y1; y++) {
      const lat = 90 - (y + 0.5) * STEP;
      const xs = [];
      for (const r of rings) for (let i = 0, j = r.length - 1; i < r.length; j = i++) {
        const [xi, yi] = r[i], [xj, yj] = r[j];
        if ((yi > lat) !== (yj > lat)) xs.push(xi + (lat - yi) / (yj - yi) * (xj - xi));
      }
      xs.sort((a, b) => a - b);
      for (let k = 0; k + 1 < xs.length; k += 2) {
        const a = Math.ceil((xs[k] + 180) / STEP - 0.5), b = Math.floor((xs[k + 1] + 180) / STEP - 0.5);
        for (let x = Math.max(0, a); x <= Math.min(W - 1, b); x++) grid[y * W + x] = v;
      }
    }
  }
}

// Abstand jeder Landzelle (km) zur nächsten Zelle, die nicht zum selben Land gehört (Meer oder Nachbar):
// Chamfer-Verfahren mit 8 Nachbarn, Ost-West-Abstand mit cos(Breite), über die Datumsgrenze hinweg.
const KM = STEP * 111.2;
const d = new Float64Array(W * H);
for (let i = 0; i < d.length; i++) d[i] = grid[i] === 255 ? 0 : Infinity;
const cosOf = (y) => Math.max(0.02, Math.cos((90 - (y + 0.5) * STEP) * Math.PI / 180));
function relax(i, j, cost) {
  const nd = (grid[j] === grid[i] ? d[j] : 0) + cost;
  if (nd < d[i]) d[i] = nd;
}
for (let round = 0; round < 2; round++) {
  for (let y = 0; y < H; y++) {
    const dx = KM * cosOf(y), dd = Math.hypot(dx, KM);
    for (let x = 0; x < W; x++) {
      const i = y * W + x; if (grid[i] === 255) continue;
      const l = y * W + (x + W - 1) % W; relax(i, l, dx);
      if (y > 0) { const u = i - W; relax(i, u, KM); relax(i, (y - 1) * W + (x + W - 1) % W, dd); relax(i, (y - 1) * W + (x + 1) % W, dd); }
      else relax(i, i, KM / 2); // Pol-Rand
    }
  }
  for (let y = H - 1; y >= 0; y--) {
    const dx = KM * cosOf(y), dd = Math.hypot(dx, KM);
    for (let x = W - 1; x >= 0; x--) {
      const i = y * W + x; if (grid[i] === 255) continue;
      const r = y * W + (x + 1) % W; relax(i, r, dx);
      if (y < H - 1) { const b = i + W; relax(i, b, KM); relax(i, (y + 1) * W + (x + W - 1) % W, dd); relax(i, (y + 1) * W + (x + 1) % W, dd); }
    }
  }
}

// Hauptland je Land: das zusammenhängende Gebiet um die Zelle, die am weitesten von Küste/Grenze weg ist
// (USA ohne Alaska, Kanada ohne die Inseln im Norden) – nur dort wird die Mitte gesucht.
const k = codes.length;
const deep = new Int32Array(k).fill(-1);
for (let i = 0; i < grid.length; i++) { const v = grid[i]; if (v !== 255 && (deep[v] < 0 || d[i] > d[deep[v]])) deep[v] = i; }
const main = new Uint8Array(W * H);
for (let v = 0; v < k; v++) {
  if (deep[v] < 0) continue;
  const stack = [deep[v]]; main[deep[v]] = 1;
  while (stack.length) {
    const i = stack.pop(), y = Math.floor(i / W), x = i % W;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const yy = y + dy; if (yy < 0 || yy >= H) continue;
      const j = yy * W + (x + dx + W) % W;
      if (!main[j] && grid[j] === v) { main[j] = 1; stack.push(j); }
    }
  }
}
// Flächenmitte des Hauptlands (Längen über die Datumsgrenze hinweg ausgerichtet), dann die beste Zelle:
// weit weg von Küste/Grenze, aber nicht weit weg von der Mitte
const sumLat = new Float64Array(k), sumX = new Float64Array(k), sumY = new Float64Array(k), sumW = new Float64Array(k);
for (let y = 0; y < H; y++) {
  const lat = 90 - (y + 0.5) * STEP, wgt = cosOf(y);
  for (let x = 0; x < W; x++) {
    const i = y * W + x, v = grid[i]; if (v === 255 || !main[i]) continue;
    const lon = (-180 + (x + 0.5) * STEP) * Math.PI / 180;
    sumLat[v] += lat * wgt; sumX[v] += Math.cos(lon) * wgt; sumY[v] += Math.sin(lon) * wgt; sumW[v] += wgt;
  }
}
const best = new Float64Array(k).fill(-Infinity), bestLat = new Float64Array(k), bestLon = new Float64Array(k);
for (let y = 0; y < H; y++) {
  const lat = 90 - (y + 0.5) * STEP;
  for (let x = 0; x < W; x++) {
    const i = y * W + x, v = grid[i]; if (v === 255 || !main[i]) continue;
    const lon = -180 + (x + 0.5) * STEP;
    const cLat = sumLat[v] / sumW[v], cLon = Math.atan2(sumY[v], sumX[v]) * 180 / Math.PI;
    let dl = Math.abs(lon - cLon); if (dl > 180) dl = 360 - dl;
    const toC = Math.hypot((lat - cLat) * 111.2, dl * 111.2 * Math.cos(lat * Math.PI / 180));
    const score = d[i] - 0.35 * toC;
    if (score > best[v]) { best[v] = score; bestLat[v] = lat; bestLon[v] = lon; }
  }
}

const head = Buffer.alloc(13 + 2 * k);
head.write('WCM2'); head.writeInt32BE(W, 4); head.writeInt32BE(H, 8); head.writeUInt8(k, 12);
codes.forEach((c, i) => head.write(c, 13 + 2 * i, 'ascii'));
const tail = Buffer.alloc(8 * k);
for (let i = 0; i < k; i++) { tail.writeFloatBE(bestLat[i], 8 * i); tail.writeFloatBE(bestLon[i], 8 * i + 4); }
fs.writeFileSync(outFile, Buffer.concat([head, grid, tail]));
let land = 0; for (const b of grid) if (b !== 255) land++;
console.log(W + 'x' + H, k, 'Länder', land, 'Landzellen');
for (const c of ['DE', 'FR', 'NO', 'SJ', 'US', 'CA', 'RU', 'GL', 'IS', 'CL', 'HR', 'IT', 'GB', 'CN', 'ID', 'NL', 'JP', 'DK', 'GR', 'VN', 'NZ', 'ES', 'SE', 'FI', 'PT', 'AT', 'MX', 'AR', 'IN', 'AU'])
  if (ix.has(c)) { const i = ix.get(c); console.log(c, bestLat[i].toFixed(1), bestLon[i].toFixed(1)); }
