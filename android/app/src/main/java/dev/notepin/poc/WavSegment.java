package dev.notepin.poc;
import java.io.*;
import java.nio.*;
import org.concentus.OpusDecoder;
public final class WavSegment implements AutoCloseable {
  private final RandomAccessFile out;
  private final File partial, ready;
  private final OpusDecoder decoder;
  public int frames;
  public WavSegment(File dir, long sid, long offset) throws Exception {
    this(dir, sid, offset, new OpusDecoder(16000, 1));
  }
  public WavSegment(File dir, long sid, long offset, OpusDecoder sessionDecoder) throws Exception {
    dir.mkdirs();
    String name = sid + "-" + offset + "-" + Long.toUnsignedString(System.nanoTime());
    partial = new File(dir, name + ".partial");
    ready = new File(dir, name + ".wav");
    out = new RandomAccessFile(partial, "rw");
    out.write(new byte[44]);
    decoder = sessionDecoder;
  }
  public byte[] append(byte[] opus) throws Exception {
    short[] pcm = new short[960];
    int n = decoder.decode(opus, 0, opus.length, pcm, 0, 960, false);
    if (n != 320)
      throw new IOException("Unexpected Opus frame duration");
    ByteBuffer b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < n; i++) b.putShort(pcm[i]);
    out.write(b.array());
    frames++;
    return b.array();
  }
  public void sync() throws IOException {
    out.getFD().sync();
  }
  @Override
  public void close() throws IOException {
    long length = out.length() - 44;
    ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes())
        .putInt((int) length + 36)
        .put("WAVEfmt ".getBytes())
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(16000)
        .putInt(32000)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes())
        .putInt((int) length);
    out.seek(0);
    out.write(b.array());
    out.getFD().sync();
    out.close();
    if (frames == 0) {
      partial.delete();
      return;
    }
    if (!partial.renameTo(ready))
      throw new IOException("Segment finalization failed");
  }
}
