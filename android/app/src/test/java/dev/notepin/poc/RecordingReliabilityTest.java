package dev.notepin.poc;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.*;
import java.util.*;
public class RecordingReliabilityTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  @Test public void mutableMetadataDoesNotResetCursorButNewKeyDoes() throws Exception {
    byte[] header = new byte[512];
    String original = RecordingIdentity.fingerprint(header);
    header[20] = 42;
    assertEquals(original, RecordingIdentity.fingerprint(header));
    header[256] = 1;
    assertNotEquals(original, RecordingIdentity.fingerprint(header));
    header[256] = 0; header[132] = 1;
    assertNotEquals(original, RecordingIdentity.fingerprint(header));
  }
  @Test public void fiveMinutePcmIsFinalizedAndPartialIsNotEligible() throws Exception {
    File dir = temp.newFolder();
    byte[] opus = new byte[80];
    opus[0]=(byte)0xbb; opus[1]=0x41; opus[2]=0x4b; opus[3]=(byte)0xff; opus[4]=(byte)0xfe;
    WavSegment wav = new WavSegment(dir, 7, 512);
    wav.append(opus);
    assertEquals(0, dir.listFiles((d,n)->n.endsWith(".wav")).length);
    for (int i=1;i<15000;i++) wav.append(opus);
    wav.close();
    File[] output = dir.listFiles((d,n)->n.endsWith(".wav"));
    assertEquals(1, output.length);
    assertEquals(9600044L, output[0].length());
    assertEquals(0, dir.listFiles((d,n)->n.endsWith(".partial")).length);
    byte[] header = new byte[44];
    try (FileInputStream in = new FileInputStream(output[0])) { assertEquals(44, in.read(header)); }
    ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(16000, h.getInt(24)); assertEquals(9600000, h.getInt(40));
  }
}
