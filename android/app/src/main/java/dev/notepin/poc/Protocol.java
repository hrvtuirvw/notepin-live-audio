package dev.notepin.poc;
import java.nio.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.*;

public final class Protocol {
  public static long u32(byte[] b, int i) {
    return Integer.toUnsignedLong(ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
  }
  public static byte[] ints(long... v) {
    ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (long x : v) {
      if (x < 0 || x > 0xffffffffL)
        throw new IllegalArgumentException("uint32");
      b.putInt((int) x);
    }
    return b.array();
  }
  public static byte[] join(byte[]... parts) {
    int n = 0;
    for (byte[] p : parts) n += p.length;
    byte[] b = new byte[n];
    int i = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, b, i, p.length);
      i += p.length;
    }
    return b;
  }
  public static PrivateKey privateKey(byte[] pem) throws Exception {
    String s = new String(pem, java.nio.charset.StandardCharsets.US_ASCII);
    byte[] der = Base64.getMimeDecoder().decode(s.replaceAll("-----[^-]+-----", ""));
    // Existing bootstrap may be PKCS#1 or PKCS#8.
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (GeneralSecurityException e) {
      org.bouncycastle.asn1.pkcs.PrivateKeyInfo info =
          new org.bouncycastle.asn1.pkcs.PrivateKeyInfo(
              new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                  org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.rsaEncryption,
                  org.bouncycastle.asn1.DERNull.INSTANCE),
              org.bouncycastle.asn1.pkcs.RSAPrivateKey.getInstance(der));
      return KeyFactory.getInstance("RSA").generatePrivate(
          new PKCS8EncodedKeySpec(info.getEncoded()));
    }
  }
  public static byte[] rsa(byte[] data, PrivateKey key) throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    c.init(Cipher.DECRYPT_MODE, key);
    return c.doFinal(data);
  }
  public static byte[] aead(boolean encrypt, byte[] session, byte[] data) throws Exception {
    ChaCha20Poly1305 c = new ChaCha20Poly1305();
    c.init(encrypt,
        new AEADParameters(new KeyParameter(Arrays.copyOfRange(session, 0, 32)), 128,
            Arrays.copyOfRange(session, 32, 44), Arrays.copyOfRange(session, 44, 56)));
    byte[] out = new byte[c.getOutputSize(data.length)];
    int n = c.processBytes(data, 0, data.length, out, 0);
    n += c.doFinal(out, n);
    return Arrays.copyOf(out, n);
  }
  public static byte[] contentKey(byte[] header, PrivateKey key) throws Exception {
    if (header.length != 512
        || !Arrays.equals(Arrays.copyOf(header, 8),
            "PLAUD.AI".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        || u32(header, 144) != 80)
      throw new IllegalArgumentException("Unexpected recording header");
    byte[] k = rsa(Arrays.copyOfRange(header, 256, 512), key);
    if (k.length != 32)
      throw new IllegalArgumentException("Content key length");
    return k;
  }
  public static byte[] frame(byte[] encrypted, byte[] header, byte[] key) {
    if (encrypted.length != 80)
      throw new IllegalArgumentException("Frame length");
    ChaCha7539Engine c = new ChaCha7539Engine();
    c.init(
        false, new ParametersWithIV(new KeyParameter(key), Arrays.copyOfRange(header, 132, 144)));
    byte[] out = new byte[80];
    c.processBytes(encrypted, 0, 80, out, 0);
    return out;
  }
  public static byte[] range(long sid, long offset, long length) {
    return join(new byte[] {1, 28, 0}, ints(sid, offset, length));
  }
  public static byte[] packet(byte[] p, long sid, long offset) {
    if (p.length < 10 || p[0] != 2 || u32(p, 1) != sid || u32(p, 5) != offset
        || (p[9] & 255) != p.length - 10)
      return null;
    return Arrays.copyOfRange(p, 10, p.length);
  }
}
