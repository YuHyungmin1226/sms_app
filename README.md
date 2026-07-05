# Signalist SMS

Signalist SMS is a Jetpack Compose Android app for sending messages to many recipients selected from the device contact list. It classifies each draft as SMS, LMS, or MMS and uses different send paths depending on whether an image is attached.

## Current behavior

- Loads contacts and contact groups from the device address book.
- Lets you filter recipients by group and select multiple contacts at once.
- Shows batch-level progress while SMS, LMS, or MMS sends are running.
- Classifies text-only drafts as:
  - `SMS` when the draft fits in one SMS part.
  - `LMS` when the draft spans multiple SMS parts.
- Sends text-only drafts directly with `SmsManager`.
  - Single-part text uses `sendTextMessage`.
  - Multipart text uses `sendMultipartTextMessage`.
- Treats any draft with an image attachment as `MMS`, even if the text is empty or short.
- MMS is not sent through a direct carrier MMS API. Instead, the app opens Google Messages with the selected image and optional text, then uses the bundled accessibility service to press a verified Send button for each queued recipient when auto-send is enabled.

## Runtime requirements

- Android 7.0 or newer (`minSdk = 24`).
- A telephony-capable device or emulator if you want to actually send SMS or LMS.
- Google Messages installed and set as the default SMS app for the image MMS flow.
- The app's accessibility service enabled if you want batch MMS auto-send through Google Messages.

## Permissions

- `READ_CONTACTS` to load contacts and groups.
- `SEND_SMS` to send SMS and multipart SMS directly.

No extra MMS permission is requested because image MMS is handed off to Google Messages.

## Build prerequisites

- JDK 17.
- Android SDK Platform 36 (`compileSdk = 36`, `targetSdk = 36`).
- Gradle 9.1.0 is provided by the wrapper in this repository.

If you build from the command line, make sure your Android SDK is installed and available through the normal Android environment variables or Android Studio setup.

## Build and test

Run unit tests:

```powershell
.\gradlew.bat testDebugUnitTest
```

Build a debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Run both in one command:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

## Notes on message types

- `LMS` in the UI means the message will be split into multiple SMS parts before sending.
- Carrier billing and final device behavior for multipart SMS can vary by network.
- The MMS auto-send flow depends on the Google Messages UI, so it is best-effort rather than a low-level MMS transport implementation.
