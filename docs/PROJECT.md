# ws プロジェクト

Samsung Galaxy の DeX を個人の開発環境にするための Android アプリ。
このページにはデザイン・アーキテクチャ・TODO を残す。仕様を変えたら同じ変更でこのページも更新する。

- [ビジョン](#ビジョン)
- [デザイン](#デザイン)
- [アーキテクチャ](#アーキテクチャ)
- [リリースと動作確認](#リリースと動作確認)
- [TODO](#todo)
- [決定ログ](#決定ログ)

## ビジョン

- **DeX でも通常のスマホアプリとしても気持ちよく使える** 開発用ワークスペース
- 開発で使う Web サービス（GitHub / Claude / Gemini / github.dev / Codespaces …）をタブでまとめ、作業の続きをすぐ再開できる
- ターミナルは自作しない。Termux に任せて連携する

やらないこと：汎用ブラウザとしての完成度（拡張機能、同期、ブックマーク管理の作り込み）、ターミナルの実装。

## デザイン

### 使う場面

| 場面 | 画面 | 入力 | 想定 |
| --- | --- | --- | --- |
| DeX（モニター） | 横長・広い（≥ 600dp） | 物理キーボード + マウス | メインの開発。隣に Termux や外部ブラウザのウィンドウ |
| スマホ単体 | 縦長・狭い（< 600dp） | タッチ + ソフトキーボード | 移動中の確認、レビュー、Claude との会話 |
| タブレット / 折りたたみ | どちらにもなる | 両方 | 幅で上の 2 つを切り替える |

画面幅 600dp を境に **wide** と **compact** の 2 つのレイアウトを切り替える。

### 画面構成

```
wide (DeX)                                    compact (スマホ)
┌──────────────────────────────────────┐      ┌───────────────────┐
│ [tab][tab][tab]                    + │      │ [tab][tab]      + │
│ ☰ ← → ⟳ [ アドレスバー  ] ⛶ >_ ⋮     │      │ ☰ [ アドレス ] >_ ⋮│
├────────┬─────────────────────────────┤      ├───────────────────┤
│ サイト │                             │      │┌──────┐           │
│ セッション │   WebView / ホーム       │      ││サイド│  WebView  │
│ ツール │                             │      ││バー  │           │
└────────┴─────────────────────────────┘      │└──────┘(重ねて表示)│
                                              └───────────────────┘
```

- **タブ列 → ツールバー → 本文** の 3 段。本文の左にサイドバー
- wide: サイドバーは本文の横に並ぶ（表示 / 非表示を記憶）
- compact: サイドバーはドロワーとして本文に重なり、初期状態は非表示。サイトやセッションを選ぶと自動で閉じる。戻る / 進む / 全画面ボタンは隠す（戻るはジェスチャー、全画面はメニュー）
- wide と compact でサイドバーの表示状態は別々に記憶する
- 何も開いていないタブは「ホーム」（サイト一覧・セッション再開・ヒント）

### 全画面

- **デフォルトで全画面**。ステータスバーとナビゲーションバーを隠し、画面端からスワイプすると一時的に出る
- DeX ではウィンドウを最大化した状態で起動する（`com.samsung.android.dex.launchwidth/launchheight = 0`）
- ツールバーの ⛶、メニュー、F11 で切り替える。状態は記憶する

### キーボードショートカット

- すべての操作を `ShortcutAction` として定義し、キーは自由に割り当てられる（メニュー →「ショートカットの設定」）
- 1 つの操作に複数のキーを割り当てられる（例：再読み込み = Ctrl+R / F5）
- 割り当て済みのキーを別の操作に割り当てると、元の操作から外して付け替える（トーストで知らせる）
- ページへの文字入力を奪わないよう、Ctrl / Alt / Meta との組み合わせか F1〜F12 のみ許可
- デフォルトは Chrome に合わせる。github.dev（VS Code）とぶつかるキー（Ctrl+/ や F1 など）はデフォルトで使わない
- ツールチップとメニューには、そのとき割り当てられているキーを表示する

| 操作 | デフォルト |
| --- | --- |
| 新しいタブ / タブを閉じる | Ctrl+T / Ctrl+W |
| 閉じたタブを開き直す | Ctrl+Shift+T |
| 次 / 前のタブ | Ctrl+Tab / Ctrl+Shift+Tab |
| タブ 1〜8 / 最後のタブ | Ctrl+1〜8 / Ctrl+9 |
| アドレスバー | Ctrl+L |
| 再読み込み | Ctrl+R, F5 |
| 戻る / 進む | Alt+← / Alt+→ |
| サイドバーの表示切替 | Ctrl+Shift+B |
| 全画面の切替 | F11 |
| セッションを保存 | Ctrl+Shift+S |
| Termux を開く | Ctrl+Alt+T |
| ショートカットの設定 | なし |

### サイト

- デフォルト：GitHub, Claude, Gemini（外部ブラウザ）, github.dev, Codespaces
- 「外部ブラウザで開く」サイトは DeX では隣のウィンドウで開く。Google は WebView でのログインをブロックするので Gemini はこれがデフォルト
- 開いているタブがあれば新しいタブを作らずそこへ切り替える

### セッション

- 開いているタブは常に自動保存し、起動時に復元する（表示するタブだけ読み込む）
- 名前を付けて保存したセッションは、以後の変更もそのセッションへ自動保存される（＝ワークスペース）
- 未保存のタブから別セッションへ切り替えるときは「自動保存 …」として残し、作業を失わない

### 見た目

- VS Code 風のダークテーマ（`Palette`）
- アイコンは今のところ文字（☰ ← → ⟳ ⛶ >_ ⋮）

## アーキテクチャ

### 方針

- **依存ライブラリを持たない**。Android フレームワークの `Activity` / View / `WebView` だけで作る。UI もコードで組み立て、リソースは manifest とアイコンだけ
  - 理由：小さく保ち、ビルドやアップデートで壊れにくくするため。JVM のユニットテストもしやすい
- 永続化は `SharedPreferences`（`ws`）に JSON（`org.json`）で保存
- Android フレームワークに依存しないロジック（URL の解釈、JSON 変換、キーマップ）は分けて JVM のユニットテストで確認する

### ファイル

| ファイル | 役割 |
| --- | --- |
| `MainActivity.kt` | 画面全体。タブ・WebView の管理、サイドバー / ホーム、ダイアログ、ショートカットの実行、全画面、外部アプリ連携 |
| `BrowserTab.kt` | タブ 1 つ分（URL、タイトル、遅延生成する WebView） |
| `Site.kt` | `Site` と `SiteStore`、デフォルトのサイト |
| `Session.kt` | `TabState` / `Session` と `SessionStore`（現在のセッション + 名前付きセッション） |
| `Shortcuts.kt` | `KeyBinding` / `ShortcutAction` / `Keymap` と `KeymapStore` |
| `Web.kt` | `UrlInput`（アドレスバー入力 → URL）、`UserAgent`（デスクトップ UA）、定数 |
| `Ui.kt` | 色と `dp()` |

### 保存データ（SharedPreferences `ws`）

| キー | 内容 |
| --- | --- |
| `sites` | `Site` の配列。なければデフォルト |
| `session.current` | 起動時に復元するセッション |
| `session.named` | 名前付きセッションの配列（新しい順） |
| `keymap` | デフォルトから変えたショートカットだけ（`{"ACTION": ["keyCode:modifiers", …]}`）。新しい操作を足すとデフォルトが効く |
| `desktop_mode` / `fullscreen` | 表示設定 |
| `sidebar_visible` / `sidebar_visible_compact` | wide / compact ごとのサイドバー表示 |

### 主な流れ

- **タブ**：`tabs` と `activeIndex` で管理。WebView はタブを初めて表示したときに作る。非表示のタブは `GONE` にして状態を保つ。`target=_blank` / `window.open` は `onCreateWindow` で新しいタブにする
- **自動保存**：`doUpdateVisitedHistory`（SPA の pushState も拾う）、タイトル変更、タブ操作、`onStop` で `persist()` → `SessionStore.saveCurrent()`
- **ショートカット**：`dispatchKeyEvent` で `KeyBinding.of(event)` → `Keymap.actionFor()` → `perform(action)`。WebView より先にキーを受け取る。設定ダイアログ表示中はキーがダイアログに行くので発火しない
- **画面サイズ**：`configChanges` で再生成を防ぎ、`onConfigurationChanged` で `applyWindowSize()`（compact / wide の切替）と `applyFullscreen()` をやり直す
- **レンダラーのクラッシュ**：`onRenderProcessGone` でそのタブの WebView を破棄し、次に表示したときに作り直す
- **外部連携**：外部ブラウザと Termux は `FLAG_ACTIVITY_LAUNCH_ADJACENT` で隣のウィンドウに開く。`intent://` は component / selector を消して BROWSABLE に限定する

### ビルド・配布

- AGP 8.11 / Kotlin 2.2 / compileSdk・targetSdk 36 / minSdk 29
- ビルドタイプが 2 つあり、別アプリとして共存する（データも別）

  | ビルド | アプリ ID | 名前 / アイコン | 用途 |
  | --- | --- | --- | --- |
  | release | `com.gozenchu.ws` | ws / 青 | 普段使い。main から GitHub Release で配布 |
  | debug | `com.gozenchu.ws.preview` | ws preview / オレンジ | PR の動作確認。debuggable なので WebView の DevTools も使える |

- バージョン：`versionName` = `<ws.versionBase>.<CI の run 番号>`（`ws.versionBase` は `gradle.properties`）、`versionCode` = run 番号。preview は `-pr<番号>` が付く
- 署名鍵は Secrets（`WS_KEYSTORE_*`）から。release と preview の両方に使う。ないときは debug 鍵（毎回変わるので上書きインストールできない）

## リリースと動作確認

### 流れ

```
PR を作る / push ──▶ CI: test・lint・build ──▶ Actions の成果物 ws-preview
                                                  │
                    Termux: scripts/install.sh preview <PR>
                                                  ▼
                                     手元の ws preview が更新される
main にマージ ─────▶ CI: test・lint・build ──▶ GitHub Release v0.1.<run> (ws-0.1.<run>.apk)
                                                  │
                    Termux: scripts/install.sh release
                                                  ▼
                                          手元の ws が更新される
```

- **GitHub Release は main への push（マージ）のときだけ**作る。PR やブランチの CI は Release を作らない
- PR の CI は `ws-preview`（preview APK）と `ws-release`（release APK）を成果物として残す
- `workflow_dispatch` で任意のブランチの CI を手動で動かせる（成果物のみ、Release なし）

### 手元の端末の準備（初回のみ）

**PC は使わず Galaxy だけで完結させる**。初心者向けの手順は [SETUP-GALAXY.md](SETUP-GALAXY.md)（Termux・GitHub ログイン・署名鍵の作成と登録まで）。

### 使い方

```sh
scripts/install.sh preview 12   # PR #12 の最新コミットのビルドを ws preview に入れる
scripts/install.sh preview      # いちばん新しいオープン PR
scripts/install.sh release      # 最新の GitHub Release を ws に入れる
```

- preview は PR の **最新コミットの CI が成功しているとき**だけ入れる（古いビルドを入れない）。CI 中や失敗なら URL を表示して止まる
- デフォルトは Android のインストーラーに APK を渡す（`termux-open`、確認画面で 1 タップ）。開発者向けオプションや adb は不要
- `WS_INSTALLER=adb` なら adb で確認なしに入れる。PR を行き来して versionCode が下がっても入る（preview は debuggable なので downgrade 可）。通常のインストーラーは downgrade できないので、そのときは ws preview をアンインストールして入れ直す
- fork からの PR では Secrets が使えず debug 鍵になるので、一度アンインストールが必要

## TODO

### 実機で確認すること

- [ ] DeX で最大化した状態で起動するか（`dex.launchwidth/launchheight = 0`）
- [ ] DeX のウィンドウで全画面（システムバー非表示）がどう振る舞うか。タスクバーとの関係
- [ ] スマホ ⇄ DeX の切り替えでタブが読み込み直されないか
- [ ] Ctrl+Tab や Ctrl+Alt+T など、DeX / One UI がアプリより先に取ってしまうキーの洗い出し
- [ ] 文字アイコン（⛶ など）が端末のフォントで表示されるか
- [ ] github.dev / Codespaces / Claude が WebView + デスクトップ UA でちゃんと動くか

### DeX / アプリとしての使い勝手

- [ ] ショートカット設定を専用画面にする（検索、操作ごとの説明、競合の一覧）
- [ ] ショートカットのエクスポート / インポート
- [ ] コマンドパレット（Ctrl+Shift+P 相当。タブ・サイト・セッション・操作をまとめて検索）
- [ ] タブのドラッグ並べ替え、中クリックで閉じる
- [ ] compact でのタブ切り替え（スワイプやタブ一覧画面）
- [ ] 文字アイコンをベクターアイコンにする
- [ ] ページ内検索（Ctrl+F は github.dev と競合するので要検討）
- [ ] ズーム（Ctrl+= / Ctrl+-）
- [ ] マウスの戻る / 進むボタン

### セッション

- [ ] WebView の戻る履歴やスクロール位置も復元する
- [ ] セッションのエクスポート / インポート（端末移行、バックアップ）
- [ ] セッションごとのメモ（何の作業か）

### 連携

- [ ] Termux の RUN_COMMAND でコマンド / dev サーバーを起動する
- [ ] localhost の dev サーバーの自動検出

### リリースと動作確認

- [ ] Secrets（`WS_KEYSTORE_*`）を登録する（未登録だと preview / release とも上書きインストールできない）
- [ ] CI のエミュレーターで UI テスト（起動・タブ・セッション復元・ショートカット）
- [ ] SETUP-GALAXY.md の手順を実機で通す（`termux-open` でインストーラーが開くか）
- [ ] ws preview のアプリ内から PR ビルドに更新できるようにする（Termux なしで完結）
- [ ] preview アプリ内に「PR #… / コミット」を表示する

### 2027 年の Android 開発者登録（サイドロード規制）への対応

2027 年から全世界で、Google 認証済み端末（Galaxy を含む）には **未登録の開発者のアプリを新しく入れられなくなる**。APK の直接インストール・F-Droid・Obtainium のどれも対象（2026-09-30 からブラジル・インドネシア・シンガポール・タイで先行）。抜け道として、adb でのインストールは対象外、1 回きりの設定で未登録アプリを許可する「advanced flow」（24 時間待ち）、無料・本人確認なし・最大 20 台の「limited distribution」アカウントがある。

- [ ] 2027 年初めに最新の状況を確認する（日本での開始時期、F-Droid・Termux・Obtainium の対応）
- [ ] limited distribution アカウント（無料）を作り、`com.gozenchu.ws` と `com.gozenchu.ws.preview` を署名鍵とともに登録する（登録手順はまだ未確認）
- [ ] 登録した署名鍵（`ws.jks`）のバックアップを確認する（なくすと登録した開発者として更新できなくなる）
- [ ] Termux が登録されていなければ、advanced flow を 1 回設定する手順を SETUP-GALAXY.md に足す
- [ ] 予備として `WS_INSTALLER=adb`（adb は対象外）の手順が動くか確認しておく

参考：[Android Developers FAQ](https://developer.android.com/developer-verification/guides/faq) / [Google ヘルプ](https://support.google.com/android-developer-console/answer/16561738?hl=en) / [F-Droid の公開書簡](https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html)

### 品質

- [ ] UI テスト（Robolectric か instrumented test）
- [ ] `MainActivity` が大きくなってきたら、タブ管理・サイドバー・ダイアログを分ける

### 完了

- [x] サイト登録（デフォルト：GitHub / Claude / Gemini / github.dev / Codespaces）
- [x] セッションの自動保存・名前付きセッション
- [x] Termux 連携
- [x] デフォルトで全画面
- [x] キーボードショートカットのカスタマイズ
- [x] compact（スマホ幅）レイアウト：ドロワー型サイドバー、ツールバーの整理
- [x] 動作確認用の preview アプリ（別アプリ ID）と PR ビルドのインストールスクリプト
- [x] GitHub Release は main のマージ時だけ

## 決定ログ

| 日付 | 決定 | 理由 |
| --- | --- | --- |
| 2026-10-24 | Docker イメージから Android アプリにスコープを変更 | DeX を開発環境にするため |
| 2026-10-24 | ターミナルは Termux に任せる | 自作のコストが大きく、Termux で十分 |
| 2026-10-24 | 依存ライブラリなし、UI はコードで組み立てる | 小さく保ち、壊れにくくするため |
| 2026-10-24 | Gemini はデフォルトで外部ブラウザ | Google が WebView でのログインをブロックするため |
| 2026-10-24 | デフォルトで全画面、600dp を境に wide / compact | DeX と通常のアプリの両方で使いやすくするため |
| 2026-10-24 | ショートカットは修飾キー必須（F キーは例外） | ページへの文字入力を奪わないため |
| 2026-10-24 | 動作確認は別アプリ ID の preview（debug ビルド）で、PR の CI 成果物を Termux の adb から入れる | 普段使いのデータを壊さず、DeX から離れずに確認するため |
| 2026-10-24 | GitHub Release は main への push のときだけ。バージョンは `<base>.<run 番号>` | リリースを main の状態に限定し、番号付けを自動にするため |
| 2026-10-24 | 開発は Claude が担当し、動作確認は PC を使わず Galaxy の Termux で行う。インストールは adb ではなく Android のインストーラー経由をデフォルトに | 開発者向けオプションやペア設定なしで、初心者でも 1 コマンド + 1 タップで済むため |
| 2026-10-24 | 2027 年の開発者登録には、無料の limited distribution アカウントで対応する方針（予備は advanced flow と adb） | 自分の端末だけで使うので 20 台制限で足り、今のインストールの流れを変えずに済むため |
