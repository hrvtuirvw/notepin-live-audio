# NotePin Live Audio — Third-party Android libraries

[English](#english) | [日本語](#日本語)

## English

### Concentus

Opus decoding uses the Java source of [Concentus](https://github.com/lostromb/concentus), imported from commit `3885c4e46513ef0fc81fca100189e54f1714c6ca`.

The license is in [Concentus-LICENSE](Concentus-LICENSE). Copyright notices for Opus and Concentus are preserved.

### WebRTC VAD

Speech detection uses [gkonovalov/android-vad](https://github.com/gkonovalov/android-vad). Gradle retrieves `com.cloudflare.realtimekit.android-vad:webrtc:2.0.10-cf.4`, distributed by Cloudflare through Maven Central.

The Android wrapper is licensed under MIT, and the native WebRTC code under BSD-3-Clause. The distribution includes JNI binaries for arm64-v8a, armeabi-v7a, x86, and x86_64.

### Other libraries

Also see the [Bouncy Castle license](https://www.bouncycastle.org/about/license/) and the project's [third-party library notices](../../THIRD_PARTY_NOTICES.md).

---

## 日本語

### Concentus

Opusのデコードに[Concentus](https://github.com/lostromb/concentus)のJavaソースを使っています。取り込み元はコミット `3885c4e46513ef0fc81fca100189e54f1714c6ca` です。

ライセンスは[Concentus-LICENSE](Concentus-LICENSE)にあります。OpusとConcentusの著作権表示を保持しています。

### WebRTC VAD

音声区間の判定には[gkonovalov/android-vad](https://github.com/gkonovalov/android-vad)を使っています。CloudflareがMaven Centralに配布する `com.cloudflare.realtimekit.android-vad:webrtc:2.0.10-cf.4` をGradleで取得します。

AndroidラッパーはMIT、ネイティブのWebRTCコードはBSD-3-Clauseです。配布物にはarm64-v8a、armeabi-v7a、x86、x86_64向けのJNIバイナリが含まれます。

### その他

[Bouncy Castleのライセンス](https://www.bouncycastle.org/about/license/)と、プロジェクト全体の[第三者ライブラリ一覧](../../THIRD_PARTY_NOTICES.md)も参照してください。
