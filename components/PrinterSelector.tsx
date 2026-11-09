// components/PrinterSelector.tsx
import { useState } from 'react';
import {
  Alert,
  Button,
  FlatList,
  Image,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useWindowDimensions,
} from 'react-native';
import {
  DEFAULT_SETTINGS,
  PAPER,
  PrinterService,
  type PaperSize,
  type PrinterInfo,
  type PrintMode,
  type QrSetting,
  type ReceiptPreview,
} from '../services/PrinterService';

type BtSearch = 'paired only' | 'scan nearby';

function Segmented<T extends string>({ value, options, onChange }: { value: T; options: readonly T[]; onChange: (v: T) => void }) {
  return (
    <View style={styles.segmented}>
      {options.map((o) => (
        <Pressable key={o} onPress={() => onChange(o)} style={[styles.segment, value === o && styles.segmentActive]}>
          <Text style={[styles.segmentText, value === o && styles.segmentTextActive]}>{o}</Text>
        </Pressable>
      ))}
    </View>
  );
}

export default function PrinterSelector() {
  const [printers, setPrinters] = useState<PrinterInfo[]>([]);
  const [selected, setSelected] = useState<PrinterInfo | null>(null);
  const [busy, setBusy] = useState<'discover' | 'print' | 'preview' | null>(null);
  const [manualIp, setManualIp] = useState('');
  const [mode, setMode] = useState<PrintMode>(DEFAULT_SETTINGS.mode);
  const [paper, setPaper] = useState<PaperSize>(DEFAULT_SETTINGS.paper);
  const [preview, setPreview] = useState<ReceiptPreview | null>(null);
  const [btSearch, setBtSearch] = useState<BtSearch>('paired only');
  const [qr, setQr] = useState<QrSetting>(DEFAULT_SETTINGS.qr);
  const { width: screenWidth } = useWindowDimensions();

  const settings = { ...DEFAULT_SETTINGS, mode, paper, qr };

  const discover = async () => {
    setBusy('discover');
    try {
      // "paired only" skips the 8 s Bluetooth scan; Wi-Fi discovery still runs.
      const found = await PrinterService.discover({ bluetoothScanMs: btSearch === 'paired only' ? 0 : 8000 });
      setPrinters(found);
      if (!PrinterService.isBluetoothEnabled()) {
        Alert.alert('Bluetooth is off', 'Only Wi-Fi printers were searched.');
      }
    } catch (e: any) {
      Alert.alert('Discovery failed', e?.message ?? String(e));
    } finally {
      setBusy(null);
    }
  };

  const addManual = () => {
    const ip = manualIp.trim();
    if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) {
      Alert.alert('Invalid IP address');
      return;
    }
    const p = PrinterService.manual(ip);
    setPrinters((prev) => [p, ...prev.filter((x) => x.id !== p.id)]);
    setSelected(p);
    setManualIp('');
  };

  const printTest = async () => {
    if (!selected) return;
    setBusy('print');
    try {
      await PrinterService.printTest(selected, settings);
    } catch (e: any) {
      Alert.alert('Print failed', e?.message ?? String(e));
    } finally {
      setBusy(null);
    }
  };

  const showPreview = async () => {
    setBusy('preview');
    try {
      setPreview(await PrinterService.previewTest(selected, settings));
    } catch (e: any) {
      Alert.alert('Preview failed', e?.message ?? String(e));
    } finally {
      setBusy(null);
    }
  };

  const previewWidth = screenWidth - 48;
  const previewHeight = preview && preview.width ? (preview.height * previewWidth) / preview.width : 0;

  return (
    <View style={styles.container}>
      <Text style={styles.label}>Bluetooth search</Text>
      <Segmented value={btSearch} options={['paired only', 'scan nearby'] as const} onChange={setBtSearch} />
      <Button title={busy === 'discover' ? 'Searching…' : 'Find printers'} onPress={discover} disabled={!!busy} />
      {busy === 'discover' && <Text style={styles.hint}>Searching Bluetooth and Wi-Fi, about 5–8 seconds…</Text>}

      <View style={styles.manualRow}>
        <TextInput
          style={styles.input}
          placeholder="Printer IP, e.g. 192.168.1.50"
          keyboardType="decimal-pad"
          value={manualIp}
          onChangeText={setManualIp}
        />
        <Button title="Add" onPress={addManual} />
      </View>

      <FlatList
        style={styles.list}
        data={printers}
        keyExtractor={(p) => p.id}
        ListEmptyComponent={<Text style={styles.hint}>No printers yet.</Text>}
        renderItem={({ item }) => (
          <Pressable
            onPress={() => setSelected(item)}
            style={[styles.item, selected?.id === item.id && styles.selected]}
          >
            <Text style={styles.name}>{item.name}</Text>
            <Text style={styles.meta}>
              {item.type === 'bluetooth'
                ? `Bluetooth · ${item.address}${item.paired ? '' : ' · not paired, pair it in Android settings first'}`
                : `Wi-Fi · ${item.address}:${item.port}`}
            </Text>
          </Pressable>
        )}
      />

      <Text style={styles.label}>Mode</Text>
      <Segmented value={mode} options={['raster', 'text'] as const} onChange={setMode} />
      <Text style={styles.label}>Paper</Text>
      <Segmented value={paper} options={Object.keys(PAPER) as PaperSize[]} onChange={setPaper} />
      <Text style={styles.label}>QR code</Text>
      <Segmented value={qr} options={['image', 'native', 'off'] as const} onChange={setQr} />

      <View style={styles.actions}>
        <View style={styles.action}>
          <Button title={busy === 'preview' ? 'Rendering…' : 'Preview'} onPress={showPreview} disabled={!!busy} />
        </View>
        <View style={styles.action}>
          <Button
            title={busy === 'print' ? 'Printing…' : 'Print test'}
            onPress={printTest}
            disabled={!selected || !!busy}
          />
        </View>
      </View>

      <Modal visible={!!preview} animationType="slide" onRequestClose={() => setPreview(null)}>
        <View style={styles.modal}>
          <Text style={styles.hint}>
            How it prints in raster mode{mode === 'text' ? ' (text mode uses the printer font instead)' : ''}
          </Text>
          <ScrollView contentContainerStyle={styles.previewScroll}>
            {preview?.uri && (
              <Image source={{ uri: preview.uri }} style={[styles.paper, { width: previewWidth, height: previewHeight }]} />
            )}
          </ScrollView>
          <Button title="Close" onPress={() => setPreview(null)} />
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, gap: 10 },
  manualRow: { flexDirection: 'row', gap: 8, alignItems: 'center' },
  input: { flex: 1, borderWidth: 1, borderColor: '#ccc', borderRadius: 6, paddingHorizontal: 10, height: 40 },
  list: { flex: 1 },
  item: { padding: 12, borderRadius: 8, borderWidth: 1, borderColor: '#ddd', marginBottom: 8 },
  selected: { borderColor: '#2563eb', backgroundColor: '#eff6ff' },
  name: { fontSize: 16, fontWeight: '600' },
  meta: { fontSize: 13, color: '#666', marginTop: 2 },
  hint: { color: '#666', textAlign: 'center' },
  label: { fontSize: 13, color: '#444', fontWeight: '600' },
  segmented: { flexDirection: 'row', borderWidth: 1, borderColor: '#2563eb', borderRadius: 6, overflow: 'hidden' },
  segment: { flex: 1, paddingVertical: 8, alignItems: 'center' },
  segmentActive: { backgroundColor: '#2563eb' },
  segmentText: { color: '#2563eb', fontSize: 13 },
  segmentTextActive: { color: '#fff', fontWeight: '600' },
  actions: { flexDirection: 'row', gap: 10 },
  action: { flex: 1 },
  modal: { flex: 1, padding: 16, gap: 12, backgroundColor: '#e5e5e5' },
  previewScroll: { alignItems: 'center', paddingVertical: 8 },
  paper: { backgroundColor: '#fff', borderWidth: 1, borderColor: '#ccc' },
});
