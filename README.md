# NotePin Live Audio

[English](#english) | [日本語](#日本語)

## English

Extract audio from PLAUD NotePin over BLE without stopping the recording.

An unofficial Android app and Linux CLI that retrieve audio while the device continues recording, save WAV files, and optionally forward decoded PCM to a PC over WebSocket.

The official workflow for the original NotePin requires stopping the recording before retrieving its audio file. This tool can retrieve audio while recording is still in progress. Sending that audio to a host PC over WebSocket and feeding it into an LLM can enable systems that provide real-time AI advice.

An Android app and a Python CLI for Linux are included. Compatibility has been verified with the original NotePin (model 880). Authentication credentials obtained from your own PLAUD account are required.

### Using the Android app

Use a device running Android 12 or later. English translations of the Japanese button labels are provided below for reference.

1. Follow the [authentication guide](docs/AUTHENTICATION.md) to create the JSON file for import.
2. Download and install the experimental debug-signed APK from [GitHub Releases](https://github.com/hrvtuirvw/notepin-live-audio/releases), or build it using the [Android instructions](android/README.md).
3. Select the JSON file using 「認証JSONを取り込む」 (Import authentication JSON), then grant the Nearby devices permission.
4. Disconnect the official app from BLE. While the NotePin is recording, tap 「自動回収を開始」 (Start automatic collection).
5. To export the saved audio, tap 「回収停止」 (Stop collection), then 「保存済みWAVをフォルダーへ書き出す」 (Export saved WAV files to a folder).

WAV files are 16 kHz, mono, 16-bit audio, split into five-minute segments. Stopping collection also saves any shorter remaining segment. “Stop collection” stops the app's communication, not the NotePin's recording. This tool does not start, stop, or delete recordings on the device, change its settings, or update its firmware.

If there is a backlog of audio that has not yet been retrieved, older audio arrives first. Files can be read while recording is in progress, but delivery of the latest audio within any particular number of seconds is not guaranteed.

### Retrieving past recordings

On Android, past recordings are collected automatically from the incremental list returned by the device. Collection alternates between past recordings and the recording in progress, and resumes from the saved position after a disconnect. Enabling WebSocket forwarding to a PC prioritizes the recording in progress.

The incremental list may omit recordings already synchronized with the official app. The device has also failed to respond to list requests in some live checks. Retrieval of all recordings has not yet been verified.

If you know the recording ID, you can retrieve it directly with the [Linux CLI](docs/LINUX.md). The same CLI can retrieve audio from an ongoing recording and convert it to WAV.

### Sending audio to a PC

No destination needs to be configured if you only want to save audio on the Android device.

To receive audio on a PC, configure [WebSocket forwarding](streaming/README.md). Decoded PCM is sent without waiting for the WAV file to be finalized. WAV files continue to be saved locally during forwarding.

### Development checks

Set up the Python environment and run the tests from the repository root.

```sh
python -m venv .venv
.venv/bin/pip install -r requirements-bootstrap.txt -r streaming/requirements.txt
.venv/bin/python -m playwright install chromium
.venv/bin/python -m unittest discover -s . -p 'test_*.py' -v
(cd streaming && ../.venv/bin/python -m unittest discover -s . -p 'test_*.py' -v)
python tools/check_public_tree.py
```

The Android build uses JDK 17, SDK 35, and build-tools 35.0.0.

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Audio retrieval, decryption, and WAV saving have been verified on a physical original NotePin. The list-handling changes in 0.5.1 have only been checked with test data. See the [validation notes](docs/VALIDATION.md) for details and the [implementation notes](docs/PROTOCOL.md) for the communication protocol and recording format.

### License

Original code is licensed under [MIT](LICENSE); third-party code remains subject to its [respective licenses](THIRD_PARTY_NOTICES.md). Official APKs, vendor SDKs, authentication credentials, and actual recordings are not included. See [SECURITY.md](SECURITY.md) for handling authentication JSON files and recordings.

---

## 日本語

録音を止めずに、PLAUD NotePinの音声をBLEで取り出します。

録音中の音声を取得し、WAVに保存する非公式のAndroidアプリとLinux CLIです。必要に応じて、デコードしたPCMをWebSocketでPCへ送れます。

公式では初代NotePinは録音中の音声ファイルを抜き出せず、音声ファイルを抜き出すには録音停止する必要がありますが、このツールを用いることでリアルタイムで音声ファイルを取得することができます。
WebSocketなどで母艦PCに送りLLMに渡すなどで、リアルタイムでAIにアドバイスをさせるシステムの構築などができるようになります。

AndroidアプリとLinux用のPython CLIがあります。対応を確認しているのは初代NotePin（model 880）です。利用には、自分のPLAUDアカウントから取得した認証情報が必要です。

### Androidで使う

Android 12以降の端末を用意してください。

1. [認証手順](docs/AUTHENTICATION.md)に従って、取り込み用JSONを作る。
2. [GitHub Releases](https://github.com/hrvtuirvw/notepin-live-audio/releases)から実験版のデバッグ署名APKをダウンロードしてインストールする。自分でビルドする場合は、[Androidのビルド手順](android/README.md)に従う。
3. アプリの「認証JSONを取り込む」でJSONを選び、近くのデバイス権限を許可する。
4. 公式アプリのBLE接続を切る。NotePinが録音中の状態で「自動回収を開始」を押す。
5. 保存した音声を取り出すには、「回収停止」を押してから「保存済みWAVをフォルダーへ書き出す」を選ぶ。

WAVは16 kHz・モノラル・16 bitで、音声5分ごとに分割します。回収を止めると、5分に満たない分も保存します。「回収停止」はアプリの通信を止める操作で、NotePin本体の録音は続きます。本体の録音開始・停止・削除・設定変更・FW更新は、このツールからは行いません。

未取得の音声があると、古い部分から届きます。録音中のファイルは読めますが、最新の音声が何秒以内に届くかは保証できません。

### 過去の録音を取得する

Androidでは、本体が返す差分一覧をもとに過去録音を自動回収します。録音中の音声と交互に取得し、切断後は保存済みの位置から再開します。PCへのWebSocket転送を有効にすると、録音中の音声を優先します。

差分一覧には、公式アプリに同期済みの録音などが出ないことがあります。実機では一覧の応答が返らないケースもあり、全録音の回収はまだ確認できていません。

録音IDが分かっている場合は、[Linux CLI](docs/LINUX.md)で直接指定して取得できます。同じCLIで、録音中の音声の取得とWAVへの変換もできます。

### PCに音声を送る

端末内に保存するだけなら、送信先の設定は不要です。

PCで音声を受け取るなら、[WebSocket転送](streaming/README.md)を設定します。WAVの確定を待たず、デコードしたPCMを送ります。転送中も端末内のWAVは保存します。

### 開発用チェック

Pythonの環境準備とテストは、リポジトリのルートで実行します。

```sh
python -m venv .venv
.venv/bin/pip install -r requirements-bootstrap.txt -r streaming/requirements.txt
.venv/bin/python -m playwright install chromium
.venv/bin/python -m unittest discover -s . -p 'test_*.py' -v
(cd streaming && ../.venv/bin/python -m unittest discover -s . -p 'test_*.py' -v)
python tools/check_public_tree.py
```

AndroidはJDK 17、SDK 35／build-tools 35.0.0を使用します。

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

音声取得・復号・WAV保存は初代NotePinの実機で確認しています。0.5.1で修正した一覧処理は、テストデータで確認した段階です。詳しくは[検証記録](docs/VALIDATION.md)を参照してください。通信や録音形式は[実装メモ](docs/PROTOCOL.md)にまとめています。

### ライセンス

独自コードは[MIT](LICENSE)、第三者コードは[各ライセンス](THIRD_PARTY_NOTICES.md)に従います。公式APK・vendor SDK・認証情報・実録音は含みません。認証JSONや録音の扱いは[SECURITY.md](SECURITY.md)を参照してください。
