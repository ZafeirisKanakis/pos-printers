// plugins/withBluetoothNeverForLocation.js
//
// Adds android:usesPermissionFlags="neverForLocation" to BLUETOOTH_SCAN.
// Without it, Android 12+ hides nearby (unpaired) Bluetooth devices unless the app
// also has location permission. app.json "permissions" can't set this flag, so we
// patch the manifest during `expo prebuild`.
const { withAndroidManifest } = require('expo/config-plugins');

const SCAN = 'android.permission.BLUETOOTH_SCAN';

module.exports = function withBluetoothNeverForLocation(config) {
  return withAndroidManifest(config, (cfg) => {
    const manifest = cfg.modResults.manifest;
    manifest.$['xmlns:tools'] = manifest.$['xmlns:tools'] || 'http://schemas.android.com/tools';

    const perms = (manifest['uses-permission'] = manifest['uses-permission'] || []);
    let scan = perms.find((p) => p.$['android:name'] === SCAN);
    if (!scan) {
      scan = { $: { 'android:name': SCAN } };
      perms.push(scan);
    }
    scan.$['android:usesPermissionFlags'] = 'neverForLocation';
    scan.$['tools:targetApi'] = 's';
    return cfg;
  });
};
