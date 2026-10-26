# ws

Samsung Galaxy DeX 向けの開発ワークスペース Android アプリ（Kotlin、依存ライブラリなし）。

## プロジェクトページ

デザイン・アーキテクチャ・TODO・決定ログは [docs/PROJECT.md](docs/PROJECT.md) にある。

- 作業を始める前に読む
- 機能・画面・保存データ・ファイル構成を変えたら、同じコミットで PROJECT.md の該当箇所を更新する
- TODO を片付けたら「完了」へ移し、新しく見つけた課題は TODO に足す
- 方針を決めたら決定ログに 1 行足す

## 開発

- DeX（幅 600dp 以上）とスマホ（600dp 未満）の両方での使い勝手を考える
- Android に依存しないロジックは `MainActivity` の外に置き、`app/src/test` に JVM ユニットテストを書く
- 確認：`./gradlew testReleaseUnitTest lintRelease assembleRelease assembleDebug`（CI と同じ）
- 変更は PR で出す。PR の CI が作る preview APK（`xyz.amasuda.ws.preview`）で実機確認し、main にマージすると GitHub Release が作られる
