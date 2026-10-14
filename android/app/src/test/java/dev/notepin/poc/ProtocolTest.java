package dev.notepin.poc;
import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;
public class ProtocolTest {
  @Test
  public void unsignedRangeAndBoundaries() {
    assertEquals(0xffffffffL, Protocol.u32(Protocol.ints(0xffffffffL), 0));
    assertArrayEquals(
        new byte[] {1, 28, 0, 7, 0, 0, 0, 0, 2, 0, 0, 80, 0, 0, 0}, Protocol.range(7, 512, 80));
    try {
      Protocol.ints(0x100000000L);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
  @Test
  public void foreignSessionsAndMalformedPacketsRejected() {
    byte[] packet = Protocol.join(new byte[] {2}, Protocol.ints(7, 512), new byte[] {3, 1, 2, 3});
    assertArrayEquals(new byte[] {1, 2, 3}, Protocol.packet(packet, 7, 512));
    assertNull(Protocol.packet(packet, 8, 512));
    assertNull(Protocol.packet(packet, 7, 592));
    assertNull(Protocol.packet(Arrays.copyOf(packet, 12), 7, 512));
    assertNull(Protocol.packet(new byte[9], 7, 512));
  }
  @Test
  public void authenticatedRoundTripAndTamperDetection() throws Exception {
    byte[] session = new byte[80];
    for (int i = 0; i < 56; i++) session[i] = (byte) i;
    byte[] message = Protocol.range(7, 512, 80);
    byte[] encrypted = Protocol.aead(true, session, message);
    assertArrayEquals(message, Protocol.aead(false, session, encrypted));
    encrypted[0] ^= 1;
    try {
      Protocol.aead(false, session, encrypted);
      fail();
    } catch (org.bouncycastle.crypto.InvalidCipherTextException expected) {
    }
  }
  @Test
  public void perFrameNonceResetMatchesKnownChaCha20Vector() {
    byte[] header = new byte[512];
    byte[] key = new byte[32];
    byte[] frame = Protocol.frame(new byte[80], header, key);
    assertEquals("76b8e0ada0f13d90405d6ae55386bd28",
        java.util.HexFormat.of().formatHex(Arrays.copyOf(frame, 16)));
    assertArrayEquals(new byte[80], Protocol.frame(frame, header, key));
  }
}
