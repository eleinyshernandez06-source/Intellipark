package com.intellipark.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

@SuppressLint("MissingPermission")
@CapacitorPlugin(name = "BtSerial", permissions = {
    @Permission(alias = "bt12", strings = {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}),
    @Permission(alias = "btloc", strings = {Manifest.permission.ACCESS_FINE_LOCATION})
})
public class BtSerial extends Plugin {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothSocket socket;
    private OutputStream out;
    private BroadcastReceiver rx;
    private volatile boolean closing = false;

    private String alias() { return Build.VERSION.SDK_INT >= 31 ? "bt12" : "btloc"; }

    private boolean ensure(PluginCall c) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) return true;
        requestPermissionForAlias(alias(), c, "permResult");
        return false;
    }

    @PermissionCallback
    private void permResult(PluginCall c) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            c.reject("Permiso de Bluetooth denegado");
            return;
        }
        switch (c.getMethodName()) {
            case "paired": doPaired(c); break;
            case "scan": doScan(c); break;
            case "connect": doConnect(c); break;
            default: c.reject("Accion no soportada");
        }
    }

    private BluetoothAdapter adapter(PluginCall c) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) { c.reject("Activa el Bluetooth del celular"); return null; }
        return a;
    }

    @PluginMethod public void paired(PluginCall c) { if (ensure(c)) doPaired(c); }
    @PluginMethod public void scan(PluginCall c) { if (ensure(c)) doScan(c); }
    @PluginMethod public void connect(PluginCall c) { if (ensure(c)) doConnect(c); }

    private void doPaired(PluginCall c) {
        BluetoothAdapter a = adapter(c);
        if (a == null) return;
        JSArray arr = new JSArray();
        for (BluetoothDevice d : a.getBondedDevices()) {
            JSObject o = new JSObject();
            o.put("name", d.getName());
            o.put("address", d.getAddress());
            arr.put(o);
        }
        JSObject r = new JSObject();
        r.put("devices", arr);
        c.resolve(r);
    }

    @SuppressWarnings("deprecation")
    private void doScan(PluginCall c) {
        BluetoothAdapter a = adapter(c);
        if (a == null) return;
        stopDiscovery(a);
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) return;
                    JSObject o = new JSObject();
                    o.put("name", d.getName());
                    o.put("address", d.getAddress());
                    notifyListeners("found", o);
                }
            }
        };
        getContext().registerReceiver(rx, new IntentFilter(BluetoothDevice.ACTION_FOUND));
        a.startDiscovery();
        c.resolve();
    }

    private void stopDiscovery(BluetoothAdapter a) {
        try { if (a != null) a.cancelDiscovery(); } catch (Exception ignored) {}
        if (rx != null) {
            try { getContext().unregisterReceiver(rx); } catch (Exception ignored) {}
            rx = null;
        }
    }

    @PluginMethod
    public void stopScan(PluginCall c) {
        stopDiscovery(BluetoothAdapter.getDefaultAdapter());
        c.resolve();
    }

    private void doConnect(PluginCall c) {
        final String addr = c.getString("address");
        if (addr == null) { c.reject("Falta la direccion"); return; }
        final BluetoothAdapter a = adapter(c);
        if (a == null) return;
        new Thread(() -> {
            try {
                closeSocket();
                stopDiscovery(a);
                BluetoothDevice d = a.getRemoteDevice(addr);
                BluetoothSocket s = null;
                try {
                    s = d.createRfcommSocketToServiceRecord(SPP);
                    s.connect();
                } catch (Exception e1) {
                    try { if (s != null) s.close(); } catch (Exception ignored) {}
                    s = (BluetoothSocket) d.getClass().getMethod("createRfcommSocket", int.class).invoke(d, 1);
                    s.connect();
                }
                socket = s;
                out = s.getOutputStream();
                closing = false;
                startReader(s);
                c.resolve();
            } catch (Exception e) {
                closeSocket();
                c.reject("No se pudo conectar: " + e.getMessage());
            }
        }).start();
    }

    private void startReader(final BluetoothSocket s) {
        new Thread(() -> {
            byte[] b = new byte[256];
            try {
                InputStream in = s.getInputStream();
                int n;
                while ((n = in.read(b)) > 0) {
                    JSObject o = new JSObject();
                    o.put("data", new String(b, 0, n));
                    notifyListeners("data", o);
                }
            } catch (Exception ignored) {}
            if (!closing && socket == s) {
                notifyListeners("disconnected", new JSObject());
                closeSocket();
            }
        }).start();
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        out = null;
    }

    @PluginMethod
    public void write(PluginCall c) {
        try {
            if (out == null) { c.reject("No conectado"); return; }
            out.write(c.getString("data", "").getBytes());
            out.flush();
            c.resolve();
        } catch (Exception e) {
            c.reject("Error al enviar: " + e.getMessage());
        }
    }

    @PluginMethod
    public void disconnect(PluginCall c) {
        closing = true;
        closeSocket();
        notifyListeners("disconnected", new JSObject());
        c.resolve();
    }
}
