# Nord Buds 4 Pro connection test

This fork is an **experimental connection fix**, based on QuickBuds 4.13.0. It has not been tested
with physical earbuds. The existing model entry (`066814`) is retained without a `confirmed` flag.
The reported setup is OnePlus Nord 5, Android 16: audio works, QuickBuds fails at channel 15,
and the phone's Earbud functions page is missing or fails. HeyMelody also does not connect.

## What changed

- Try both known OPPO service UUIDs with authenticated RFCOMM, followed by Android's insecure
  RFCOMM API. Both methods use service discovery for the channel.
- Prefer known UUIDs present in the phone's cached service records, while still trying both.
- Close every failed socket, including the existing channel 15 fallback.
- Log the phone/Android version, bond state, cached service UUIDs, and the outcome of each attempt.
  No Bluetooth addresses are added to these diagnostics.

These changes address gaps in the connection code. They do not prove that the Nord Buds 4 Pro
requires insecure RFCOMM or that channel 15 is correct. There are no guessed commands or feature writes.

## Install and test

1. [Download the experimental test APK](https://github.com/NazimFilzer/QuickBuds/releases/tag/nord-buds-4-pro-test-1).
   To build it yourself, use JDK 21+ and Android SDK 37: `./gradlew assembleDebug`. The APK is
   `app/build/outputs/apk/debug/app-debug.apk`. Alternatively run **Nord Buds connection test** in
   GitHub Actions and download its APK artifact. This workflow needs no upstream signing key.
2. Install the debug APK. Its application id is `com.spizganed.quickbuds.nordtest` and version ends
   in `-nord-test`, so it can coexist with official QuickBuds. Both icons retain the QuickBuds name;
   use Android App info to check the version. Force-stop official QuickBuds before opening the test app.
3. Keep the earbuds paired in Android, with the lid open and at least one bud awake. Confirm that
   music plays. Close HeyMelody and the Bluetooth Earbud functions page before connecting in the test app.
4. Allow Nearby devices when prompted, open Dev tools, clear the log, and try connecting once.
5. A useful success is `Connected via ...` followed by `RX` handshake/product-id replies and live
   battery readings. A socket opening alone does not establish full model support.
6. Export the complete log from Dev tools. If connected, test battery, ANC and EQ separately, then
   use Settings → Test my earbuds. Do not mark the model confirmed until these are actually tested.
7. Remove the test app when finished. Its debug signing key differs from the official release key.
   Do not use the upstream in-app updater inside the test app.

## Check the OnePlus system controls too

OnePlus documents the control path as **Settings → Bluetooth → OnePlus Nord Buds 4 Pro → Earbud
functions**; HeyMelody is the companion path on other phones
([official product page](https://www.oneplus.com/by/oneplus-nord-buds-4-pro)).
Check system/app updates for the phone's Wireless Earphones component, and make sure it is enabled.
If the controls remain missing, record that alongside the QuickBuds log: the screenshot alone
cannot distinguish a phone-side service problem, another control client, or an unsupported transport.

If all transport attempts still fail, capture Bluetooth HCI traffic while reproducing the failure
and, if available, a successful session with the stock controls or HeyMelody on another phone.
[PACKET-CAPTURE.md](./PACKET-CAPTURE.md) explains the capture steps. HCI captures may include private
device information; keep the original private and share only a relevant redacted excerpt.

## Host regression checks

Run `scripts/test-rfcomm.sh` with a local `kotlinc` on PATH, or set `KOTLIN_HOME` to a Kotlin compiler
distribution. These tests use fake sockets to exercise the actual fallback and cleanup policy;
they cannot verify Android's Bluetooth stack or the physical device.
