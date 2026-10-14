package dev.notepin.poc;
import java.security.MessageDigest;
import java.util.*;
/** Only stable encryption material identifies a recording; mutable metadata does not. */
public final class RecordingIdentity {
  public static String fingerprint(byte[] header) throws Exception {
    if (header.length != 512) throw new IllegalArgumentException("Header length");
    return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(
        Protocol.join(Arrays.copyOfRange(header, 132, 144), Arrays.copyOfRange(header, 256, 512))));
  }
}
