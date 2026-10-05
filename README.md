# Reface CS Live

An offline Android patch librarian and setlist controller for the Yamaha reface CS.

The app captures the reface CS tone-generator state through Yamaha SysEx, stores it on the phone, and restores it with one tap during a performance.

## Features

- Automatic reface CS detection through Android USB MIDI
- Explicit MIDI device selection when several devices are connected
- Capture and validate the current 61-byte reface CS patch dump
- One-tap patch recall
- Large stage-friendly previous/next controls
- Multiple ordered setlists
- Rename, duplicate, reorder, and delete patches
- Standard `.syx` import and export
- Complete JSON library backup and restore
- Offline-only operation; the app requests no Internet permission
- Keeps the screen awake while open
- Sends All Notes Off before patch recall

## Hardware connection

Connect the independently powered reface CS directly to the Android phone:

```text
Android USB-C → USB-C/OTG data cable → reface CS USB TO HOST
```

The phone must support USB host/OTG and Android MIDI. A charge-only cable will not work.

## Install the development APK

The generated APK is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Enable installation from your file manager, copy the APK to the phone, and open it. New APK versions use the same application ID, so Android updates the existing app instead of installing a second copy, while retaining the local library. Alternatively, with Android debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. Power the reface CS and connect it to the phone.
2. Open **Reface CS Live** and confirm that the status changes to **Connected**.
3. Open **Manage** and select **Capture** to save the current sound.
4. Add or reorder patches in a setlist.
5. Open **Perform** and tap a patch, or use **Previous** and **Next**.

The CS sliders do not move after a recall, so their physical positions may not match the recalled internal values.

## Build

Open the repository in Android Studio, allow Gradle sync to finish, and build the `app` module. The command-line build requires JDK 17 and Android SDK 35:

```bash
./gradlew testDebugUnitTest assembleDebug
```

## Technical notes

- Minimum Android version: Android 6 / API 23
- UI: Kotlin and Jetpack Compose
- MIDI: Android `android.media.midi`
- Patch transport: Yamaha reface CS bulk SysEx
- Patch files: standard 61-byte `.syx` dumps
- Storage: local Android preferences with explicit JSON backup/export

The protocol implementation validates Yamaha manufacturer/model bytes, block addresses, byte counts, parameter ranges, and checksums before storing or transmitting a patch.

## Testing status

Protocol unit tests, Android compilation, APK assembly, and Android lint pass. End-to-end USB behavior must still be verified with the physical reface CS and target Android phone.

## License

MIT — see [LICENSE](LICENSE).
