package dev.notepin.poc;
import java.util.ArrayDeque;
/** 20ms frames; classifier-independent pre-roll and hangover. Original positions retained. */
public final class SpeechGate {
  public interface Sink {
    void audio(long sample, byte[] pcm, long utterance) throws Exception;
    void ended() throws Exception;
  }
  private static final class Frame {
    final long sample; final byte[] pcm;
    Frame(long s, byte[] p) { sample = s; pcm = p.clone(); }
  }
  private final Sink sink;
  private final ArrayDeque<Frame> before = new ArrayDeque<>();
  private boolean active;
  private int silence;
  private long next = -1, utterance;
  public SpeechGate(Sink sink) { this.sink = sink; }
  public void append(long sample, byte[] pcm, boolean speech) throws Exception {
    if (pcm.length != 640) throw new IllegalArgumentException("20ms PCM required");
    if (next >= 0 && sample != next) finish();
    next = sample + 320;
    if (!active) {
      if (!speech) {
        before.addLast(new Frame(sample, pcm));
        if (before.size() > 20) before.removeFirst();
        return;
      }
      utterance = before.isEmpty() ? sample : before.peekFirst().sample;
      active = true;
      for (Frame f : before) sink.audio(f.sample, f.pcm, utterance);
      before.clear();
    }
    sink.audio(sample, pcm, utterance);
    silence = speech ? 0 : silence + 1;
    if (silence >= 40) finish();
  }
  public void finish() throws Exception {
    if (active) sink.ended();
    active = false; silence = 0; next = -1; before.clear();
  }
}
