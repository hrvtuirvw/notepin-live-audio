package dev.notepin.poc;
import android.content.Context;
import android.security.keystore.*;
import java.io.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
public final class Vault {
  /** Retire old WAV-upload credentials without altering owner or WebSocket settings. */
  static boolean removeLegacyTransferSettings(JSONObject config) {
    boolean changed = false;
    for (String field : new String[] {"uploadEnabled", "uploadProvider", "uploadUrl",
        "uploadBearer", "pcloudHost", "pcloudFolderId", "pcloudAuthToken"}) {
      if (config.has(field)) {
        config.remove(field);
        changed = true;
      }
    }
    return changed;
  }
  private static javax.crypto.SecretKey key() throws Exception {
    KeyStore s = KeyStore.getInstance("AndroidKeyStore");
    s.load(null);
    if (!s.containsAlias("notepin-bootstrap")) {
      KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      g.init(new KeyGenParameterSpec
              .Builder("notepin-bootstrap",
                  KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (javax.crypto.SecretKey) s.getKey("notepin-bootstrap", null);
  }
  public static synchronized void store(Context c, byte[] value) throws Exception {
    JSONObject config = new JSONObject(new String(value, StandardCharsets.UTF_8));
    removeLegacyTransferSettings(config);
    value = config.toString().getBytes(StandardCharsets.UTF_8);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    android.util.AtomicFile f =
        new android.util.AtomicFile(new File(c.getFilesDir(), "bootstrap.vault"));
    FileOutputStream out = f.startWrite();
    try {
      out.write(cipher.getIV());
      out.write(cipher.doFinal(value));
      f.finishWrite(out);
    } catch (Exception e) {
      f.failWrite(out);
      throw e;
    }
  }
  public static synchronized byte[] read(Context c) throws Exception {
    byte[] b;
    try (InputStream in = new android.util.AtomicFile(new File(c.getFilesDir(), "bootstrap.vault")).openRead()) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096]; int n;
      while ((n = in.read(buffer)) != -1) {
        bytes.write(buffer, 0, n);
        if (bytes.size() > 65564) throw new IOException("Vault too large");
      }
      b = bytes.toByteArray();
    }
    if (b.length < 28) throw new IOException("Vault incomplete");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, java.util.Arrays.copyOf(b, 12)));
    byte[] value = cipher.doFinal(java.util.Arrays.copyOfRange(b, 12, b.length));
    JSONObject config = new JSONObject(new String(value, StandardCharsets.UTF_8));
    if (removeLegacyTransferSettings(config)) {
      value = config.toString().getBytes(StandardCharsets.UTF_8);
      store(c, value);
    }
    return value;
  }
}
