# Galaxy だけで ws をインストール・更新する

PC は使わない。Galaxy の **Termux** から 1 コマンドで、最新の ws や PR の動作確認用ビルド（ws preview）を入れられるようにする。
最初の準備は 15 分ほど。2 回目からは「コマンド 1 つ → インストール画面で 1 タップ」。

> 用語：**APK** は Android アプリのインストールファイル。**ws preview** は PR の動作確認用の別アプリで、普段使いの ws とは別に入り、データも別。

## 1. Termux を入れる（初回のみ）

1. [F-Droid](https://f-droid.org/) をインストールし、F-Droid から **Termux** を入れる
   - Play ストア版の Termux は更新が止まっているので使わない
2. Termux を開いて次を実行する

   ```sh
   pkg update
   pkg install gh git
   ```

## 2. GitHub にログインする（初回のみ）

```sh
gh auth login
```

質問には次のように答える。

| 質問 | 答え |
| --- | --- |
| Where do you use GitHub? | GitHub.com |
| Preferred protocol | HTTPS |
| Authenticate Git with your GitHub credentials? | Yes |
| How would you like to authenticate? | Login with a web browser |

表示された 8 桁のコードをメモし、Enter でブラウザが開いたらコードを入れて許可する。

## 3. ws のリポジトリを取ってくる（初回のみ）

```sh
gh repo clone go-zen-chu/ws ~/ws
echo 'alias wsi=~/ws/scripts/install.sh' >> ~/.bashrc
source ~/.bashrc
```

以降は `wsi` だけでインストール用のスクリプトを呼べる。スクリプトを最新にしたいときは `git -C ~/ws pull`。

## 4. 署名鍵を作って GitHub に登録する（初回のみ・最重要）

Android は **同じ鍵で署名された APK でしか上書き更新できない**。鍵がないと毎回アンインストールが必要になり、保存したサイトやセッションが消える。
Termux だけで作って登録できる。

```sh
pkg install openjdk-17
cd ~
keytool -genkeypair -v -keystore ws.jks -alias ws -keyalg RSA -keysize 4096 -validity 10000
# パスワードを 2 回聞かれる。名前などの質問は Enter で飛ばしてよく、最後に yes

# GitHub の Secrets に登録（<パスワード> は上で決めたもの）
base64 -w0 ws.jks | gh secret set WS_KEYSTORE_BASE64 -R go-zen-chu/ws
gh secret set WS_KEYSTORE_PASSWORD -R go-zen-chu/ws --body '<パスワード>'
gh secret set WS_KEY_PASSWORD -R go-zen-chu/ws --body '<パスワード>'
gh secret set WS_KEY_ALIAS -R go-zen-chu/ws --body 'ws'
```

- `ws.jks` とパスワードは **なくさない**（なくすと次の更新から上書きできなくなる）。Google ドライブなどにバックアップしておく。リポジトリには入れない
- 登録後に CI で作られたビルドから、上書き更新できるようになる

## 5. インストールする

### 普段使いの ws（main の最新リリース）

```sh
wsi release
```

### PR の動作確認（ws preview）

```sh
wsi preview 12   # PR #12 を入れる
wsi preview      # いちばん新しいオープン PR を入れる
```

PR の **最新コミットの CI が成功しているときだけ** 入る。まだ CI 中なら「実行中」と出て止まるので、少し待ってもう一度。

### インストール画面が出たら

- 初回は「このソースからのアプリを許可」の画面が出る → **Termux を許可**して戻る
- 「インストール」（2 回目以降は「更新」）をタップ
- Play プロテクトの警告が出たら「詳細」→「インストールする」（自分で作ったアプリなので問題ない）

## うまくいかないとき

| 症状 | 原因と対処 |
| --- | --- |
| 「アプリはインストールされていません」 | ① 署名鍵が違う（手順 4 の前のビルドが入っている）→ 一度アンインストールして入れ直す。② 入っているものより古い PR を入れようとした → ws preview をアンインストールして入れ直す（preview のデータは消えてよい） |
| 「CI は failure です」 | PR のビルドが失敗している。Claude に直してもらう |
| `gh: command not found` など | 手順 1〜2 をやり直す |
| `HTTP 401` / `HTTP 403` | `gh auth login` をやり直す |

## おまけ

### 普段使いの ws を自動で更新したい

[Obtainium](https://github.com/ImranR98/Obtainium) に `https://github.com/go-zen-chu/ws` を登録すると、main でリリースされるたびに通知が来てタップで更新できる（PR のビルドは扱えないので、preview は `wsi preview` を使う）。

### タップなしで入れたい（上級者向け）

開発者向けオプションの「ワイヤレスデバッグ」で Termux の adb を自分の端末につなぐと、確認画面なしで入れられ、古い PR への入れ替えもできる。

```sh
pkg install android-tools
# 設定 → 端末情報 → ソフトウェア情報 → ビルド番号 を 7 回タップ → 開発者向けオプション → ワイヤレスデバッグ ON
adb pair localhost:<ペア設定のポート> <ペア設定コード>   # 初回のみ（「ペア設定コードによるデバイスのペア設定」の画面）
adb connect localhost:<ワイヤレスデバッグ画面のポート>   # 再起動するたびに
WS_INSTALLER=adb wsi preview 12
```
