# Linuxで音声を取得する

Python 3.11以降、BlueZ、BLEアダプター、システムのlibopusが必要です。Linuxでの直接BLE回収用で、Windows／macOS対応は未確認です。Androidアプリと同時接続しないでください。

## 準備

[認証手順](AUTHENTICATION.md)で自分の機器の認証素材を用意します。

```sh
python -m venv .venv
.venv/bin/pip install -r requirements.txt
export NOTEPIN_ADDRESS='AA:BB:CC:DD:EE:FF'
export NOTEPIN_SERIAL='YOUR_DEVICE_SERIAL'
export NOTEPIN_AUTH_DIR='/private/path/private-auth'
export NOTEPIN_ADAPTER='hci0'
mkdir -m 700 recordings
```

MAC・SNは自分の対象へ置き換えます。表示名だけで別の機器を選ぶフォールバックはありません。認証ディレクトリには`private-research-auth.json`、`private-account-key.pem`、`private-owner-handshake.bin`を置きます。

## 録音中の取得

NotePin本体で録音を開始し、公式アプリのBLE接続を解放します。

```sh
.venv/bin/python direct_probe.py --start 512 --seconds 30 --output recordings/live.bin
.venv/bin/python decode_cache.py --header recordings/live.bin.header.bin \
  --key "$NOTEPIN_AUTH_DIR/private-account-key.pem" \
  --input recordings/live.bin --remote-offset 512 --output recordings/live.wav
```

`--seconds`は接続中の回収時間で、取得音声の長さとは別です。未取得の音声があれば先に届きます。本体の録音を開始・停止・削除しません。

保存したメタデータの連続した位置から、継続して回収できます。

```sh
.venv/bin/python continuous_collect.py --resume recordings/live.bin.json \
  --directory recordings/continuous --seconds 300
```

このCLIは暗号化した回収データを保存します。各区間を`decode_cache.py`でWAVへ変換してください。LinuxのWAV自動5分分割は実装していません。

## 過去録音の取得

既知の録音IDを指定します。NotePinが録音していない状態でも、過去IDを選択できます。

```sh
.venv/bin/python history_probe.py --history OLD_RECORDING_ID \
  --start 512 --seconds 60 --output recordings/archive.bin
.venv/bin/python decode_cache.py --header recordings/archive.bin.header.bin \
  --key "$NOTEPIN_AUTH_DIR/private-account-key.pem" \
  --input recordings/archive.bin --remote-offset 512 --output recordings/archive.wav
```

現在録音と同じIDを過去録音として指定することは拒否します。新しい出力名を使ってください。ファイルの存在や指定時間の経過だけでは、録音全体の回収完了を意味しません。

一覧要求は次のとおりです。

```sh
.venv/bin/python history_probe.py --list
```

認証で得た通信バージョンを使って、要求UID・ページ順・総件数・重複を確認します。結果は`request_complete`、`total`、取得できたID／サイズで表示します。応答がない場合は`request_complete=false`、`total=null`です。「録音0件」と読み替えないでください。

公式アプリへの同期後など、差分一覧に現れない録音があります。現時点で全件列挙は保証しません。[検証範囲](VALIDATION.md)
