# Expo ESC/POS Printer

An experimental Expo native module (Kotlin) for printing receipts to thermal printers from React Native, over **Wi-Fi** and **Bluetooth**.

It finds printers on the local network and nearby Bluetooth devices, builds receipts in JavaScript, and sends them as ESC/POS. It has a **raster mode** in which the phone draws the receipt as an image. In that mode, Greek or any other Unicode text prints correctly, even on printers that don't have the right code page.

> ⚠️ **Experimental.** This is a learning project and a starting point, not a universal printer driver. It has been tested with a generic 58mm Bluetooth thermal printer and with the included virtual printer. Compatibility with other models is not guaranteed.

---

## Features

- **Printer discovery**
  - Bluetooth Classic: paired devices instantly, with an optional scan for nearby unpaired ones
  - Wi-Fi: mDNS (`_pdl-datastream`, `_printer`, `_ipp`) and a scan of the local /24 subnet on port 9100
  - Manual IP entry for network printers that don't advertise themselves
- **Transports:** raw TCP (port 9100) and Bluetooth SPP, with chunked writes for printers with small buffers
- **Two print modes**
  - `text`: ESC/POS text in the printer's built-in font with a configurable code page. Fast and small, but non-ASCII text depends on the printer.
  - `raster` (default): the receipt is drawn on an Android `Canvas` and sent as `GS v 0` image strips. The printer receives only dots, so Greek, accents and € always print correctly.
- **QR codes:** drawn into the image (ZXing), sent with the printer's native `GS ( k` command, or turned off
- **Receipt preview:** renders the receipt to a PNG inside the app before printing
- **Virtual printer:** a Node.js script that acts as a network printer and saves every job as `.bmp` / `.txt`, so you can test without hardware

## Why raster mode?

Most thermal printers don't understand UTF-8. They use **code pages** (character tables), and the code page numbers differ between brands. A Greek receipt that prints correctly on an Epson can come out as random symbols on a cheap clone.

In raster mode the phone renders the text itself with a system font, so code pages no longer matter. The trade-off is size: a raster receipt is about 20–40 KB instead of 1–2 KB, so it prints more slowly over cheap Bluetooth printers.

```
Receipt (JS)  →  Kotlin encoder  ─┬─ text:   ESC/POS text + code page
                                  └─ raster: Canvas → 1-bit bitmap → GS v 0
                                         ↓
                              TCP :9100  or  Bluetooth SPP
```

---

## Project structure

```
modules/pos-printer/
  android/
    build.gradle                    # module dependencies (ZXing)
    src/main/java/zk/modules/posprinter/
      PosPrinterModule.kt           # Expo module: JS API, options
      BluetoothPrinters.kt          # Bluetooth discovery
      NetworkPrinters.kt            # mDNS + subnet scan
      PrinterTransport.kt           # TCP and Bluetooth sending
      EscPosEncoder.kt              # ESC/POS commands, text and raster encoding
      RasterRenderer.kt             # draws the receipt on a bitmap
  src/PosPrinter.ts                 # typed JS wrapper
  expo-module.config.json
plugins/
  withBluetoothNeverForLocation.js  # config plugin for Android 12+ Bluetooth scanning
services/
  Receipt.ts                        # receipt builder
  PrinterService.ts                 # permissions, paper presets, settings
components/
  PrinterSelector.tsx               # test screen
virtual-printer.mjs                 # fake network printer for testing
```

## Requirements

- Android only (iOS is not supported)
- Expo SDK with Expo Modules support (built with expo-modules-core 58)
- A **development build**. Expo Go can't load custom native modules.
- A real Android device for Bluetooth (the emulator has no Bluetooth)
- Node.js 18+ for the virtual printer

## Getting started

```bash
git clone https://github.com/ZafeirisKanakis/pos-printers.git
cd pos-printers
npm install
npx expo prebuild
npx expo run:android
```

After changing only JS/TS files, you don't need to rebuild:

```bash
npx expo start --dev-client
```

Any change to Kotlin code, `app.json` or the module's `build.gradle` requires `npx expo run:android` again.

## Usage

```ts
import { PrinterService, Receipt } from './services/PrinterService';

// Find printers ("paired only" = skip the 8 s Bluetooth scan)
const printers = await PrinterService.discover({ bluetoothScanMs: 0 });

// Or add a network printer by IP
const printer = PrinterService.manual('192.168.1.50');

// Build a receipt (32 columns for 58mm paper, 48 for 80mm)
const receipt = new Receipt(48)
  .title('ΑΠΟΔΕΙΞΗ')
  .divider()
  .row('Καφές εσπρέσο x2', '5,00 €')
  .row('Κρουασάν', '2,80 €')
  .divider()
  .row('ΣΥΝΟΛΟ', '7,80 €', { bold: true })
  .qr('https://example.com/receipt/123')
  .cut();

// Print
await PrinterService.print(printer.id, receipt, {
  mode: 'raster', // or 'text'
  paper: '80mm',  // '58mm' | '80mm' | '80mm (512)'
  qr: 'image',    // 'image' | 'native' | 'off'
});

// Preview as PNG (data URI, works with <Image source={{ uri }} />)
const { uri } = await PrinterService.preview(receipt, { mode: 'raster', paper: '80mm', qr: 'image' });
```

Printer IDs have the form `bt:AA:BB:CC:DD:EE:FF` or `tcp:192.168.1.50:9100`.

### Settings

| Setting | Values | Notes |
|---|---|---|
| `mode` | `raster` (default), `text` | Use `raster` for Greek/Unicode text |
| `paper` | `58mm` (384 dots), `80mm` (576 dots), `80mm (512)` | Printable width differs by model |
| `qr` | `image` (default), `native`, `off` | Many cheap printers ignore `native` |
| `codePage` / `charset` | default `15` / `ISO-8859-7` | Text mode only. These are Epson's numbers and vary by brand. |

## Testing without a printer

`virtual-printer.mjs` listens on port 9100 and acts like a network ESC/POS printer.

```bash
node virtual-printer.mjs
```

1. Connect the phone to the same Wi-Fi as the PC. In Windows, allow Node through the firewall on **Private** networks.
2. In the app, press **Find printers** or enter the IP the script prints.
3. Print a test receipt. Each job is saved in `virtual-printer-output/`:
   - `.bmp`: the raster image, exactly as the printer would print it
   - `.txt`: decoded text in text mode, with markers for QR codes and cuts
   - `.bin`: the raw bytes

This tests discovery, TCP and the encoder. It can't test Bluetooth or a specific model's support for commands.

## Troubleshooting

| Problem | Likely cause / fix |
|---|---|
| Greek prints as random symbols | Text mode with the wrong code page. Use `raster` mode. |
| Text is cut off on the right | Wrong paper width for this model. Try `80mm (512)` or `58mm`. |
| Raster prints garbage, text mode works | The printer doesn't support `GS v 0` |
| The end of the receipt or the QR is missing (Bluetooth) | Printer buffer overflow. Lower `RASTER_STRIP_ROWS` in `EscPosEncoder.kt` and increase the delay in `PrinterTransport.kt`. |
| QR missing with `native` | The printer doesn't support `GS ( k`. Use `qr: 'image'`. |
| Nearby Bluetooth printers aren't found | Pair the printer in Android settings first, or check that `neverForLocation` is in the generated `AndroidManifest.xml` |
| Network printer isn't found | Make sure it's on the same /24 subnet and not behind a VPN, or add it by IP. Check the logs with `adb logcat -s PosPrinter`. |

## Limitations

- Android only
- ESC/POS only. Star (native StarPRNT/Graphic mode, TSP100), TSPL label printers and Zebra ZPL are not supported yet. The raster renderer is designed so these can be added as extra encoders.
- Bluetooth Classic only. BLE-only printers are not supported.
- No USB, and no printer status (paper out, cover open)
- No `ESC *` fallback for very old printers without `GS v 0`
- The printer and its settings are not saved between app restarts
- A successful `print()` means the bytes reached the printer, not that the receipt actually printed

## Roadmap

- [ ] Save printers and per-printer profiles
- [ ] Per-printer send speed for slow Bluetooth printers
- [ ] `ESC *` image fallback
- [ ] Star, TSPL and ZPL encoders

## License

MIT
