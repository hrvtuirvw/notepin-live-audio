package dev.notepin.poc;
import org.junit.Test;
import static org.junit.Assert.*;
public final class RecordingTimeTest {
  @Test public void ownerIdsAndUnsignedBoundary() {
    assertEquals("2026/10/06 08:51:03 JST", RecordingTime.japan(1791244263L));
    assertEquals("2026/10/06 13:51:17 JST", RecordingTime.japan(1791262277L));
    assertEquals("2106/02/07 15:28:15 JST", RecordingTime.japan(0xffffffffL));
    assertEquals("不明", RecordingTime.japan(-1));
    assertEquals("不明", RecordingTime.japan(0));
    assertEquals("不明", RecordingTime.japan(0x100000000L));
  }
}
