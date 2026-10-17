# Releasing

## Once: the signing key

Release builds are signed with your own key. Make one (keep a copy
somewhere safe outside GitHub: losing it means Play can't take updates
from you), then add four secrets in GitHub › Settings › Secrets and
variables › Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the `.jks` file, base64: `base64 -i release.jks` |
| `KEYSTORE_PASSWORD` | the keystore's password |
| `KEY_ALIAS` | the key's alias |
| `KEY_PASSWORD` | the key's password |

They're the same names dailylog uses.

## Each release

1. In `app/build.gradle.kts`, raise `versionCode` by one and set
   `versionName` (e.g. `0.2.0`).
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: what
   changed, in a few lines (500 characters at most), and the same text as
   `<versionCode>1.txt` to `<versionCode>4.txt` (for version code 2: `2.txt`,
   `21.txt` … `24.txt`). The GitHub release shows the first; F-Droid finds a
   changelog by each APK's own version code (below). The Release workflow
   stops if a copy is missing or different.
3. Merge to main, then tag that commit and push the tag:
   `git tag v0.2.0 && git push origin v0.2.0`.
4. The **Release** workflow checks the tag matches `versionName`, builds the
   signed APKs and the AAB, and attaches them to a **draft** GitHub release.
   Try the arm64 APK on a phone, then publish the draft.

There's one APK per ABI (arm64-v8a, armeabi-v7a, x86_64, x86), since the
podcast voice's native code is about 30 MB for each 64-bit one. Each has
version code `versionCode * 10` plus 1 to 4, in that order: armeabi-v7a,
arm64-v8a, x86, x86_64. A store offers a device the highest code it can
run. The AAB takes the first of these, `versionCode * 10 + 1`, as Android's
build gives a bundle its first output's code. Play makes its own per-device
APKs from it and only needs the code to rise, which it does.

Before the first release, install a release build on a real phone and go
through onboarding, an edition and a send: CI only tests the debug build.

## F-Droid

F-Droid builds the app itself from the tag and signs it with its own key.

- **First time:** open a merge request to
  [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding
  `metadata/com.app.newspaperss.yml`, from the draft in
  [docs/fdroid/](fdroid/com.app.newspaperss.yml). Its reviewers check that it
  builds; they may ask for changes (for example if their build server
  doesn't yet support compileSdk 37).
- **After that:** each new `v*` tag is picked up on its own, one build per
  ABI (`VercodeOperation` in the draft). The `latest-debug` tag is ignored,
  as `UpdateCheckMode` only matches `v` tags.
- **The draft is kept in the form `fdroid rewritemeta` writes,** so it has no
  comments; fdroiddata's CI rejects anything else. Check it with
  fdroidserver (`pip install fdroidserver`) in a sparse clone of fdroiddata
  (`git sparse-checkout set config`), with the draft in `metadata/`:
  `fdroid lint com.app.newspaperss` and `fdroid rewritemeta com.app.newspaperss`
  should change nothing.
- **Why it looks as it does:**
  - Four builds, one per ABI, because the app's build splits its APKs; each
    names its APK with `output:`.
  - `NonFreeNet`: the podcast voice (Kokoro) downloads from Hugging Face, as
    SherpaTTS, the other sherpa-onnx app on F-Droid, declares.
  - sherpa-onnx comes from JitPack, which F-Droid's scanner allows; SherpaTTS
    uses the same library the same way.

The store text and screenshots come from `fastlane/metadata/android/en-US/`.

## Google Play

- **The account:** a new personal developer account must run a closed test
  with at least 12 testers for 14 days before it can publish to everyone.
  Start this early.
- **Signing:** turn on Play App Signing and upload the AAB from the draft
  release. The key in the secrets above becomes your upload key.
- **The listing:** the same text and screenshots as `fastlane/`, the icon at
  `fastlane/metadata/android/en-US/images/icon.png`, and the 1024×500 feature
  graphic at `images/featureGraphic.png` beside it. Its source is
  [docs/store/featureGraphic.html](store/featureGraphic.html): open it in a
  browser at 1024×500 and screenshot it to change it.
- **Forms:** Data safety (nothing collected or shared), the content rating
  questionnaire, the target audience, and the privacy policy:
  https://github.com/madCode/newspaperss/blob/main/docs/PRIVACY.md
