// services/Receipt.ts
import type { Align, PrintCommand } from '../modules/pos-printer';

type TextStyle = {
  align?: Align;
  bold?: boolean;
  underline?: boolean;
  width?: number; // 1–8
  height?: number; // 1–8
};

export class Receipt {
  private commands: PrintCommand[] = [];

  /** Characters per line at normal size: 58mm paper ≈ 32, 80mm ≈ 48. */
  constructor(readonly columns = 48) {}

  text(value: string, style: TextStyle = {}) {
    this.commands.push({ type: 'text', value: value + '\n', ...style });
    return this;
  }

  title(value: string) {
    return this.text(value, { align: 'center', bold: true, width: 2, height: 2 });
  }

  /** Left text and right text on one line, e.g. item and price. */
  row(left: string, right: string, style: TextStyle = {}) {
    const width = Math.floor(this.columns / (style.width ?? 1));
    const room = Math.max(0, width - right.length - 1);
    const l = left.length > room ? left.slice(0, room) : left;
    return this.text(l.padEnd(width - right.length) + right, style);
  }

  divider(char = '-') {
    return this.text(char.repeat(this.columns));
  }

  feed(lines = 1) {
    this.commands.push({ type: 'feed', lines });
    return this;
  }

  qr(value: string, size = 6) {
    this.commands.push({ type: 'qr', value, size, align: 'center' });
    return this;
  }

  cut(partial = false) {
    this.commands.push({ type: 'cut', partial });
    return this;
  }

  build(): PrintCommand[] {
    return [...this.commands];
  }
}
