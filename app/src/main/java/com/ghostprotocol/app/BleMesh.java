package com.ghostprotocol.app;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.*;
import com.ghostprotocol.core.*;
import java.io.*;
import java.util.*;

/** Foreground BLE mesh. Ciphertexts flood once across authenticated room links. */
@SuppressLint("MissingPermission")
public final class BleMesh implements AutoCloseable {
  public interface Listener {
    void packet(byte[] plain);

    void signal(String peer, int rssi);

    void status(String text);
  }

  private static final UUID SERVICE = UUID.fromString("c0da0001-8e2f-4ab3-a171-4263cd305701");
  private static final UUID CHANNEL = UUID.fromString("c0da0002-8e2f-4ab3-a171-4263cd305701");
  private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  private static final int COMPANY = 0xFFFF;
  private final Context context;
  private final Handler handler =
      new Handler(Looper.getMainLooper()) {
        @Override
        public void dispatchMessage(Message message) {
          try {
            super.dispatchMessage(message);
          } catch (SecurityException | IllegalStateException error) {
            if (!closed) {
              listener.status("Bluetooth was interrupted. Toggle Bluetooth to reconnect.");
              close();
            }
          }
        }
      };
  private final String self;
  private final RoomCrypto crypto;
  private final Listener listener;
  private BluetoothAdapter adapter;
  private BluetoothGattServer server;
  private BluetoothGattCharacteristic serverCharacteristic;
  private BluetoothLeAdvertiser advertiser;
  private BluetoothLeScanner scanner;
  private final Map<String, Link> links = new HashMap<>();
  private final Map<String, Seen> discovered = new TreeMap<>();
  private final LinkedHashSet<String> seenCiphertexts = new LinkedHashSet<>();
  private boolean closed, advertising, scanning, connecting;
  private int frameId = new Random().nextInt(), advertisementRotation = new Random().nextInt(65536);
  private long lastRotation, lastConnect;

  private record Seen(BluetoothDevice device, long at) {}

  private static final class Link {
    String peer, address;
    BluetoothDevice device;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic characteristic;
    int mtu = 23;
    boolean ready, busy;
    long lastProgress = SystemClock.elapsedRealtime();
    long operationStartedAt;
    final ArrayDeque<byte[]> writes = new ArrayDeque<>();
    final Fragments.Assembler assembler = new Fragments.Assembler();
  }

  public BleMesh(Context context, String self, RoomCrypto crypto, Listener listener) {
    this.context = context;
    this.self = self;
    this.crypto = crypto;
    this.listener = listener;
  }

  public void start() {
    BluetoothManager manager = context.getSystemService(BluetoothManager.class);
    adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null || !adapter.isEnabled()) {
      listener.status("Turn on Bluetooth to connect this room.");
      return;
    }
    advertiser = adapter.getBluetoothLeAdvertiser();
    scanner = adapter.getBluetoothLeScanner();
    if (advertiser == null || scanner == null) {
      listener.status(
          "This phone does not support Bluetooth LE advertising. Try the training room.");
      return;
    }
    server = manager.openGattServer(context, serverCallback);
    if (server == null) {
      listener.status("Bluetooth could not open a local channel. Toggle Bluetooth, then rejoin.");
      return;
    }
    BluetoothGattService service =
        new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
    serverCharacteristic =
        new BluetoothGattCharacteristic(
            CHANNEL,
            BluetoothGattCharacteristic.PROPERTY_WRITE
                | BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE);
    serverCharacteristic.addDescriptor(
        new BluetoothGattDescriptor(
            CCC,
            BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ));
    service.addCharacteristic(serverCharacteristic);
    server.addService(service);
  }

  private final BluetoothGattServerCallback serverCallback =
      new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
          handler.post(
              () -> {
                if (closed) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                  listener.status("Bluetooth service failed to start.");
                  return;
                }
                advertise();
                scan();
                handler.post(maintenance);
              });
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int state) {
          handler.post(
              () -> {
                if (closed) return;
                String address = device.getAddress();
                if (state == BluetoothProfile.STATE_CONNECTED) {
                  Link link = links.get(address);
                  if (link == null) {
                    if (links.size() >= 10) {
                      server.cancelConnection(device);
                      return;
                    }
                    link = new Link();
                    link.address = address;
                    link.device = device;
                    links.put(address, link);
                  }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) drop(address);
              });
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
          handler.post(
              () -> {
                Link l = links.get(device.getAddress());
                if (l != null) l.mtu = mtu;
              });
        }

        @Override
        public void onDescriptorWriteRequest(
            BluetoothDevice device,
            int requestId,
            BluetoothGattDescriptor descriptor,
            boolean preparedWrite,
            boolean responseNeeded,
            int offset,
            byte[] value) {
          handler.post(
              () -> {
                if (closed) return;
                Link l = links.get(device.getAddress());
                boolean valid =
                    l != null
                        && descriptor.getUuid().equals(CCC)
                        && offset == 0
                        && !preparedWrite
                        && Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                if (responseNeeded)
                  server.sendResponse(
                      device,
                      requestId,
                      valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                      0,
                      null);
                if (valid) {
                  l.ready = true;
                  l.lastProgress = SystemClock.elapsedRealtime();
                  listener.status("Encrypted local links active.");
                }
              });
        }

        @Override
        public void onDescriptorReadRequest(
            BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
          handler.post(
              () -> {
                if (!closed)
                  server.sendResponse(
                      device,
                      requestId,
                      BluetoothGatt.GATT_SUCCESS,
                      0,
                      BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
              });
        }

        @Override
        public void onCharacteristicWriteRequest(
            BluetoothDevice device,
            int requestId,
            BluetoothGattCharacteristic characteristic,
            boolean preparedWrite,
            boolean responseNeeded,
            int offset,
            byte[] value) {
          handler.post(
              () -> {
                if (closed) return;
                Link l = links.get(device.getAddress());
                boolean valid =
                    l != null
                        && characteristic.getUuid().equals(CHANNEL)
                        && !preparedWrite
                        && offset == 0;
                if (responseNeeded)
                  server.sendResponse(
                      device,
                      requestId,
                      valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                      0,
                      null);
                if (valid) frame(l, value);
              });
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
          handler.post(
              () -> {
                if (closed) return;
                Link link = links.get(device.getAddress());
                if (link == null || link.gatt != null || !link.busy) return;
                link.busy = false;
                link.lastProgress = SystemClock.elapsedRealtime();
                if (status != BluetoothGatt.GATT_SUCCESS) {
                  drop(link.address);
                  return;
                }
                link.writes.poll();
                pump(link);
              });
        }
      };

  private void scan() {
    byte[] prefix = prefix();
    ScanFilter filter = new ScanFilter.Builder().setManufacturerData(COMPANY, prefix).build();
    scanner.startScan(
        List.of(filter),
        new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build(),
        scanCallback);
    scanning = true;
  }

  private byte[] prefix() {
    byte[] p = new byte[7];
    p[0] = 'G';
    p[1] = 'P';
    p[2] = 1;
    System.arraycopy(crypto.roomTag(), 0, p, 3, 4);
    return p;
  }

  private final ScanCallback scanCallback =
      new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
          handler.post(
              () -> {
                if (closed || result.getScanRecord() == null) return;
                byte[] b = result.getScanRecord().getManufacturerSpecificData(COMPANY);
                String id = crypto.readBeacon(b);
                if (id == null) return;
                if (id.equals(self)) return;
                discovered.put(id, new Seen(result.getDevice(), SystemClock.elapsedRealtime()));
                listener.signal(id, result.getRssi());
              });
        }

        @Override
        public void onScanFailed(int error) {
          handler.post(
              () ->
                  listener.status(
                      "Bluetooth scanning failed ("
                          + error
                          + "). Leave and rejoin after toggling Bluetooth."));
        }
      };

  private void advertise() {
    if (closed) return;
    if (advertising) advertiser.stopAdvertising(advertiseCallback);
    byte[] b = crypto.beacon(self, advertisementRotation++);
    advertiser.startAdvertising(
        new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setTimeout(0)
            .build(),
        new AdvertiseData.Builder()
            .addManufacturerData(COMPANY, b)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build(),
        advertiseCallback);
    lastRotation = SystemClock.elapsedRealtime();
  }

  private final AdvertiseCallback advertiseCallback =
      new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settings) {
          handler.post(
              () -> {
                advertising = true;
                listener.status("Discovering phones in this room…");
              });
        }

        @Override
        public void onStartFailure(int error) {
          handler.post(
              () -> {
                advertising = false;
                listener.status(
                    "Bluetooth advertising failed ("
                        + error
                        + "). Check Bluetooth and location settings.");
              });
        }
      };
  private final Runnable maintenance =
      new Runnable() {
        @Override
        public void run() {
          if (closed) return;
          long now = SystemClock.elapsedRealtime();
          if (now - lastRotation >= 15000) advertise();
          for (Link l : new ArrayList<>(links.values()))
            if ((!l.ready && now - l.lastProgress > 12000)
                || (l.busy && now - l.operationStartedAt > 10000)) drop(l.address);
          connecting = links.values().stream().anyMatch(l -> l.gatt != null && !l.ready);
          long outgoing = links.values().stream().filter(l -> l.gatt != null).count();
          if (!connecting && outgoing < 3 && now - lastConnect > 1500) {
            for (var entry : discovered.entrySet()) {
              Seen seen = entry.getValue();
              String id = entry.getKey();
              if (self.compareTo(id) >= 0
                  || now - seen.at > 10000
                  || links.values().stream().anyMatch(link -> id.equals(link.peer))
                  || links.containsKey(seen.device.getAddress())) continue;
              Link l = new Link();
              l.peer = id;
              l.device = seen.device;
              l.address = seen.device.getAddress();
              links.put(l.address, l);
              lastConnect = now;
              connecting = true;
              l.gatt =
                  l.device.connectGatt(
                      context, false, clientCallback, BluetoothDevice.TRANSPORT_LE);
              break;
            }
          }
          handler.postDelayed(this, 1000);
        }
      };
  private final BluetoothGattCallback clientCallback =
      new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
          handler.post(
              () -> {
                if (closed) {
                  g.close();
                  return;
                }
                Link l = clientLink(g);
                if (l == null) {
                  g.close();
                  return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED
                    && status == BluetoothGatt.GATT_SUCCESS) {
                  l.lastProgress = SystemClock.elapsedRealtime();
                  g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                  if (!g.requestMtu(517)) g.discoverServices();
                } else if (state == BluetoothProfile.STATE_DISCONNECTED
                    || status != BluetoothGatt.GATT_SUCCESS) drop(l.address);
              });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
          handler.post(
              () -> {
                Link l = clientLink(g);
                if (l != null) {
                  l.mtu = status == BluetoothGatt.GATT_SUCCESS ? mtu : 23;
                  l.lastProgress = SystemClock.elapsedRealtime();
                  g.discoverServices();
                }
              });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
          handler.post(
              () -> {
                Link l = clientLink(g);
                if (l == null || closed) return;
                BluetoothGattService service = g.getService(SERVICE);
                if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                  drop(l.address);
                  return;
                }
                l.characteristic = service.getCharacteristic(CHANNEL);
                if (l.characteristic == null) {
                  drop(l.address);
                  return;
                }
                BluetoothGattDescriptor d = l.characteristic.getDescriptor(CCC);
                if (d == null || !g.setCharacteristicNotification(l.characteristic, true)) {
                  drop(l.address);
                  return;
                }
                d.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                if (!g.writeDescriptor(d)) drop(l.address);
              });
        }

        @Override
        public void onDescriptorWrite(
            BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
          handler.post(
              () -> {
                Link l = clientLink(g);
                if (l == null || closed) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                  l.ready = true;
                  l.lastProgress = SystemClock.elapsedRealtime();
                  listener.status("Encrypted local links active.");
                  pump(l);
                } else drop(l.address);
              });
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
          handler.post(
              () -> {
                Link l = clientLink(g);
                if (l == null || closed) return;
                l.busy = false;
                l.lastProgress = SystemClock.elapsedRealtime();
                if (status != BluetoothGatt.GATT_SUCCESS) {
                  drop(l.address);
                  return;
                }
                l.writes.poll();
                pump(l);
              });
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
          byte[] b = characteristic.getValue();
          handler.post(
              () -> {
                Link l = clientLink(g);
                if (l != null && !closed) frame(l, b);
              });
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
          handler.post(
              () -> {
                Link l = clientLink(g);
                if (l != null && !closed) frame(l, value);
              });
        }
      };

  private Link clientLink(BluetoothGatt g) {
    Link link = links.get(g.getDevice().getAddress());
    return link != null && link.gatt == g ? link : null;
  }

  public void send(byte[] plain) {
    if (closed) return;
    byte[] encrypted = crypto.encrypt(plain);
    remember(encrypted);
    flood(encrypted, null);
  }

  private boolean remember(byte[] b) {
    String h = RoomCrypto.hex(RoomCrypto.hash(b));
    if (!seenCiphertexts.add(h)) return false;
    while (seenCiphertexts.size() > 4096) seenCiphertexts.remove(seenCiphertexts.iterator().next());
    return true;
  }

  private void frame(Link link, byte[] value) {
    try {
      byte[] cipher = link.assembler.accept(value);
      link.lastProgress = SystemClock.elapsedRealtime();
      if (cipher == null || !remember(cipher)) return;
      byte[] plain = crypto.decrypt(cipher);
      listener.packet(plain);
      flood(cipher, link.address);
    } catch (Exception ignored) {
      /* unauthenticated or incomplete messages never enter game state */
    }
  }

  private void flood(byte[] cipher, String except) {
    for (Link link : new ArrayList<>(links.values())) {
      if (!link.ready || link.address.equals(except)) continue;
      List<byte[]> frames = Fragments.split(cipher, link.mtu, ++frameId);
      if (link.writes.size() + frames.size() > 2000) {
        drop(link.address);
        continue;
      }
      link.writes.addAll(frames);
      pump(link);
    }
  }

  // ATT operations remain ordered per connection; unrelated peers can make progress together.
  private void pump(Link link) {
    if (closed || !link.ready || link.busy || link.writes.isEmpty()) return;
    link.busy = true;
    link.operationStartedAt = SystemClock.elapsedRealtime();
    byte[] value = link.writes.peek();
    boolean accepted;
    if (link.gatt != null) {
      link.characteristic.setValue(value);
      link.characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
      accepted = link.gatt.writeCharacteristic(link.characteristic);
    } else if (Build.VERSION.SDK_INT >= 33) {
      accepted =
          server.notifyCharacteristicChanged(link.device, serverCharacteristic, true, value)
              == BluetoothStatusCodes.SUCCESS;
    } else {
      // All calls run on this handler and frame byte arrays are immutable.
      serverCharacteristic.setValue(value);
      accepted = server.notifyCharacteristicChanged(link.device, serverCharacteristic, true);
    }
    if (!accepted) {
      link.busy = false;
      drop(link.address);
    }
  }

  private void drop(String address) {
    Link l = links.remove(address);
    if (l != null) {
      if (l.gatt != null) {
        l.gatt.disconnect();
        l.gatt.close();
      } else if (server != null) server.cancelConnection(l.device);
    }
  }

  public int connectedCount() {
    return (int) links.values().stream().filter(l -> l.ready).count();
  }

  @Override
  public void close() {
    closed = true;
    handler.removeCallbacksAndMessages(null);
    release(
        () -> {
          if (scanner != null && scanning) scanner.stopScan(scanCallback);
        });
    release(
        () -> {
          if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        });
    for (Link link : new ArrayList<>(links.values()))
      if (link.gatt != null) {
        release(link.gatt::disconnect);
        release(link.gatt::close);
      }
    release(
        () -> {
          if (server != null) server.close();
        });
    links.clear();
    discovered.clear();
    seenCiphertexts.clear();
  }

  private void release(Runnable action) {
    try {
      action.run();
    } catch (SecurityException | IllegalStateException ignored) {
      /* Continue releasing other radio resources. */
    }
  }
}
