
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import PrinterSelector from './components/PrinterSelector';

export default function App() {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={{ flex: 1 }}>
        <PrinterSelector />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}
