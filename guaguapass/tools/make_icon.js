// Genera un PNG valido para el icono de la app (sin dependencias externas).
const fs = require('fs');
const zlib = require('zlib');

function crc32(buf) {
  let c, table = [];
  for (let n = 0; n < 256; n++) {
    c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  let crc = 0xffffffff;
  for (const b of buf) crc = table[(crc ^ b) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

const W = 192, H = 192;
const raw = Buffer.alloc(H * (1 + W * 4));
for (let y = 0; y < H; y++) {
  const off = y * (1 + W * 4);
  raw[off] = 0; // filtro none
  for (let x = 0; x < W; x++) {
    const p = off + 1 + x * 4;
    const t = y / H;
    let r = Math.round(0x00), g = Math.round(0x69 + (0x4d - 0x69) * t), b = Math.round(0x5c + (0x40 - 0x5c) * t);
    if (x >= 36 && x < 156 && y >= 70 && y < 128) { r = 0xff; g = 0xff; b = 0xff; }
    const d1 = Math.hypot(x - 66, y - 132), d2 = Math.hypot(x - 126, y - 132);
    if (d1 < 14 || d2 < 14) { r = 0x21; g = 0x21; b = 0x21; }
    if (x >= 44 && x < 148 && y >= 78 && y < 100 && ((x - 44) % 26) < 20) { r = 0x80; g = 0xcb; b = 0xc4; }
    if (x >= 36 && x < 156 && y >= 118 && y < 128) { r = 0xff; g = 0x6f; b = 0x00; }
    raw[p] = r; raw[p + 1] = g; raw[p + 2] = b; raw[p + 3] = 255;
  }
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4);
ihdr[8] = 8; ihdr[9] = 6; // 8-bit RGBA
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(raw)),
  chunk('IEND', Buffer.alloc(0)),
]);
fs.writeFileSync(process.argv[2] || 'ic_launcher.png', png);
console.log('icono escrito:', png.length, 'bytes');
