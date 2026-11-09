# Bud Ringer

Sends the Fast Pair "Ring" command straight to paired Pixel Buds over Bluetooth, without using Find Hub.

## Build
1. Open this folder in Android Studio (File > Open) and let Gradle sync.
2. On the phone, turn on Developer options and USB debugging, then plug it in.
3. Click Run. You can also use Build > Build APK(s) and copy the APK over.

### Build on GitHub (no Android Studio needed)
Push this folder to a GitHub repo. The **Build APK** workflow runs on every push to `main`:
open the **Actions** tab → latest run → download **BudRinger-apk**.
Push a tag like `v1.0` to also get the APK attached to a GitHub Release.

## Use
- When the app asks, allow Nearby devices.
- Pick your Pixel Buds and tap a Ring button. The log shows ACK if the command was accepted and NAK if it was rejected.
- At least one bud must be connected. For the case to ring, a bud has to be inside it.
- If the connection fails, force-stop the Pixel Buds app, turn Bluetooth off and on, and try again.
