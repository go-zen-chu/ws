# ws (work station)

[![ci](https://github.com/go-zen-chu/ws/actions/workflows/ci.yml/badge.svg)](https://github.com/go-zen-chu/ws/actions/workflows/ci.yml)

An Android app that turns Samsung Galaxy **DeX** into a personal development environment.
It opens the dev pages you star as tabs and keeps your work as sessions. The terminal is [Termux](https://termux.dev/).

The app is in English by default and in Japanese when the device language is Japanese.

## Features

### Stars

Star the pages you use often; they are listed in the sidebar and on the home screen. The defaults are:

| Star | URL |
| --- | --- |
| Notifications | https://github.com/notifications |
| Pull requests | https://github.com/pulls |
| GitHub | https://github.com |
| github.dev | https://github.dev |
| Codespaces | https://github.com/codespaces |

- Add one with "+ Add star" or "Star this page" in the menu
- Long-press or right-click a star to edit, reorder or delete it
- If a tab for the star is already open, ws switches to it instead of opening a new one. A tab belongs to the most specific star (a tab on github.com/pulls/… belongs to Pull requests, not GitHub)
- Google blocks sign-in inside a WebView, so set stars that need a Google sign-in to "Open in external browser". On DeX it opens in a window next to ws

### Sessions (pick up where you left off)

- Open tabs are always saved and restored on next launch (only the tab you look at is loaded)
- "Save session" (Ctrl+Shift+S) saves the tabs as a named session; later changes are saved to it automatically
- Switch sessions from the sidebar. Unsaved tabs are kept as "Autosave …" when you switch away, so nothing is lost
- Long-press a session to open it, add its tabs to the current session, rename it or delete it
- Each tab's text size is saved with the session

### Terminal

Termux handles it. Open it with `>_` in the toolbar, from the sidebar or with Ctrl+Alt+T; on DeX it opens in a window next to ws.
Type `localhost:3000` (for example) in the address bar to open a dev server started in Termux in a ws tab.

### Works on both DeX and phones

- **Full screen by default** (status and navigation bars hidden; maximized on DeX). Toggle with ⛶, the menu or F11
- At 600dp wide or more (DeX etc.) the sidebar sits beside the page; below that (phones) it slides over it as a drawer. Drag its right edge to resize it
- Resizing the window or moving to/from DeX does not reload tabs
- Desktop site (a desktop Chrome UA) is the default; switch it in the menu
- Text size per tab: Ctrl+= / Ctrl+- / Ctrl+0 or the menu
- **Keyboard shortcuts are customizable** (menu → "Keyboard shortcuts"). Defaults match Chrome (Ctrl+T / Ctrl+W / Ctrl+Tab / Ctrl+L / Ctrl+1–9 / Alt+← …). See the list in [docs/PROJECT.md](docs/PROJECT.md#keyboard-shortcuts)
- Open links in a new tab: long-press or right-click a link, Ctrl+click or middle-click (background tab), or Ctrl+Shift+click
- `target="_blank"` and `window.open` (including OAuth pop-ups) open in a new tab
- File upload and download
- "Share" a URL from another app to open it in a new ws tab

## Project

Design, architecture and TODOs are in [docs/PROJECT.md](docs/PROJECT.md).

## Install

- **ws** (everyday use): every merge to main publishes an APK to [Releases](https://github.com/go-zen-chu/ws/releases)
- **ws preview** (for checking PRs; installs as a separate app): each PR's build is published as a pre-release `v<version>-pr<N>` (deleted when the PR closes), and is also kept as the `ws-preview` artifact of its CI run

No PC needed. Install or update from Termux on the Galaxy (first-time setup: [docs/SETUP-GALAXY.md](docs/SETUP-GALAXY.md)).

```sh
scripts/install.sh preview <PR number>   # a PR's build as ws preview
scripts/install.sh release               # the latest release as ws
```

### Signing key (needed to keep your data across updates)

An APK signed with a different key cannot be installed over the existing app, and uninstalling deletes your saved stars and sessions.
The CI runner's debug key changes every run, so register fixed keys in GitHub.
Release and preview use **separate keys**, so CI for PRs and branches never sees the release key.

```shell
keytool -genkeypair -v -keystore ws.jks -alias ws -keyalg RSA -keysize 4096 -validity 10000
keytool -genkeypair -v -keystore ws-preview.jks -alias ws-preview -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 ws.jks  # => WS_KEYSTORE_BASE64
```

| Key | Where | Secrets |
| --- | --- | --- |
| release | Environment `release` (deployment branches: `main` only) | `WS_KEYSTORE_BASE64`, `WS_KEYSTORE_PASSWORD`, `WS_KEY_PASSWORD` (alias `ws`) |
| preview | Repository Secrets | `WS_PREVIEW_KEYSTORE_BASE64`, `WS_PREVIEW_KEYSTORE_PASSWORD`, `WS_PREVIEW_KEY_PASSWORD` (alias `ws-preview`) |

CI checks with `scripts/verify-apk-cert.sh` that APKs are signed by the certificates in `WS_RELEASE_CERT_SHA256` and `WS_PREVIEW_CERT_SHA256` (`.github/workflows/ci.yml`); update them if you replace a key. Without the preview Secrets, preview builds are signed with the debug key.

## Development

Needs JDK 17+ and the Android SDK.

```shell
./gradlew testReleaseUnitTest lintRelease assembleRelease assembleDebug
# Install on a device (the debug build installs as ws preview)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## dotfiles

`dotfiles/` holds the zsh / vim / tmux / git settings from the earlier container image (archlinux) days, kept as a reference for Termux.

## LICENSE

MIT : [LICENSE](./LICENSE)
