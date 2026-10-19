# Gleamvox

Source code of **Gleamvox**, a private keyboard for Android (https://gleamvox.com), published under the
GNU General Public License v3.0 (file `LICENSE`). Gleamvox is based on [HeliBoard](https://github.com/HeliBorg/HeliBoard)
(GPL-3.0, itself based on AOSP / OpenBoard); the original README is in `README-HeliBoard.md`, the credits in `NOTICE.md`.

- Version of this source: **1.0.4439** (the build type `play` is the Google Play version: package `com.gleamvox.keyboard`,
  no internet permission).
- The code contains parts that other build types of the project switch on (for example AI functions with the user's own key);
  they are disabled in the Google Play version.
- Swipe typing in the Google Play version is an own open implementation (`app/src/main/java/helium314/keyboard/latin/glide`,
  GPL-3.0, tested in `GlideMatcherTest`); it does not use the closed-source swipe library of Google.
- Not in this repository: signing keys, the closed-source swipe library of Google (never distributed), third-party
  dictionaries (get them from the sources listed in `docs/DICTIONARIES.md`, put them in a folder and pass
  `-PaikbDictsDir=<folder>`; the build checks them by SHA-256).
- Build: `./gradlew assemblePlay` (JDK 21, Android SDK 36). `tools/aikb/build.sh` is the wrapper the author uses on a small
  shared machine.
- Questions: support@gleamvox.com
