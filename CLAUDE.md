# ws

A development workspace Android app for Samsung Galaxy DeX (Kotlin, no dependencies).

## Language

- English is the default language of this repository: docs, CLAUDE.md, code comments, commit messages, PR titles and descriptions, and review replies
- The app UI is English by default and Japanese when the device language is Japanese. Put every user-facing string in `app/src/main/res/values/strings.xml` (English) and `values-ja/strings.xml` (Japanese); never hard-code UI text in Kotlin

## Project page

Design, architecture, TODOs and the decision log are in [docs/PROJECT.md](docs/PROJECT.md).

- Read it before starting work
- When you change features, screens, stored data or the file layout, update the matching parts of PROJECT.md in the same commit
- Move finished TODOs to "Done", and add newly found issues to TODO
- Add one line to the decision log when you decide on an approach

## Development

- Consider usability on both DeX (600dp wide or more) and phones (under 600dp)
- Keep logic that does not depend on Android outside `MainActivity`, and write JVM unit tests for it in `app/src/test`
- Check: `./gradlew testReleaseUnitTest lintRelease assembleRelease assembleDebug` (same as CI)
- Submit changes as PRs. Test on a device with the preview APK (`xyz.amasuda.ws.preview`) that PR CI builds; merging to main creates a GitHub Release
