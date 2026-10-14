# NotePin Live Audio — WebSocket streaming

[English](#english) | [日本語](#日本語)

## English

Sends PCM decoded on Android to a PC without waiting for WAV finalization. Messages contain about one second of audio each. WAV files continue to be saved locally.

Run the receiver server separately on the PC. Installing the Android app alone does not enable forwarding.

### Preparing the receiving PC

Python 3.11 or later is required. Install this directory's dependencies on the receiving PC.

```sh
python -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Create a random authentication token of at least 24 characters and set it in the `NOTEPIN_STREAM_TOKEN` environment variable. Configure the same value on Android. This token is separate from your PLAUD credentials; the receiving PC does not need your PLAUD private key.

After setting the token, start the receiver server.

```sh
.venv/bin/python receiver.py --host 127.0.0.1 --port 8765 --directory ./stream-inbox
```

This command accepts connections only from the PC itself. To connect from Android, expose `/audio` through a WebSocket-capable TLS reverse proxy, or pass `--cert fullchain.pem --key privkey.pem` to the receiver and bind it to a LAN or VPN address.

The endpoint is `wss://HOST/audio`. Use a TLS certificate trusted by Android and a hostname that matches the certificate. Using Tailscale still requires separate receiver-server and TLS configuration.

### Android configuration

The English labels below are translations of the app's Japanese button labels for reference.

1. Open 「PCへのWebSocket転送を設定／停止」 (Configure/stop WebSocket forwarding to a PC).
2. Enter the receiver URL and the token configured on the PC, then enable forwarding.
3. Tap 「自動回収を開始」 (Start automatic collection). If collection is already running, leave it running.

Forwarding is disabled by default. The URL and token are stored in the app's encrypted vault. Changes take effect at the next connection or collection transition, and queued audio is also sent to the new destination.

WebSocket forwarding can use mobile data. While enabled, it prioritizes the recording in progress and does not request new lists of past recordings.

If unretrieved audio or unsent data has accumulated, older audio arrives first. Sending roughly one-second audio chunks does not mean they arrive with one-second latency.

### Sending and retrying

Android stores PCM in a local outgoing queue before sending it. Disconnects and acknowledgement timeouts trigger retries. The queued copy is deleted only after a matching ACK is received; the original WAV remains.

Acknowledged positions are retained so that the same range is not queued again even if the WAV's saved position is older. Interrupted writes are recovered by reconciling queued data with saved positions.

Data from the recording in progress is prioritized over past recordings; data within the same recording is sent from the oldest position first. Unacknowledged data stays in app-specific storage, so extended offline periods consume space. Collection pauses when free space falls below 64 MiB.

The normal BLE reconnection delay is one second while forwarding is enabled. The delay increases after connection failures.

### Data format

The WebSocket path is `/audio`, and the authentication header is `Authorization: Bearer TOKEN`. Audio is sent as JSON text with these fields.

| Field | Meaning |
| --- | --- |
| `v` / `type` | `1` / `audio` |
| `format` / `rate` / `channels` | `s16le` / `16000` / `1` |
| `source` | Hash of device identifiers; raw serial numbers and MAC addresses are not sent |
| `recording_id` | uint32 recording ID |
| `recording_key` | Recording-header fingerprint hash |
| `start_sample` | Sample position in the original recording; a 20 ms frame contains 320 samples |
| `mode` | `live` for an ongoing recording; `history` for a past recording |
| `id` | `source:recording_id:recording_key:start_sample` |
| `pcm` | Base64 PCM, 640–32,000 bytes in whole 20 ms frames |
| `sha256` | SHA-256 of the PCM |
| `recording_start_jst` | Estimated start time based on the device clock |

`mode: live` means the audio was retrieved from a file that is still being recorded. It does not necessarily represent audio from the current moment.

The receiver commits audio and metadata to SQLite before returning an ACK, using `synchronous=FULL`.

```json
{"v":1,"type":"ack","id":"…","sha256":"…","duplicate":false}
```

An ACK confirms receipt and storage, not completion of transcription or advice generation. Retries with the same ID and content are acknowledged as duplicates. Retries with different content and overlapping ranges are rejected.

### Sending speech only (VAD)

Enable 「VAD：発話のみ送信」 (VAD: send speech only) in the WebSocket settings. It is OFF by default. WebRTC VAD runs in NORMAL mode on 16 kHz audio in 20 ms frames. Forwarded speech includes 400 ms before and 800 ms after the detected speech. Silence is omitted only from forwarded audio; local WAV files retain it.

VAD-filtered audio uses the same outgoing queue and ACK handling. These fields are added to the JSON and saved in the receiver's `metadata`.

- `vad: "webrtc-normal"`
- Start position including the 400 ms pre-roll.
- `true` on the final audio message of a segment.

Because silence is omitted, downstream processing must use `start_sample` to track positions in the original recording. Concatenating chunks as continuous audio without accounting for omitted silence shifts timestamps. Use `utterance_end` as well as position gaps to identify segment endings.

Segments also close when collection ends, positions become discontinuous, or a five-minute WAV boundary is reached. These boundaries do not necessarily coincide with sentence endings. A new collection session may lack pre-roll from the previous session. Setting changes apply from the next collection session; audio already queued is not filtered again.

The implementation uses [android-vad](https://github.com/gkonovalov/android-vad). Tests cover segment padding and finalization, but detection accuracy for quiet speech and noisy environments still requires physical-device checks.

### Using received audio

Audio is stored in the `chunks` table of `stream-inbox/inbox.sqlite3`. A transcription engine and advice generation are not included.

For downstream processing, group unprocessed rows by `source` and `recording_key`, then read them in `start_sample` order. Check for missing ranges, save the processing results, and only then mark the corresponding rows as processed. To handle ongoing recordings first, prioritize `live` over `history`.

To export audio for experimentation, use the following command.

```sh
.venv/bin/python export_inbox.py --inbox ./stream-inbox/inbox.sqlite3 --output ./asr-input
```

This exports one WAV and JSON file per unprocessed message. It does not mark rows as processed or perform continuous transcription. The receiver database and export directory contain actual audio; do not place them in public locations.

### Tests

```sh
.venv/bin/python -m unittest discover -s . -p 'test_*.py' -v
```

Tests use a temporary local server to check retries after lost ACKs, duplicate handling after a restart, and authentication, checksum, and range validation. These are separate from physical-device communication checks. See the [physical-device validation notes](../docs/VALIDATION.md).

WebSocket communication uses [OkHttp](https://github.com/square/okhttp) and [websockets](https://websockets.readthedocs.io/en/stable/).

---

## 日本語

AndroidでデコードしたPCMを、WAVの確定を待たずにPCへ送ります。送信は音声約1秒ごとです。端末内のWAV保存も続けます。

受信サーバはPC側で別に起動してください。アプリをインストールしただけでは転送されません。

### 受信PCの準備

Python 3.11以降が必要です。受信するPCで、このディレクトリの依存パッケージを入れます。

```sh
python -m venv .venv
.venv/bin/pip install -r requirements.txt
```

認証用に24文字以上のランダムなトークンを作り、環境変数 `NOTEPIN_STREAM_TOKEN` に設定します。Androidにも同じ値を設定します。トークンはPLAUDの認証情報とは別のもので、受信PCにPLAUDの秘密鍵を渡す必要はありません。

トークンを設定したら、受信サーバを起動します。

```sh
.venv/bin/python receiver.py --host 127.0.0.1 --port 8765 --directory ./stream-inbox
```

このコマンドはPC内からの接続だけを受け付けます。Androidから接続するには、WebSocketに対応したTLSリバースプロキシで `/audio` を公開するか、受信サーバに `--cert fullchain.pem --key privkey.pem` を渡してLAN・VPN側のアドレスにバインドしてください。

接続先は `wss://HOST/audio` です。Androidが信頼するTLS証明書と、証明書に一致するホスト名が必要です。Tailscaleを使う場合も、受信サーバとTLSの設定は別に必要です。

### Androidの設定

1. 「PCへのWebSocket転送を設定／停止」を開く。
2. 受信URLと、PC側に設定したトークンを入力して有効にする。
3. 「自動回収を開始」を押す。すでに回収中なら、そのまま続ける。

転送は初期状態では無効です。URLとトークンはアプリの暗号化保管庫に保存します。設定変更は次の接続・回収の切り替わりから反映され、キューに残っている音声も変更後の送信先へ送ります。

WebSocket転送はモバイル通信も使います。有効な間は録音中の音声を優先し、新しい過去録音一覧は要求しません。

未取得の音声や未送信のデータがたまっていると、古い部分から届きます。音声約1秒ごとの送信は、1秒の遅延で届くという意味ではありません。

### 送信と再送

AndroidはPCMを端末内の送信キューに保存してから送ります。切断や受信確認のタイムアウトでは再送し、一致するACKを受け取ってからキューのコピーを削除します。元のWAVは残ります。

送信済み位置はACK後も保持します。WAV側の保存位置が古くても、同じ範囲をキューへ再登録しないためです。中断した書き込みは、キュー内のデータと保存位置を照合して復旧します。

録音中のデータを過去録音より優先し、同じ録音内では古い位置から送ります。未確認のデータはアプリ専用ストレージに残るため、通信できない時間が長いと容量を使います。空き容量が64 MiB未満になると回収を一時停止します。

BLEの通常の再接続待ちは、転送中は1秒です。接続に失敗したときは待ち時間を延ばします。

### データ形式

WebSocketのパスは `/audio`、認証ヘッダーは `Authorization: Bearer TOKEN` です。音声は次のフィールドを持つJSONテキストで送ります。

| フィールド | 内容 |
| --- | --- |
| `v` / `type` | `1` / `audio` |
| `format` / `rate` / `channels` | `s16le` / `16000` / `1` |
| `source` | 機器識別情報のハッシュ。SN・MACの生値は送らない |
| `recording_id` | uint32の録音ID |
| `recording_key` | 録音ヘッダーの指紋ハッシュ |
| `start_sample` | 元の録音内のサンプル位置。20 msのフレームは320サンプル |
| `mode` | 録音中の取得は `live`、過去録音は `history` |
| `id` | `source:recording_id:recording_key:start_sample` |
| `pcm` | Base64のPCM。640〜32,000バイト、20 msのフレーム単位 |
| `sha256` | PCMのSHA-256 |
| `recording_start_jst` | 機器時計に基づく推定開始時刻 |

`mode: live` は録音中のファイルから取得したという分類です。その音声が現在時刻のものとは限りません。

受信側は音声とメタデータをSQLiteへコミットしてからACKを返します。`synchronous=FULL` で保存しています。

```json
{"v":1,"type":"ack","id":"…","sha256":"…","duplicate":false}
```

ACKは受信・保存の確認です。書き起こしや助言生成の完了通知ではありません。同じID・内容の再送には重複としてACKを返し、内容が違う再送や範囲の重なりは拒否します。

### 発話だけを送る（VAD）

WebSocket設定の「VAD：発話のみ送信」で有効にできます。初期状態ではOFFです。WebRTC VADのNORMALモードを使い、16 kHz・20 ms単位で判定します。発話の前400 msと後800 msを含めて送ります。無音を省くのは転送分だけで、端末内のWAVには無音も残ります。

VADを使う音声も同じ送信キューとACKで扱います。JSONには次のフィールドを追加し、受信側は `metadata` に保存します。

- `vad: "webrtc-normal"`
- `utterance_start_sample`：前400 msの余白を含む開始位置
- `utterance_end`：区間の最後の音声メッセージで `true`

無音を省くため、後段では `start_sample` で元の録音内の位置を追ってください。省略した無音を無視して連続音声としてつなぐと、時刻がずれます。区間の終了は位置の飛びだけでなく、`utterance_end` も見て判断します。

回収の終了、位置の不連続、WAVの5分区切りでも区間を閉じます。必ずしも文の終わりとは一致せず、次の回収開始時には前の回収からの余白がない場合があります。設定の変更は次の回収から適用し、すでにキューにある音声は再選別しません。

実装は[android-vad](https://github.com/gkonovalov/android-vad)を使っています。テストでは区間の余白や終了処理を確認していますが、小声や雑音下での判定精度は実機での確認が必要です。

### 受信した音声を使う

保存先は `stream-inbox/inbox.sqlite3` の `chunks` テーブルです。書き起こしエンジンや助言生成は同梱していません。

別の処理へ渡す場合は、未処理の行を `source` と `recording_key` でまとめ、`start_sample` の順に読みます。位置の欠落を確認し、処理結果を保存してから対応する行を処理済みにします。録音中の音声を先に扱うなら、`live` を `history` より優先してください。

音声を書き出して試すには、次のコマンドを使えます。

```sh
.venv/bin/python export_inbox.py --inbox ./stream-inbox/inbox.sqlite3 --output ./asr-input
```

未処理のメッセージごとにWAVとJSONを書き出します。行を処理済みにする操作や、継続的な書き起こしは行いません。受信DBと書き出し先には実音声が入るので、公開の場所には置かないでください。

### テスト

```sh
.venv/bin/python -m unittest discover -s . -p 'test_*.py' -v
```

ローカルの一時サーバを使い、ACKが失われた場合の再送、再起動後の重複処理、認証・チェックサム・範囲の検証を確認します。実機の通信確認とは別のテストです。[実機の検証記録](../docs/VALIDATION.md)

WebSocket通信には[OkHttp](https://github.com/square/okhttp)と[websockets](https://websockets.readthedocs.io/en/stable/)を使っています。
