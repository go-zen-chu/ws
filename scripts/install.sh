#!/usr/bin/env bash
# Install ws on this device from Termux.
#
#   scripts/install.sh preview [PR]   preview app (xyz.amasuda.ws.preview) from a PR's CI build;
#                                     without PR, the newest open PR
#   scripts/install.sh release        app (xyz.amasuda.ws) from the latest GitHub Release
#
# By default the APK is handed to Android's installer (tap "Install" to confirm).
# WS_INSTALLER=adb installs silently with adb over Wireless debugging instead.
# Setup: docs/SETUP-GALAXY.md
set -euo pipefail

REPO="${WS_REPO:-go-zen-chu/ws}"
INSTALLER="${WS_INSTALLER:-android}"
WORK_DIR=""
trap '[[ -n "$WORK_DIR" ]] && rm -rf "$WORK_DIR"' EXIT

usage() {
	sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
	exit 1
}

require_tools() {
	command -v gh >/dev/null || { echo "gh がありません: pkg install gh && gh auth login" >&2; exit 1; }
	if [[ "$INSTALLER" == "adb" ]]; then
		require_adb
	else
		command -v termux-open >/dev/null || { echo "termux-open がありません: pkg install termux-tools" >&2; exit 1; }
	fi
}

require_adb() {
	command -v adb >/dev/null || { echo "adb がありません: pkg install android-tools" >&2; exit 1; }
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
	WORK_DIR=$(mktemp -d)
	gh run download "$run" -R "$REPO" -n ws-preview -D "$WORK_DIR"
	echo "PR #$pr (${head:0:7}) を ws preview にインストールします"
	# -d: PR を行き来すると versionCode が下がることがあるので downgrade も許可する (preview は debuggable)
	install_apk "$WORK_DIR"/*.apk -d
}

install_release() {
	WORK_DIR=$(mktemp -d)
	gh release download -R "$REPO" --pattern 'ws-*.apk' -D "$WORK_DIR"
	echo "$(basename "$WORK_DIR"/*.apk) を ws にインストールします"
	install_apk "$WORK_DIR"/*.apk
}

install_apk() {
	local apk="$1"
	shift
	if [[ "$INSTALLER" == "adb" ]]; then
		adb install -r "$@" "$apk"
		return
	fi
	# Android's own installer. It cannot downgrade: if it says the app can't be installed, an
	# older build than the installed one was picked; uninstall ws preview and run again.
	termux-open --content-type application/vnd.android.package-archive "$apk"
	echo "インストール画面で「インストール」 (更新なら「更新」) をタップしてください"
	# termux-open returns immediately; keep the APK until the installer has read it.
	sleep 15
}

case "${1:-}" in
preview)
	require_tools
	install_preview "${2:-}"
	;;
release)
	require_tools
	install_release
	;;
*) usage ;;
esac
