# NotePin Live Audio — Android

[English](#english) | [日本語](#日本語)

## English

Retrieves recordings from the original PLAUD NotePin and saves them as WAV files on the Android device. Android 12 or later is required.

A prebuilt experimental debug-signed APK is available in [GitHub Releases](https://github.com/hrvtuirvw/notepin-live-audio/releases). The instructions below are for building it yourself.

### Build and installation

Install JDK 17, Android SDK platform 35, and build-tools 35.0.0, then run the following commands from this directory.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb -s YOUR_ANDROID_DEVICE install app/build/outputs/apk/debug/app-debug.apk
```

You can also open this directory in Android Studio to build the app. Set `sdk.dir` in `local.properties` to specify the SDK location. This file is excluded from Git.

### Usage

The app's button labels are in Japanese. The English labels below are translations for reference.

1. Transfer the `private-android-bootstrap.json` created with the [authentication guide](../docs/AUTHENTICATION.md) to your Android device. It contains a private key; do not place it in a public location.
2. Select the file using 「認証JSONを取り込む」 (Import authentication JSON).
3. Grant the Nearby devices permission, and grant notification permission if requested.
4. Disconnect the official app from BLE and keep the NotePin nearby.
5. While the NotePin is recording, tap 「自動回収を開始」 (Start automatic collection). If it is already recording, leave that recording running.
6. To finish collection, tap 「回収停止（機器の録音は継続）」 (Stop collection; device recording continues).
7. Select an export folder using 「保存済みWAVをフォルダーへ書き出す」 (Export saved WAV files to a folder).

A notification is shown during collection. WAV files are 16 kHz, mono, 16-bit audio stored in app-specific storage. Files are split into five-minute audio segments; shorter segments are also finalized when collection stops or reconnects. The device continues recording even when app collection is stopped.

### Past recordings

When WebSocket forwarding to a PC is disabled, the app requests an incremental recording list on BLE connection, with a 15-minute request interval. Past recordings found in the list are retrieved in ascending ID order, alternating with the recording in progress. Past-recording collection runs for about 60 seconds per connection and resumes on the next connection.

Collection is marked complete only after reading to the end indicated by the list and saving that audio to WAV. A missing response or an incomplete sequence of pages is not treated as an empty list or a complete retrieval. See the [implementation notes](../docs/PROTOCOL.md) for page validation.

Some recordings, including those already synchronized with the official app, may be absent from the incremental list. Retrieval of all recordings and paginated list handling on a physical device have not been verified. If you know the recording ID, use the [Linux CLI](../docs/LINUX.md) to read it directly.

### Storage and resuming

- The retrieval position is saved after the WAV file is finalized. Unfinalized audio left after a forced exit is retrieved again next time. A crash between file finalization and position saving may produce duplicate audio.
- The key and nonce in the recording header are compared. If they identify a different recording with the same ID, retrieval restarts from the beginning.
- Collection pauses when free space falls below 64 MiB. WAV files are not deleted automatically.
- After a device reboot and unlock, the app attempts to resume collection. An OS force-stop or manufacturer power-saving settings may prevent resuming. Actual reboot recovery and multi-day continuous operation have not been verified.
- Imported credentials are encrypted with AES-GCM using Android Keystore. The original JSON remains on the device; delete unneeded copies.

### Forwarding to a PC

Forwarding is disabled by default. No configuration is needed to save audio locally.

Configure [WebSocket forwarding](../streaming/README.md) to send PCM to a PC without waiting for WAV finalization. Local WAV files are retained.

When upgrading from an older version, saved WAV files, retrieval positions, WebSocket settings, and the outgoing queue are preserved. Legacy WAV-transfer jobs are canceled, and obsolete transfer settings are removed the next time credentials are loaded. The original imported JSON file is not rewritten.

### Tests

Physical-device checks are documented in the [validation notes](../docs/VALIDATION.md). The included Android tests use synthetic data, not BLE communication, networking, or owner keys. Build the test APK with the following command.

```sh
./gradlew :app:assembleDebugAndroidTest
```

[Third-party code licenses](../THIRD_PARTY_NOTICES.md)

---

## 日本語

初代PLAUD NotePinの録音を取得し、端末にWAVで保存します。Android 12以降が必要です。

[GitHub Releases](https://github.com/hrvtuirvw/notepin-live-audio/releases)からビルド済みの実験版デバッグ署名APKを取得できます。以下は自分でビルドする手順です。

### ビルドとインストール

JDK 17、Android SDK platform 35、build-tools 35.0.0を用意し、このディレクトリで実行します。

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb -s YOUR_ANDROID_DEVICE install app/build/outputs/apk/debug/app-debug.apk
```

Android Studioでこのディレクトリを開いてもビルドできます。SDKの場所は`local.properties`の`sdk.dir`で指定できます。このファイルはGitの管理対象外です。

### 使い方

アプリのボタン表記は日本語です。

1. [認証手順](../docs/AUTHENTICATION.md)で作った`private-android-bootstrap.json`を端末へ転送する。秘密鍵を含むファイルなので、公開の場所には置かない。
2. 「認証JSONを取り込む」でファイルを選ぶ。
3. 近くのデバイス権限を許可する。通知権限を求められた場合も許可する。
4. 公式アプリのBLE接続を切り、NotePinを近くに置く。
5. NotePinが録音中の状態で「自動回収を開始」を押す。すでに録音していれば、そのまま続けてよい。
6. 回収を終えるときは「回収停止（機器の録音は継続）」を押す。
7. 「保存済みWAVをフォルダーへ書き出す」で保存先を選ぶ。

回収中は通知が表示されます。WAVは16 kHz・モノラル・16 bitで、アプリ専用のストレージに保存します。音声5分ごとにファイルを分け、停止・再接続時には短いファイルも確定します。本体の録音は、アプリの回収を止めても続きます。

### 過去録音

PCへのWebSocket転送を無効にしているときは、BLE接続時に差分一覧を要求します。要求の間隔は15分です。一覧で見つかった過去録音を古いID順に取得し、録音中の音声と交互に処理します。過去録音は1接続あたり約60秒回収し、次の接続で続きを取得します。

一覧に記載された末尾まで読み出し、その音声をWAVに保存した時点で回収完了を記録します。一覧が応答しない場合や、ページが途中で途切れた場合は、空の一覧や全件取得済みとは扱いません。ページの確認方法は[実装メモ](../docs/PROTOCOL.md)を参照してください。

公式アプリに同期済みの録音など、差分一覧に出ないものがあります。全録音の回収と分割一覧の実機動作は未確認です。録音IDが分かっていれば、[Linux CLI](../docs/LINUX.md)から直接読み出せます。

### 保存と再開

- 取得位置はWAVを確定してから保存します。強制終了で残った未確定部分は、次回取り直します。ファイル確定と位置保存の間に落ちた場合は、音声が重複することがあります。
- 録音ヘッダー内の鍵・nonceを照合し、同じIDでも別の録音と判定した場合は先頭から取り直します。
- 空き容量が64 MiB未満になると回収を一時停止します。WAVは自動削除しません。
- 端末の再起動後は、ロック解除後に回収の再開を試みます。OSの強制停止やメーカーの省電力設定で再開できないことがあります。実際の再起動と複数日の連続動作は未確認です。
- 取り込んだ認証情報は、Android Keystoreを使ってAES-GCMで暗号化します。元のJSONは端末に残るので、不要なコピーは削除してください。

### PCへの転送

送信は初期状態では無効です。端末に保存するだけなら、設定は不要です。

[WebSocket転送](../streaming/README.md)を設定すると、WAVの確定を待たず、PCMをPCへ送ります。端末内のWAVはそのまま残ります。

旧版から更新した場合も、保存済みWAV・取得位置・WebSocket設定と送信キューは引き継ぎます。旧WAV転送のジョブを取り消し、不要になった転送設定は認証情報の次回読み込み時に削除します。取り込み元のJSONは書き換えません。

### テスト

実機で確認した内容は[検証記録](../docs/VALIDATION.md)にまとめています。同梱のAndroidテストは合成データを使い、BLE通信・ネットワーク・所有者鍵は使いません。テスト用APKは次のコマンドでビルドできます。

```sh
./gradlew :app:assembleDebugAndroidTest
```

[第三者コードのライセンス](../THIRD_PARTY_NOTICES.md)
