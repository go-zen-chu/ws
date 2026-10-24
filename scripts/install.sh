#!/usr/bin/env bash
# Install ws on this device from Termux.
#
#   scripts/install.sh preview [PR]   preview app (com.gozenchu.ws.preview) from a PR's CI build;
#                                     without PR, the newest open PR
#   scripts/install.sh release        app (com.gozenchu.ws) from the latest GitHub Release
#
# Needs: pkg install gh android-tools, `gh auth login`, and adb connected to this device
# over Wireless debugging (see docs/PROJECT.md "リリースと動作確認").
set -euo pipefail

REPO="${WS_REPO:-go-zen-chu/ws}"

usage() {
	sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
	exit 1
}

require_adb() {
	if ! adb get-state >/dev/null 2>&1; then
		echo "adb が端末につながっていません。開発者向けオプション → ワイヤレスデバッグ を開いて:" >&2
		echo "  adb pair localhost:<ペア設定のポート> <ペア設定コード>   # 初回のみ" >&2
		echo "  adb connect localhost:<接続のポート>" >&2
		exit 1
	fi
}

install_preview() {
	local pr="${1:-}"
	if [[ -z "$pr" ]]; then
		pr=$(gh pr list -R "$REPO" --state open --limit 1 --json number -q '.[0].number')
		[[ -n "$pr" ]] || { echo "オープンな PR がありません" >&2; exit 1; }
	fi
	local head
	head=$(gh pr view "$pr" -R "$REPO" --json headRefOid -q .headRefOid)
	# The build of the PR's current head, so a stale build is never installed by mistake.
	local run status
	read -r run status < <(gh run list -R "$REPO" --workflow ci.yml --commit "$head" --event pull_request \
		--limit 1 --json databaseId,conclusion -q '.[0] | "\(.databaseId) \(.conclusion)"')
	if [[ -z "${run:-}" || "$run" == "null" ]]; then
		echo "PR #$pr の最新コミット (${head:0:7}) の CI がまだ始まっていません" >&2
		exit 1
	fi
	if [[ "$status" != "success" ]]; then
		echo "PR #$pr の最新コミット (${head:0:7}) の CI は ${status:-実行中} です: https://github.com/$REPO/actions/runs/$run" >&2
		exit 1
	fi
	local dir
	dir=$(mktemp -d)
	trap 'rm -rf "$dir"' EXIT
	gh run download "$run" -R "$REPO" -n ws-preview -D "$dir"
	echo "PR #$pr (${head:0:7}) をプレビューアプリにインストールします"
	# -d: PR を行き来すると versionCode が下がることがあるので downgrade も許可する (preview は debuggable)
	adb install -r -d "$dir"/*.apk
}

install_release() {
	local dir
	dir=$(mktemp -d)
	trap 'rm -rf "$dir"' EXIT
	gh release download -R "$REPO" --pattern 'ws-*.apk' -D "$dir"
	echo "$(basename "$dir"/*.apk) をインストールします"
	adb install -r "$dir"/*.apk
}

case "${1:-}" in
preview)
	require_adb
	install_preview "${2:-}"
	;;
release)
	require_adb
	install_release
	;;
*) usage ;;
esac
