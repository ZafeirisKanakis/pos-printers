// modules/pos-printer (replaces your current PosPrinter.ts)
import { requireNativeModule } from 'expo-modules-core';

export type PrinterType = 'bluetooth' | 'network';

export interface PrinterInfo {
  /** "bt:AA:BB:CC:DD:EE:FF" or "tcp:192.168.1.50:9100" */
  id: string;
  name: string;
  address: string;
  type: PrinterType;
  port?: number;
  paired?: boolean;
  likelyPrinter?: boolean;
  source: 'bonded' | 'scan' | 'mdns' | 'subnet' | 'manual';
}

export interface DiscoveryOptions {
  bluetooth?: boolean;
  bluetoothScanMs?: number;
  mdns?: boolean;
  mdnsTimeoutMs?: number;
  subnetScan?: boolean;
  subnetPort?: number;
}

export type Align = 'left' | 'center' | 'right';

export type PrintCommand =
  | { type: 'text'; value: string; align?: Align; bold?: boolean; underline?: boolean; width?: number; height?: number }
  | { type: 'feed'; lines: number }
  | { type: 'cut'; partial?: boolean }
  | { type: 'qr'; value: string; size?: number; align?: Align }
  | { type: 'raw'; base64: string };

export type PrintMode = 'raster' | 'text';
export type QrMode = 'image' | 'native';

export interface PrintOptions {
  /** 'raster' (default): phone draws the text, Greek works on any printer. 'text': printer font + code page. */
  mode?: PrintMode;
  /** Raster: printable width in dots. 58mm = 384, 80mm = 576 (some Epson models 512). */
  widthDots?: number;
  /** Raster: characters per line. Must match the Receipt's columns. */
  columns?: number;
  /** 'image' (default): phone draws the QR, works on any printer. 'native': printer's own QR command. */
  qrMode?: QrMode;
  /** Text: ESC t n. Epson: 15 = ISO-8859-7, 47 = WPC1253. Varies by brand. */
  codePage?: number;
  /** Text: must match codePage, e.g. 'ISO-8859-7' or 'windows-1253'. */
  charset?: string;
  timeoutMs?: number;
}

export interface ReceiptPreview {
  /** PNG data URI, usable directly as <Image source={{ uri }} />. null if the receipt is empty. */
  uri: string | null;
  width: number;
  height: number;
}

interface PosPrinterNativeModule {
  discoverPrinters(options: DiscoveryOptions): Promise<PrinterInfo[]>;
  /** Resolves with the number of bytes sent. */
  print(printerId: string, commands: PrintCommand[], options: PrintOptions): Promise<number>;
  previewReceipt(commands: PrintCommand[], options: PrintOptions): Promise<ReceiptPreview>;
  isBluetoothEnabled(): boolean;
}

const PosPrinter = requireNativeModule<PosPrinterNativeModule>('PosPrinter');

export default PosPrinter;
