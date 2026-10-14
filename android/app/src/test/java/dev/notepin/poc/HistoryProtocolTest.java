package dev.notepin.poc;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HistoryProtocolTest {
  private byte[] page(long uid, int total, int index, int stride, long... pairs) {
    byte[] body = new byte[11 + stride * pairs.length / 2];
    body[0] = 1;
    body[1] = 26;
    System.arraycopy(Protocol.ints(uid), 0, body, 3, 4);
    body[7] = (byte) total;
    body[8] = (byte) (total >> 8);
    body[9] = (byte) index;
    body[10] = (byte) (index >> 8);
    for (int i = 0; i < pairs.length; i += 2)
      System.arraycopy(Protocol.ints(pairs[i], pairs[i + 1]), 0, body, 11 + stride * i / 2, 8);
    return body;
  }

  @Test public void exactReadOnlyRequest() {
    assertArrayEquals(new byte[] {1,26,0,4,3,2,1,0,0,0,0,0}, HistoryProtocol.listRequest(0x01020304));
  }

  @Test public void handshakeSelectsVersionAndRejectsRefusal() {
    byte[] reply = {1, 1, 0, 0, 20, 0, 0, 0, 1};
    assertEquals(20, HistoryProtocol.protocolVersion(reply));
    reply[3] = 1;
    assertThrows(IllegalArgumentException.class, () -> HistoryProtocol.protocolVersion(reply));
    assertThrows(IllegalArgumentException.class, () -> HistoryProtocol.protocolVersion(new byte[5]));
  }

  @Test public void supportedVersionsAndUnsignedIds() {
    for (int version : new int[] {1, 2, 6, 7, 20, 65535}) {
      int stride = version == 1 ? 8 : version <= 6 ? 9 : 10;
      HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(3, version);
      assertEquals(Long.valueOf(1312), inventory.accept(page(3, 1, 0, stride, 0xffffffffL, 1312)).get(0xffffffffL));
      assertTrue(inventory.complete());
    }
  }

  @Test public void totalIsNotTheNumberOfEntriesInThisPage() {
    HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(7, 20);
    assertEquals(2, inventory.accept(page(7, 3, 0, 10, 10, 1312, 11, 2112)).size());
    assertFalse(inventory.complete());
    assertEquals(Collections.singletonMap(12L, 512L), inventory.accept(page(7, 3, 2, 10, 12, 512)));
    assertTrue(inventory.complete());
  }

  @Test public void repeatedPagesDoNotDuplicateEntries() {
    HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(7, 20);
    byte[] first = page(7, 2, 0, 10, 10, 1312);
    inventory.accept(first);
    assertTrue(inventory.accept(first.clone()).isEmpty());
    assertFalse(inventory.complete());
    inventory.accept(page(7, 2, 1, 10, 11, 1312));
    assertTrue(inventory.complete());
  }

  @Test public void conflictingRetransmissionRejected() {
    HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(7, 20);
    inventory.accept(page(7, 2, 0, 10, 10, 1312));
    assertThrows(IllegalArgumentException.class, () -> inventory.accept(page(7, 2, 0, 10, 10, 2112)));
  }

  @Test public void responseFromDifferentRequestRejected() {
    HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(7, 20);
    assertThrows(IllegalArgumentException.class, () -> inventory.accept(page(8, 1, 0, 10, 10, 1312)));
    assertFalse(inventory.complete());
  }

  @Test public void totalChangesRejected() {
    HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(7, 20);
    inventory.accept(page(7, 2, 0, 10, 10, 1312));
    assertThrows(IllegalArgumentException.class, () -> inventory.accept(page(7, 3, 1, 10, 11, 1312)));
  }

  @Test public void gapsRejectedWithoutLosingValidProgress() {
    HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(7, 20);
    inventory.accept(page(7, 3, 0, 10, 10, 1312));
    assertThrows(IllegalArgumentException.class, () -> inventory.accept(page(7, 3, 2, 10, 12, 1312)));
    inventory.accept(page(7, 3, 1, 10, 11, 1312, 12, 1312));
    assertTrue(inventory.complete());
  }

  @Test public void duplicateIdsAcrossPagesRejected() {
    HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(7, 20);
    inventory.accept(page(7, 2, 0, 10, 10, 1312));
    assertThrows(IllegalArgumentException.class, () -> inventory.accept(page(7, 2, 1, 10, 10, 1312)));
  }

  @Test public void invalidFileSizesFilteredButStillCountTowardPageCompletion() {
    HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(7, 20);
    assertTrue(inventory.accept(page(7, 3, 0, 10, 10, 0, 11, 513)).isEmpty());
    assertFalse(inventory.complete());
    assertEquals(1, inventory.accept(page(7, 3, 2, 10, 12, 1312)).size());
    assertTrue(inventory.complete());
  }

  @Test public void emptyReplyIsDifferentFromNoReply() {
    HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(7, 20);
    assertFalse(inventory.complete());
    assertTrue(inventory.accept(page(7, 0, 0, 10)).isEmpty());
    assertTrue(inventory.complete());
  }

  @Test public void badShapesRejected() {
    HistoryProtocol.Inventory inventory = new HistoryProtocol.Inventory(7, 20);
    assertThrows(IllegalArgumentException.class, () -> inventory.accept(new byte[10]));
    assertThrows(IllegalArgumentException.class, () -> inventory.accept(page(7, 1, 0, 9, 10, 1312)));
    assertThrows(IllegalArgumentException.class, () -> inventory.accept(page(7, 1, 1, 10, 10, 1312)));
    assertThrows(IllegalArgumentException.class, () -> inventory.accept(page(7, 2, 0, 10)));
    assertThrows(IllegalArgumentException.class, () -> inventory.accept(page(7, 2, 0, 10, 10, 1312, 10, 1312)));
  }

  @Test public void missingVersionAndInvalidUidRejected() {
    assertThrows(IllegalArgumentException.class, () -> new HistoryProtocol.Inventory(7, 0));
    assertThrows(IllegalArgumentException.class, () -> new HistoryProtocol.Inventory(7, 65536));
    assertThrows(IllegalArgumentException.class, () -> new HistoryProtocol.Inventory(-1, 20));
  }
}
