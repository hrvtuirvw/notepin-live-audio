package dev.notepin.poc;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.security.PrivateKey;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class CollectorService extends Service {
  private volatile boolean running;
  private Thread worker;
  private LiveStream liveStream;
  private static final java.util.concurrent.Semaphore collectorLock = new java.util.concurrent.Semaphore(1);
  private PowerManager.WakeLock wake;
  private void status(String text) {
    if (!running)
      return;
    getSharedPreferences("status", 0).edit().putString("text", text).apply();
    getSystemService(NotificationManager.class).notify(1, notification(text));
  }
  private Notification notification(String text) {
    PendingIntent stop = PendingIntent.getService(this, 1,
        new Intent(this, CollectorService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "collection")
        .setContentTitle("NotePin Live Audio")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(null, "回収を停止", stop).build())
        .build();
  }
  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null && "STOP".equals(intent.getAction())) {
      Automation.setEnabled(this, false);
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!Automation.enabled(this)) { stopSelf(); return START_NOT_STICKY; }
    if (running)
      return START_STICKY;
    getSystemService(NotificationManager.class)
        .createNotificationChannel(new NotificationChannel(
            "collection", "録音データ回収", NotificationManager.IMPORTANCE_LOW));
    startForeground(1, notification("接続準備中"));
    running = true;
    wake = getSystemService(PowerManager.class)
               .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notepin:collection");
    wake.setReferenceCounted(false);
    wake.acquire(10 * 60 * 1000L);
    liveStream = new LiveStream(this);
    worker = new Thread(this::run, "notepin-collector");
    worker.start();
    return START_STICKY;
  }
  private void run() {
    boolean acquired = false;
    try {
      collectorLock.acquire();
      acquired = true;
      runLoop();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (acquired) collectorLock.release();
    }
  }
  private void runLoop() {
    int failures = 0;
    while (running) {
      try {
        renewWake();
        if (getFilesDir().getUsableSpace() < 64L * 1024 * 1024) {
          status("空き容量不足：回収を一時停止。端末の音声は保持しています");
          Thread.sleep(60000);
          continue;
        }
        collect();
        failures = 0;
      } catch (InterruptedException e) {
        break;
      } catch (Exception e) {
        if (!running)
          break;
        status(
            "接続／回収待ち。自動再試行 (" + e.getClass().getSimpleName() + ")");
        getSharedPreferences("status", 0).edit().putString("lastError", e.getClass().getSimpleName() + ": " + e.getMessage()).apply();
        android.util.Log.w("NotePinCollector", "Collection failed", e);
        failures++;
      }
      try {
        boolean streaming = getSharedPreferences("automation", 0).getBoolean("streamFastPoll", false);
        Thread.sleep(failures == 0 ? (streaming ? 1000 : 60000) : Math.min(60000, 3000L * (1L << Math.min(failures, 5))));
      } catch (InterruptedException e) {
        break;
      }
    }
  }
  private byte[] plain(BleLink link, byte[] session, long timeout) throws Exception {
    long end = android.os.SystemClock.elapsedRealtime() + timeout;
    while (running && android.os.SystemClock.elapsedRealtime() < end) {
      byte[] b = link.receive(1000);
      if (b == null)
        continue;
      try {
        byte[] d = Protocol.aead(false, session, b);
        if (d.length >= 4)
          return Arrays.copyOfRange(d, 4, d.length);
      } catch (org.bouncycastle.crypto.InvalidCipherTextException ignored) {
      }
    }
    throw new IOException("Reply timeout");
  }
  private byte[] reply(BleLink link, byte[] session, byte[] prefix) throws Exception {
    long end = SystemClock.elapsedRealtime() + 10000;
    while (SystemClock.elapsedRealtime() < end) {
      byte[] b = plain(link, session, 10000);
      if (b.length >= prefix.length && Arrays.equals(Arrays.copyOf(b, prefix.length), prefix))
        return b;
    }
    throw new IOException("Expected reply missing");
  }
  private void collect() throws Exception {
    JSONObject config =
        new JSONObject(new String(Vault.read(this), java.nio.charset.StandardCharsets.UTF_8));
    String address = config.getString("address"), serial = config.getString("serial");
    byte[] signature = Base64.getDecoder().decode(config.getString("signature"));
    if (signature.length != 256 || serial.isEmpty())
      throw new IOException("Bootstrap target missing");
    PrivateKey key = Protocol.privateKey(
        config.getString("privateKeyPem").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    byte[] handshake = Base64.getDecoder().decode(config.getString("ownerHandshake"));
    if (handshake.length < 3 || handshake[0] != 1 || handshake[1] != 1 || handshake[2] != 0)
      throw new IOException("Owner handshake mismatch");
    try (BleLink link = new BleLink(this, address)) {
      status("機器認証中");
      for (int i = 0; i < 3; i++) {
        link.send(Protocol.join(new byte[] {16, (byte) 254, 3, (byte) i},
            Arrays.copyOfRange(signature, i * 100, Math.min(signature.length, (i + 1) * 100))));
        Thread.sleep(120);
      }
      byte[][] parts = new byte[3][];
      long end = SystemClock.elapsedRealtime() + 12000;
      while (
          Arrays.asList(parts).contains(null) && running && SystemClock.elapsedRealtime() < end) {
        byte[] b = link.receive(1000);
        if (b != null && b.length >= 4 && b[0] == 18 && b[1] == (byte) 254 && b[2] == 3
            && (b[3] & 255) < 3)
          parts[b[3] & 255] = Arrays.copyOfRange(b, 4, b.length);
      }
      for (byte[] p : parts)
        if (p == null)
          throw new IOException("Authentication timeout");
      byte[] session = Protocol.rsa(Protocol.join(parts), key);
      if (session.length != 80
          || !Arrays.equals(Protocol.aead(false, session, Arrays.copyOfRange(session, 56, 80)),
              "PLAUD.AI".getBytes()))
        throw new IOException("Authentication tag mismatch");
      link.send(Protocol.aead(true, session, Protocol.join(Protocol.ints(1), handshake)));
      int protocolVersion = HistoryProtocol.protocolVersion(reply(link, session, new byte[] {1, 1, 0}));
      status("認証成功・録音状態を確認中");
      link.send(
          Protocol.aead(true, session, Protocol.join(Protocol.ints(2), new byte[] {1, 3, 0})));
      byte[] state = reply(link, session, new byte[] {1, 3, 0});
      status("録音状態の応答受信");
      if (state.length < 18) throw new IOException("Short device state");
      getSharedPreferences("status", 0).edit().putInt("recordingFlag", state[10]&255).apply();
      long active = state[10] == 1 ? Protocol.u32(state, 11) : -1;
      getSharedPreferences("status", 0).edit().putLong("activeRecordingId", active).apply();
      android.content.SharedPreferences inventory = getSharedPreferences("history", 0);
      boolean enumerate = !config.optBoolean("streamEnabled", false) && System.currentTimeMillis() - inventory.getLong("lastListAttempt", 0) >= 15 * 60 * 1000L;
      if (enumerate) {
      inventory.edit().putLong("lastListAttempt", System.currentTimeMillis()).commit();
      status("過去録音一覧を要求中");
      // Save each observed delta before downloading: subsequent sessions may omit it.
      long requestUid = System.currentTimeMillis() / 1000;
      HistoryProtocol.Inventory listing = new HistoryProtocol.Inventory(requestUid, protocolVersion);
      link.send(Protocol.aead(true, session, Protocol.join(Protocol.ints(3),
          HistoryProtocol.listRequest(requestUid))));
      long listingEnd = SystemClock.elapsedRealtime() + 12000;
      boolean received = false;
      int listed = 0;
      while (running && !listing.complete() && SystemClock.elapsedRealtime() < listingEnd) {
        byte[] wire = link.receive(1000);
        if (wire == null) continue;
        byte[] payload = Protocol.aead(false, session, wire);
        if (payload.length < 7) continue;
        byte[] body = Arrays.copyOfRange(payload, 4, payload.length);
        getSharedPreferences("status", 0).edit().putString("listFrame",
            "type=" + (body[0]&255) + ",opcode=" + (body[1]&255) + ",length=" + body.length).apply();
        if (body[0] != 1 || body[1] != 26 || body[2] != 0) continue;
        Map<Long, Long> batch = listing.accept(body);
        listed += batch.size();
        android.content.SharedPreferences.Editor edit = inventory.edit();
        for (Map.Entry<Long, Long> entry : batch.entrySet())
          edit.putLong(address + ":" + serial + ":" + entry.getKey(), entry.getValue());
        if (!edit.commit()) throw new IOException("Inventory save failed");
        received = true;
      }
      inventory.edit().putBoolean("listReplyObserved", received)
          .putBoolean("lastListComplete", listing.complete()).putInt("lastListed", listed).commit();
      if (!received) { status("過去録音一覧の応答なし・再接続して通常回収へ戻ります"); return; }
      }
      // One unfinished archived recording per connection; active recording gets a turn too.
      long archived = -1, archiveSize = 0;
      String targetPrefix = address + ":" + serial + ":";
      for (Map.Entry<String, ?> entry : inventory.getAll().entrySet()) {
        if (!entry.getKey().startsWith(targetPrefix) || entry.getKey().endsWith(":done") || !(entry.getValue() instanceof Long)) continue;
        long id = Long.parseLong(entry.getKey().substring(targetPrefix.length()));
        long size = (Long) entry.getValue();
        if (id == active || size < 512 || size == inventory.getLong(entry.getKey() + ":done", -1)) continue;
        if (archived < 0 || id < archived) { archived = id; archiveSize = size; }
      }
      boolean lastWasHistory = inventory.getBoolean("lastWasHistory", false);
      boolean historical = archived >= 0 && (active < 0 || (!config.optBoolean("streamEnabled", false) && !lastWasHistory));
      long sid = historical ? archived : active;
      if (sid < 0) { status("録音待機中・過去録音の差分なし"); return; }
      getSharedPreferences("status", 0).edit().putLong("transferRecordingId", sid)
          .putBoolean("transferHistorical", historical).apply();
      inventory.edit().putBoolean("lastWasHistory", historical).commit();
      long expectedEnd = historical ? archiveSize : Long.MAX_VALUE;
      long transferEnd = SystemClock.elapsedRealtime() + (historical ? 60000 : archived >= 0 ? 30000 : 300000);
      android.content.SharedPreferences prefs = getSharedPreferences("cursor", 0);
      String cursorKey = address + ":" + serial + ":" + sid;
      long cursor = prefs.getLong(cursorKey, 512);
      if (cursor < 512 || (cursor - 512) % 80 != 0)
        throw new IOException("Invalid cursor");
      status("録音ヘッダー取得中");
      link.send(Protocol.aead(
          true, session, Protocol.join(Protocol.ints(enumerate ? 4 : 3), Protocol.range(sid, 0, 512))));
      ByteArrayOutputStream headerOut = new ByteArrayOutputStream();
      TreeMap<Long, byte[]> headerParts = new TreeMap<>();
      end = SystemClock.elapsedRealtime() + 10000;
      while (headerOut.size() < 512 && SystemClock.elapsedRealtime() < end) {
        byte[] p = plain(link, session, 10000);
        if (p.length >= 10 && p[0] == 2 && Protocol.u32(p, 1) == sid
            && (p[9] & 255) == p.length - 10 && Protocol.u32(p, 5) < 640)
          headerParts.put(Protocol.u32(p, 5), Arrays.copyOfRange(p, 10, p.length));
        while (headerParts.containsKey((long) headerOut.size()))
          headerOut.write(headerParts.remove((long) headerOut.size()));
      }
      byte[] header = Arrays.copyOf(headerOut.toByteArray(), 512);
      if (headerOut.size() < 512)
        throw new IOException("Incomplete header");
      String headerHash = RecordingIdentity.fingerprint(header);
      String oldHash = prefs.getString(cursorKey + ":header", "");
      if (!oldHash.isEmpty() && !oldHash.equals(headerHash)) cursor = 512;
      if (!prefs.edit().putString(cursorKey + ":header", headerHash).putLong(cursorKey, cursor).commit())
        throw new IOException("Header checkpoint failed");
      byte[] content = Protocol.contentKey(header, key);
      status("ヘッダー復号成功・音声データ待機中");
      if (historical && cursor > expectedEnd) throw new IOException("Archive size regressed");
      if (cursor < expectedEnd) link.send(Protocol.aead(
          true, session, Protocol.join(Protocol.ints(enumerate ? 5 : 4), Protocol.range(sid, cursor, historical ? Math.max(0, expectedEnd - cursor) : 0))));
      TreeMap<Long, byte[]> pending = new TreeMap<>();
      ByteArrayOutputStream remainder = new ByteArrayOutputStream();
      WavSegment segment = null;
      LiveStream.Capture streamCapture = liveStream.capture(address + ":" + serial, headerHash, sid, historical);
      org.concentus.OpusDecoder audioDecoder = new org.concentus.OpusDecoder(16000, 1);
      long lastStatus = 0;
      try {
        boolean completed = false;
        while (running && cursor < expectedEnd && SystemClock.elapsedRealtime() < transferEnd) {
          renewWake();
          byte[] p;
          try { p = plain(link, session, 10000); }
          catch (IOException e) {
            if (!historical && running && "Reply timeout".equals(e.getMessage())) continue;
            throw e;
          }
          if (p.length < 10 || p[0] != 2 || Protocol.u32(p, 1) != sid
              || (p[9] & 255) != p.length - 10)
            continue;
          long offset = Protocol.u32(p, 5);
          getSharedPreferences("status", 0).edit().putLong("lastPacketOffset", offset)
              .putInt("lastPacketBytes", p.length - 10).apply();
          if (offset == 0xffffffffL) {
            status(historical ? "過去録音の転送終端を受信" : "取得済み位置に到達・次の音声を待機");
            // Live caught-up is not completion. Keep the bounded connection open
            // like the direct PC collector, instead of discarding later packets.
            if (!historical) continue;
            if (cursor == expectedEnd && remainder.size() == 0 && pending.isEmpty()) completed = true;
            break;
          }
          byte[] chunk = Arrays.copyOfRange(p, 10, p.length);
          long networkCursor = cursor + remainder.size();
          if (offset < networkCursor || chunk.length == 0)
            continue;
          if (historical && offset + chunk.length > expectedEnd) throw new IOException("Archive exceeds listed size");
          pending.put(offset, chunk);
          if (pending.size() > 4096)
            throw new IOException("Too many gaps");
          while (pending.containsKey(cursor + remainder.size()))
            remainder.write(pending.remove(cursor + remainder.size()));
          byte[] complete = remainder.toByteArray();
          int used = 0;
          while (complete.length - used >= 80) {
            if (segment == null)
              segment =
                  new WavSegment(new File(getFilesDir(), "recordings"), sid, cursor, audioDecoder);
            byte[] decodedPcm = segment.append(
                Protocol.frame(Arrays.copyOfRange(complete, used, used + 80), header, content));
            if (streamCapture != null) streamCapture.append(((cursor - 512) / 80) * 320, decodedPcm);
            cursor += 80;
            used += 80;
            if (segment.frames >= 15000) {
              if (streamCapture != null) streamCapture.checkpoint();
              segment.close();
              segment = null;
              if (!prefs.edit().putLong(cursorKey, cursor).commit())
                throw new IOException("Cursor save failed");
            }
          }
          remainder.reset();
          remainder.write(complete, used, complete.length - used);
          if (SystemClock.elapsedRealtime() - lastStatus >= 1000) {
            if (getFilesDir().getUsableSpace() < 64L * 1024 * 1024)
              throw new IOException("Low storage");
            lastStatus = SystemClock.elapsedRealtime();
            status((historical ? "過去録音 " + sid + " · " : "回収中 · ") + ((cursor - 512) / 4000) + "秒取得済み · 5分ごとにWAV保存");
          }
        }
        completed = completed || (historical && cursor == expectedEnd && remainder.size() == 0 && pending.isEmpty());
        // Completion is committed only after the final WAV and cursor are durable.
        if (completed) {
          if (streamCapture != null) streamCapture.close();
          if (segment != null) { segment.close(); segment = null; }
          if (!prefs.edit().putLong(cursorKey, cursor).commit()) throw new IOException("Cursor save failed");
          if (!inventory.edit().putLong(cursorKey + ":done", archiveSize).commit()) throw new IOException("Completion save failed");
          status("過去録音 " + sid + " の回収完了");
        }
      } finally {
        boolean streamDurable = false;
        try { if (streamCapture != null) streamCapture.close(); streamDurable = true; }
        finally {
        if (segment != null) {
          segment.close();
          if (streamDurable && !prefs.edit().putLong(cursorKey, cursor).commit())
            throw new IOException("Cursor save failed");
        }
        }
      }
    }
  }
  private synchronized void renewWake() {
    if (running && !wake.isHeld())
      wake.acquire(10 * 60 * 1000L);
  }
  @Override
  public void onDestroy() {
    synchronized (this) {
      running = false;
      if (wake != null && wake.isHeld())
        wake.release();
    }
    getSharedPreferences("status", 0)
        .edit()
        .putString("text", "回収停止（機器の録音は継続）")
        .apply();
    if (worker != null)
      worker.interrupt();
    if (liveStream != null) liveStream.close();

    super.onDestroy();
  }
  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
