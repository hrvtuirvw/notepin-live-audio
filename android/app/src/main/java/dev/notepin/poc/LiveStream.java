package dev.notepin.poc;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.JSONObject;
/** Local durable outbox. Remote ACK is required before deleting only outbox copies. */
public final class LiveStream implements AutoCloseable {
  private static final Object OUTBOX_LOCK = new Object();
  private final Context context;
  private final File directory;
  private volatile boolean running = true;
  private volatile WebSocket socket;
  private final Thread worker;
  private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
      .connectTimeout(10, TimeUnit.SECONDS).build();
  public LiveStream(Context c) {
    context = c.getApplicationContext();
    directory = new File(context.getFilesDir(), "stream-outbox"); directory.mkdirs();
    worker = new Thread(this::sendLoop, "notepin-stream"); worker.start();
  }
  private void status(String s) { context.getSharedPreferences("status", 0).edit().putString("stream", s).apply(); }
  private static JSONObject config(Context c) throws Exception {
    return new JSONObject(new String(Vault.read(c), StandardCharsets.UTF_8));
  }
  public static void check(String endpoint, String token) {
    HttpUrl url = HttpUrl.parse(endpoint.replaceFirst("^wss:", "https:"));
    if (!endpoint.startsWith("wss://") || url == null || !url.encodedPath().equals("/audio")
        || !url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || url.fragment() != null
        || token.length() < 24 || token.contains("\n") || token.contains("\r"))
      throw new IllegalArgumentException("WSS /audio URL and 24+ character token required");
  }
  public static String hash(byte[] b) throws Exception {
    StringBuilder s = new StringBuilder();
    for (byte v : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format(Locale.ROOT, "%02x", v & 255));
    return s.toString();
  }
  public final class Capture implements AutoCloseable {
    private final String source, key, ledger;
    private final long sid;
    private final boolean historical;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private long start = -1, end, utterance = -1, processed;
    private boolean utteranceEnd, closed;
    private final com.konovalov.vad.webrtc.VadWebRTC detector;
    private final SpeechGate gate;
    private Capture(String target, String fingerprint, long recording, boolean history) throws Exception {
      source = hash(target.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
      key = hash(fingerprint.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
      sid = recording; historical = history; ledger = source + ":" + sid + ":" + key;
      detector = config(context).optBoolean("streamVad", false) ? new com.konovalov.vad.webrtc.VadWebRTC(
          com.konovalov.vad.webrtc.config.SampleRate.SAMPLE_RATE_16K,
          com.konovalov.vad.webrtc.config.FrameSize.FRAME_SIZE_320,
          com.konovalov.vad.webrtc.config.Mode.NORMAL, 0, 0) : null;
      gate = detector == null ? null : new SpeechGate(new SpeechGate.Sink() {
        public void audio(long sample, byte[] frame, long segment) throws Exception {
          utterance = segment; appendAudio(sample, frame);
        }
        public void ended() throws Exception { utteranceEnd = true; flush(); utteranceEnd = false; }
      });
      synchronized (OUTBOX_LOCK) {
        long saved = context.getSharedPreferences("stream-cursor", 0).getLong(ledger, 0);
        File[] pending = directory.listFiles((d,n) -> n.endsWith(".json"));
        if (pending != null) for (File f : pending) {
          JSONObject m = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
          if (m.getString("id").startsWith(ledger + ":")) saved = Math.max(saved, m.getLong("start_sample") + Base64.getDecoder().decode(m.getString("pcm")).length / 2);
        }
        if (!context.getSharedPreferences("stream-cursor", 0).edit().putLong(ledger, saved).commit()) throw new IOException("Stream recovery failed");
      }
    }
    public void append(long sample, byte[] frame) throws Exception {
      long saved = context.getSharedPreferences("stream-cursor", 0).getLong(ledger, 0);
      if (sample < saved) return; // WAV replay must not create a different PCM duplicate.
      if (closed) throw new IOException("Capture closed");
      if (frame.length != 640) throw new IOException("Expected 20ms PCM frame");
      processed = sample + frame.length / 2;
      if (gate != null) gate.append(sample, frame, detector.isSpeech(frame));
      else appendAudio(sample, frame);
    }
    private void appendAudio(long sample, byte[] frame) throws Exception {
      if (start >= 0 && (sample != end || pcm.size() >= 32000)) flush();
      if (start < 0) start = sample;
      pcm.write(frame); end = sample + frame.length / 2;

    }
    private void flush() throws Exception {
      synchronized (OUTBOX_LOCK) {
      if (pcm.size() == 0) return;
      byte[] bytes = pcm.toByteArray();
      JSONObject message = new JSONObject().put("v", 1).put("type", "audio")
          .put("source", source).put("recording_key", key).put("recording_id", sid)
          .put("start_sample", start).put("rate", 16000).put("channels", 1).put("format", "s16le")
          .put("mode", historical ? "history" : "live").put("sha256", hash(bytes))
          .put("recording_start_jst", RecordingTime.japan(sid))
          .put("id", ledger + ":" + start).put("pcm", Base64.getEncoder().encodeToString(bytes));
      if (gate != null) message.put("vad", "webrtc-normal").put("utterance_start_sample", utterance)
          .put("utterance_end", utteranceEnd);
      String name = (historical ? "H-" : "L-") + source + "-" + sid + "-" + key + "-" + String.format(Locale.ROOT, "%016d", start) + ".json";
      android.util.AtomicFile f = new android.util.AtomicFile(new File(directory, name));
      FileOutputStream out = f.startWrite();
      try { out.write(message.toString().getBytes(StandardCharsets.UTF_8)); f.finishWrite(out); }
      catch (Exception e) { f.failWrite(out); throw e; }
      // Ledger survives ACK deletion, preventing replay of an older WAV checkpoint.
      if (!context.getSharedPreferences("stream-cursor", 0).edit().putLong(ledger, end).commit())
        throw new IOException("Stream checkpoint failed");
      pcm.reset(); start = -1;
      }
    }
    public void checkpoint() throws Exception {
      if (closed) return;
      if (gate != null) gate.finish();
      flush();
      // Suppressed silence is processed too. Commit only after all selected PCM is durable.
      if (processed > 0 && !context.getSharedPreferences("stream-cursor", 0).edit()
          .putLong(ledger, Math.max(processed, context.getSharedPreferences("stream-cursor", 0).getLong(ledger, 0))).commit())
        throw new IOException("VAD checkpoint failed");
    }
    public void close() throws Exception {
      if (closed) return;
      checkpoint(); closed = true; if (detector != null) detector.close();
    }
  }
  public Capture capture(String target, String fingerprint, long sid, boolean history) throws Exception {
    if (!config(context).optBoolean("streamEnabled", false)) return null;
    return new Capture(target, fingerprint, sid, history);
  }
  private void sendLoop() {
    while (running) {
      WebSocket connection = null;
      try {
        JSONObject settings = config(context);
        if (!settings.optBoolean("streamEnabled", false)) { status("PC即時転送: 無効"); Thread.sleep(2000); continue; }
        String url = settings.getString("streamUrl"), token = settings.getString("streamToken"); check(url, token);
        File[] files = directory.listFiles((d,n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) { status("PC即時転送: 接続待機・未送信なし"); Thread.sleep(500); continue; }
        Arrays.sort(files, Comparator.comparing((File f) -> f.getName().startsWith("L-") ? 0 : 1).thenComparing(File::getName));
        BlockingQueue<String> replies = new LinkedBlockingQueue<>(4);
        CountDownLatch open = new CountDownLatch(1);
        connection = client.newWebSocket(new Request.Builder().url(url).header("Authorization", "Bearer " + token).build(), new WebSocketListener() {
          @Override public void onOpen(WebSocket ws, Response response) { open.countDown(); }
          @Override public void onMessage(WebSocket ws, String text) { replies.offer(text); }
          @Override public void onFailure(WebSocket ws, Throwable error, Response response) { replies.offer("{}"); open.countDown(); }
          @Override public void onClosed(WebSocket ws, int code, String reason) { replies.offer("{}"); }
        });
        socket = connection;
        if (!open.await(12, TimeUnit.SECONDS)) throw new IOException("Connect timeout");
        // Keep a persistent socket; rescan after each ACK so newly queued live audio wins.
        while (running && config(context).optBoolean("streamEnabled", false)) {
          JSONObject current = config(context);
          if (!url.equals(current.optString("streamUrl")) || !token.equals(current.optString("streamToken"))) break;
          files = directory.listFiles((d,n) -> n.endsWith(".json"));
          if (files == null || files.length == 0) { Thread.sleep(200); continue; }
          Arrays.sort(files, Comparator.comparing((File f) -> f.getName().startsWith("L-") ? 0 : 1).thenComparing(File::getName));
          File file = files[0];
          String text;
          synchronized (OUTBOX_LOCK) { text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
          JSONObject chunk = new JSONObject(text);
          if (!connection.send(text)) throw new IOException("Send rejected");
          String reply = replies.poll(15, TimeUnit.SECONDS);
          if (reply == null) throw new IOException("ACK timeout");
          JSONObject ack = new JSONObject(reply);
          if (!ack.optString("type").equals("ack") || !ack.optString("id").equals(chunk.getString("id"))
              || !ack.optString("sha256").equals(chunk.getString("sha256"))) throw new IOException("ACK mismatch");
          if (!file.delete()) throw new IOException("Outbox cleanup failed");
          status("PC即時転送: 受信確認済み · 未送信 " + Math.max(0, files.length - 1) + "チャンク");
        }
      } catch (InterruptedException e) { break; }
      catch (Exception e) { status("PC即時転送: 再送待ち（" + e.getClass().getSimpleName() + "）");
        try { Thread.sleep(3000); } catch (InterruptedException stop) { break; } }
      finally { if (connection != null) connection.cancel(); socket = null; }
    }
  }
  @Override public void close() {
    running = false; if (socket != null) socket.cancel(); worker.interrupt();
    client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll();
  }
}
