package dev.notepin.poc;
import android.app.*;
import android.os.*;
import java.io.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
public final class SelfTest extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }
  private static void require(boolean value) throws Exception {
    if (!value)
      throw new IOException("Assertion failed");
  }
  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      try (com.konovalov.vad.webrtc.VadWebRTC vad = new com.konovalov.vad.webrtc.VadWebRTC(
          com.konovalov.vad.webrtc.config.SampleRate.SAMPLE_RATE_16K,
          com.konovalov.vad.webrtc.config.FrameSize.FRAME_SIZE_320,
          com.konovalov.vad.webrtc.config.Mode.NORMAL, 0, 0)) {
        for (int i = 0; i < 100; i++) require(!vad.isSpeech(new byte[640]));
      }
      byte[] session = new byte[80], message = {1, 2, 3};
      byte[] encrypted = Protocol.aead(true, session, message);
      require(Arrays.equals(message, Protocol.aead(false, session, encrypted)));
      encrypted[0] ^= 1;
      boolean rejected = false;
      try {
        Protocol.aead(false, session, encrypted);
      } catch (org.bouncycastle.crypto.InvalidCipherTextException expected) {
        rejected = true;
      }
      require(rejected);
      byte[] header = new byte[512];
      System.arraycopy("PLAUD.AI".getBytes(), 0, header, 0, 8);
      header[144] = 80;
      byte[] content = new byte[32];
      new SecureRandom().nextBytes(content);
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      KeyPair pair = g.generateKeyPair();
      Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      rsa.init(Cipher.ENCRYPT_MODE, pair.getPublic());
      System.arraycopy(rsa.doFinal(content), 0, header, 256, 256);
      require(Arrays.equals(content, Protocol.contentKey(header, pair.getPrivate())));
      byte[] frame = Protocol.frame(new byte[80], header, new byte[32]);
      require((frame[0] & 255) == 0x76 && (frame[1] & 255) == 0xb8);
      File dir = new File(getTargetContext().getCacheDir(), "synthetic-validation");
      try (WavSegment wav = new WavSegment(dir, 1, 512)) {
        wav.append(
            Base64.getDecoder().decode("u0FL//"
                                       + "4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                       + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        require(wav.frames == 1);
      }
      File[] output = dir.listFiles((d, n) -> n.endsWith(".wav"));
      require(output != null && output.length == 1 && output[0].length() == 684);
      require(output[0].delete());
      result.putString("stream",
          "PASS: native WebRTC VAD (100 silent frames), Android AEAD/tamper, RSA content key, ChaCha vector, synthetic Opus/WAV. No BLE "
          + "or network used.\n");
      File[] recordings = new File(getTargetContext().getFilesDir(), "recordings")
          .listFiles((d, n) -> n.endsWith(".wav"));
      if (recordings != null && recordings.length > 0) {
        Arrays.sort(recordings);
        final int[] sent = {0}, ended = {0};
        SpeechGate gate = new SpeechGate(new SpeechGate.Sink() {
          public void audio(long sample, byte[] pcm, long utterance) { sent[0]++; }
          public void ended() { ended[0]++; }
        });
        int frames = 0, speech = 0;
        try (RandomAccessFile wav = new RandomAccessFile(recordings[0], "r");
             com.konovalov.vad.webrtc.VadWebRTC vad = new com.konovalov.vad.webrtc.VadWebRTC(
                 com.konovalov.vad.webrtc.config.SampleRate.SAMPLE_RATE_16K,
                 com.konovalov.vad.webrtc.config.FrameSize.FRAME_SIZE_320,
                 com.konovalov.vad.webrtc.config.Mode.NORMAL, 0, 0)) {
          require(wav.readInt() == 0x52494646);
          wav.seek(44);
          byte[] pcm = new byte[640];
          while (wav.getFilePointer() + 640 <= wav.length() && frames < 15000) {
            wav.readFully(pcm);
            boolean detected = vad.isSpeech(pcm);
            if (detected) speech++;
            gate.append(frames * 320L, pcm, detected);
            frames++;
          }
          gate.finish();
        }
        require(frames > 0);
        result.putString("stream", result.getString("stream") + "Local WAV VAD: frames="
            + frames + " speech=" + speech + " sent=" + sent[0] + " utterances=" + ended[0]
            + ". Counts only; no accuracy ground truth or network.\n");
      }
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("stream", "FAIL: " + error.getClass().getSimpleName() + "\n");
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
