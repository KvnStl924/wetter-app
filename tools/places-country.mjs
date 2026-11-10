// Baut app/src/main/assets/places-cc.bin: zu jedem Ort aus places.bin das Land (ISO-Code aus GeoNames cities1000.txt).
// Aufruf: node tools/places-country.mjs <cities1000.txt> <places.bin> <ausgabe places-cc.bin>
// Format: "WCC1", Anzahl Länder uint8, je Land 2 Byte ASCII-Code, Anzahl Orte int32 (BE), dann je Ort 1 Byte Länder-Nummer
// (255 = unbekannt). Gleiche Reihenfolge wie places.bin. Zuordnung über die (float32-)Koordinaten.
import fs from 'fs';
const [citiesFile, placesFile, outFile] = process.argv.slice(2);

const f32 = (x) => Math.fround(x);
const byPos = new Map();
for (const line of fs.readFileSync(citiesFile, 'utf8').split('\n')) {
  const c = line.split('\t'); if (c.length < 15) continue;
  byPos.set(f32(+c[4]) + ',' + f32(+c[5]), c[8]);
}

const b = fs.readFileSync(placesFile);
if (b.toString('ascii', 0, 4) !== 'WCT1') throw new Error('places.bin: falsches Format');
const n = b.readInt32BE(4);
const codes = []; const codeIx = new Map();
const out = Buffer.alloc(n);
let p = 8, miss = 0;
for (let i = 0; i < n; i++) {
  const lat = b.readFloatBE(p), lon = b.readFloatBE(p + 4), len = b.readUInt8(p + 12);
  p += 13 + len;
  const cc = byPos.get(lat + ',' + lon);
  if (!cc || cc.length !== 2) { out[i] = 255; miss++; continue; }
  if (!codeIx.has(cc)) { codeIx.set(cc, codes.length); codes.push(cc); }
  out[i] = codeIx.get(cc);
}
if (codes.length > 254) throw new Error('zu viele Länder');
const head = Buffer.alloc(5 + 2 * codes.length + 4);
head.write('WCC1'); head.writeUInt8(codes.length, 4);
codes.forEach((c, k) => head.write(c, 5 + 2 * k, 'ascii'));
head.writeInt32BE(n, 5 + 2 * codes.length);
fs.writeFileSync(outFile, Buffer.concat([head, out]));
console.log(n, 'Orte,', codes.length, 'Länder,', miss, 'ohne Land');
