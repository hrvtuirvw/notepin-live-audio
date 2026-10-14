package dev.notepin.poc;
import org.junit.Test;
import static org.junit.Assert.*;
public final class LiveStreamTest {
  private static final String TOKEN = "unit-test-only-token-123456789";
  @Test public void endpointBoundaries() {
    LiveStream.check("wss://receiver.example/audio", TOKEN);
    for (String url : new String[]{"ws://receiver.example/audio", "https://receiver.example/audio", "wss://user:pass@receiver.example/audio", "wss://receiver.example/audio?token=secret", "wss://receiver.example/wrong"}) {
      try { LiveStream.check(url, TOKEN); fail(url); } catch (IllegalArgumentException expected) {}
    }
    try { LiveStream.check("wss://receiver.example/audio", "short"); fail(); } catch (IllegalArgumentException expected) {}
  }
  @Test public void pcmChecksumVector() throws Exception {
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", LiveStream.hash(new byte[0]));
  }
}
