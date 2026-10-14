# 認証取得・初回導入

## 混同しない3種類

| 認証 | 用途 | 本実装での扱い |
|---|---|---|
| PLAUDアカウントのログインセッション | 所有者の認証素材を準備する段階 | 公式ページで本人がログイン。その後は自動取得。AndroidにログインCookieやパスワードは入れない |
| NotePin所有者用bootstrap | BLE認証と音声復号 | 自分の対象機器の署名・アカウントRSA秘密鍵・所有者ハンドシェイクを取り込む |
| PC受信Bearerトークン | Android→受信PCのWSS認証 | 受信PCで生成した独立の秘密値を両者に設定 |

いずれもGitHubに置きません。

## 自動取得する（APNE1の検証済みリクエスト形式）

PCで`auto_bootstrap.py`を使います。ログインだけ公式Webページで本人が行い、その後の対象確認・所有者メンバーID確認・RSA鍵取得・署名取得・ハンドシェイク生成・Android JSON作成は自動です。パスワードやMFAコードをCLIに渡す必要はありません。

```sh
python -m venv .venv
.venv/bin/pip install -r requirements-bootstrap.txt
.venv/bin/python -m playwright install chromium
.venv/bin/python auto_bootstrap.py \
  --address AA:BB:CC:DD:EE:FF --serial YOUR_DEVICE_SERIAL \
  --output /private/path/new-private-auth
```

出力先の親ディレクトリは先に作ってください。出力ディレクトリは新規作成のみです。既存Chromiumを使う場合は`--chromium /path/to/chromium`を指定できます。

1. 自動で開いた公式`https://web.plaud.ai`の画面で、自分のアカウントにログイン。
2. 指定SNがアカウントの機器一覧にあることをツールが確認。
3. `/workspaces/list`と機器の`owner_member_id`／`workspace_id`を照合。自分の個人ワークスペースで一致するIDだけを所有者ハンドシェイクに使う。不一致や未対応形式は鍵取得前に停止。
4. アカウント鍵と対象SNだけの署名を取得。暗号化応答の完全性を検証。
5. 4個の私的ファイルを保存。スマホには`private-android-bootstrap.json`を取り込む。

API処理は`GET /device/list`、`GET /workspaces/list`、`POST /e2ee/gen_key`、`POST /device/sn-sign`（`sns`配列）の順です。過去に観測した公式アプリ／公式WebのAPNE1形式であり、一般公開API契約ではありません。`gen_key`は鍵未設定アカウントでは作成を伴う可能性があります。ツールは独自の鍵生成・既存鍵の置換・bind/unbind・録音操作をしません。鍵取得の自動再試行もしません。

ログイン用ブラウザは専用の私的プロファイルを使用（既定`~/.local/share/notepin-live/private-browser`、0700）。Cookieはそこに保存されるためGitHubには置かないでください。ログイン待ち上限は既定300秒、`--login-timeout`で変更可能です。

**制限：APNE1のみ、32文字hexの所有者メンバーID・2048bit RSA・観測済み38バイトの所有者ハンドシェイクに限定。** 他地域・旧式機種・別形式では推測せず停止します。共有・チーム機器は拒否し、機器所有者IDと個人ワークスペースの本人メンバーIDが一致する対象のみ扱います。既に紐付け済みの機器に対する`bind-token`は以前の実行で拒否されたため使いません。公式アプリの通常接続はWorkspaceContextManagerのメンバーIDを用いることを静的解析で確認しました。APIからのメンバーID取得と実機認証の通し検証はまだ必要です。

アプリ共通の応答エンコード定数を使ってFernet応答を検証・復号します。この定数はユーザーの秘密鍵でもログイン用トークンでもなく、APIアクセスには本人のログインCookieが別途必要です。

自動取得コードは模擬APIでのフロー、改ざん拒否、秘密ファイル権限、既存ファイル保護をテスト済み。Chromiumで公式オリジン限定・CORS／fetchの経路も模擬APIで確認しました。過去の実機ハンドシェイク／鍵とのバイト一致も確認しました。**今回、新規公式ログインから実機までの一連の通し検証は未実施**です。取得完了メッセージはBLE認証・録音復号の完了を意味しません。

[Playwrightの公式インストール手順](https://playwright.dev/python/docs/intro)。公式PLAUD SDKのパートナー認証とは別方式です：[PLAUD SDK](https://github.com/Plaud-AI/plaud-sdk-public)。

## 必要なローカルファイル

自分だけが読める`private-auth/`（0700）、各ファイル0600：

- `private-research-auth.json`：`sign.status == 0`、`sign.data.signature[自分のSN]`にBase64の256バイト署名。
- `private-account-key.pem`：対象署名・録音と整合するRSA秘密鍵PEM。
- `private-owner-handshake.bin`：観測・検証済みの所有者ハンドシェイク。先頭`01 01 00`。

自動取得ツールがこれらとAndroid JSONを作成します。手動作成する場合も、この名前のファイルを作るだけでは正しい認証情報にはなりません。MAC・SNは公式アプリと所有機器で確認し、表示名だけで選ばないでください。

## Android取り込みファイルを作る

既に上記素材を持っている場合：

```sh
python -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python make_android_bootstrap.py \
  --auth-dir /private/path/private-auth \
  --address AA:BB:CC:DD:EE:FF --serial YOUR_DEVICE_SERIAL \
  --output /private/path/private-android-bootstrap.json
```

出力は新規作成のみ・0600。署名長、PEM、ハンドシェイク先頭を検証しますが、それだけで機器認証成功を保証するものではありません。値はログに出しません。送信は無効で作成します。

1. APKをインストール。
2. JSONを私的な経路でスマホへ転送し、「認証JSONを取り込む」で選択。
3. 近くのデバイス／通知権限を許可。
4. 公式アプリのBLE接続を解除し、自動回収を開始。
5. WAVと機器認証成功を確認。必要ならOEMの自動起動・電池制限を調整。
6. 取り込み元JSONは別に残るので、端末上の不要コピーを消し、必要な原本だけ安全に保管。

アプリ内はAndroid Keystoreを使ったAES-GCM保管です。共有ストレージ上の取り込み元JSONまで暗号化するものではありません。

## PC転送トークン

受信PCで暗号学的乱数の24文字以上の値を生成し、受信サービスの`NOTEPIN_STREAM_TOKEN`とAndroidの設定へ同じ値を入れます。PLAUDアカウント鍵は受信PCへ渡す必要がありません。受信PCは復号済みPCMを受け取ります。

URLは`wss://HOST/audio`。信頼されるTLS証明書が必要です。詳細は[受信仕様](../streaming/README.md)。

## トラブル切り分け

- BLE connect／MTU／services／CCCD／write失敗：機器との通信。公式アプリの接続、距離、権限を確認。
- 機器認証・鍵復号失敗：対象SN、署名、鍵、ハンドシェイクの整合を確認。
- 「接続待機・未送信なし」：送信キューが空。PC疎通確認済みではない。
- 「再送待ち」：音声はあるが接続／TLS／認証／ACK等に失敗。
- 「受信確認済み」：PCの永続受信ACK。ASR完了や最新音声の到着を意味しない。
