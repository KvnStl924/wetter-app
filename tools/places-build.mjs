// Baut app/src/main/assets/places.bin aus GeoNames (CC BY 4.0, https://www.geonames.org/):
//   cities1000.txt (Orte ab 1000 Einwohnern) + deutsche Namen aus alternateNamesV2 (nur Sprache "de" → de.txt).
// Aufruf: node tools/places-build.mjs <cities1000.txt> <de.txt> <ausgabe places.bin> <WorldCities.kt>
// Format (Big Endian): "WCT1", Anzahl int32, dann je Ort: Breite float32, Länge float32, Einwohner int32,
// Namenslänge uint8, Name UTF-8. Sortiert nach Größe (Hauptstädte dreifach gewichtet).
import fs from 'fs';
const [citiesFile, deFile, outFile, worldCitiesFile] = process.argv.slice(2);

const de = new Map();
for (const line of fs.readFileSync(deFile, 'utf8').split('\n')) {
  const c = line.split('\t'); if (c.length < 4) continue;
  const id = c[1], name = c[3], pref = c[4] === '1', colloq = c[6] === '1', hist = c[7] === '1';
  if (colloq || hist || !name) continue;
  const old = de.get(id);
  if (!old || (pref && !old.pref)) de.set(id, { name, pref });
}

// nur echte Orte – keine Stadtteile (PPLX), keine verlassenen/historischen Orte
const keep = new Set(['PPL', 'PPLA', 'PPLA2', 'PPLA3', 'PPLA4', 'PPLA5', 'PPLC', 'PPLG', 'PPLF', 'PPLS', 'PPLL']);
const rows = [];
for (const line of fs.readFileSync(citiesFile, 'utf8').split('\n')) {
  const c = line.split('\t'); if (c.length < 15) continue;
  if (!keep.has(c[7])) continue;
  const pop = parseInt(c[14]) || 0; if (pop < 1000) continue;
  rows.push({ name: de.get(c[0])?.name || c[1], lat: +c[4], lon: +c[5], pop, cap: c[7] === 'PPLC' ? 1 : 0 });
}

// Deutsche Namen der Weltliste der App für große Städte in der Nähe übernehmen (z. B. Beijing → Peking)
const wc = [...fs.readFileSync(worldCitiesFile, 'utf8').matchAll(/([^|\n"]+);(-?[0-9.]+);(-?[0-9.]+);[123]/g)]
  .map(m => [m[1].trim(), +m[2], +m[3]]);
for (const [n, la, lo] of wc) {
  let best = null, bd = 0.35;
  for (const r of rows) {
    if (r.pop < 200000) continue;
    const d = Math.hypot(r.lat - la, (r.lon - lo) * Math.cos(la * Math.PI / 180));
    if (d < bd) { bd = d; best = r; }
  }
  if (best) best.name = n;
}

// Hauptstädte zählen dreifach – aber keine Zwergstaaten vor Großstädten (sonst verdrängte Vatikanstadt Rom)
rows.sort((a, b) => b.pop * (1 + 2 * b.cap) - a.pop * (1 + 2 * a.cap));
const parts = [];
const head = Buffer.alloc(8); head.write('WCT1'); head.writeInt32BE(rows.length, 4); parts.push(head);
for (const r of rows) {
  const nb = Buffer.from(r.name, 'utf8').subarray(0, 255);
  const b = Buffer.alloc(13 + nb.length);
  b.writeFloatBE(r.lat, 0); b.writeFloatBE(r.lon, 4); b.writeInt32BE(r.pop, 8); b.writeUInt8(nb.length, 12); nb.copy(b, 13);
  parts.push(b);
}
const out = Buffer.concat(parts);
fs.writeFileSync(outFile, out);
console.log(rows.length, 'Orte', out.length, 'Bytes');
console.log(rows.slice(0, 12).map(r => r.name).join(', '));
