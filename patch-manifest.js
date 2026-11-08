// Ajoute les autorisations Bluetooth / USB au manifeste Android (idempotent).
const fs = require('fs');
const f = 'android/app/src/main/AndroidManifest.xml';
let s = fs.readFileSync(f, 'utf8');
if (!s.includes('BLUETOOTH_CONNECT')) {
  const add = `
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-feature android:name="android.hardware.usb.host" android:required="false" />
    <uses-feature android:name="android.hardware.bluetooth" android:required="false" />`;
  s = s.replace(/(<uses-permission android:name="android.permission.INTERNET"\s*\/>)/, '$1' + add);
  fs.writeFileSync(f, s);
  console.log('Manifeste mis à jour');
}
