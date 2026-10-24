# ws (work station)

[![ci](https://github.com/go-zen-chu/ws/actions/workflows/ci.yml/badge.svg)](https://github.com/go-zen-chu/ws/actions/workflows/ci.yml)

Samsung Galaxy の **DeX** を個人の開発環境にするための Android アプリ。
よく使う開発サイトをタブで開き、作業状態をセッションとして残す。ターミナルは [Termux](https://termux.dev/) を使う。

## 機能

### よくアクセスするサイト

サイドバーとホーム画面にサイトを登録しておける。デフォルトは次のとおり。

| サイト | URL | 開き方 |
| --- | --- | --- |
| GitHub | https://github.com | ws のタブ |
| Claude | https://claude.ai | ws のタブ |
| Gemini | https://gemini.google.com | 外部ブラウザ ↗ |
| github.dev | https://github.dev | ws のタブ |
| Codespaces | https://github.com/codespaces | ws のタブ |

- 「＋ サイトを追加」またはメニューの「このページをサイトに追加」で登録する
- 長押し / 右クリックで編集・並べ替え・削除
- すでに開いているタブがあれば、新しいタブを作らずにそのタブへ切り替える
- Google は WebView 内でのログインをブロックするため、Google ログインが必要なサイトは「外部ブラウザで開く」にする。DeX では隣のウィンドウで開く

### セッション（前回の作業を残す）

- 開いているタブは常に自動保存され、次に起動したときに復元される（読み込むのは表示したタブだけ）
- 「名前を付けて保存」（Ctrl+Shift+S）でタブ一式を名前付きセッションにできる。以降の変更はそのセッションに自動で保存される
- サイドバーからセッションを切り替えられる。未保存のタブを開いたまま切り替えても「自動保存 …」として残るので失われない
- セッションは長押しで開く・現在のセッションに追加・名前の変更・削除ができる

### ターミナル

Termux に任せる。ツールバーの `>_`、サイドバー、または Ctrl+Alt+T で起動し、DeX では隣のウィンドウで開く。
Termux で起動した開発サーバーは、アドレスバーに `localhost:3000` のように入力すると ws のタブで確認できる。

### DeX とスマホの両方に対応

- **デフォルトで全画面**（ステータスバー・ナビゲーションバーを隠す。DeX では最大化して起動）。⛶ / メニュー / F11 で切り替え
- 幅 600dp 以上（DeX など）はサイドバーが横に並び、未満（スマホ）はドロワーとして重なる
- 画面サイズや DeX の切り替えでタブを読み込み直さない
- デスクトップ表示（デスクトップ版 Chrome の UA）がデフォルト。メニューで切り替えられる
- **キーボードショートカットはカスタマイズできる**（メニュー →「ショートカットの設定」）。デフォルトは Chrome と同じ（Ctrl+T / Ctrl+W / Ctrl+Tab / Ctrl+L / Ctrl+1〜9 / Alt+← など）。一覧は [docs/PROJECT.md](docs/PROJECT.md#キーボードショートカット)
- リンクの右クリックで「新しいタブで開く」「バックグラウンドのタブで開く」など
- `target="_blank"` や `window.open`（OAuth のポップアップを含む）は新しいタブで開く
- ファイルのアップロードとダウンロードに対応
- 他のアプリから URL を「共有」すると ws の新しいタブで開く

## プロジェクト

デザイン・アーキテクチャ・TODO は [docs/PROJECT.md](docs/PROJECT.md)。

## インストール

- **ws**（普段使い）：main にマージされるたびに [Releases](https://github.com/go-zen-chu/ws/releases) へ APK が上がる
- **ws preview**（動作確認用、別アプリとして共存）：PR の CI の成果物 `ws-preview`

Termux からは次のコマンドでインストール / 更新できる（準備は [docs/PROJECT.md](docs/PROJECT.md#リリースと動作確認)）。

```sh
scripts/install.sh preview <PR 番号>   # PR のビルドを ws preview に
scripts/install.sh release             # 最新リリースを ws に
```

### 署名鍵（アップデート時にデータを残すために必要）

署名鍵が変わった APK は上書きインストールできず、アンインストールすると保存したサイトやセッションも消える。
CI の runner の debug 鍵は毎回変わるので、固定の鍵をリポジトリの Secrets に登録しておく。

```shell
keytool -genkeypair -v -keystore ws.jks -alias ws -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 ws.jks  # => WS_KEYSTORE_BASE64
```

| Secret | 内容 |
| --- | --- |
| `WS_KEYSTORE_BASE64` | base64 にした keystore |
| `WS_KEYSTORE_PASSWORD` | keystore のパスワード |
| `WS_KEY_ALIAS` | 鍵のエイリアス（上の例では `ws`） |
| `WS_KEY_PASSWORD` | 鍵のパスワード |

Secrets は release と preview の両方の署名に使う。なければ debug 鍵で署名する。

## 開発

JDK 17 以上と Android SDK が必要。

```shell
./gradlew testReleaseUnitTest lintRelease assembleRelease assembleDebug
# 端末に入れる（debug ビルドは ws preview として入る）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## dotfiles

`dotfiles/` は以前のコンテナイメージ（archlinux）時代の zsh / vim / tmux / git の設定。Termux で使う場合の参考として残している。

## LICENSE

MIT : [LICENSE](./LICENSE)
