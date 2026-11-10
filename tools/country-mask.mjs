// Baut app/src/main/assets/country-mask.bin: Weltgitter (0,2°), je Zelle das Land (für Max/Min je Land über die ganze Fläche).
// Quelle: Natural Earth 1:50m Admin 0 Countries (gemeinfrei), https://www.naturalearthdata.com/
// Aufruf: node tools/country-mask.mjs <ne_50m_admin_0_countries.geojson> <ausgabe country-mask.bin>
// Format: "WCM1", Breite int32, Höhe int32 (BE), Anzahl Länder uint8, je Land 2 Byte ASCII-Code,
// dann Breite×Höhe Bytes (Zeile 0 = Norden 90°, Spalte 0 = Westen −180°; 255 = kein Land). Zellmitte zählt.
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
  if (!ix.has(cc)) { ix.set(cc, codes.length); codes.push(cc); }
  const v = ix.get(cc);
  const polys = f.geometry.type === 'Polygon' ? [f.geometry.coordinates] : f.geometry.coordinates;
  for (const rings of polys) {
    let s = 90, n = -90, w = 180, e = -180;
    for (const r of rings) for (const [lo, la] of r) { if (la < s) s = la; if (la > n) n = la; if (lo < w) w = lo; if (lo > e) e = lo; }
    // Überseegebiete nicht zum Mutterland zählen (sonst wäre Französisch-Guayana der Höchstwert Frankreichs)
    const home = HOME[cc];
    if (home && (n < home[0] || s > home[1] || e < home[2] || w > home[3])) continue;
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
const head = Buffer.alloc(13 + 2 * codes.length);
head.write('WCM1'); head.writeInt32BE(W, 4); head.writeInt32BE(H, 8); head.writeUInt8(codes.length, 12);
codes.forEach((c, k) => head.write(c, 13 + 2 * k, 'ascii'));
fs.writeFileSync(outFile, Buffer.concat([head, grid]));
let land = 0; for (const b of grid) if (b !== 255) land++;
console.log(W + 'x' + H, codes.length, 'Länder', land, 'Landzellen');
