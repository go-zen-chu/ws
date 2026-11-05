# Install and update ws with just the Galaxy

No PC. From **Termux** on the Galaxy, one command installs the latest ws or a PR's test build (ws preview).
The first-time setup takes about 15 minutes. After that it is "one command → one tap on the install screen".

> Terms: an **APK** is an Android app's install file. **ws preview** is a separate app for checking PRs; it installs alongside your everyday ws and has its own data.
>
> The Galaxy screens below are named in English with the Japanese label in parentheses.

## 1. Install Termux (first time only)

1. Install [F-Droid](https://f-droid.org/), then install **Termux** from F-Droid
   - Do not use the Play Store Termux; it is no longer updated
2. Open Termux and run

   ```sh
   pkg update
   pkg install gh git
   ```

## 2. Sign in to GitHub (first time only)

```sh
gh auth login
```

Answer the questions like this:

| Question | Answer |
| --- | --- |
| Where do you use GitHub? | GitHub.com |
| Preferred protocol | HTTPS |
| Authenticate Git with your GitHub credentials? | Yes |
| How would you like to authenticate? | Login with a web browser |

Note the 8-character code, press Enter, and when the browser opens enter the code and authorize.

## 3. Get the ws repository (first time only)

```sh
gh repo clone go-zen-chu/ws ~/ws
echo 'alias wsi=~/ws/scripts/install.sh' >> ~/.bashrc
source ~/.bashrc
```

From now on `wsi` runs the install script. To update the script, run `git -C ~/ws pull`.

## 4. Create a signing key and register it on GitHub (first time only, most important)

Android **only updates an app with an APK signed by the same key**. Without a fixed key you have to uninstall every time, which deletes your saved sites and sessions.
You can create and register it with Termux alone.

```sh
pkg install openjdk-17
cd ~
# Two keys: ws (release) and ws preview (PR builds). Each asks for a password twice.
# Press Enter to skip the name questions, then answer yes
keytool -genkeypair -v -keystore ws.jks -alias ws -keyalg RSA -keysize 4096 -validity 10000
keytool -genkeypair -v -keystore ws-preview.jks -alias ws-preview -keyalg RSA -keysize 4096 -validity 10000

# The release key goes into the "release" environment, which only main can use
R=go-zen-chu/ws
echo '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' \
  | gh api -X PUT repos/$R/environments/release --input -
gh api -X POST repos/$R/environments/release/deployment-branch-policies -f name=main -f type=branch
base64 -w0 ws.jks | gh secret set WS_KEYSTORE_BASE64 -R $R -e release
gh secret set WS_KEYSTORE_PASSWORD -R $R -e release --body '<release password>'
gh secret set WS_KEY_PASSWORD -R $R -e release --body '<release password>'

# The preview key goes into the repository Secrets
base64 -w0 ws-preview.jks | gh secret set WS_PREVIEW_KEYSTORE_BASE64 -R $R
gh secret set WS_PREVIEW_KEYSTORE_PASSWORD -R $R --body '<preview password>'
gh secret set WS_PREVIEW_KEY_PASSWORD -R $R --body '<preview password>'

# CI only accepts APKs signed by these certificates: put each SHA-256 (lowercase, no colons)
# into WS_RELEASE_CERT_SHA256 / WS_PREVIEW_CERT_SHA256 in .github/workflows/ci.yml
keytool -list -v -keystore ws.jks -alias ws | grep SHA256
keytool -list -v -keystore ws-preview.jks -alias ws-preview | grep SHA256
```

- **Do not lose** `ws.jks` or the password (without them, later builds cannot update the installed app). Back them up, e.g. to Google Drive. Never commit them to the repository
- Builds made by CI after you register the key can update the app in place

## 5. Install

### Everyday ws (latest release from main)

```sh
wsi release
```

### Checking a PR (ws preview)

```sh
wsi preview 12   # install PR #12
wsi preview      # install the newest open PR
```

It installs **only when CI succeeded on the PR's latest commit**. If CI is still running it says so and stops; wait a little and try again.

### When the install screen appears

- The first time, a screen asks to allow apps from this source ("この提供元のアプリを許可") → **allow Termux** and go back
- Tap "Install" ("インストール"), or "Update" ("更新") from the second time on
- If Play Protect warns you, tap "More details" ("詳細") → "Install anyway" ("インストールする"). It is your own app, so this is fine

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| "App not installed" ("アプリはインストールされていません") | (1) Different signing key (a build from before step 4 is installed) → uninstall once and install again. (2) You installed a PR older than the one installed → uninstall ws preview and install again (losing preview data is fine) |
| "CI is failure" | The PR's build failed. Ask Claude to fix it |
| `gh: command not found` etc. | Redo steps 1–2 |
| `HTTP 401` / `HTTP 403` | Run `gh auth login` again |

## Extras

### Update everyday ws automatically

Add `https://github.com/go-zen-chu/ws` to [Obtainium](https://github.com/ImranR98/Obtainium) to get a notification on every release from main and update with a tap (it cannot install PR builds, so use `wsi preview` for those).

### Install without tapping (advanced)

Connect Termux's adb to your own device with "Wireless debugging" in Developer options to install without the confirmation screen, and to switch back to older PRs.

```sh
pkg install android-tools
# Settings → About phone → Software information → tap Build number 7 times → Developer options → Wireless debugging ON
adb pair localhost:<pairing port> <pairing code>   # first time only (the "Pair device with pairing code" screen)
adb connect localhost:<port on the Wireless debugging screen>   # after every restart
WS_INSTALLER=adb wsi preview 12
```
