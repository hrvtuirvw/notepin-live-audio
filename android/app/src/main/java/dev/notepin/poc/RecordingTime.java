package dev.notepin.poc;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
/** Device-clock recording start, inferred from this NotePin's uint32 Unix-second IDs. */
public final class RecordingTime {
  private static final DateTimeFormatter JST = DateTimeFormatter
      .ofPattern("uuuu/MM/dd HH:mm:ss", java.util.Locale.JAPAN).withZone(ZoneId.of("Asia/Tokyo"));
  public static String japan(long id) {
    if (id <= 0 || id > 0xffffffffL) return "不明";
    return JST.format(Instant.ofEpochSecond(id)) + " JST";
  }
  private RecordingTime() {}
}
