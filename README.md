# Local CCTV for Android

A small Android app that can stream one phone's camera to another phone on the same local Wi-Fi network or hotspot.

## Build

Open this repository in Android Studio and run the `app` configuration, or build from the repository root with:

```bash
gradle assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Use

1. Install the app on both Android phones and connect them to the same trusted Wi-Fi network (or use a phone hotspot).
2. On the camera phone, choose **Set up this phone as a camera**, set a unique password of at least 8 characters, then allow camera access.
3. Keep the camera app open. Enter one of the displayed local IP addresses and the password in **View another camera** on the viewer phone.
4. Tap **Stop camera** when finished.

The app streams live video over the local network, reports basic motion changes, and can alert the viewer. It does not save video or send it to a cloud service. Use it only where you have permission to monitor. The local stream uses HTTP rather than encrypted transport, so use a trusted network and do not forward port 8080 to the public internet.
