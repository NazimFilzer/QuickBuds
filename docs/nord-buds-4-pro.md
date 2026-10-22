# Nord Buds 4 Pro connection test

This fork is an **experimental connection fix**, based on QuickBuds 4.13.0. Build 1 failed in
the owner's device test. Build 2 opens SPP and the owner confirms audible ANC changes, but it
receives no status or battery replies. Build 3's BLE and dual-connection changes need a device test.
The existing model entry (`066814`) is retained without a `confirmed` flag.
The reported setup is OnePlus Nord 5, Android 16: audio works, QuickBuds fails at channel 15,
and the phone's Earbud functions page is missing or fails. HeyMelody also does not connect.

The owner tested build 1: the corrected log targets the Nord Buds, but all custom OPPO service
and channel 15 attempts fail. Its cached service records advertise standard Serial Port Profile
(`00001101-0000-1000-8000-00805f9b34fb`) instead of the custom OPPO UUIDs. **Build 2** tries this
advertised service first, for the exact Bluetooth name `OnePlus Nord Buds 4 Pro` only. Whether
it carries the OPPO command protocol still needs a device test.

The later test-2 log shows no received packets at all after SPP opens. The dual switch was
recreated as Off because no firmware state had arrived. **Build 3** tries the publicly documented
Nord Buds OPO Bluetooth LE control path, including notifications across discovered characteristics.
It retains SPP as a fallback and uses the existing command payloads. The dual switch now shows a
waiting state until the earbuds report feature `11`, and a requested change remains pending
across screen navigation until read-back confirms it or a timeout reports failure.

## What changed

- Try both known OPPO service UUIDs with authenticated RFCOMM, followed by Android's insecure
  RFCOMM API. Both methods use service discovery for the channel.
- Prefer known UUIDs present in the phone's cached service records, while still trying both.
- Build 2: include advertised standard SPP for Nord Buds 4 Pro, with secure and insecure attempts;
  record Android's Bluetooth socket error code on Android 14+.
- Build 3: discover OPO GATT, subscribe before writing, serialize BLE operations, handle MTU limits,
  and confirm the dual switch from actual feature replies instead of optimistic local state.
- Close every failed socket, including the existing channel 15 fallback.
- Log the phone/Android version, bond state, cached service UUIDs, and the outcome of each attempt.
  No Bluetooth addresses are added to these diagnostics.

These changes address gaps in the connection code. They do not prove that the Nord Buds 4 Pro
requires insecure RFCOMM or that channel 15 is correct. There are no guessed commands or feature writes.

## Install and test

1. [Download experimental test APK 3](https://github.com/NazimFilzer/QuickBuds/releases/tag/nord-buds-4-pro-test-3).
   To build it yourself, use JDK 21+ and Android SDK 37: `./gradlew assembleDebug`. The APK is
   `app/build/outputs/apk/debug/app-debug.apk`. Alternatively run **Nord Buds connection test** in
   GitHub Actions and download its APK artifact. This workflow needs no upstream signing key.
2. Install the debug APK. Its application id is `com.spizganed.quickbuds.nordtest` and its version
   is `4.13.0-nord-test3`. It updates test 1 or 2 directly and can coexist with official QuickBuds.
   Both icons retain the QuickBuds name;
   use Android App info to check the version. Force-stop official QuickBuds before opening the test app.
3. Keep the earbuds paired in Android, with the lid open and at least one bud awake. Confirm that
   music plays. Close HeyMelody and the Bluetooth Earbud functions page before connecting in the test app.
   Disconnect other paired OPPO/OnePlus/realme earphones while testing, and check the log targets
   `OnePlus Nord Buds 4 Pro`. The app still uses upstream's automatic device selection.
4. Allow Nearby devices when prompted, open Dev tools, clear the log, and try connecting once.
5. A useful success is `Connected via ...` followed by `RX` handshake/product-id replies and live
   battery readings. A socket opening alone does not establish full model support.
6. Export the complete log from Dev tools. If connected, test battery, ANC and EQ separately, then
   use Settings → Test my earbuds. Do not mark the model confirmed until these are actually tested.
7. Remove the test app when finished. Its debug signing key differs from the official release key.
   Do not use the upstream in-app updater inside the test app.

### Dual connection with the Samsung S24 Ultra

Wait for the dual screen's waiting message to clear. Turn the switch on, and wait for the earbuds
to confirm it. Reopen the screen to check the confirmed state remains on. Then pair/connect the
earbuds from the S24 Ultra's Bluetooth settings while keeping the Nord 5 connected. Pairing the
second phone is a separate Android Bluetooth step; QuickBuds does not create that pairing.

If the screen keeps waiting, shows a confirmation error, or the second phone displaces the first,
export the complete log starting with the BLE scan/discovery lines and including the dual toggle.
Useful evidence is `BLE subscribed`, `RX` handshake/status frames, `FEATURES: ... 11=1`, and a device
list showing both phones. Merely keeping the switch visually On is not evidence of multipoint.

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
