// services/PrinterService.ts
import { PermissionsAndroid, Platform } from 'react-native';
import PosPrinter from '../modules/pos-printer';
import type {
  DiscoveryOptions,
  PrinterInfo,
  PrintMode,
  PrintOptions,
  ReceiptPreview,
} from '../modules/pos-printer';
import { Receipt } from './Receipt';

export type { PrinterInfo, PrintMode, ReceiptPreview };
export { Receipt };

/** Paper presets: printable dots and characters per line. */
export const PAPER = {
  '58mm': { widthDots: 384, columns: 32 },
  '80mm': { widthDots: 576, columns: 48 },
  '80mm (512)': { widthDots: 512, columns: 42 }, // e.g. some Epson TM-T88 models
} as const;
export type PaperSize = keyof typeof PAPER;
export type QrSetting = 'image' | 'native' | 'off';

export interface PrinterSettings {
  mode: PrintMode;
  paper: PaperSize;
  /**
   * 'image' (default): phone draws the QR, works on any printer.
   * 'native': printer's own QR command (sharper, less data, but many cheap printers ignore it).
   * 'off': no QR.
   */
  qr: QrSetting;
  /** Text mode only. Defaults are Epson's Greek code page. */
  codePage?: number;
  charset?: string;
}

export const DEFAULT_SETTINGS: PrinterSettings = {
  mode: 'raster',
  paper: '80mm',
  qr: 'image',
  codePage: 15,
  charset: 'ISO-8859-7',
};

function toOptions(receipt: Receipt, settings: PrinterSettings): PrintOptions {
  const s = { ...DEFAULT_SETTINGS, ...settings };
  return {
    mode: s.mode,
    widthDots: PAPER[s.paper].widthDots,
    columns: receipt.columns,
    qrMode: s.qr === 'native' ? 'native' : 'image',
    codePage: s.codePage,
    charset: s.charset,
  };
}

async function ensureBluetoothPermissions(): Promise<void> {
  if (Platform.OS !== 'android') return;
  const perms =
    Number(Platform.Version) >= 31
      ? [PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN, PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT]
      : [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION];

  const result = await PermissionsAndroid.requestMultiple(perms);
  const denied = perms.filter((p) => result[p] !== PermissionsAndroid.RESULTS.GRANTED);
  if (denied.length) throw new Error(`Missing permissions: ${denied.join(', ')}`);
}

async function print(printerId: string, receipt: Receipt, settings: PrinterSettings = DEFAULT_SETTINGS) {
  if (printerId.startsWith('bt:')) await ensureBluetoothPermissions();
  return PosPrinter.print(printerId, receipt.build(), toOptions(receipt, settings));
}

function preview(receipt: Receipt, settings: PrinterSettings = DEFAULT_SETTINGS): Promise<ReceiptPreview> {
  return PosPrinter.previewReceipt(receipt.build(), toOptions(receipt, settings));
}

function buildTestReceipt(printer: PrinterInfo | null, paper: PaperSize, qr: QrSetting): Receipt {
  const r = new Receipt(PAPER[paper].columns).title('ΑΠΟΔΕΙΞΗ');
  if (printer) {
    r.text(printer.name, { align: 'center' }).text(
      printer.type === 'bluetooth' ? printer.address : `${printer.address}:${printer.port}`,
      { align: 'center' },
    );
  }
  r.divider()
    .row('Καφές εσπρέσο x2', '5,00 €')
    .row('Κρουασάν βουτύρου', '2,80 €')
    .row('Χυμός πορτοκάλι', '3,50 €')
    .divider()
    .row('ΣΥΝΟΛΟ', '11,30 €', { bold: true })
    .feed(1)
    .text('Ευχαριστούμε πολύ!', { align: 'center' })
    .text('άέήίόύώ ϊϋΐΰ ΆΈΉΊΌΎΏ', { align: 'center' })
    .feed(1);
  if (qr !== 'off') r.qr('https://example.com/receipt/123');
  return r.feed(2).cut();
}

export const PrinterService = {
  isBluetoothEnabled: () => PosPrinter.isBluetoothEnabled(),

  async discover(options: DiscoveryOptions = {}): Promise<PrinterInfo[]> {
    if (options.bluetooth !== false) await ensureBluetoothPermissions();
    return PosPrinter.discoverPrinters(options);
  },

  /** For network printers that don't show up in discovery. */
  manual(ip: string, port = 9100): PrinterInfo {
    return { id: `tcp:${ip}:${port}`, name: `Printer @ ${ip}`, address: ip, port, type: 'network', source: 'manual' };
  },

  print,
  preview,

  printTest(printer: PrinterInfo, settings: PrinterSettings = DEFAULT_SETTINGS) {
    return print(printer.id, buildTestReceipt(printer, settings.paper, settings.qr), settings);
  },

  previewTest(printer: PrinterInfo | null, settings: PrinterSettings = DEFAULT_SETTINGS) {
    return preview(buildTestReceipt(printer, settings.paper, settings.qr), settings);
  },
};
