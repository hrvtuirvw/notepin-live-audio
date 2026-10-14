package dev.notepin.poc;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.*;
import java.io.*;
import org.json.JSONObject;
public final class MainActivity extends Activity {
  private TextView status;
  private final Handler handler = new Handler();
  private final Runnable refresh = new Runnable() {
    public void run() {
      status.setText(getSharedPreferences("status", 0).getString("text", "未開始") + "\n"
          + "\n自動回収: " + (Automation.enabled(MainActivity.this) ? "有効" : "停止")
          + "\n" + getSharedPreferences("status", 0).getString("stream", "PC即時転送: 無効")
          + recordingSummary() + storageSummary() + "\n録音の開始・停止・削除は行いません");
      handler.postDelayed(this, 2000);
    }
  };
  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(32, 32, 32, 32);
    layout.setOnApplyWindowInsetsListener((view, insets) -> {
      android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
          | android.view.WindowInsets.Type.displayCutout());
      view.setPadding(32 + bars.left, 32 + bars.top, 32 + bars.right, 32 + bars.bottom);
      return insets;
    });
    ScrollView scroll = new ScrollView(this);
    scroll.addView(layout);
    setContentView(scroll);
    TextView title = new TextView(this);
    title.setText("NotePin Live Audio "
        + "PoC\n所有者用認証JSONを取り込み、公式アプリのBluetooth接続を解放してから開始し"
        + "てください。");
    layout.addView(title);
    Button importButton = new Button(this);
    importButton.setText("認証JSONを取り込む");
    layout.addView(importButton);
    importButton.setOnClickListener(v
        -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                      .setType("application/json")
                                      .addCategory(Intent.CATEGORY_OPENABLE),
            10));
    Button start = new Button(this);
    start.setText("自動回収を開始（再起動後も再開）");
    layout.addView(start);
    start.setOnClickListener(v -> {
      if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
              != PackageManager.PERMISSION_GRANTED
          || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
              != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(Build.VERSION.SDK_INT >= 33
                ? new String[] {Manifest.permission.BLUETOOTH_CONNECT,
                      Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS}
                : new String[] {Manifest.permission.BLUETOOTH_CONNECT,
                      Manifest.permission.BLUETOOTH_SCAN},
            11);
        return;
      }
      if (!new File(getFilesDir(), "bootstrap.vault").exists()) {
        Toast.makeText(this, "先に認証JSONを取り込んでください", Toast.LENGTH_LONG).show(); return;
      }
      Automation.setEnabled(this, true);
      Automation.recover(this);
    });
    Button stop = new Button(this);
    stop.setText("回収停止（機器の録音は継続）");
    layout.addView(stop);
    stop.setOnClickListener(v -> {
      Automation.setEnabled(this, false); stopService(new Intent(this, CollectorService.class));
    });
    Button stream = new Button(this); stream.setText("PCへのWebSocket転送を設定／停止"); layout.addView(stream);
    stream.setOnClickListener(v -> streamSettings());
    Button battery = new Button(this); battery.setText("端末のバックグラウンド設定を開く"); layout.addView(battery);
    battery.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:" + getPackageName()))));
    Button export = new Button(this);
    export.setText("保存済みWAVをフォルダーへ書き出す");
    layout.addView(export);
    export.setOnClickListener(
        v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 12));
    status = new TextView(this);
    layout.addView(status);
  }
  @Override
  protected void onResume() {
    super.onResume();
    handler.post(refresh);
    Automation.recover(this);
  }
  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }
  private String recordingSummary() {
    android.content.SharedPreferences prefs = getSharedPreferences("status", 0);
    long active = prefs.getLong("activeRecordingId", -1);
    long transfer = prefs.getLong("transferRecordingId", -1);
    StringBuilder text = new StringBuilder();
    if (active > 0) text.append("\n現在録音の開始: ").append(RecordingTime.japan(active))
        .append("\n録音ID: ").append(active).append("（最終確認値）");
    if (transfer > 0 && transfer != active) text.append("\n回収対象の開始: ")
        .append(RecordingTime.japan(transfer)).append(" / ID: ").append(transfer);
    if (active > 0 || transfer > 0) text.append("\n※日時は機器の時計に基づく推定開始時刻です。");
    return text.toString();
  }
  private String storageSummary() {
    File[] files = new File(getFilesDir(), "recordings").listFiles((d, n) -> n.endsWith(".wav"));
    long bytes = 0; int count = files == null ? 0 : files.length;
    if (files != null) for (File f : files) bytes += f.length();
    StringBuilder text = new StringBuilder("\n端末に保存: " + count + "件 / " + (bytes / 1024 / 1024) + " MB");
    java.util.TreeSet<Long> ids = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
    if (files != null) for (File f : files) {
      try { long id = Long.parseLong(f.getName().split("-", 2)[0]);
        if (id > 0 && id <= 0xffffffffL) ids.add(id);
      } catch (NumberFormatException ignored) {}
    }
    if (!ids.isEmpty()) text.append("\n保存済み録音の開始日時（新しい順）:");
    int shown = 0;
    for (long id : ids) {
      if (shown++ == 8) { text.append("\nほか ").append(ids.size() - 8).append("録音"); break; }
      text.append("\n").append(RecordingTime.japan(id)).append(" / ID: ").append(id);
    }
    if (!ids.isEmpty()) text.append("\n※日時は機器の時計に基づく推定開始時刻です。");
    return text.toString();
  }
  private void streamSettings() {
    final JSONObject config;
    try { config = new JSONObject(new String(Vault.read(this), java.nio.charset.StandardCharsets.UTF_8)); }
    catch (Exception e) { Toast.makeText(this, "先に認証JSONを取り込んでください", Toast.LENGTH_LONG).show(); return; }
    LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(32,16,32,16);
    EditText url = new EditText(this); url.setHint("wss://受信PCのホスト名/audio");
    url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    url.setText(config.optString("streamUrl")); form.addView(url);
    EditText token = new EditText(this); token.setHint("受信トークン（空欄なら保存済み）"); token.setSaveEnabled(false);
    token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); form.addView(token);
    android.widget.CheckBox vad = new android.widget.CheckBox(this); vad.setText("VAD：発話のみ送信（前400ms・後800ms）"); vad.setChecked(config.optBoolean("streamVad", false)); form.addView(vad);
    TextView note = new TextView(this); note.setText("有効化後に取得した音声を約1秒単位で送信します。通信断は端末で再送待ち。モバイル通信でも送信します。保存済みWAVは保持します。"); form.addView(note);
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("PC即時転送")
        .setView(form).setPositiveButton("保存して有効化", null).setNegativeButton("キャンセル", null)
        .setNeutralButton("転送を停止", (d,w) -> {
          try { config.put("streamEnabled", false); getSharedPreferences("automation", 0).edit().putBoolean("streamFastPoll", false).apply(); Vault.store(this, config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
          catch (Exception e) { Toast.makeText(this, "保存できませんでした", Toast.LENGTH_LONG).show(); }
        }).create();
    dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      try {
        String endpoint = url.getText().toString().trim(), auth = token.getText().toString().trim();
        if (auth.isEmpty()) auth = config.optString("streamToken");
        LiveStream.check(endpoint, auth);
        config.put("streamUrl", endpoint).put("streamToken", auth).put("streamEnabled", true).put("streamVad", vad.isChecked());
        getSharedPreferences("automation", 0).edit().putBoolean("streamFastPoll", true).apply();
        Vault.store(this, config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); dialog.dismiss();
        Toast.makeText(this, "設定を保存しました。自動回収中に送信します", Toast.LENGTH_LONG).show();
      } catch (Exception e) { Toast.makeText(this, "wss://…/audio と24文字以上のトークンを確認してください", Toast.LENGTH_LONG).show(); }
    })); dialog.show();
  }
  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null)
      return;
    if (request == 12) {
      new Thread(() -> {
        try {
          android.net.Uri tree = data.getData();
          android.net.Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
              tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
          File[] files =
              new File(getFilesDir(), "recordings").listFiles((dir, name) -> name.endsWith(".wav"));
          int count = 0;
          if (files != null)
            for (File file : files) {
              android.net.Uri dest = android.provider.DocumentsContract.createDocument(
                  getContentResolver(), parent, "audio/wav", file.getName());
              try (InputStream in = new FileInputStream(file);
                  OutputStream out = getContentResolver().openOutputStream(dest)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
              }
              count++;
            }
          final int total = count;
          runOnUiThread(
              () -> Toast.makeText(this, total + "件を書き出しました", Toast.LENGTH_LONG).show());
        } catch (Exception e) {
          runOnUiThread(() -> Toast.makeText(this, "書き出し失敗", Toast.LENGTH_LONG).show());
        }
      }).start();
      return;
    }
    if (request == 10)
      try (InputStream in = getContentResolver().openInputStream(data.getData())) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
          out.write(buf, 0, n);
          if (out.size() > 65536)
            throw new IOException("Too large");
        }
        JSONObject c = new JSONObject(out.toString("UTF-8"));
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(c.getString("address"))
            || c.getString("serial").isEmpty()
            || java.util.Base64.getDecoder().decode(c.getString("signature")).length != 256)
          throw new IOException("Target/signature invalid");
        Protocol.privateKey(c.getString("privateKeyPem").getBytes());
        byte[] h = java.util.Base64.getDecoder().decode(c.getString("ownerHandshake"));
        if (h.length < 3 || h[0] != 1 || h[1] != 1 || h[2] != 0)
          throw new IOException("Handshake invalid");
        if (Automation.enabled(this)) throw new IOException("Stop collection before import");
        Vault.store(this, out.toByteArray());
        Toast.makeText(this, "認証情報を暗号化して保存しました", Toast.LENGTH_LONG).show();
      } catch (Exception e) {
        Toast.makeText(this, "取り込み失敗: 認証JSONの形式を確認してください", Toast.LENGTH_LONG)
            .show();
      }
  }
}
