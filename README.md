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

### DeX 向けの細かい対応

- フリーフォームウィンドウ・サイズ変更対応。画面サイズや DeX の切り替えでタブを読み込み直さない
- デスクトップ表示（デスクトップ版 Chrome の UA）がデフォルト。メニューで切り替えられる
- キーボードショートカット

  | キー | 動作 |
  | --- | --- |
  | Ctrl+T / Ctrl+W | 新しいタブ / タブを閉じる |
  | Ctrl+Shift+T | 閉じたタブを開き直す |
  | Ctrl+Tab / Ctrl+Shift+Tab | 次 / 前のタブ |
  | Ctrl+1〜9 | タブへ移動（9 は最後） |
  | Ctrl+L | アドレスバー |
  | Ctrl+R / F5 | 再読み込み |
  | Alt+← / Alt+→ | 戻る / 進む |
  | Ctrl+Shift+B | サイドバーの表示切替 |
  | Ctrl+Shift+S | セッションを保存 |
  | Ctrl+Alt+T | Termux を開く |

- リンクの右クリックで「新しいタブで開く」「バックグラウンドのタブで開く」など
- `target="_blank"` や `window.open`（OAuth のポップアップを含む）は新しいタブで開く
- ファイルのアップロードとダウンロードに対応
- 他のアプリから URL を「共有」すると ws の新しいタブで開く

## インストール

[Actions](https://github.com/go-zen-chu/ws/actions/workflows/ci.yml) の成果物 `ws-apk`、または `v*` タグを打ったときの [Releases](https://github.com/go-zen-chu/ws/releases) から APK を取得してインストールする。

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

Secrets がなければ debug 鍵で署名する。

## 開発

JDK 17 以上と Android SDK が必要。

```shell
./gradlew testReleaseUnitTest lintRelease assembleRelease
# 端末に入れる
adb install -r app/build/outputs/apk/release/app-release.apk
```

## dotfiles

`dotfiles/` は以前のコンテナイメージ（archlinux）時代の zsh / vim / tmux / git の設定。Termux で使う場合の参考として残している。

## LICENSE

MIT : [LICENSE](./LICENSE)
