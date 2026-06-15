# Setup

## Android Device Setup

The app is intended to run on a physical Android tablet with a front-facing camera.

### Enable USB Debugging

1. On the tablet, open **Settings**.
2. Go to **About tablet**.
3. Tap **Build number** seven times to enable Developer Options.
4. Go back to **Settings → Developer options**.
5. Turn on **USB debugging**.
6. Connect the tablet to the development computer with a USB data cable.
7. When prompted, choose **Always allow from this computer**, then tap **Allow**.

Verify that ADB can see the device:

```bash
adb devices
```

Expected output:

```text
List of devices attached
DEVICE_ID    device
```

If the device appears as `unauthorized`, unlock the tablet and accept the USB debugging prompt. If needed, restart ADB:

```bash
adb kill-server
adb start-server
adb devices
```

## Android Studio

Open the project from a local disk location rather than a NAS or mounted network volume. Android Studio and Gradle may fail to sync correctly when the project is opened from some mounted filesystems.

After opening the project:

1. Wait for Gradle sync to complete.
2. Confirm the tablet appears in **Device Manager** as a physical device.
3. Select the device in the Run target dropdown.
4. Run the app with the `app` configuration.

If Android Studio does not show the device but `adb devices` does, restart Android Studio and ADB:

```bash
adb kill-server
adb start-server
adb devices
```

## Photo Backup with ADB

The app stores captured frames in app-private external storage, typically under:

```text
/sdcard/Android/data/com.toddmargolis.astroandroidapp/files/AstroTimeDelay
```

List available capture sessions:

```bash
adb shell ls -la /sdcard/Android/data/com.toddmargolis.astroandroidapp/files/AstroTimeDelay
```

Example session folders may include:

```text
Moon_YYYY-MM-DD_HH-MM-SS
Sun_YYYY-MM-DD_HH-MM-SS
Saturn_YYYY-MM-DD_HH-MM-SS
Proxima Centauri_YYYY-MM-DD_HH-MM-SS
```

For paths with spaces, use nested quoting:

```bash
adb shell "ls -la '/sdcard/Android/data/com.toddmargolis.astroandroidapp/files/AstroTimeDelay/Proxima Centauri_YYYY-MM-DD_HH-MM-SS'"
```

Count files before backup:

```bash
adb shell "find '/sdcard/Android/data/com.toddmargolis.astroandroidapp/files/AstroTimeDelay/Proxima Centauri_YYYY-MM-DD_HH-MM-SS' -type f | wc -l"
```

Create a local backup folder:

```bash
mkdir -p ./AndroidTimeDelayBackup
```

Pull all sessions:

```bash
adb pull "/sdcard/Android/data/com.toddmargolis.astroandroidapp/files/AstroTimeDelay" \
  ./AndroidTimeDelayBackup/
```

Or pull a single session:

```bash
adb pull "/sdcard/Android/data/com.toddmargolis.astroandroidapp/files/AstroTimeDelay/Proxima Centauri_YYYY-MM-DD_HH-MM-SS" \
  ./AndroidTimeDelayBackup/
```

Verify the local backup count:

```bash
find "./AndroidTimeDelayBackup/Proxima Centauri_YYYY-MM-DD_HH-MM-SS" -type f | wc -l
```

The local count should match the count from the tablet.

## Optional NAS Copy

After backing up locally, copy the files to long-term storage with `rsync`:

```bash
rsync -avh --progress \
  "./AndroidTimeDelayBackup/Proxima Centauri_YYYY-MM-DD_HH-MM-SS/" \
  "/Volumes/NAS_OR_BACKUP_VOLUME/AndroidTimeDelay/Proxima Centauri_YYYY-MM-DD_HH-MM-SS/"
```

Backing up to the local disk first is usually faster and safer than pulling directly from ADB to a network volume, especially when copying thousands of small image files.

## Troubleshooting

If `adb pull` copies fewer files than expected, confirm that the source path points to the full session folder rather than a subfolder such as `candidates/`.

Show file counts grouped by subfolder:

```bash
adb shell "find '/sdcard/Android/data/com.toddmargolis.astroandroidapp/files/AstroTimeDelay/Proxima Centauri_YYYY-MM-DD_HH-MM-SS' -type f | sed 's|/[^/]*$||' | sort | uniq -c"
```

If quoted paths fail inside `adb shell`, use nested quoting:

```bash
adb shell "ls -la '/path/with spaces'"
```
