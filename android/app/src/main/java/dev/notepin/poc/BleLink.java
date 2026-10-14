package dev.notepin.poc;
import android.bluetooth.*;
import android.content.Context;
import java.util.*;
import java.util.concurrent.*;

@SuppressWarnings("MissingPermission")
public final class BleLink implements AutoCloseable {
  private final BlockingQueue<Integer> operations = new ArrayBlockingQueue<>(16);
  private final BlockingQueue<byte[]> notifications = new ArrayBlockingQueue<>(4096);
  private volatile boolean failed;
  private BluetoothGatt gatt;
  private BluetoothGattCharacteristic write;
  private final BluetoothGattCallback callback = new BluetoothGattCallback() {
    @Override
    public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
      if (status != 0 || state == BluetoothProfile.STATE_DISCONNECTED) {
        failed = true;
        operations.offer(-1);
      } else if (state == BluetoothProfile.STATE_CONNECTED)
        operations.offer(0);
    }
    @Override
    public void onServicesDiscovered(BluetoothGatt g, int status) {
      operations.offer(status);
    }
    @Override
    public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
      operations.offer(status == 0 && mtu >= 128 ? 0 : -1);
    }
    @Override
    public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
      operations.offer(status);
    }
    @Override
    public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
      operations.offer(status);
    }
    @Override
    public void onCharacteristicChanged(
        BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
      if (!notifications.offer(value.clone()))
        failed = true;
    }
    @Override
    public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
      if (android.os.Build.VERSION.SDK_INT < 33)
        if (!notifications.offer(c.getValue().clone()))
          failed = true;
    }
  };
  private void complete(String stage) throws Exception {
    Integer s = operations.poll(15, TimeUnit.SECONDS);
    if (failed || s == null || s != 0)
      throw new IllegalStateException("BLE " + stage + " failed: status=" + s + ", disconnected=" + failed);
  }
  public BleLink(Context context, String address) throws Exception {
    try {
      BluetoothManager manager = context.getSystemService(BluetoothManager.class);
      BluetoothAdapter adapter = manager.getAdapter();
      if (adapter == null || !adapter.isEnabled())
        throw new IllegalStateException("Enable Bluetooth");
      BluetoothDevice device = adapter.getRemoteDevice(address);
      gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
      complete("connect");
      if (!gatt.requestMtu(247))
        throw new IllegalStateException("MTU request failed");
      complete("MTU");
      if (!gatt.discoverServices())
        throw new IllegalStateException("Discovery failed");
      complete("services");
      BluetoothGattService s =
          gatt.getService(UUID.fromString("00001910-0000-1000-8000-00805f9b34fb"));
      if (s == null)
        throw new IllegalStateException("Wrong target service");
      write = s.getCharacteristic(UUID.fromString("00002bb1-0000-1000-8000-00805f9b34fb"));
      BluetoothGattCharacteristic notify =
          s.getCharacteristic(UUID.fromString("00002bb0-0000-1000-8000-00805f9b34fb"));
      if (write == null || notify == null
          || (write.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0
          || (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0)
        throw new IllegalStateException("Wrong characteristic roles");
      if (!gatt.setCharacteristicNotification(notify, true))
        throw new IllegalStateException("Notify failed");
      BluetoothGattDescriptor d =
          notify.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
      if (d == null)
        throw new IllegalStateException("Missing CCCD");
      d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
      if (!gatt.writeDescriptor(d))
        throw new IllegalStateException("CCCD failed");
      complete("CCCD");
    } catch (Exception e) {
      close();
      throw e;
    }
  }
  public void send(byte[] value) throws Exception {
    write.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
    write.setValue(value);
    if (!gatt.writeCharacteristic(write))
      throw new IllegalStateException("Write rejected");
    complete("write");
  }
  public byte[] receive(long milliseconds) throws Exception {
    if (failed)
      throw new IllegalStateException("BLE disconnected or queue overflow");
    return notifications.poll(milliseconds, TimeUnit.MILLISECONDS);
  }
  @Override
  public void close() {
    if (gatt != null) {
      gatt.disconnect();
      gatt.close();
      gatt = null;
    }
  }
}
