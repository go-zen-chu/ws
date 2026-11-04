# ws (work station)

[![ci](https://github.com/go-zen-chu/ws/actions/workflows/ci.yml/badge.svg)](https://github.com/go-zen-chu/ws/actions/workflows/ci.yml)

An Android app that turns Samsung Galaxy **DeX** into a personal development environment.
It opens the dev sites you use as tabs and keeps your work as sessions. The terminal is [Termux](https://termux.dev/).

The app is in English by default and in Japanese when the device language is Japanese.

## Features

### Frequently used sites

Register sites in the sidebar and on the home screen. The defaults are:

| Site | URL | Opens in |
| --- | --- | --- |
| GitHub | https://github.com | a ws tab |
| Claude | https://claude.ai | a ws tab |
| Gemini | https://gemini.google.com | the external browser ↗ |
| github.dev | https://github.dev | a ws tab |
| Codespaces | https://github.com/codespaces | a ws tab |

- Add one with "+ Add site" or "Add this page to sites" in the menu
- Long-press or right-click a site to edit, reorder or delete it
- If a tab for the site is already open, ws switches to it instead of opening a new one
- Google blocks sign-in inside a WebView, so set sites that need a Google sign-in to "Open in external browser". On DeX it opens in a window next to ws

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
- **ws preview** (for checking PRs; installs as a separate app): the `ws-preview` artifact of a PR's CI run

No PC needed. Install or update from Termux on the Galaxy (first-time setup: [docs/SETUP-GALAXY.md](docs/SETUP-GALAXY.md)).

```sh
scripts/install.sh preview <PR number>   # a PR's build as ws preview
scripts/install.sh release               # the latest release as ws
```

### Signing key (needed to keep your data across updates)

An APK signed with a different key cannot be installed over the existing app, and uninstalling deletes your saved sites and sessions.
The CI runner's debug key changes every run, so register a fixed key in the repository's Secrets.

```shell
keytool -genkeypair -v -keystore ws.jks -alias ws -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 ws.jks  # => WS_KEYSTORE_BASE64
```

| Secret | Value |
| --- | --- |
| `WS_KEYSTORE_BASE64` | the keystore, base64-encoded |
| `WS_KEYSTORE_PASSWORD` | the keystore password |
| `WS_KEY_ALIAS` | the key alias (`ws` in the example above) |
| `WS_KEY_PASSWORD` | the key password |

The Secrets sign both release and preview builds. Without them, builds are signed with the debug key.

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
