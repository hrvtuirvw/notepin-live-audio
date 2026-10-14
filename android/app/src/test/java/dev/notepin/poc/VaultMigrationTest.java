package dev.notepin.poc;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class VaultMigrationTest {
  private static void assertSettingsUnchanged(JSONObject expected, JSONObject actual)
      throws Exception {
    assertEquals(expected.length(), actual.length());
    java.util.Iterator<String> fields = expected.keys();
    while (fields.hasNext()) {
      String field = fields.next();
      assertEquals(expected.get(field).toString(), actual.get(field).toString());
    }
  }
  @Test public void upgradingKeepsOwnerStreamingAndUnknownSettings() throws Exception {
    JSONObject retained = new JSONObject()
        .put("address", "AA:BB:CC:DD:EE:FF").put("serial", "synthetic-serial")
        .put("signature", "synthetic-signature").put("privateKeyPem", "synthetic-key\n")
        .put("ownerHandshake", "synthetic-handshake")
        .put("streamEnabled", true).put("streamUrl", "wss://receiver.example/audio")
        .put("streamToken", "unit-test-only-stream-token-123456789")
        .put("streamVad", true).put("futureSetting", new JSONObject().put("enabled", true));
    for (String provider : new String[] {"pcloud", "https-put"}) {
      JSONObject old = new JSONObject(retained.toString())
          .put("uploadEnabled", true).put("uploadProvider", provider)
          .put("uploadUrl", "https://retired.example/recordings/")
          .put("uploadBearer", "unit-test-only-old-bearer")
          .put("pcloudHost", "api.pcloud.com").put("pcloudFolderId", 123)
          .put("pcloudAuthToken", "unit-test-only-old-token");
      assertTrue(Vault.removeLegacyTransferSettings(old));
      assertSettingsUnchanged(retained, old);
      assertFalse(Vault.removeLegacyTransferSettings(old));
      assertSettingsUnchanged(retained, old);
    }
  }
}
