package dev.notepin.poc;
import java.util.*;
/** Read-only, request-scoped inventory pages. No guessed entry widths. */
public final class HistoryProtocol {
  private HistoryProtocol() {}

  public static byte[] listRequest(long epoch) {
    return Protocol.join(new byte[] {1, 26, 0}, Protocol.ints(epoch, 0), new byte[] {0});
  }

  public static int protocolVersion(byte[] ownerReply) {
    if (ownerReply.length < 9 || ownerReply[0] != 1 || ownerReply[1] != 1
        || ownerReply[2] != 0 || ownerReply[3] != 0)
      throw new IllegalArgumentException("Short/refused owner handshake");
    int version = u16(ownerReply, 4);
    entryStride(version);
    return version;
  }

  private static int u16(byte[] body, int offset) {
    return (body[offset] & 255) | ((body[offset + 1] & 255) << 8);
  }

  private static int entryStride(int protocolVersion) {
    if (protocolVersion < 1 || protocolVersion > 65535)
      throw new IllegalArgumentException("Explicit handshake protocol version required");
    return protocolVersion == 1 ? 8 : protocolVersion <= 6 ? 9 : 10;
  }

  private static final class Page {
    final long uid;
    final int total, index;
    final Map<Long, Long> entries;
    Page(long uid, int total, int index, Map<Long, Long> entries) {
      this.uid = uid;
      this.total = total;
      this.index = index;
      this.entries = entries;
    }
  }

  private static Page decode(byte[] body, int protocolVersion) {
    if (body.length < 11 || body[0] != 1 || body[1] != 26 || body[2] != 0)
      throw new IllegalArgumentException("Short/wrong inventory");
    int total = u16(body, 7), index = u16(body, 9);
    int stride = entryStride(protocolVersion);
    int bytes = body.length - 11;
    if (bytes % stride != 0) throw new IllegalArgumentException("Incomplete inventory entry");
    int count = bytes / stride;
    if (index > total || index + count > total || (count == 0 && total != 0))
      throw new IllegalArgumentException("Inconsistent inventory bounds");
    Map<Long, Long> out = new LinkedHashMap<>();
    for (int i = 11; i < body.length; i += stride) {
      long id = Protocol.u32(body, i), size = Protocol.u32(body, i + 4);
      if (out.put(id, size) != null) throw new IllegalArgumentException("Duplicate inventory id");
    }
    return new Page(Protocol.u32(body, 3), total, index, out);
  }

  public static final class Inventory {
    private final long requestUid;
    private final int protocolVersion;
    private final Map<Integer, byte[]> pages = new HashMap<>();
    private final Set<Long> seen = new HashSet<>();
    private int total = -1, receivedCount;

    public Inventory(long requestUid, int protocolVersion) {
      if (requestUid < 0 || requestUid > 0xffffffffL)
        throw new IllegalArgumentException("Invalid inventory request UID");
      entryStride(protocolVersion);
      this.requestUid = requestUid;
      this.protocolVersion = protocolVersion;
    }

    public boolean complete() { return total >= 0 && receivedCount == total; }

    /** Returns only newly accepted entries with the supported recording format. */
    public Map<Long, Long> accept(byte[] body) {
      Page page = decode(body, protocolVersion);
      if (page.uid != requestUid)
        throw new IllegalArgumentException("Inventory request UID mismatch");
      if (total >= 0 && page.total != total)
        throw new IllegalArgumentException("Inventory total changed");
      byte[] previous = pages.get(page.index);
      if (previous != null) {
        if (!Arrays.equals(previous, body))
          throw new IllegalArgumentException("Conflicting duplicate inventory page");
        return Collections.emptyMap();
      }
      if (page.index != receivedCount)
        throw new IllegalArgumentException("Missing/out-of-order inventory page");
      for (long id : page.entries.keySet())
        if (seen.contains(id)) throw new IllegalArgumentException("Duplicate inventory id across pages");
      Map<Long, Long> downloadable = new LinkedHashMap<>();
      for (Map.Entry<Long, Long> entry : page.entries.entrySet()) {
        long size = entry.getValue();
        if (size >= 512 && (size - 512) % 80 == 0)
          downloadable.put(entry.getKey(), size);
      }
      total = page.total;
      receivedCount += page.entries.size();
      seen.addAll(page.entries.keySet());
      pages.put(page.index, body.clone());
      return Collections.unmodifiableMap(downloadable);
    }
  }
}
