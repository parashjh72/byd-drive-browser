# Drive Browser for BYD

A full web browser for BYD DiLink head units (Atto 3, Seal, Dolphin, Sealion, Han, Tang…).
Big touch buttons, a wide-screen desktop-site mode, and audio that keeps playing in the background.

## Features
- **Multiple tabs (up to 8).** Tabs are shown along the top. A song keeps playing in one tab while you browse in another. A 🔊 on a tab means it's playing. Links that open a new window open as a new tab, and pop-up spam is blocked.
- **"Now playing" notification** with ⏮ ⏯ ⏭ ✕ buttons. The **steering-wheel media buttons** work too. Tap the notification to jump back to the tab that's playing.
- **Keeps playing in the background.** Music doesn't stop when you open Maps or the BYD home screen, or when you press Back.
- **Light.** No extra libraries, and the release build is shrunk, so the APK should be well under 1 MB. Background tabs that aren't playing are paused. When memory is low they are unloaded and reload when you open them. Saved tabs only load when you first open them.
- **Browse anything.** Full Chromium WebView with an address bar, Google search, back/forward/reload/home, and downloads.
- **Big buttons** sized for a car touchscreen. Text is 115% size.
- **Desktop / mobile site toggle** (🖥 / 📱). Desktop is the default because the screen is wide.
- **Fullscreen video** (YouTube, Netflix…) and an immersive mode (⛶) that hides the toolbar.
- **🎤 Voice search.** Say "youtube dot com" or "petrol pumps near me".
- **🔊 Audio-only mode.** Turns the screen black and keeps the sound playing. Press and hold to bring the screen back.
- **Driving check.** When GPS shows the car moving above 15 km/h, the app asks once per trip:
  *"I'm a passenger"* (keep browsing) or *"Audio only"* (driver). It asks again after the car has been stopped for 3+ minutes.

## Get the APK
1. Push this folder to a GitHub repository.
2. The **Build APK** workflow runs on its own. Open the run on the repo's **Actions** tab and download `DriveBrowser-apk`.
   Or open the folder in Android Studio and choose *Build → Build APK*.

## Install on the BYD
1. Copy `DriveBrowser.apk` to a USB stick (FAT32 or exFAT).
2. Put the USB stick in the car and open the **File Manager** app on the head unit.
3. Tap the APK and allow installation from unknown sources if the car asks.
   - Some newer DiLink firmware blocks installing from USB. In that case install with ADB over Wi-Fi
     (`adb connect <car-ip>:5555`, then `adb install DriveBrowser.apk`), or use the BYD app store's "local install" option if your region has it.
4. Open **Drive Browser**. Allow Location (for the driving check) and Microphone (for voice search).

## Safety
Watching video or reading web pages while you drive is dangerous and illegal in most countries.
The app does not get around any BYD system lock. When you're the driver, use Audio only and 🎤 voice search.
