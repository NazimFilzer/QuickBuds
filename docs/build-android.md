# Build the Android app

You need JDK 21 and the Android SDK with platform 37. Android Studio brings both. Its bundled JBR
works as `JAVA_HOME`.

```bash
git clone https://github.com/spizganed/QuickBuds.git
cd QuickBuds
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # your SDK path
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- The first build downloads Gradle through the wrapper. The only library is `androidx.core`.
- This fork's debug build uses application id `com.spizganed.quickbuds.nordtest` and a
  `-nord-test3` version suffix, so it installs beside the official release. Both icons use the
  QuickBuds name. Force-stop the official app while testing, and remove the test app when done.
- `./gradlew assembleRelease` without the release key builds an unsigned APK. That is expected.

Versions: Gradle 9.6.0, AGP 9.4.0, Kotlin 2.4.0, `compileSdk` / `targetSdk` 37, `minSdk` 26. AGP 9
compiles Kotlin itself. Do not add the `org.jetbrains.kotlin.android` plugin.

Run `scripts/test-rfcomm.sh` with `kotlinc` on PATH (or `KOTLIN_HOME` set) for the host transport
regression checks. Run `python3 scripts/check-locales.py` and `./gradlew assembleDebug lintDebug`,
then test on real buds. See [the Nord Buds 4 Pro test guide](./nord-buds-4-pro.md).
