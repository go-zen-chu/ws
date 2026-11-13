# ws project

An Android app that turns Samsung Galaxy DeX into a personal development environment.
This page records the design, architecture and TODOs. When the behavior changes, update this page in the same change.

- [Vision](#vision)
- [Design](#design)
- [Architecture](#architecture)
- [Releases and testing on a device](#releases-and-testing-on-a-device)
- [TODO](#todo)
- [Decision log](#decision-log)

## Vision

- A development workspace that **feels good both on DeX and as an ordinary phone app**
- Gathers the web services used for development (GitHub notifications and pull requests / github.dev / Codespaces …) as stars that open in tabs, so you can resume work right away
- No home-made terminal: leave it to Termux and integrate with it

Out of scope: polish as a general-purpose browser (extensions, sync, elaborate bookmark management) and implementing a terminal.

## Design

### Where it is used

| Setting | Screen | Input | Use |
| --- | --- | --- | --- |
| DeX (monitor) | Landscape, wide (≥ 600dp) | Physical keyboard + mouse | Main development. Termux or an external browser in windows next to it |
| Phone alone | Portrait, narrow (< 600dp) | Touch + soft keyboard | Checking things on the go, reviews, talking to Claude |
| Tablet / foldable | Either | Both | Switches between the two above by width |

At 600dp the layout switches between **wide** and **compact**.

### Layout

```
wide (DeX)                                    compact (phone)
┌──────────────────────────────────────┐      ┌───────────────────┐
│ ☰ ← → ⟳ [ address bar  ] ★ ⛶ >_ ⋮    │      │ ☰ [address] ★ >_ ⋮│
├────────┬─────────────────────────────┤      ├───────────────────┤
│Tabs ▾ +│                             │      │┌──────┐           │
│ pinned │   WebView / Home            │      ││Tabs  │  WebView  │
│▾ Group │                             │      ││      │           │
│   tab  │                             │      │└──────┘ (overlay) │
│ tab    │                             │      └───────────────────┘
└────────┴─────────────────────────────┘
```

- Two rows: **toolbar → content**, with the sidebar (the **vertical tab list**) left of the content
- ★ at the top right opens the stars; sessions, keyboard shortcuts and the other settings are in the menu ⋮
- wide: the sidebar sits beside the content (its visibility is remembered)
- Drag the sidebar's right edge to resize it (touch and mouse; on DeX the pointer becomes a left-right arrow). The width is 120dp and up (at most 60% of the window on wide, 85% on compact, and 560dp) and is shared by wide and compact
- compact: the sidebar is a drawer over the content and starts hidden. The content behind it is dimmed; tapping it closes the drawer. Picking a tab or pressing + closes it too. The back / forward / full-screen buttons are hidden (back is a gesture, full screen is in the menu)
- The sidebar's visibility is remembered separately for wide and compact
- A tab with nothing open is "Home" (stars, resume a session, tips)

### Tabs

- Tabs are a **vertical list** in the sidebar, with search (▾) and new tab (+) at its top. It scrolls to keep the active tab in view
- **Move**: long-press a tab and drag it up or down (touch or mouse). A blue line shows where it will land, and the list scrolls at its edges. Releasing without moving opens the tab menu (right-click opens it directly)
- **Tab menu**: pin / unpin, add to group (new or existing) / remove from group, tab display, close
- **Pinned tabs** stay at the top of the list, always show their icon and have no × (Ctrl+W still closes them). They are never grouped, can be reordered among themselves but not mixed with the others; new tabs never land among them. Pinning is saved with the tab in the session
- **Tab display** (menu ⋮ or the tab menu → "Tab display"): icon and title (default) / title only / icon only. Icon only narrows the list to a 48dp rail of icons without × (and without resizing). Saved per session (`tabDisplay`), so each workspace can look different
- **Icons** are the page's favicon, cached per host in `cacheDir/favicons/<host>.png` (`FaviconStore`) so restored, not-yet-loaded tabs have one. Without one, the host's first letter is shown (⌂ for Home)
- **Tooltips** show the title and URL, since icon-only tabs hide the title
- **Search tabs** (▾ at the top of the tab list, the menu or Ctrl+Shift+A): every word must appear in the title or URL, ignoring case. Enter opens the first match

### Tab groups

- A **group** is a named, collapsible set of adjacent tabs, drawn as a header (▾ name · count) with its tabs indented under it. Groups are saved per session (`groups`)
- **Drag and drop** decides grouping (`TabGroups.drop`), with a blue box or line showing the result:
  - onto the middle of a tab: joins its group, or makes a new group "Group N" with it
  - onto the lower half of a group's header: becomes the group's first tab
  - between two tabs of the same group: joins it; anywhere else (including the upper half of a header): leaves its group
- The tabs of a group always stay together: a tab opened from a grouped tab (links, `target=_blank`, background tabs) joins its group, and any tab inserted inside a group joins it (`TabGroups.groupForInsert`). Removing a tab from the middle of a group moves it after the group
- Tap a header to collapse / expand. A collapsed group still shows its active tab. Long-press or right-click a header for rename, collapse / expand, ungroup and close group
- Groups without tabs are removed. Sessions saved with broken groups (unknown ids, split groups) are repaired on load (`TabGroups.normalize`)

### Pull to refresh

- On touch, pulling a page down from its top and releasing past the point where the ⟳ indicator turns blue reloads it (like Chrome on Android). Mouse drags on DeX never pull
- The WebView only knows the document's scroll position, so the injected `PullToRefresh.SCRIPT` reports at each touchstart whether the page is really at the top (no scrolled ancestor of the touched element, no `overscroll-behavior-y: none / contain` on the root), and reports when the page handles the drag itself (`preventDefault` on touchmove, as editors like github.dev do). Either one stops the pull, so scrolling back up an inner list never reloads
- Until the page's script has run (before the first `onPageFinished`), pulling does nothing; use ⟳ or Ctrl+R
- The touches still go to the page; ws only watches them (`PullGesture` decides)

### Language

- The UI is in **English by default** and in **Japanese when the device language is Japanese** (`res/values/strings.xml` and `res/values-ja/strings.xml`)
- Every user-facing string lives in the string resources; Kotlin code has no UI text. Add new strings to both files (lint fails on a missing translation)
- `ShortcutAction` holds a string resource ID (`labelRes`); `MainActivity.actionLabel()` turns it into text
- Names ws generates and stores (e.g. "Autosave …" sessions) use the language at the time they are created

### Full screen

- **Full screen by default**: the status and navigation bars are hidden and appear briefly when you swipe from an edge
- On DeX the window starts maximized (`com.samsung.android.dex.launchwidth/launchheight = 0`)
- Toggle with ⛶ in the toolbar, the menu or F11. The state is remembered

### Keyboard shortcuts

- Every operation is a `ShortcutAction`, and any keys can be assigned to it (menu → "Keyboard shortcuts")
- An operation can have several keys (e.g. Reload = Ctrl+R / F5)
- Assigning a key that is already used moves it from the other operation (a toast says so)
- To avoid stealing typing from pages, only combinations with Ctrl / Alt / Meta, or F1–F12, are allowed
- Defaults follow Chrome. Keys that clash with github.dev (VS Code), such as Ctrl+/ and F1, are not used by default
- Tooltips and the menu show the keys currently assigned
- Menu → "Keyboard shortcuts" shows a two-column table (operation / keys); picking a row adds, removes or resets its keys

| Operation | Default |
| --- | --- |
| New tab / close tab | Ctrl+T / Ctrl+W |
| Reopen closed tab | Ctrl+Shift+T |
| Next / previous tab | Ctrl+Tab / Ctrl+Shift+Tab |
| Tab 1–8 / last tab | Ctrl+1–8 / Ctrl+9 |
| Search tabs | Ctrl+Shift+A (like Chrome) |
| Pin / unpin tab | none |
| Address bar | Ctrl+L |
| Reload | Ctrl+R, F5 |
| Back / forward | Alt+← / Alt+→ |
| Larger / smaller text | Ctrl+= (Ctrl++) / Ctrl+- (and numpad + / -) |
| Reset text size | Ctrl+0 |
| Show / hide tabs (the sidebar) | Ctrl+Shift+B |
| Toggle full screen | F11 |
| Save session | Ctrl+Shift+S |
| Stars (★ menu) | none |
| Link hints / open a link in a background tab | none (F / Shift+F on a page, see below) |
| Open Termux | Ctrl+Alt+T |
| Keyboard shortcuts | none |

### Input modes

- A page is in one of two modes, shown semi-transparently at the bottom right of the page with how to switch (hidden on home):
  - **VISUAL**: keys are ws's commands: `f` link hints, `F` link in a background tab, `i` insert mode
  - **INSERT**: keys go to the page for typing. On while a text field (or an iframe) has the focus, or after `i`; Esc goes back to visual (Esc the page did not use itself also leaves the text field, like Vimium)
- `i` in visual mode is for sites with their own single-key shortcuts: it lets every key through until Esc
- The page script decides the mode and reports it through `wsHints.mode()`; `BrowserTab.inputMode` keeps it per tab (not saved; a new page starts visual)

### Link hints

- Like Vimium: **F** on a page puts a label on every visible link and control; typing the label clicks it (text fields get the focus instead). **Shift+F** opens the picked link in a background tab
- Esc, scrolling, a click or a key outside the labels cancels; Backspace takes back the last letter
- F is only caught when the focus is not in a text field (input, textarea, contenteditable), so typing is never stolen. This is the one exception to "shortcuts need a modifier": the page's script decides, not the keymap
- Labels use Vimium's characters `sadfjklewcmpgh` and none is a prefix of another, so a hint fires as soon as it is typed
- When the WebView does not have the focus (e.g. after a toolbar button), F and Shift+F start the hints from the app. The keymap also has "Link hints" actions (no default) for starting them from anywhere
- Links inside iframes are not labeled

### Settings export / import

- Menu → "Export settings" saves the keyboard shortcuts and stars as a JSON file (`ws-settings-YYYYMMDD.json`, `{"format": "ws-settings", "version": 1, "keymap": {…}, "stars": […]}`) with the system file picker; "Import settings" reads one back and, after asking, replaces both
- Import rejects files that are not ws settings, drops keys without a modifier and stars that are not http(s), and reads at most 1 MB

### Stars

- The pages used often are **stars** (a `Star` is a name, a URL and "open in external browser")
- **★ at the top right** lists the stars (↗ marks external ones) and has "Star this page" and "Edit stars" (edit, move, delete, add). Home lists them too; long-press or right-click one there to edit it
- Defaults: GitHub notifications, GitHub pull requests, GitHub, github.dev, Codespaces
- "Open in external browser" stars open in a window next to ws on DeX (for sites that need Google sign-in, which a WebView blocks)
- If a tab for the star is open, ws switches to it instead of creating one. A tab belongs to the most specific star covering its URL (`Star.ownerOf`), so github.com/pulls/… belongs to Pull requests, not GitHub
- The `sites` saved by older versions are moved to `stars` once on load: the old Claude / Gemini defaults are dropped and the new defaults are added at the top (`Star.migrateFromSites`)
- Sites that misbehave in a WebView are fixed by injecting CSS (`PageFixes`). For now only GitHub's code view: a transparent `<textarea>` (`#read-only-cursor-text-area`) over the code turns a drag into a text selection so the code cannot scroll, so `pointer-events: none` lets touches through to the code lines underneath (code can still be selected with a long-press or a mouse drag)

### Opening links in a new tab

| Action | Result |
| --- | --- |
| Long-press / right-click → menu | Open in new tab, background tab or external browser; copy link |
| Ctrl+click / middle-click | Open in a background tab (a toast says so) |
| Ctrl+Shift+click | Open in a new tab and switch to it |
| `target=_blank` / `window.open` | Open in a new tab and switch to it |

- These are also explained in the Tips on the home screen
- A WebView simply follows Ctrl+click / middle-click, so injected JS catches them and hands the link to the app through the `wsLinks` interface (`LinkClicks`). To stop pages from opening tabs on their own, only http(s) links from the visible tab within 1.5 seconds of a real click / touch are accepted

### Sessions

- Open tabs are always saved and restored at launch (only the tab shown is loaded)
- A session saved under a name keeps receiving later changes automatically (= a workspace)
- Switching away from unsaved tabs keeps them as "Autosave …" so no work is lost
- Sessions are in the menu ⋮ → "Sessions": the current one is checked (an unsaved one shows as "Unsaved · N tabs"; picking it saves it), pick another to switch, plus "Save session", "New session" and "Manage sessions…" (open, add its tabs, rename, delete). Home also lists sessions to resume
- Text size is per tab and saved with the tab in the session (it comes back on restore, when switching sessions and when reopening a closed tab)

### Text size

- `WebSettings.textZoom` enlarges or shrinks only the text (the layout width stays)
- Steps: 50 / 67 / 75 / 80 / 90 / 100 / 110 / 125 / 150 / 175 / 200 / 250 / 300% (the same as Chrome's zoom levels)
- Change it with the shortcuts or menu → "Text size: N%". A toast shows the new value
- Tabs opened from a page (a link's "Open in new tab", `target=_blank`, background tabs) inherit the opener's text size. A new tab from Ctrl+T is 100%

### Look

- A VS Code-like dark theme (`Palette`)
- Icons are characters for now (☰ ← → ⟳ ★ ⛶ >_ ⋮)

## Architecture

### Principles

- **No dependencies.** Built only on the Android framework's `Activity` / View / `WebView`. The UI is built in code; the resources are the manifest, icons and strings
  - Why: to stay small and hard to break with builds and updates, and to keep JVM unit tests easy
- Persistence is JSON (`org.json`) in `SharedPreferences` (`ws`)
- Logic that does not depend on the Android framework (URL parsing, JSON conversion, keymaps) is kept separate and checked with JVM unit tests

### Files

| File | Role |
| --- | --- |
| `MainActivity.kt` | The whole screen: tabs and WebViews, the vertical tab list / home, star and session menus, dialogs, running shortcuts, full screen, other apps |
| `BrowserTab.kt` | One tab (URL, title, text size, pinned, group, lazily created WebView) |
| `Tabs.kt` | `TabDisplay` (tab list look), `TabOrder` (pinned-tab rules), `TabSearch`, `Favicons` / `FaviconStore` (per-host favicon cache) |
| `TabGroups.kt` | `TabGroup`, and `TabGroups`: the rows of the tab list, drop targets and where a dropped tab lands, keeping groups together |
| `Star.kt` | `Star` and `StarStore`, the default stars, migration from the old `sites` |
| `Session.kt` | `TabState` / `Session` (tabs, active tab, tab display) and `SessionStore` (the current session + named sessions), `TextZoom` (text size steps) |
| `Shortcuts.kt` | `KeyBinding` / `ShortcutAction` / `Keymap` and `KeymapStore` |
| `PullToRefresh.kt` | `PullToRefresh` (injected script that says whether a touch may pull) and `PullGesture` (decides when a pull reloads) |
| `LinkHints.kt` | `InputMode` (visual / insert) and `LinkHints`: the injected script for F / Shift+F link hints and the modes, and the label generator it calls through `wsHints` |
| `SettingsBackup.kt` | `SettingsBackup`: the exported settings file (shortcuts and stars) and its validation |
| `Web.kt` | `UrlInput` (address bar input → URL), `UserAgent` (desktop UA), `LinkClicks` (Ctrl+click / middle-click), `PageFixes` (per-site CSS fixes), constants |
| `Ui.kt` | Colors and `dp()`, `SidebarWidth` (sidebar width range) |
| `res/values/strings.xml` / `res/values-ja/strings.xml` | UI text in English (default) / Japanese |

### Stored data (SharedPreferences `ws`)

| Key | Contents |
| --- | --- |
| `stars` | Array of `Star`. When absent, migrated from the old `sites` key (then removed), or the defaults |
| `session.current` | The session restored at launch: `{"name", "active", "savedAt", "tabDisplay", "groups", "tabs"}`. Tabs are `{"url", "title", "textZoom", "pinned", "group"}` (`textZoom` is omitted at 100%, `pinned` when false, `group` when ungrouped; a missing `tabDisplay` means icon and title). Groups are `{"id", "name", "collapsed"}` (`groups` is omitted when empty, `collapsed` when false) |
| `session.named` | Array of named sessions (newest first) |
| `keymap` | Only the shortcuts changed from the defaults (`{"ACTION": ["keyCode:modifiers", …]}`), so new actions get their defaults |
| `desktop_mode` / `fullscreen` | Display settings |
| `sidebar_visible` / `sidebar_visible_compact` | Sidebar visibility for wide / compact |
| `sidebar_width` | Sidebar width (dp). Fitted to the window with `SidebarWidth.clamp` when shown |

Favicons are not in SharedPreferences: they are PNGs in `cacheDir/favicons/` (one per host), which Android may clear at any time; they come back on the next visit.

### Main flows

- **Tabs**: `tabs`, `activeIndex` and `groups`. New tabs go through `insertTab` (keeps pinned tabs first and groups together, shifts `activeIndex`) and reordering through `reorderTabs` (keeps the same tab active); group changes from `TabGroups` come back as a list of `Placed` that `applyPlacement` applies. The tab list is drawn from `TabGroups.rows` (headers and tabs), and `renderedRows` maps each child view back to its row. Dragging uses the framework's drag and drop: the tab's long-press starts it, `TabDragListener` on the list's scroll view shows the drop indicator from `TabGroups.dropAt` on each location event (skipped until the redrawn list is laid out) and moves the tab on `ACTION_DROP`. A tab's WebView is created the first time it is shown. Hidden tabs are `GONE` to keep their state. `target=_blank` / `window.open` become new tabs in `onCreateWindow`
- **Auto-save**: `persist()` → `SessionStore.saveCurrent()` on `doUpdateVisitedHistory` (catches SPA pushState too), title changes, tab operations and `onStop`
- **Shortcuts**: `dispatchKeyEvent` → `KeyBinding.of(event)` → `Keymap.actionFor()` → `perform(action)`, before the WebView sees the key. While the settings dialog is open keys go to the dialog, so nothing fires
- **Window size**: `configChanges` prevents recreation; `onConfigurationChanged` reruns `applyWindowSize()` (compact / wide) and `applyFullscreen()`
- **Page injection**: on `onPageFinished` and `doUpdateVisitedHistory` (SPA navigation), `evaluateJavascript` runs `LinkClicks.SCRIPT`, `PullToRefresh.SCRIPT`, `LinkHints.SCRIPT` and `PageFixes.scriptFor(url)`. They do nothing the second time on the same page (checked with `window.__wsLinkClicks`, `window.__wsPull`, `window.__wsHints` and `<style id="ws-page-fixes">`)
- **Link hints**: the script's capture-phase `keydown` listener starts hint mode, gets labels synchronously from `HintBridge.labels()` and draws them in a closed shadow root (so page CSS does not touch them). Shift+F hands the URL to `HintBridge.openInNewTab()`, which only accepts http(s) URLs from the visible tab within 1.5 s of a key press
- **Sidebar**: `body` (a FrameLayout) stacks the content, the scrim (compact only) and the sidebar. On wide the content's left margin is the sidebar width. The margin is applied by reassigning the LayoutParams (only mutating `marginStart` is not re-resolved after the first layout, which left a blank strip when the sidebar was hidden)
- **Renderer crash**: `onRenderProcessGone` destroys that tab's WebView and recreates it when the tab is shown again
- **Other apps**: the external browser and Termux open in an adjacent window with `FLAG_ACTIVITY_LAUNCH_ADJACENT`. `intent://` URLs lose their component / selector and are limited to BROWSABLE

### Build and distribution

- AGP 8.11 / Kotlin 2.2 / compileSdk and targetSdk 36 / minSdk 29
- Two build types that install side by side as separate apps (with separate data)

  | Build | App ID | Name / icon | Use |
  | --- | --- | --- | --- |
  | release | `xyz.amasuda.ws` | ws / blue | Everyday use. Distributed from main as a GitHub Release |
  | debug | `xyz.amasuda.ws.preview` | ws preview / orange | Checking PRs. Debuggable, so WebView DevTools work |

- Version: `versionName` = `<ws.versionBase>.<CI run number>` (`ws.versionBase` is in `gradle.properties`), `versionCode` = the run number. Preview builds get `-pr<number>`
- Release and preview have separate signing keys. The release key (`WS_KEYSTORE_*`) is in the GitHub Environment `release`, which only `main` can deploy to; the preview key (`WS_PREVIEW_KEYSTORE_*`) is in the repository Secrets. Without a key the debug key is used (it changes every run, so the app cannot be updated in place)

## Releases and testing on a device

### Flow

```
Open / push a PR ──▶ CI: test, lint, build ──▶ Actions artifact ws-preview
                                             └─▶ pre-release v0.1.<run>-pr<N> (ws-preview-….apk)
                                                  │
                    Termux: scripts/install.sh preview <PR>  /  Obtainium (newest PR)
                                                  ▼
                                     ws preview on the device is updated
Merge to main ─────▶ CI: test, lint, build ──▶ GitHub Release v0.1.<run> (ws-0.1.<run>.apk)
                                                  │
                    Termux: scripts/install.sh release
                                                  ▼
                                          ws on the device is updated
```

- **A GitHub Release is created only on a push (merge) to main.** Same-repo PRs publish only pre-releases `v<version>-pr<N>` (`publish-preview` job), keeping the newest one per PR; `preview-cleanup.yml` deletes them when the PR closes. Pre-releases are never marked Latest, so `install.sh release` and Obtainium's ws entry ignore them
- `publish-preview` and `preview-cleanup` have a write token but never check out or run PR code; they only upload the APK `build` produced
- main is protected by the repository ruleset `main`: changes land only through PRs whose `build` check passed (no bypass, so admins included; no force pushes or deletions), so only reviewed code reaches the `release` environment
- PR CI keeps `ws-preview` (the preview APK) as an artifact. The release APK is built and signed only in the `release` job on main
- `workflow_dispatch` runs CI on any branch by hand (artifacts only, no Release)
- The `release` job fails if the release key is missing or the APK is not signed by the certificate in `WS_RELEASE_CERT_SHA256`, so an APK that cannot update ws is never released. `scripts/verify-apk-cert.sh` does the check, and PR CI runs it on the preview APK too (`WS_PREVIEW_CERT_SHA256`), so a broken check shows up before merge
- Actions are pinned to commit SHAs, checkouts do not keep the token (`persist-credentials: false`), and the `release` job does not use the Gradle cache, so code from PRs and third parties has fewer ways into the job that holds the release key

### Preparing the device (first time only)

**Everything is done on the Galaxy, without a PC.** Beginner-friendly steps are in [SETUP-GALAXY.md](SETUP-GALAXY.md) (Termux, GitHub sign-in, creating and registering the signing key).

### Usage

```sh
scripts/install.sh preview 12   # install the build of PR #12's latest commit as ws preview
scripts/install.sh preview      # the newest open PR
scripts/install.sh release      # install the latest GitHub Release as ws
```

- preview installs **only when CI succeeded on the PR's latest commit** (never an old build). If CI is running or failed, it prints the URL and stops
- By default the APK is handed to Android's installer (`termux-open`, one tap to confirm). No developer options or adb needed
- `WS_INSTALLER=adb` installs with adb without confirmation, even when switching PRs lowers versionCode (preview is debuggable, so downgrades are allowed). The normal installer cannot downgrade, so in that case uninstall ws preview and install again
- PRs from forks cannot use Secrets and get the debug key, so they need one uninstall

## TODO

### Check on a device

- [ ] Does ws start maximized on DeX (`dex.launchwidth/launchheight = 0`)?
- [ ] How does full screen (hidden system bars) behave in a DeX window, and with the taskbar?
- [ ] Do tabs avoid reloading when switching between phone and DeX?
- [ ] List the keys DeX / One UI take before the app (Ctrl+Tab, Ctrl+Alt+T, …)
- [ ] Do the character icons (⛶ etc.) render in the device font?
- [ ] Do github.dev / Codespaces work properly in a WebView with a desktop UA?
- [ ] Can GitHub's code view be scrolled by dragging, and selected / copied with a long-press or the mouse? (It stops working if GitHub's DOM changes)
- [ ] Do Ctrl+click, middle-click and right-click on links work with a DeX mouse? (Not before the page finishes loading)
- [ ] Do the text size shortcuts work on a JIS keyboard (Ctrl+Shift+; is assigned as Ctrl++)? Is it a problem that ws takes Ctrl+0 (focus sidebar in VS Code) on github.dev?
- [ ] Sidebar resizing: can the edge be grabbed with a DeX mouse, and is it easy with touch on a phone (the grab area is 12dp)?
- [ ] Tab dragging: does a long-press drag work with touch and the DeX mouse, and does releasing without moving open the menu? Does the list scroll at its edges? Are the "onto" (middle half) and "between" (edges) zones easy to hit?
- [ ] Is the vertical tab list comfortable on a phone, where it is a drawer that starts hidden?
- [ ] Does DeX / One UI take Ctrl+Shift+A (search tabs) before the app?
- [ ] Do favicons show up for GitHub and github.dev (WebView sends `onReceivedIcon`)?
- [ ] Pull to refresh: does it reload on GitHub, and never while scrolling back up in github.dev's editor or file tree, or inner lists?
- [ ] Link hints: do F / Shift+F work on GitHub and github.dev without stealing typing in the editor, search boxes and comment fields? Are labels placed well and not hidden behind sticky headers?
- [ ] Input modes: does the indicator follow focus changes (address bar, github.dev editor, iframes) and stay out of the way on a phone? Does taking `i` clash with GitHub's `g i`?
- [ ] Settings export / import: does the file picker save to and open from Downloads / Drive?
- [ ] Does the UI switch between English and Japanese with the device language, and does any text overflow in either language?

### DeX / app usability

- [ ] A dedicated shortcut settings screen (search, per-action descriptions, list of conflicts)
- [ ] Command palette (like Ctrl+Shift+P: search tabs, stars, sessions and operations together)
- [ ] Middle-click a tab to close it
- [ ] Drag a group's header to move the whole group
- [ ] Group colors (like Chrome)
- [ ] Hold the pointer still at the tab list's edge to keep scrolling while dragging (today it scrolls only while the pointer moves)
- [ ] Open image links (avatars etc.) in a new tab from the long-press menu (only text links today)
- [ ] Switching tabs on compact (swipe or a tab overview)
- [ ] Replace the character icons with vector icons
- [ ] Find in page (Ctrl+F clashes with github.dev; needs thought)
- [ ] Mouse back / forward buttons

### Sessions

- [ ] Also restore WebView back history and scroll position
- [ ] Export / import sessions (moving devices, backups)
- [ ] A note per session (what the work is)

### Integration

- [ ] Start commands / dev servers with Termux's RUN_COMMAND
- [ ] Detect localhost dev servers automatically

### Releases and testing on a device

- [ ] UI tests on a CI emulator (launch, tabs, session restore, shortcuts)
- [ ] Walk through SETUP-GALAXY.md on a real device (does `termux-open` open the installer?)
- [ ] Pick and install a PR build from inside ws preview (list the public `v*-pr*` pre-releases; no token needed)
- [ ] Show "PR #… / commit" inside the preview app

### Android developer verification in 2027 (sideloading restrictions)

From 2027, worldwide, Google-certified devices (including Galaxy) **can no longer newly install apps from unregistered developers**. This covers direct APK installs, F-Droid and Obtainium alike (starting early, from 2026-09-30, in Brazil, Indonesia, Singapore and Thailand). Ways around it: installs via adb are exempt, a one-time "advanced flow" setting allows unregistered apps (with a 24-hour wait), and there are free "limited distribution" accounts with no identity check for up to 20 devices.

- [ ] Check the latest status in early 2027 (when it starts in Japan, and what F-Droid, Termux and Obtainium do)
- [ ] Create a limited distribution account (free) and register `xyz.amasuda.ws` and `xyz.amasuda.ws.preview` with the signing key (the steps are not confirmed yet)
- [ ] Check the backup of the registered signing key (`ws.jks`) (losing it means you can no longer update as the registered developer)
- [ ] If Termux is not registered, add the one-time advanced flow steps to SETUP-GALAXY.md
- [ ] As a fallback, confirm that the `WS_INSTALLER=adb` steps work (adb is exempt)

References: [Android Developers FAQ](https://developer.android.com/developer-verification/guides/faq) / [Google Help](https://support.google.com/android-developer-console/answer/16561738?hl=en) / [F-Droid's open letter](https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html)

### Quality

- [ ] UI tests (Robolectric or instrumented tests)
- [ ] Split tab management, sidebar and dialogs out of `MainActivity` as it grows

### Done

- [x] Vertical tabs in the sidebar; tab groups made by drag and drop, collapsible; stars in a ★ menu at the top right; sessions in the menu ⋮
- [x] Pull a page down from its top to reload it
- [x] Move tabs by long-press dragging; pin tabs; per-session tab display (icon and title / title / icon); search tabs (Ctrl+Shift+A)
- [x] Stars replace sites; GitHub notifications / pulls at the top by default, Claude / Gemini removed
- [x] Publish PR builds as pre-releases for Obtainium; protect main
- [x] Separate release and preview signing keys; the release key is only available to main through the `release` environment
- [x] Register the Secrets (`WS_KEYSTORE_*`); CI on main fails without them
- [x] English UI by default, Japanese when the device language is Japanese; docs, CLAUDE.md and PRs in English
- [x] Open links in a new tab with Ctrl+click / middle-click; explain the ways to do it in the home Tips
- [x] Change text size (per tab, saved in the session)
- [x] Fix the blank strip left of the content after hiding the sidebar
- [x] Resize the sidebar by dragging
- [x] On compact, tap outside the drawer to close it
- [x] Work around GitHub's code view turning drags into text selection (with CSS)
- [x] Site registration (defaults: GitHub / Claude / Gemini / github.dev / Codespaces)
- [x] Session auto-save and named sessions
- [x] Termux integration
- [x] Full screen by default
- [x] Customizable keyboard shortcuts
- [x] Keyboard shortcut settings as a table
- [x] Export / import settings (shortcuts and stars)
- [x] Vimium-style link hints (F / Shift+F)
- [ ] Link hints inside iframes
- [x] compact (phone width) layout: drawer sidebar, trimmed toolbar
- [x] Preview app for testing (separate app ID) and the PR build install script
- [x] GitHub Releases only on merges to main

## Decision log

| Date | Decision | Why |
| --- | --- | --- |
| 2026-10-24 | Change the scope from a Docker image to an Android app | To make DeX a development environment |
| 2026-10-24 | Leave the terminal to Termux | Building one is costly, and Termux is enough |
| 2026-10-24 | No dependencies; the UI is built in code | To stay small and hard to break |
| 2026-10-24 | Gemini opens in the external browser by default | Google blocks sign-in inside a WebView |
| 2026-10-24 | Full screen by default; wide / compact split at 600dp | To work well both on DeX and as an ordinary app |
| 2026-10-24 | Shortcuts need a modifier key (F-keys excepted) | So they never steal typing from pages |
| 2026-10-24 | Test on a device with a preview app (debug build) under a separate app ID, installing the PR's CI artifact with adb from Termux | To keep everyday data safe and test without leaving DeX |
| 2026-10-24 | GitHub Releases only on pushes to main; version is `<base>.<run number>` | To limit releases to the state of main and number them automatically |
| 2026-10-24 | Claude does the development; testing on a device uses the Galaxy's Termux, no PC. Installing goes through Android's installer by default rather than adb | One command + one tap, with no developer options or pairing, so even a beginner can do it |
| 2026-10-24 | Handle the 2027 developer verification with a free limited distribution account (fallbacks: advanced flow and adb) | Only my own devices use it, so the 20-device limit is enough and the install flow stays the same |
| 2026-10-24 | App ID and package name `xyz.amasuda.ws` (preview: `xyz.amasuda.ws.preview`) | Using my own domain amasuda.xyz avoids clashes and makes it easy to register as my ID for the 2027 developer verification. Nothing is lost by changing it now, before it is distributed to anyone |
| 2026-11-03 | Fix misbehaving sites by injecting CSS with `PageFixes`. Resize the sidebar by dragging its right edge; one width shared by wide and compact | Small, targeted fixes without building browser extensions. Width is a matter of taste, so one value is enough |
| 2026-11-03 | Text size is per tab via `textZoom` and saved in the session. Default keys follow Chrome: Ctrl+= / Ctrl+- / Ctrl+0 | Readability differs by site and task, so reopening a session should look the same. Text only, not whole-page zoom, so the wide DeX layout is not broken |
| 2026-11-04 | Catch Ctrl+click / middle-click with JS and hand them to the app via `addJavascriptInterface`, accepting only http(s) links from the visible tab right after a click | A WebView does not open these in new tabs. No dependency needed, and pages cannot spawn tabs on their own |
| 2026-11-04 | English is the repository's default language (docs, CLAUDE.md, PRs, the app UI). The app shows Japanese when the device language is Japanese, via Android string resources | To make the repository readable to anyone, while the app stays comfortable in Japanese on my own device |
| 2026-11-04 | Fail CI on main when the signing Secrets are missing | Releases v0.1.11 and v0.1.16 were signed with each runner's throwaway debug key, so Obtainium could not update between them |
| 2026-11-05 | Separate signing keys: the release key lives in the `release` environment (main only), the preview key in the repository Secrets. Pin Actions to SHAs; no Gradle cache in the release job | PR and branch CI runs code from PRs and third-party actions, and a leaked release key could sign an update that reads ws's data, including sign-in cookies |
| 2026-11-05 | Key aliases are plain values in the workflow, not Secrets | GitHub masks every occurrence of a Secret's value in logs, and the alias `ws` hid most of the log |
| 2026-11-05 | Publish each same-repo PR's preview APK as a pre-release `v<version>-pr<N>` from a job that never runs PR code; delete them when the PR closes. Protect main | Obtainium can only follow Releases, and Actions artifacts need a GitHub login. The tag matches the preview's versionName. Branch protection closes the gap where a direct push to main could use the `release` environment |
| 2026-11-05 | Call the often-used pages "stars"; defaults start with GitHub notifications and pulls, without Claude / Gemini. Old `sites` are migrated once | GitHub notifications and PRs are what I open most, and Claude / Gemini are not needed in ws. Migrating keeps the stars already added |
| 2026-11-06 | `persist-credentials: false` on every checkout; main protection lives only in the ruleset `main` (required check `build` added there, classic branch protection removed) | No job pushes, so the token need not stay on disk for later steps. One place for the rules, matching GuitarMR |
| 2026-11-08 | Tabs move by long-press dragging with the framework's drag and drop; releasing without moving opens the tab menu. Pinned tabs work like Chrome's (first, icon only, no ×). Tab display is saved per session; favicons are cached per host in the cache directory. Search tabs uses Chrome's Ctrl+Shift+A | Long-press is the only gesture that works the same with touch and the DeX mouse while leaving taps and scrolling alone. Each workspace may want a different density. Favicons are rebuildable, so they do not belong in the session JSON |
| 2026-11-09 | Pull to refresh is our own `PullGesture` watching the WebView's touches, gated by an injected script that reports whether the page is at its top and whether it handles the drag | No dependencies (no SwipeRefreshLayout). The WebView's scroll position alone misses inner scrollers, which would reload github.dev while scrolling up |
| 2026-11-10 | Tabs move from the horizontal strip into the sidebar as a vertical list with groups; stars go to a ★ menu at the top right and sessions into the menu ⋮. Grouping is by drag and drop: the middle of a tab means "onto", its edges "between". Dragging now moves the tab on drop (with an indicator) instead of live | A vertical list fits many long titles and a hierarchy, and DeX has width to spare. Moving live cannot tell "onto" from "past", so the result is shown first and applied on release. Keeping a group's tabs adjacent lets the list stay a flat `tabs` list with a group id per tab |
| 2026-11-10 | Link hints are caught by an injected script on plain F / Shift+F, not by the keymap, and only when no text field has the focus. Labels come from Kotlin through a synchronous `@JavascriptInterface` call | Only the page knows whether the user is typing, so this keeps the "never steal typing" rule. Generating labels in Kotlin keeps one, unit-tested implementation without a JS test setup |
| 2026-11-10 | Settings export covers shortcuts and stars only, as a JSON file through the system file picker (SAF) | Those are what takes effort to set up again; sessions and display settings are per device. SAF needs no storage permission |
| 2026-11-10 | Visual / insert modes like Vimium, with an always-on semi-transparent indicator drawn by the app (not the page) at the bottom right; `i` enters insert mode, Esc leaves it | The indicator shows when F works and how to switch. Drawing it natively keeps page CSS from hiding or restyling it, and it is not clickable so it never blocks the page |
