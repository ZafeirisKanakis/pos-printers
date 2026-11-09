// virtual-printer.mjs — a fake ESC/POS network printer for testing without hardware.
//
// Run on your PC:   node virtual-printer.mjs          (listens on port 9100)
//                   node virtual-printer.mjs 9101     (other port)
//
// Every print job is saved in ./virtual-printer-output/:
//   job-….bin  raw bytes exactly as the app sent them
//   job-….txt  decoded text (text mode) plus markers for images, QR codes and cuts
//   job-….bmp  the raster image (raster mode) — open it in Windows Photos/Paint
//
// No dependencies. Needs Node 18+.

import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const PORT = Number(process.argv[2] ?? 9100);
const OUT_DIR = path.resolve('virtual-printer-output');
fs.mkdirSync(OUT_DIR, { recursive: true });

// ESC t n → text encoding (Epson numbering). Unknown pages fall back to latin1.
const CODE_PAGES = { 15: 'iso-8859-7', 47: 'windows-1253', 16: 'windows-1252' };

function decodeText(bytes, codePage) {
  const label = CODE_PAGES[codePage] ?? 'latin1';
  try {
    return new TextDecoder(label).decode(Uint8Array.from(bytes));
  } catch {
    return new TextDecoder('latin1').decode(Uint8Array.from(bytes));
  }
}

// ---------- 1-bit drawing helpers for the BMP ----------

const setPx = (row, x) => { row[x >> 3] |= 0x80 >> (x & 7); };

function qrPlaceholderRows(widthBytes) {
  const W = widthBytes * 8;
  const side = 160;
  const left = Math.floor((W - side) / 2);
  const rows = [];
  for (let y = 0; y < side + 16; y++) {
    const r = new Uint8Array(widthBytes);
    const yy = y - 8;
    if (yy >= 0 && yy < side) {
      if (yy < 3 || yy >= side - 3) {
        for (let x = left; x < left + side; x++) setPx(r, x);
      } else {
        for (const x of [left, left + 1, left + 2, left + side - 3, left + side - 2, left + side - 1]) setPx(r, x);
      }
    }
    rows.push(r);
  }
  return rows;
}

function cutRows(widthBytes) {
  const rows = [];
  for (let y = 0; y < 20; y++) {
    const r = new Uint8Array(widthBytes);
    if (y === 9 || y === 10) {
      for (let x = 0; x < widthBytes * 8; x++) if (x % 16 < 9) setPx(r, x);
    }
    rows.push(r);
  }
  return rows;
}

// ---------- ESC/POS parser (only the commands our app sends) ----------

function parse(buf) {
  let i = 0;
  let codePage = 0;
  let textBytes = [];
  let text = '';
  let widthBytes = 0;
  const rows = [];
  let qrData = null;
  let lastWasImage = false;
  const stats = { rasterStrips: 0, qr: 0, cuts: 0 };

  const flushText = () => {
    if (textBytes.length) {
      text += decodeText(textBytes, codePage);
      textBytes = [];
      lastWasImage = false;
    }
  };

  while (i < buf.length) {
    const b = buf[i];

    if (b === 0x1b) { // ESC
      const c = buf[i + 1];
      switch (c) {
        case 0x40: i += 2; break;                                        // ESC @ reset
        case 0x74: flushText(); codePage = buf[i + 2]; i += 3; break;    // ESC t n code page
        case 0x61: case 0x45: case 0x2d: case 0x21: i += 3; break;       // align / bold / underline / mode
        case 0x64: flushText(); text += '\n'.repeat(buf[i + 2]); i += 3; break; // ESC d n feed
        default: i += 2;
      }
    } else if (b === 0x1d) { // GS
      const c = buf[i + 1];
      if (c === 0x21) {                                                  // GS ! n size
        i += 3;
      } else if (c === 0x56) {                                           // GS V cut
        flushText();
        text += '\n✂ - - - - - - CUT - - - - - -\n';
        stats.cuts++;
        if (widthBytes) rows.push(...cutRows(widthBytes));
        const m = buf[i + 2];
        i += m === 65 || m === 66 ? 4 : 3;
      } else if (c === 0x28 && buf[i + 2] === 0x6b) {                    // GS ( k  QR
        const len = buf[i + 3] + buf[i + 4] * 256;
        const fn = buf[i + 6];
        if (fn === 0x50) qrData = buf.subarray(i + 8, i + 5 + len).toString('utf8');
        if (fn === 0x51) {
          flushText();
          text += `\n[QR: ${qrData}]\n`;
          stats.qr++;
          if (widthBytes) rows.push(...qrPlaceholderRows(widthBytes));
        }
        i += 5 + len;
      } else if (c === 0x76 && buf[i + 2] === 0x30) {                    // GS v 0 raster
        flushText();
        const xBytes = buf[i + 4] + buf[i + 5] * 256;
        const height = buf[i + 6] + buf[i + 7] * 256;
        const start = i + 8;
        if (widthBytes && xBytes !== widthBytes) {
          console.warn(`  ! raster width changed ${widthBytes} → ${xBytes} bytes`);
        }
        widthBytes = xBytes;
        for (let r = 0; r < height; r++) {
          rows.push(Uint8Array.from(buf.subarray(start + r * xBytes, start + (r + 1) * xBytes)));
        }
        stats.rasterStrips++;
        if (!lastWasImage) text += '[image]\n';
        lastWasImage = true;
        i = start + xBytes * height;
      } else {
        i += 2;
      }
    } else {
      textBytes.push(b);
      i++;
    }
  }
  flushText();
  return { text, widthBytes, rows, codePage, stats };
}

// ---------- 1-bit BMP writer ----------

function writeBmp(file, widthBytes, rows) {
  const width = widthBytes * 8;
  const height = rows.length;
  const stride = Math.ceil(widthBytes / 4) * 4;
  const dataSize = stride * height;
  const out = Buffer.alloc(62 + dataSize);

  out.write('BM', 0);
  out.writeUInt32LE(62 + dataSize, 2);
  out.writeUInt32LE(62, 10);          // pixel data offset
  out.writeUInt32LE(40, 14);          // DIB header size
  out.writeInt32LE(width, 18);
  out.writeInt32LE(height, 22);       // positive = bottom-up
  out.writeUInt16LE(1, 26);           // planes
  out.writeUInt16LE(1, 28);           // 1 bit per pixel
  out.writeUInt32LE(0, 30);           // no compression
  out.writeUInt32LE(dataSize, 34);
  out.writeInt32LE(8000, 38);         // ~203 dpi
  out.writeInt32LE(8000, 42);
  out.writeUInt32LE(2, 46);           // palette colors
  out.writeUInt32LE(2, 50);
  out.writeUInt32LE(0x00ffffff, 54);  // index 0 = white
  out.writeUInt32LE(0x00000000, 58);  // index 1 = black (printer bit 1 = black dot)

  rows.forEach((row, y) => {
    Buffer.from(row.buffer, row.byteOffset, row.byteLength).copy(out, 62 + (height - 1 - y) * stride);
  });
  fs.writeFileSync(file, out);
}

// ---------- server ----------

const server = net.createServer((socket) => {
  const from = socket.remoteAddress?.replace('::ffff:', '');
  const chunks = [];
  socket.on('data', (d) => chunks.push(d));
  socket.on('error', () => {});
  socket.on('close', () => {
    const buf = Buffer.concat(chunks);
    if (!buf.length) {
      console.log(`· connection from ${from} with no data (the app's discovery scan found us)`);
      return;
    }
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const base = path.join(OUT_DIR, `job-${stamp}`);
    fs.writeFileSync(`${base}.bin`, buf);

    const r = parse(buf);
    fs.writeFileSync(`${base}.txt`, r.text, 'utf8');
    const isRaster = r.rows.length > 0;
    if (isRaster) writeBmp(`${base}.bmp`, r.widthBytes, r.rows);

    console.log(`\n🖨  Job from ${from}: ${buf.length} bytes, ${isRaster ? 'RASTER' : 'TEXT'} mode`);
    if (isRaster) {
      console.log(`   width ${r.widthBytes * 8} dots, ${r.rows.length} rows, ${r.stats.rasterStrips} strips`);
      console.log(`   → ${base}.bmp`);
    } else {
      console.log(`   code page ${r.codePage} (${CODE_PAGES[r.codePage] ?? 'unknown, shown as latin1'})`);
    }
    console.log(`   QR codes: ${r.stats.qr}, cuts: ${r.stats.cuts}`);
    console.log(`   → ${base}.txt`);
    if (!isRaster) console.log('\n' + r.text);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces())
    .flat()
    .filter((a) => a && a.family === 'IPv4' && !a.internal)
    .map((a) => a.address);
  console.log(`Virtual printer listening on port ${PORT}`);
  console.log(`In the app, add printer IP: ${ips.join('  or  ') || '(no network found)'}`);
  console.log(`Output folder: ${OUT_DIR}\n`);
});
