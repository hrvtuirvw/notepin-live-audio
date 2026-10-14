package dev.notepin.poc;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public final class SpeechGateTest {
  static final class Output implements SpeechGate.Sink {
    final List<Long> positions = new ArrayList<>();
    int ends; long segment;
    public void audio(long s, byte[] p, long u) { positions.add(s); segment = u; }
    public void ended() { ends++; }
  }
  @Test public void silenceSuppressedPreRollAndHangover() throws Exception {
    Output o = new Output(); SpeechGate g = new SpeechGate(o); byte[] f = new byte[640];
    for (int i=0;i<100;i++) g.append(i*320, f, false);
    assertTrue(o.positions.isEmpty());
    g.append(32000, f, true);
    assertEquals(25600, o.segment); assertEquals(21, o.positions.size());
    for (int i=101;i<141;i++) g.append(i*320, f, false);
    assertEquals(1, o.ends); assertEquals(61, o.positions.size());
    g.append(45120, f, false); g.finish(); assertEquals(61, o.positions.size());
  }
  @Test public void shortPauseStaysInUtteranceAndFinishIsIdempotent() throws Exception {
    Output o = new Output(); SpeechGate g = new SpeechGate(o); byte[] f = new byte[640];
    g.append(0,f,true);
    for(int i=1;i<30;i++) g.append(i*320,f,false);
    g.append(9600,f,true); assertEquals(0,o.ends);
    g.finish(); g.finish(); assertEquals(1,o.ends);
  }
  @Test public void gapsDoNotBridgeOrInventAudio() throws Exception {
    Output o = new Output(); SpeechGate g = new SpeechGate(o); byte[] f = new byte[640];
    g.append(0,f,true); g.append(32000,f,true);
    assertEquals(1,o.ends); assertEquals(Arrays.asList(0L,32000L),o.positions);
    assertEquals(32000,o.segment);
  }
}
