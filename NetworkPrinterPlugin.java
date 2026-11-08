package com.caisse.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(
    name = "NetworkPrinter",
    permissions = { @Permission(alias = "bt", strings = { Manifest.permission.BLUETOOTH_CONNECT }) }
)
public class NetworkPrinterPlugin extends Plugin {

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String USB_PERM = "com.caisse.app.USB_PERMISSION";

    private static byte[] toBytes(JSArray data) throws Exception {
        byte[] b = new byte[data.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) data.getInt(i);
        return b;
    }

    /* ---------- Ethernet / Wi-Fi (TCP 9100) ---------- */
    @PluginMethod
    public void print(final PluginCall call) {
        final String host = call.getString("host");
        final int port = call.getInt("port", 9100);
        final JSArray data = call.getArray("data");
        if (host == null || data == null) { call.reject("Paramètres manquants"); return; }
        new Thread(() -> {
            try {
                byte[] bytes = toBytes(data);
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, port), 5000);
                    s.setSoTimeout(5000);
                    OutputStream os = s.getOutputStream();
                    os.write(bytes);
                    os.flush();
                }
                call.resolve(new JSObject());
            } catch (Exception e) {
                call.reject("Imprimante injoignable : " + e.getMessage());
            }
        }).start();
    }

    /* ---------- Bluetooth classique (SPP) ---------- */
    private boolean btReady(PluginCall call, String callback) {
        if (Build.VERSION.SDK_INT >= 31 && getPermissionState("bt") != PermissionState.GRANTED) {
            requestPermissionForAlias("bt", call, callback);
            return false;
        }
        return true;
    }

    @PluginMethod
    public void listBluetooth(PluginCall call) {
        if (btReady(call, "listBtCb")) doListBt(call);
    }

    @PermissionCallback
    private void listBtCb(PluginCall call) {
        if (Build.VERSION.SDK_INT < 31 || getPermissionState("bt") == PermissionState.GRANTED) doListBt(call);
        else call.reject("Permission Bluetooth refusée");
    }

    @SuppressLint("MissingPermission")
    private void doListBt(PluginCall call) {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { call.reject("Bluetooth indisponible"); return; }
        if (!ad.isEnabled()) { call.reject("Active le Bluetooth de la tablette"); return; }
        JSArray list = new JSArray();
        Set<BluetoothDevice> bonded = ad.getBondedDevices();
        for (BluetoothDevice d : bonded) {
            JSObject o = new JSObject();
            o.put("name", d.getName());
            o.put("address", d.getAddress());
            list.put(o);
        }
        JSObject res = new JSObject();
        res.put("devices", list);
        call.resolve(res);
    }

    @PluginMethod
    public void printBluetooth(PluginCall call) {
        if (btReady(call, "printBtCb")) doPrintBt(call);
    }

    @PermissionCallback
    private void printBtCb(PluginCall call) {
        if (Build.VERSION.SDK_INT < 31 || getPermissionState("bt") == PermissionState.GRANTED) doPrintBt(call);
        else call.reject("Permission Bluetooth refusée");
    }

    @SuppressLint("MissingPermission")
    private void doPrintBt(final PluginCall call) {
        final String address = call.getString("address");
        final JSArray data = call.getArray("data");
        if (address == null || data == null) { call.reject("Paramètres manquants"); return; }
        new Thread(() -> {
            BluetoothSocket sock = null;
            try {
                byte[] bytes = toBytes(data);
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null || !ad.isEnabled()) throw new Exception("Bluetooth désactivé");
                ad.cancelDiscovery();
                BluetoothDevice dev = ad.getRemoteDevice(address);
                sock = dev.createRfcommSocketToServiceRecord(SPP);
                sock.connect();
                OutputStream os = sock.getOutputStream();
                int chunk = 512;
                for (int i = 0; i < bytes.length; i += chunk) {
                    os.write(bytes, i, Math.min(chunk, bytes.length - i));
                    os.flush();
                    Thread.sleep(20);
                }
                Thread.sleep(400);
                call.resolve(new JSObject());
            } catch (Exception e) {
                call.reject("Bluetooth : " + e.getMessage());
            } finally {
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    /* ---------- USB ---------- */
    @PluginMethod
    public void listUsb(PluginCall call) {
        UsbManager um = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
        JSArray list = new JSArray();
        for (UsbDevice d : um.getDeviceList().values()) {
            JSObject o = new JSObject();
            String n = null;
            if (Build.VERSION.SDK_INT >= 21) n = d.getProductName();
            o.put("name", n != null ? n : ("USB " + Integer.toHexString(d.getVendorId()) + ":" + Integer.toHexString(d.getProductId())));
            o.put("id", d.getDeviceName());
            list.put(o);
        }
        JSObject res = new JSObject();
        res.put("devices", list);
        call.resolve(res);
    }

    @PluginMethod
    public void printUsb(final PluginCall call) {
        final String id = call.getString("deviceId");
        final JSArray data = call.getArray("data");
        if (id == null || data == null) { call.reject("Paramètres manquants"); return; }
        new Thread(() -> {
            UsbDeviceConnection conn = null;
            try {
                final Context ctx = getContext();
                UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
                UsbDevice dev = um.getDeviceList().get(id);
                if (dev == null) throw new Exception("Imprimante USB non branchée");
                if (!um.hasPermission(dev)) {
                    final CountDownLatch latch = new CountDownLatch(1);
                    BroadcastReceiver r = new BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent i) { latch.countDown(); }
                    };
                    ContextCompat.registerReceiver(ctx, r, new IntentFilter(USB_PERM), ContextCompat.RECEIVER_NOT_EXPORTED);
                    Intent in = new Intent(USB_PERM).setPackage(ctx.getPackageName());
                    int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                    um.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, in, flags));
                    latch.await(30, TimeUnit.SECONDS);
                    ctx.unregisterReceiver(r);
                    if (!um.hasPermission(dev)) throw new Exception("Autorisation USB refusée");
                }
                UsbInterface ifc = null; UsbEndpoint ep = null;
                for (int i = 0; i < dev.getInterfaceCount(); i++) {
                    UsbInterface c = dev.getInterface(i);
                    for (int k = 0; k < c.getEndpointCount(); k++) {
                        UsbEndpoint e = c.getEndpoint(k);
                        if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && e.getDirection() == UsbConstants.USB_DIR_OUT) {
                            if (ep == null || c.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) { ifc = c; ep = e; }
                        }
                    }
                }
                if (ep == null) throw new Exception("Aucune sortie d'impression trouvée");
                conn = um.openDevice(dev);
                if (conn == null) throw new Exception("Ouverture USB impossible");
                conn.claimInterface(ifc, true);
                byte[] bytes = toBytes(data);
                int chunk = 4096;
                for (int i = 0; i < bytes.length; i += chunk) {
                    byte[] part = java.util.Arrays.copyOfRange(bytes, i, Math.min(i + chunk, bytes.length));
                    int n = conn.bulkTransfer(ep, part, part.length, 5000);
                    if (n < 0) throw new Exception("Envoi USB échoué");
                }
                call.resolve(new JSObject());
            } catch (Exception e) {
                call.reject("USB : " + e.getMessage());
            } finally {
                if (conn != null) conn.close();
            }
        }).start();
    }

    /* ---------- Recherche d'imprimantes sur le réseau local ---------- */
    @PluginMethod
    public void scanNetwork(final PluginCall call) {
        final int port = call.getInt("port", 9100);
        new Thread(() -> {
            try {
                String base = null;
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                            String h = a.getHostAddress();
                            base = h.substring(0, h.lastIndexOf('.') + 1);
                            break;
                        }
                    }
                    if (base != null) break;
                }
                if (base == null) throw new Exception("Tablette non connectée à un réseau Wi-Fi");
                final String b = base;
                ExecutorService ex = Executors.newFixedThreadPool(64);
                final List<String> found = Collections.synchronizedList(new ArrayList<String>());
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 1; i < 255; i++) {
                    final String ip = b + i;
                    fs.add(ex.submit(() -> {
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(ip, port), 400);
                            found.add(ip);
                        } catch (Exception ignored) {}
                    }));
                }
                for (Future<?> f : fs) f.get();
                ex.shutdown();
                List<String> sorted = new ArrayList<>(found);
                Collections.sort(sorted);
                JSArray arr = new JSArray();
                for (String ip : sorted) arr.put(ip);
                JSObject res = new JSObject();
                res.put("ips", arr);
                call.resolve(res);
            } catch (Exception e) {
                call.reject("Recherche impossible : " + e.getMessage());
            }
        }).start();
    }
}
